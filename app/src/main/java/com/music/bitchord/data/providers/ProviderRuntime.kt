package com.music.bitchord.data.providers

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.StateFlow

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
    @Volatile private var installedInProcess: Boolean = false
    @Volatile private var testGateOverride: ProviderPolicyGate? = null

    private val plainUnitTestGate: ProviderPolicyGate by lazy {
        val mb = BuiltInProviderKeys.MUSICBRAINZ
        val caps = setOf(
            ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ProviderCapability.IDENTITY,
        )
        ProviderPolicyGate(
            ProviderCapabilityPolicy(
                descriptors = listOf(ProviderDescriptor(mb, caps, ProviderReadiness.READY)),
                choices = caps.associateWith { listOf(ProviderChoice(mb, enabled = true, order = 0)) },
            ),
        )
    }

    private fun isUnitTestHarness(): Boolean {
        if (installedInProcess) return false
        val isRobolectric = runCatching {
            Build.FINGERPRINT.equals("robolectric", ignoreCase = true) ||
                Class.forName("org.robolectric.Robolectric") != null
        }.getOrDefault(false)
        if (isRobolectric) return true
        val isAndroidDevice = runCatching { Build.VERSION.SDK_INT != 0 }.getOrDefault(false)
        return !isAndroidDevice
    }

    fun install(context: Context) {
        installedInProcess = true
        if (session != null) return
        synchronized(this) {
            if (session != null) return
            val prefs = context.applicationContext.getSharedPreferences(
                "bit_chord_provider_capabilities", Context.MODE_PRIVATE,
            )
            session = ProviderCapabilitySession(
                store = ProviderPreferenceStore(prefs),
                descriptors = listOf(
                    BuiltInProviderDescriptors.musicBrainz(ProviderReadiness.READY),
                    BuiltInProviderDescriptors.coverArtArchive(ProviderReadiness.READY),
                    BuiltInProviderDescriptors.itunes(ProviderReadiness.NOT_IMPLEMENTED),
                    BuiltInProviderDescriptors.appleArtistArt(ProviderReadiness.NOT_IMPLEMENTED),
                    BuiltInProviderDescriptors.discogs(
                        tokenConfigured = false,
                        implemented = false,
                    ),
                ) + BuiltInProviderDescriptors.offeredLyrics(ProviderReadiness.READY),
            )
        }
    }

    /** Null outside an installed Android runtime (e.g. plain JVM tests). */
    fun gateOrNull(): ProviderPolicyGate? = session?.gate ?: testGateOverride

    /**
     * In production Android, absence of an installed session gate fails closed
     * (returns null so ProviderCachedReadAccess denies reads). Only non-installed
     * JVM/Robolectric unit test harnesses use an explicit test gate.
     */
    fun gateForCacheReadOrNull(): ProviderPolicyGate? =
        session?.gate
            ?: testGateOverride
            ?: if (isUnitTestHarness()) plainUnitTestGate else null

    fun setTestGate(gate: ProviderPolicyGate?) {
        testGateOverride = gate
    }

    fun resetForTesting() {
        session = null
        installedInProcess = false
        testGateOverride = null
    }

    fun sessionOrNull(): ProviderCapabilitySession? = session

    val state: StateFlow<ProviderSessionSnapshot>?
        get() = session?.state

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
        val key = BuiltInProviderKeys.MUSICBRAINZ
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
