package com.music.bitchord.data.catalog

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.data.providers.ProviderSessionSnapshot
import com.music.bitchord.data.providers.ProviderSessionStatus
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import java.util.Locale

/**
 * YouTube lyrics and captions make InnerTube requests even when the audio
 * is local or comes from an authorized remote provider. Honor the same
 * metadata-discovery and source opt-out boundary without silently removing
 * independent lyric providers (LRCLIB, local embedded lyrics, etc.).
 *
 * When Provider Engine 2.0 has authoritative [ProviderCapability.LYRICS_LOOKUP]
 * policy loaded, it also enforces per-source opt-out and complete deactivation
 * without hidden fallback.
 */
internal fun allowedCanonicalLyricsSources(
    requested: Set<LyricsSource>,
    canonicalDiscovery: Boolean,
    configs: List<SourceConfig>,
    snapshot: ProviderSessionSnapshot? = ProviderRuntime.state?.value,
): Set<LyricsSource> {
    val youtubeDisabled = configs.any {
        it.kind == SourceKind.YOUTUBE && !it.enabled
    }
    val baseAllowed = if (!canonicalDiscovery && !youtubeDisabled) {
        requested
    } else {
        requested - setOf(
            LyricsSource.YOUTUBE_MUSIC,
            LyricsSource.YOUTUBE_TRANSCRIPT,
        )
    }

    if (snapshot == null) return baseAllowed
    if (snapshot.status == ProviderSessionStatus.STORAGE_ERROR) return emptySet()

    val hasAuthoritativeLyrics = ProviderCapability.LYRICS_LOOKUP in snapshot.configured
    if (!hasAuthoritativeLyrics) return baseAllowed

    val eligibleIds = snapshot.eligible[ProviderCapability.LYRICS_LOOKUP]
        .orEmpty()
        .mapTo(HashSet()) { it.providerId }
    return baseAllowed.filterTo(LinkedHashSet()) { source ->
        val id = BuiltInProviderKeys.lyrics(source).providerId.lowercase(Locale.ROOT)
        id in eligibleIds &&
            (ProviderCapability.LYRICS_LOOKUP to BuiltInProviderKeys.lyrics(source)) !in
            snapshot.quarantinedProviders
    }
}
