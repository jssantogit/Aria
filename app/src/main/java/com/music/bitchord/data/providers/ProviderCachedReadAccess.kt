package com.music.bitchord.data.providers

/**
 * Restricts source-owned cache reads independently of database persistence.
 *
 * Fail-closed when no gate is supplied: absence of authorization never grants
 * permission in production. A disabled source is not queried, and a source
 * disabled while a Room query is suspended cannot publish stale rows.
 *
 * Does not replace the final observer's policy check: callers holding a
 * previous visible result must still react to policy changes.
 */
internal class ProviderCachedReadAccess(
    private val policyGate: () -> ProviderPolicyGate? = {
        ProviderRuntime.gateForCacheReadOrNull()
    },
) {
    suspend fun <T> read(
        capability: ProviderCapability,
        provider: ProviderKey,
        unavailable: T,
        fetch: suspend () -> T,
    ): T {
        val gate = policyGate() ?: return unavailable
        val ticket = gate.open(capability, provider) ?: return unavailable
        val fetched = fetch()
        return if (gate.accept(ticket)) fetched else unavailable
    }

    companion object {
        /** Explicit test helper for non-Android unit tests without ProviderRuntime. */
        fun permissiveForUnitTests(
            provider: ProviderKey = ProviderKey("musicbrainz"),
            capabilities: Set<ProviderCapability> = ProviderCapability.entries.toSet(),
        ): ProviderCachedReadAccess {
            val testGate = ProviderPolicyGate(
                ProviderCapabilityPolicy(
                    descriptors = listOf(
                        ProviderDescriptor(provider, capabilities, ProviderReadiness.READY),
                    ),
                    choices = capabilities.associateWith {
                        listOf(ProviderChoice(provider, enabled = true, order = 0))
                    },
                ),
            )
            return ProviderCachedReadAccess { testGate }
        }
    }
}
