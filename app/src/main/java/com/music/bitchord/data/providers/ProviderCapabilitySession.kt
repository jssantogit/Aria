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
    private val quarantinedProviders = mutableSetOf<Pair<ProviderCapability, ProviderKey>>()
    private val quarantinedCapabilities = mutableSetOf<ProviderCapability>()
    private val health = mutableMapOf<ProviderKey, ProviderOperationalHealth>()
    private var initialized: Boolean = false
    private var storageFailure: Boolean = false

    /** Admission gate shared by adapters; closed until initialization. */
    val gate = ProviderPolicyGate(ProviderCapabilityPolicy(descriptors))
    private val _state = MutableStateFlow(ProviderSessionSnapshot())
    /** Immutable, non-secret status for future presentation or diagnostics. */
    val state: StateFlow<ProviderSessionSnapshot> = _state.asStateFlow()

    private fun allQuarantinedCapabilities(): Set<ProviderCapability> =
        quarantinedCapabilities + quarantinedProviders.map { it.first }

    private fun publishSnapshot() {
        val allQuarantined = allQuarantinedCapabilities()
        val status = when {
            storageFailure -> ProviderSessionStatus.STORAGE_ERROR
            !initialized -> ProviderSessionStatus.BOOTING
            allQuarantined.isNotEmpty() -> ProviderSessionStatus.PERSISTING
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
            quarantined = allQuarantined,
            quarantinedProviders = quarantinedProviders.toSet(),
            health = health.toMap(),
        )
    }

    private fun effectivePolicy(): ProviderCapabilityPolicy {
        val effectiveChoices = ProviderCapability.entries.associateWith { capability ->
            if (capability in quarantinedCapabilities) {
                emptyList()
            } else {
                choices[capability].orEmpty().map { choice ->
                    if ((capability to choice.key) in quarantinedProviders) {
                        choice.copy(enabled = false)
                    } else {
                        choice
                    }
                }
            }
        }
        return ProviderCapabilityPolicy(
            descriptors = descriptors,
            choices = effectiveChoices,
            health = health,
        )
    }

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
        quarantinedProviders.clear()
        quarantinedCapabilities.clear()
        initialized = true
        storageFailure = false
        gate.replace(effectivePolicy())
        publishSnapshot()
        true
    }

    /**
     * Immediate per-(capability, provider instance) revocation before durable I/O.
     *
     * Only the specific provider instances whose authorization changed are
     * quarantined before disk commit; unaffected sibling providers in the same
     * capability keep their active tickets and in-flight requests intact.
     */
    suspend fun update(
        capability: ProviderCapability,
        next: List<ProviderChoice>,
    ): Boolean = lock.withLock {
        if (!initialized) return@withLock false
        require(next.distinctBy { it.key }.size == next.size) {
            "Duplicate provider instance for $capability"
        }
        val capabilityQuarantined = capability in allQuarantinedCapabilities()
        if (!capabilityQuarantined && choices[capability] == next) {
            return@withLock true // stable policy, no network revocation or disk I/O
        }

        val previousByKey = choices[capability].orEmpty().associateBy(ProviderChoice::key)
        val nextByKey = next.associateBy(ProviderChoice::key)
        val changedKeys = (previousByKey.keys + nextByKey.keys).filter { key ->
            previousByKey[key]?.enabled != nextByKey[key]?.enabled
        }
        if (changedKeys.isEmpty()) {
            quarantinedCapabilities.add(capability)
        } else {
            changedKeys.forEach { key -> quarantinedProviders.add(capability to key) }
        }
        gate.replace(effectivePolicy())
        publishSnapshot()

        if (!store.replaceCapability(capability, next)) {
            storageFailure = true
            publishSnapshot()
            return@withLock false
        }

        choices = choices + (capability to next.toList())
        quarantinedCapabilities.remove(capability)
        quarantinedProviders.removeAll { it.first == capability }
        gate.replace(effectivePolicy())
        if (allQuarantinedCapabilities().isEmpty()) storageFailure = false
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
        val isQuarantined = capability in quarantinedCapabilities ||
            (capability to key) in quarantinedProviders
        effectivePolicy().status(capability, key, isQuarantined)
    }

    private fun failClosed(storageError: Boolean) {
        initialized = false
        storageFailure = storageError
        choices = emptyMap()
        quarantinedProviders.clear()
        quarantinedCapabilities.clear()
        gate.replace(ProviderCapabilityPolicy(descriptors, health = health))
        publishSnapshot()
    }
}
