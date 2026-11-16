package com.music.bitchord.data.providers

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import java.util.Locale

/**
 * Single-authority cutover coordinator for legacy settings (AppSettings lyrics
 * and SourceRegistry extensions/servers).
 *
 * Eliminates dual-write and conflicting state:
 * - Reads project exclusively from [ProviderCapabilitySession] once initialized.
 * - Writes mutate exclusively [ProviderCapabilitySession] (never a second store).
 * - Retired providers (JioSaavn, YouTube) are filtered out safely without
 *   deleting historical IDs or private server URLs.
 */
internal class ProviderAuthorityCoordinator(
    private val session: ProviderCapabilitySession,
) {
    suspend fun migrateOnce(
        lyricsOrder: List<LyricsSource>,
        enabledLyrics: Set<LyricsSource>,
        sources: List<SourceConfig>,
        includeMusicBrainzBaseline: Boolean = true,
        includeDefaultArtworkBaseline: Boolean = true,
        lastfmConfigured: Boolean = false,
        listenBrainzConfigured: Boolean = false,
        lastfmEnabled: Boolean = false,
        listenBrainzEnabled: Boolean = false,
    ): Boolean {
        val cleanedSources = sources.filterNot {
            it.kind == SourceKind.JIOSAAVN || it.kind == SourceKind.YOUTUBE
        }
        val descriptors = ProviderCapabilityRegistry.descriptorsFrom(
            ProviderCapabilityRegistry.inventory(
                sources = cleanedSources,
                lastfmConfigured = lastfmConfigured,
                listenBrainzConfigured = listenBrainzConfigured,
            ),
        )
        session.refreshDescriptors(descriptors)

        val imported = mutableMapOf<ProviderCapability, List<ProviderChoice>>()
        if (includeMusicBrainzBaseline) {
            imported.putAll(ProviderRuntime.musicBrainzBaseline())
        }
        if (includeDefaultArtworkBaseline) {
            imported[ProviderCapability.RELEASE_ARTWORK] = listOf(
                ProviderChoice(BuiltInProviderKeys.COVER_ART_ARCHIVE, enabled = true, order = 0),
            )
            imported[ProviderCapability.ARTIST_ARTWORK] = listOf(
                ProviderChoice(BuiltInProviderKeys.APPLE_ARTIST_ART, enabled = true, order = 0),
            )
        }
        imported[ProviderCapability.LISTENING_STATISTICS] = listOf(
            ProviderChoice(BuiltInProviderKeys.LASTFM, enabled = lastfmEnabled && lastfmConfigured, order = 0),
            ProviderChoice(BuiltInProviderKeys.LISTENBRAINZ, enabled = listenBrainzEnabled && listenBrainzConfigured, order = 1),
        )
        imported.putAll(legacyProviderChoices(lyricsOrder, enabledLyrics, cleanedSources))
        return session.initialize(imported)
    }

    suspend fun onSourcesChanged(rawSources: List<SourceConfig>) {
        val cleanedSources = rawSources.filterNot {
            it.kind == SourceKind.JIOSAAVN || it.kind == SourceKind.YOUTUBE
        }
        val descriptors = ProviderCapabilityRegistry.descriptorsFrom(
            ProviderCapabilityRegistry.inventory(sources = cleanedSources),
        )
        session.refreshDescriptors(descriptors)
    }

    fun currentLyricsProjection(
        fallbackOrder: List<LyricsSource>,
        fallbackEnabled: Set<LyricsSource>,
    ): LegacyLyricsProjection = LegacyProviderCompatibility.projectLyrics(
        snapshot = session.state.value,
        fallbackOrder = fallbackOrder,
        fallbackEnabled = fallbackEnabled,
    )

    suspend fun setLyricsPreferences(
        order: List<LyricsSource>,
        enabled: Set<LyricsSource>,
    ): Boolean = LegacyProviderCompatibility.writeLyrics(
        session = session,
        order = order,
        enabled = enabled,
    )

    suspend fun setSingleLyricSourceEnabled(
        source: LyricsSource,
        enabled: Boolean,
        fallbackOrder: List<LyricsSource>,
        fallbackEnabled: Set<LyricsSource>,
    ): Boolean {
        if (source !in LyricsSource.offered || source.hidden) return false
        val current = currentLyricsProjection(fallbackOrder, fallbackEnabled)
        val nextEnabled = if (enabled) current.enabled + source else current.enabled - source
        return setLyricsPreferences(current.order, nextEnabled)
    }

    suspend fun reorderLyricSources(
        newOrder: List<LyricsSource>,
        fallbackOrder: List<LyricsSource>,
        fallbackEnabled: Set<LyricsSource>,
    ): Boolean {
        val current = currentLyricsProjection(fallbackOrder, fallbackEnabled)
        return setLyricsPreferences(newOrder, current.enabled)
    }

    fun currentExtensionsProjection(rawSources: List<SourceConfig>): List<SourceConfig> =
        LegacyProviderCompatibility.projectExtensions(
            snapshot = session.state.value,
            rawSources = rawSources,
        )

    suspend fun setExtensionEnabled(
        configId: String,
        enabled: Boolean,
        rawSources: List<SourceConfig>,
    ): Boolean {
        val extensions = currentExtensionsProjection(rawSources).map { config ->
            if (config.id == configId) config.copy(enabled = enabled) else config
        }
        return LegacyProviderCompatibility.writeExtensions(session, extensions)
    }

    suspend fun reorderExtensions(
        orderedIds: List<String>,
        rawSources: List<SourceConfig>,
    ): Boolean {
        val current = currentExtensionsProjection(rawSources)
        val byId = current.associateBy { it.id }
        val reordered = orderedIds.mapNotNull { byId[it] } +
            current.filterNot { it.id in orderedIds.toSet() }
        return LegacyProviderCompatibility.writeExtensions(session, reordered)
    }

    fun isLyricSourcePermitted(source: LyricsSource): Boolean {
        if (source !in LyricsSource.offered || source.hidden) return false
        val snapshot = session.state.value
        if (snapshot.status == ProviderSessionStatus.STORAGE_ERROR) return false
        val key = BuiltInProviderKeys.lyrics(source)
        if ((ProviderCapability.LYRICS_LOOKUP to key) in snapshot.quarantinedProviders) return false
        return session.gate.open(ProviderCapability.LYRICS_LOOKUP, key) != null
    }
}
