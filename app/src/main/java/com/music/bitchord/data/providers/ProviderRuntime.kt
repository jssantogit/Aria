package com.music.bitchord.data.providers

import android.content.Context
import android.os.Build
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.flow.StateFlow

/**
 * Installed early in Android startup, fail-closed. MusicBrainz is currently
 * the only legacy network source migrated into this session automatically.
 *
 * Lyrics and addon switches are STILL written by AppSettings/SourceRegistry.
 * Do not snapshot their default values into this store until their writers and
 * consumers have been atomically cut over to a single authority. In particular,
 * never treat "all offered lyrics" as the user's persisted opt-in.
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
                choices = caps.associateWith {
                    listOf(ProviderChoice(mb, enabled = true, order = 0))
                },
            ),
        )
    }

    fun isAndroidEnvironment(): Boolean = installedInProcess || runCatching {
        Build.VERSION.SDK_INT != 0
    }.getOrDefault(false)

    private fun installedDescriptors(sources: List<SourceConfig>): List<ProviderDescriptor> =
        listOf(BuiltInProviderDescriptors.musicBrainz(ProviderReadiness.READY)) +
            BuiltInProviderDescriptors.offeredLyrics(ProviderReadiness.READY) +
            extensionDescriptors(sources)

    /** Source identity only. This must be evaluated AFTER SourceRegistry.init. */
    fun extensionDescriptors(sources: List<SourceConfig>): List<ProviderDescriptor> =
        sources.asSequence()
            .filter { it.kind == SourceKind.ADDON || it.kind == SourceKind.CUSTOM_MODULE }
            .distinctBy(SourceConfig::id)
            .map { config ->
                ProviderDescriptor(
                    key = ProviderKey(
                        providerId = if (config.kind == SourceKind.ADDON) "addon"
                            else "custom_module",
                        instanceId = config.id,
                    ),
                    capabilities = setOf(ProviderCapability.EXTENSION_PLAYBACK),
                    readiness = ProviderReadiness.READY,
                )
            }
            .toList()

    fun install(context: Context) {
        installedInProcess = true
        if (session != null) return
        synchronized(this) {
            if (session != null) return
            val prefs = context.applicationContext.getSharedPreferences(
                "bit_chord_provider_capabilities", Context.MODE_PRIVATE,
            )
            // SourceRegistry is not yet initialized: do NOT capture its empty
            // in-memory list and mistake it for the user's configured servers.
            session = ProviderCapabilitySession(
                store = ProviderPreferenceStore(prefs),
                initialDescriptors = installedDescriptors(emptyList()),
            )
        }
    }

    fun gateOrNull(): ProviderPolicyGate? = session?.gate

    /** Missing policy in Android must not authorize source-owned cached reads. */
    fun gateForCacheReadOrNull(): ProviderPolicyGate? =
        if (isAndroidEnvironment()) session?.gate else plainJvmTestGate

    fun sessionOrNull(): ProviderCapabilitySession? = session

    val state: StateFlow<ProviderSessionSnapshot>?
        get() = session?.state

    /**
     * Run from the startup initialization worker, AFTER SourceRegistry.init.
     * Preserve the original MusicBrainz access policy once and register the
     * restored extension identities, without migrating any active legacy
     * lyrics/addon toggle while its old setter remains the real writer.
     */
    suspend fun migrateMusicBrainzLegacyAccess(): Boolean {
        val current = session ?: return false
        current.refreshDescriptors(installedDescriptors(SourceRegistry.configs.value))
        return current.initialize(musicBrainzBaseline())
    }

    /** No lyric or extension defaults may be persisted by a startup migration. */
    fun musicBrainzBaseline(): Map<ProviderCapability, List<ProviderChoice>> {
        val mb = ProviderKey("musicbrainz")
        return listOf(
            ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ProviderCapability.IDENTITY,
        ).associateWith { listOf(ProviderChoice(mb, enabled = true, order = 0)) }
    }

    suspend fun setChoices(
        capability: ProviderCapability,
        choices: List<ProviderChoice>,
    ): Boolean = session?.update(capability, choices) ?: false
}

/** Denial is not a transient error and must never trigger a secret fallback. */
internal class ProviderRequestDeniedException(
    val capability: ProviderCapability,
    val providerKey: ProviderKey,
) : IllegalStateException("Provider request not permitted: $providerKey / $capability")
