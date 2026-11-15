package com.music.bitchord.data.providers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One source of truth for admission decisions within the new provider engine.
 *
 * The instance starts closed. It only publishes READY descriptors that are
 * also explicitly enabled in a valid persisted/migrated policy. Nothing on
 * the old search, playback or UI paths is redirected until its caller opts
 * into this runtime.
 *
 * All writes are serialized. Revocation is applied synchronously to the gate
 * BEFORE durable I/O, so a delayed SharedPreferences commit cannot allow
 * a provider to continue delivering late results after an opt-out begins.
 */
class ProviderCapabilitySession(
    private val store: ProviderPreferenceStore,
    private val descriptors: List<ProviderDescriptor>,
) {
    private val lock = Mutex()
    private var choices: Map<ProviderCapability, List<ProviderChoice>> = emptyMap()
    private var initialized: Boolean = false

    /** Admission gate used by each actual adapter; closed until initialize. */
    val gate = ProviderPolicyGate(ProviderCapabilityPolicy(descriptors))

    /**
     * Legacy choices are imported ONLY for previously unconfigured categories.
     * Existing explicit opt-outs always win, including a stored empty list.
     * A corrupted/newer policy is never replaced with legacy defaults.
     */
    suspend fun initialize(
        legacyChoices: Map<ProviderCapability, List<ProviderChoice>>,
    ): Boolean = lock.withLock {
        if (!store.migrateMissing(legacyChoices)) return@withLock false
        val loaded = store.read() as? ProviderPreferenceStore.Read.Loaded
            ?: return@withLock false
        choices = loaded.choices
        initialized = true
        gate.replace(ProviderCapabilityPolicy(descriptors, choices))
        true
    }

    /**
     * The user's new setting is made authoritative at this boundary.
     *
     * Revoking the affected capability before the disk commit prevents active
     * calls from publishing stale results during storage latency. Other
     * capabilities remain available. Failed persistence leaves the affected
     * capability closed in this process and returns false to the caller.
     */
    suspend fun update(
        capability: ProviderCapability,
        next: List<ProviderChoice>,
    ): Boolean = lock.withLock {
        if (!initialized) return@withLock false
        require(next.distinctBy { it.key }.size == next.size) {
            "Duplicate provider instance for $capability"
        }
        val closed = choices + (capability to emptyList())
        gate.replace(ProviderCapabilityPolicy(descriptors, closed))
        if (!store.replaceCapability(capability, next)) return@withLock false

        choices = choices + (capability to next.toList())
        gate.replace(ProviderCapabilityPolicy(descriptors, choices))
        true
    }
}
