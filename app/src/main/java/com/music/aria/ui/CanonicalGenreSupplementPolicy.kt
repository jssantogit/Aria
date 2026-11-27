package com.music.aria.ui

import com.music.aria.data.catalog.metadata.AppleExploreGenrePolicy
import com.music.aria.data.catalog.metadata.ItunesEditorialRelease
import com.music.aria.data.model.UiState
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderKey

/**
 * Artist membership and recording popularity have different authorities from
 * Apple's genre-specific album releases. For Apple-primary genres only, load
 * MusicBrainz's independent tag membership *while* Apple is loading/valid.
 * If Apple actually misses, the existing full MusicBrainz fallback owns the
 * request instead, never two overlapping MusicBrainz scans.
 */
internal fun shouldSupplementAppleGenre(
    tag: String,
    ordered: List<ProviderKey>,
    appleState: UiState<List<ItunesEditorialRelease>>?,
): Boolean =
    ordered.firstOrNull() == BuiltInProviderKeys.ITUNES &&
        BuiltInProviderKeys.MUSICBRAINZ in ordered &&
        AppleExploreGenrePolicy.supports(tag) &&
        !editorialPrimaryMiss(appleState)
