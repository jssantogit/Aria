package com.music.aria.data.catalog

import com.music.aria.data.lyrics.LyricsSource
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderRuntime
import com.music.aria.data.providers.ProviderSessionSnapshot
import com.music.aria.data.providers.ProviderSessionStatus
import com.music.aria.data.sources.SourceConfig

/**
 * Shared headless policy for Now Playing and background lyrics selection.
 *
 * Retired YouTube lyrics are excluded unconditionally: even old preferences
 * cannot resurrect InnerTube. Once an Android ProviderRuntime snapshot exists,
 * eligibility comes ONLY from the canonical per-source capability choices,
 * not the legacy settings or an alternative-provider fallback.
 */
internal fun allowedCanonicalLyricsSources(
    requested: Set<LyricsSource>,
    @Suppress("UNUSED_PARAMETER") canonicalDiscovery: Boolean,
    @Suppress("UNUSED_PARAMETER") configs: List<SourceConfig>,
    snapshot: ProviderSessionSnapshot? = ProviderRuntime.state?.value,
): Set<LyricsSource> {
    val candidates = requested - setOf(
        LyricsSource.YOUTUBE_MUSIC,
        LyricsSource.YOUTUBE_TRANSCRIPT,
    )
    // Pure JVM callers without an installed Android runtime keep their
    // explicit request; actual Android request admission is policy-gated.
    val current = snapshot ?: return candidates
    if (current.status == ProviderSessionStatus.BOOTING ||
        current.status == ProviderSessionStatus.STORAGE_ERROR ||
        ProviderCapability.LYRICS_LOOKUP !in current.configured
    ) return emptySet()

    val permitted = current.eligible[ProviderCapability.LYRICS_LOOKUP].orEmpty().toSet()
    val quarantined = current.quarantinedProviders
    return candidates.filterTo(LinkedHashSet()) { source ->
        val key = BuiltInProviderKeys.lyrics(source)
        key in permitted && (ProviderCapability.LYRICS_LOOKUP to key) !in quarantined
    }
}
