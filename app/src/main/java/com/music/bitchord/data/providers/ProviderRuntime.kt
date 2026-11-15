package com.music.bitchord.data.providers

import android.content.Context
import android.os.Build
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-owned provider policy: installed closed before Android startup launches
 * network work. Migrates legacy lyric and extension choices into the single
 * authority session without creating dual-write discrepancies.
 */
internal object ProviderRuntime {
    @Volatile private var session: ProviderCapabilitySession? = null
    @Volatile private var installedInProcess: Boolean = false

    private val plainJvmTestGate: ProviderPolicyGate by lazy {
        val mb = ProviderKey("musicbrainz")
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

    fun isAndroidEnvironment(): Boolean = installedInProcess || runCatching {
        Build.VERSION.SDK_INT != 0
    }.getOrDefault(false)

    fun install(context: Context) {
        installedInProcess = true
        if (session != null) return
        synchronized(this) {
            if (session != null) return
            val prefs = context.applicationContext.getSharedPreferences(
                "bit_chord_provider_capabilities", Context.MODE_PRIVATE,
            )
            val lyricDescriptors = BuiltInProviderDescriptors.offeredLyrics(ProviderReadiness.READY)
            val extensionDescriptors = SourceRegistry.configs.value
                .filter { it.kind.let { k -> k == com.music.bitchord.data.sources.SourceKind.ADDON || k == com.music.bitchord.data.sources.SourceKind.CUSTOM_MODULE } }
                .map { config ->
                    ProviderDescriptor(
                        key = ProviderKey(
                            providerId = if (config.kind == com.music.bitchord.data.sources.SourceKind.ADDON) "addon" else "custom_module",
                            instanceId = config.id,
                        ),
                        capabilities = setOf(ProviderCapability.EXTENSION_PLAYBACK),
                        readiness = ProviderReadiness.READY,
                    )
                }
            val mb = BuiltInProviderDescriptors.musicBrainz(ProviderReadiness.READY)
            session = ProviderCapabilitySession(
                store = ProviderPreferenceStore(prefs),
                descriptors = listOf(mb) + lyricDescriptors + extensionDescriptors,
            )
        }
    }

    /** Null outside an installed Android runtime (e.g. plain JVM tests). */
    fun gateOrNull(): ProviderPolicyGate? = session?.gate

    /**
     * Cache reads fail closed in any Android runtime if the session gate is
     * missing; only pure non-Android JVM tests fall back to a plain test gate.
     */
    fun gateForCacheReadOrNull(): ProviderPolicyGate? =
        if (isAndroidEnvironment()) session?.gate else plainJvmTestGate

    fun sessionOrNull(): ProviderCapabilitySession? = session

    val state: StateFlow<ProviderSessionSnapshot>?
        get() = session?.state

    /**
     * Migrate legacy MusicBrainz, lyrics and extension choices into the single
     * authority session once during startup.
     */
    suspend fun migrateMusicBrainzLegacyAccess(): Boolean {
        val current = session ?: return false
        val lyricsOrder = LyricsSource.offered
        val enabledLyrics = LyricsSource.offered.toSet()
        val sources = SourceRegistry.configs.value
        return LegacyProviderCompatibility.migrateAllLegacy(
            session = current,
            lyricsOrder = lyricsOrder,
            enabledLyrics = enabledLyrics,
            sources = sources,
            includeMusicBrainzDefaults = true,
        )
    }

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
