package com.music.bitchord.data.providers

/**
 * Restricts source-owned cache reads independently of database persistence.
 *
 * A disabled source is not even queried. A source disabled while a Room query
 * is suspended cannot publish the old result after revocation. Its stored
 * evidence and the user's locally owned media remain untouched.
 *
 * Does not replace the final observer's policy check: callers holding a
 * previous visible result must still react to policy changes.
 */
internal class ProviderCachedReadAccess(
    private val policyGate: () -> ProviderPolicyGate? = {
        ProviderRuntime.gateOrNull()
    },
) {
    suspend fun <T> read(
        capability: ProviderCapability,
        provider: ProviderKey,
        unavailable: T,
        fetch: suspend () -> T,
    ): T {
        val gate = policyGate()
            ?: return fetch() // Test-only/legacy environment without Android runtime.
        val ticket = gate.open(capability, provider) ?: return unavailable
        val fetched = fetch()
        return if (gate.accept(ticket)) fetched else unavailable
    }
}
