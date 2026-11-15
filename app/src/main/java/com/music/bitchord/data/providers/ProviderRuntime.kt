package com.music.bitchord.data.providers

import android.content.Context

/**
 * Process-owned provider policy: installed closed before Android startup launches
 * network work. A missing provider setting is migrated once from the behavior
 * already available in legacy BitChord; it is NOT inferred from YouTube mode.
 *
 * Only MusicBrainz HTTP is connected here. Lyrics and extension settings remain
 * owned by their legacy stores until a synchronized cutover exists.
 */
internal object ProviderRuntime {
    @Volatile private var session: ProviderCapabilitySession? = null

    fun install(context: Context) {
        if (session != null) return
        synchronized(this) {
            if (session != null) return
            val prefs = context.applicationContext.getSharedPreferences(
                "bit_chord_provider_capabilities", Context.MODE_PRIVATE,
            )
            val mb = ProviderKey("musicbrainz")
            session = ProviderCapabilitySession(
                store = ProviderPreferenceStore(prefs),
                descriptors = listOf(ProviderDescriptor(
                    key = mb,
                    capabilities = setOf(
                        ProviderCapability.CATALOG_SEARCH,
                        ProviderCapability.EDITORIAL_DISCOGRAPHY,
                        ProviderCapability.IDENTITY,
                    ),
                    readiness = ProviderReadiness.READY,
                )),
            )
        }
    }

    /** Null only outside the installed Android runtime, e.g. plain JVM tests. */
    fun gateOrNull(): ProviderPolicyGate? = session?.gate

    /**
     * Old builds have no individual MusicBrainz toggle. Preserve the existing
     * MusicBrainz catalog/identity capabilities ONCE for older installations.
     * A saved off/empty selection in any category takes absolute precedence.
     *
     * Must execute on a background dispatcher after install(). Before this
     * completes the installed gate is closed, never permissive.
     */
    suspend fun migrateMusicBrainzLegacyAccess(): Boolean {
        val current = session ?: return false
        val key = ProviderKey("musicbrainz")
        val legacy = listOf(
            ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ProviderCapability.IDENTITY,
        ).associateWith { listOf(ProviderChoice(key, enabled = true, order = 0)) }
        return current.initialize(legacy)
    }

    /**
     * For a future native Settings switch; an explicit opt-out is applied to
     * the running HTTP gate BEFORE its durable preference commit.
     */
    suspend fun setChoices(
        capability: ProviderCapability,
        choices: List<ProviderChoice>,
    ): Boolean = session?.update(capability, choices) ?: false
}

/**
 * Indicates policy denial, not a transient server error and not a signal to
 * try the same service through another HTTP path.
 */
internal class ProviderRequestDeniedException(
    val capability: ProviderCapability,
    val providerKey: ProviderKey,
) : IllegalStateException("Provider request not permitted: $providerKey / $capability")
