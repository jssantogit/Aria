package com.music.bitchord.data.providers

/**
 * Provider-neutral capabilities, not vendor identities or interchangeable data sources.
 *
 * Editorial products, canonical identity and edition evidence intentionally have
 * different contracts. A provider can offer several capabilities independently.
 */
enum class ProviderCapability {
    CATALOG_SEARCH,
    EDITORIAL_DISCOGRAPHY,
    IDENTITY,
    EDITION_METADATA,
    ACOUSTIC_IDENTIFICATION,
    RELEASE_ARTWORK,
    ARTIST_ARTWORK,
    LYRICS_SYNCED,
    LYRICS_PLAIN,
    CHART_MOST_PLAYED,
    CHART_SALES,
    LISTENING_STATISTICS,
    PUBLIC_PLAYLISTS,
    PERSONAL_LIBRARY,
    AUDIO_PLAYBACK,
    DOWNLOAD,
}

/** Provider identity plus an optional user-configured account/server instance. */
data class ProviderKey(
    val providerId: String,
    val instanceId: String = "default",
) {
    init {
        require(providerId.isNotBlank() && providerId.length <= 128)
        require(instanceId.isNotBlank() && instanceId.length <= 128)
    }
}

/**
 * READY means an adapter has independently established its prerequisites.
 * A registered-but-unimplemented service must NOT claim READY.
 */
enum class ProviderReadiness {
    READY,
    NEEDS_CONFIGURATION,
    NOT_IMPLEMENTED,
    BLOCKED,
}

data class ProviderDescriptor(
    val key: ProviderKey,
    val capabilities: Set<ProviderCapability>,
    val readiness: ProviderReadiness = ProviderReadiness.NOT_IMPLEMENTED,
) {
    init {
        require(capabilities.isNotEmpty()) { "A provider must declare at least one capability" }
    }
}

/** Activation and ordering belong to a capability, not to the whole provider. */
data class ProviderChoice(
    val key: ProviderKey,
    val enabled: Boolean,
    val order: Int,
) {
    init {
        require(order >= 0)
    }
}

/**
 * Immutable, explicit policy snapshot. Nothing is implicitly enabled:
 * absent choices, disabled last choices and unavailable adapters produce an
 * empty list. A policy can also include preferences for providers not yet installed.
 *
 * Persistence, authentication and network calls belong to separate adapters.
 */
class ProviderCapabilityPolicy(
    descriptors: List<ProviderDescriptor>,
    choices: Map<ProviderCapability, List<ProviderChoice>> = emptyMap(),
) {
    private val byKey: Map<ProviderKey, ProviderDescriptor> = descriptors
        .also { require(it.distinctBy(ProviderDescriptor::key).size == it.size) }
        .associateBy(ProviderDescriptor::key)

    private val eligibleByCapability: Map<ProviderCapability, List<ProviderDescriptor>> =
        ProviderCapability.entries.associateWith { capability ->
            val options = choices[capability].orEmpty()
            require(options.distinctBy(ProviderChoice::key).size == options.size) {
                "A provider instance may only be selected once per capability"
            }
            options.asSequence()
                .filter(ProviderChoice::enabled)
                .sortedBy(ProviderChoice::order)
                .mapNotNull { choice ->
                    byKey[choice.key]?.takeIf { descriptor ->
                        descriptor.readiness == ProviderReadiness.READY &&
                            capability in descriptor.capabilities
                    }
                }
                .toList()
        }

    fun eligible(capability: ProviderCapability): List<ProviderDescriptor> =
        eligibleByCapability.getValue(capability)

    fun permits(capability: ProviderCapability, key: ProviderKey): Boolean =
        eligibleByCapability.getValue(capability).any { it.key == key }
}

/**
 * A tiny admission/result guard for future suspending adapters.
 *
 * Call open BEFORE dispatching network work, and accept BEFORE publishing a
 * result. Replacing the policy invalidates only capabilities whose eligible
 * provider set/order/readiness actually changed. It is not a network scheduler.
 */
data class ProviderRequestTicket internal constructor(
    val capability: ProviderCapability,
    val key: ProviderKey,
    val revision: Long,
)

class ProviderPolicyGate(initial: ProviderCapabilityPolicy) {
    private var policy: ProviderCapabilityPolicy = initial
    private val revisions = LongArray(ProviderCapability.entries.size)

    @Synchronized
    fun ordered(capability: ProviderCapability): List<ProviderDescriptor> = policy.eligible(capability)

    @Synchronized
    fun open(capability: ProviderCapability, key: ProviderKey): ProviderRequestTicket? =
        if (policy.permits(capability, key)) {
            ProviderRequestTicket(capability, key, revisions[capability.ordinal])
        } else null

    @Synchronized
    fun accept(ticket: ProviderRequestTicket): Boolean =
        revisions[ticket.capability.ordinal] == ticket.revision &&
            policy.permits(ticket.capability, ticket.key)

    @Synchronized
    fun replace(next: ProviderCapabilityPolicy) {
        for (capability in ProviderCapability.entries) {
            if (policy.eligible(capability) != next.eligible(capability)) {
                revisions[capability.ordinal]++
            }
        }
        policy = next
    }
}
