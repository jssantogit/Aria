package com.music.bitchord.data.providers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One source of truth for admission decisions within the new provider engine.
 *
 * The instance starts closed. It only publishes READY descriptors that are
 * also explicitly enabled in a valid persisted/migrated policy. Existing
 * search/playback paths do not use the session until their cutover is complete.
 */
class ProviderCapabilitySession(
    private val store: ProviderPreferenceStore,
    private val descriptors: List<ProviderDescriptor>,
) {
    private val lock = Mutex()
    private var choices: Map<ProviderCapability, List<ProviderChoice>> = emptyMap()
    private val quarantined = mutableSetOf<ProviderCapability>()
    private val health = mutableMapOf<ProviderKey, ProviderOperationalHealth>()
    private var initialized: Boolean = false
    private var storageFailure: Boolean = false

    /** Admission gate shared by adapters; closed until initialization. */
    val gate = ProviderPolicyGate(ProviderCapabilityPolicy(descriptors))
    private val _state = MutableStateFlow(ProviderSessionSnapshot())
    /** Immutable, non-secret status for future presentation or diagnostics. */
    val state: StateFlow<ProviderSessionSnapshot> = _state.asStateFlow()

    private fun publishSnapshot() {
        val status = when {
            storageFailure -> ProviderSessionStatus.STORAGE_ERROR
            !initialized -> ProviderSessionStatus.BOOTING
            quarantined.isNotEmpty() -> ProviderSessionStatus.PERSISTING
            else -> ProviderSessionStatus.READY
        }
        val eligible = ProviderCapability.entries.mapNotNull { capability ->
            gate.ordered(capability).map { it.key }
                .takeIf { it.isNotEmpty() }
                ?.let { capability to it }
        }.toMap()
        _state.value = ProviderSessionSnapshot(
            status = status,
            configured = choices.mapValues { (_, selections) -> selections.toList() },
            eligible = eligible,
            quarantined = quarantined.toSet(),
            health = health.toMap(),
        )
    }

    private fun effectivePolicy(): ProviderCapabilityPolicy =
        ProviderCapabilityPolicy(
            descriptors = descriptors,
            choices = choices + quarantined.associateWith { emptyList<ProviderChoice>() },
            health = health,
        )

    /**
     * Import only missing legacy categories. An existing explicit opt-out
     * always wins. Corrupt/newer documents must NEVER acquire default choices.
     */
    suspend fun initialize(
        legacyChoices: Map<ProviderCapability, List<ProviderChoice>>,
    ): Boolean = lock.withLock {
        if (!store.migrateMissing(legacyChoices)) {
            failClosed(storageError = true)
            return@withLock false
        }
        val loaded = when (val snapshot = store.read()) {
            is ProviderPreferenceStore.Read.Loaded -> snapshot.choices
            // A genuinely fresh installation has no provider preferences.
            // Empty is valid: the user must still be able to opt into a
            // first source without inheriting a fake mandatory fallback.
            ProviderPreferenceStore.Read.Missing ->
                if (legacyChoices.isEmpty()) emptyMap() else null
            ProviderPreferenceStore.Read.Unreadable -> null
        }
        if (loaded == null) {
            failClosed(storageError = true)
            return@withLock false
        }
        choices = loaded
        quarantined.clear()
        initialized = true
        storageFailure = false
        gate.replace(effectivePolicy())
        publishSnapshot()
        true
    }

    /**
     * Immediate capability-local revocation before durable I/O.
     *
     * A failed commit keeps the affected capability quarantined even when
     * another category is edited later. A subsequent successful commit for
     * that capability recovers it cleanly without re-enabling opt-outs.
     */
    suspend fun update(
        capability: ProviderCapability,
        next: List<ProviderChoice>,
    ): Boolean = lock.withLock {
        if (!initialized) return@withLock false
        require(next.distinctBy { it.key }.size == next.size) {
            "Duplicate provider instance for $capability"
        }
        if (capability !in quarantined && choices[capability] == next) {
            return@withLock true // stable policy, no network revocation or disk I/O
        }

        quarantined.add(capability)
        gate.replace(effectivePolicy())
        publishSnapshot()
        if (!store.replaceCapability(capability, next)) {
            storageFailure = true
            publishSnapshot()
            return@withLock false
        }

        choices = choices + (capability to next.toList())
        quarantined.remove(capability)
        gate.replace(effectivePolicy())
        if (quarantined.isEmpty()) storageFailure = false
        publishSnapshot()
        true
    }

    /**
     * Update live operational health for a provider instance without mutating
     * persisted user choices. UNAVAILABLE suspends dispatch immediately.
     */
    suspend fun reportHealth(
        key: ProviderKey,
        status: ProviderOperationalHealth,
    ) = lock.withLock {
        val previous = health[key] ?: ProviderOperationalHealth.UNKNOWN
        if (previous == status) return@withLock
        if (status == ProviderOperationalHealth.UNKNOWN) health.remove(key)
        else health[key] = status
        gate.replace(effectivePolicy())
        publishSnapshot()
    }

    suspend fun statusOf(
        capability: ProviderCapability,
        key: ProviderKey,
    ): ProviderCapabilityStatus = lock.withLock {
        effectivePolicy().status(capability, key, capability in quarantined)
    }

    private fun failClosed(storageError: Boolean) {
        initialized = false
        storageFailure = storageError
        choices = emptyMap()
        quarantined.clear()
        gate.replace(ProviderCapabilityPolicy(descriptors, health = health))
        publishSnapshot()
    }
}
