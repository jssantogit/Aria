package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderKey

/**
 * Headless dispatch for existing Home. Keep this out of BitChordApp's huge
 * Compose method so new provider conditions do not inflate JVM bytecode.
 * No provider ID or data is ever converted to another provider's identity.
 */
/**
 * Uses the same *visible* MB-native release shelf that Home projects.
 * A raw query may contain only untyped/filtered rows, or personal rows may
 * fill the feed without proving the MB catalog returned a usable release.
 */
internal fun shouldStartAppleHomeEditorial(
    ordered: List<ProviderKey>,
    mbLoading: Boolean,
    visibleHomeShelves: List<HomeShelf>,
    releasesHeading: String,
    discogsState: UiState<List<HomeShelf>> = UiState.Loading,
    amsState: UiState<List<HomeShelf>> = UiState.Loading,
): Boolean {
    if (BuiltInProviderKeys.ITUNES !in ordered) return false
    return ordered.takeWhile { it != BuiltInProviderKeys.ITUNES }.all { source ->
        when (source) {
            BuiltInProviderKeys.MUSICBRAINZ -> !mbLoading &&
                musicBrainzHomeEditorialShelves(
                    visibleHomeShelves, releasesHeading,
                ).isEmpty()
            BuiltInProviderKeys.DISCOGS -> editorialPrimaryMiss(discogsState)
            BuiltInProviderKeys.APPLE_MUSIC_WEB -> editorialPrimaryMiss(amsState)
            else -> false
        }
    }
}

/** MB may be a third-choice fallback: an Apple miss does not bypass a
 * still-pending Discogs request in the middle of the user's order. */
internal fun shouldStartMusicBrainzHomeEditorial(
    ordered: List<ProviderKey>,
    appleState: UiState<List<ItunesEditorialRelease>>,
    discogsState: UiState<List<HomeShelf>> = UiState.Loading,
    amsState: UiState<List<HomeShelf>> = UiState.Loading,
): Boolean {
    if (BuiltInProviderKeys.MUSICBRAINZ !in ordered) return false
    return ordered.takeWhile { it != BuiltInProviderKeys.MUSICBRAINZ }.all { source ->
        when (source) {
            BuiltInProviderKeys.ITUNES -> editorialPrimaryMiss(appleState)
            BuiltInProviderKeys.DISCOGS -> editorialPrimaryMiss(discogsState)
            BuiltInProviderKeys.APPLE_MUSIC_WEB -> editorialPrimaryMiss(amsState)
            else -> false
        }
    }
}

/** A lower-priority provider starts only after EVERY earlier editorial source
 * has settled without eligible release shelves. Never bypass a loading source. */
internal fun shouldStartDiscogsHomeEditorial(
    ordered: List<ProviderKey>,
    mbLoading: Boolean,
    visibleHomeShelves: List<HomeShelf>,
    releasesHeading: String,
    appleState: UiState<List<ItunesEditorialRelease>>,
    amsState: UiState<List<HomeShelf>> = UiState.Loading,
): Boolean {
    if (BuiltInProviderKeys.DISCOGS !in ordered) return false
    val preceding = ordered.takeWhile { it != BuiltInProviderKeys.DISCOGS }
    return preceding.all { source ->
        when (source) {
            BuiltInProviderKeys.MUSICBRAINZ -> !mbLoading &&
                musicBrainzHomeEditorialShelves(visibleHomeShelves, releasesHeading).isEmpty()
            BuiltInProviderKeys.ITUNES -> editorialPrimaryMiss(appleState)
            BuiltInProviderKeys.APPLE_MUSIC_WEB -> editorialPrimaryMiss(amsState)
            else -> false
        }
    }
}

internal fun shouldStartAmsHomeEditorial(
    ordered: List<ProviderKey>,
    mbLoading: Boolean,
    visibleHomeShelves: List<HomeShelf>,
    releasesHeading: String,
    appleState: UiState<List<ItunesEditorialRelease>>,
    discogsState: UiState<List<HomeShelf>>,
): Boolean {
    val ams = BuiltInProviderKeys.APPLE_MUSIC_WEB
    if (ams !in ordered) return false
    return ordered.takeWhile { it != ams }.all { source ->
        when (source) {
            BuiltInProviderKeys.MUSICBRAINZ -> !mbLoading &&
                musicBrainzHomeEditorialShelves(visibleHomeShelves, releasesHeading).isEmpty()
            BuiltInProviderKeys.ITUNES -> editorialPrimaryMiss(appleState)
            BuiltInProviderKeys.DISCOGS -> editorialPrimaryMiss(discogsState)
            else -> false
        }
    }
}

internal object CanonicalHomeEditorialDispatch {
    fun preferred(
        viewModel: MainViewModel,
        ordered: List<ProviderKey>,
        force: Boolean = false,
    ) {
        when (ordered.firstOrNull()) {
            BuiltInProviderKeys.MUSICBRAINZ ->
                viewModel.loadCanonicalWorldwideReleases(force)
            BuiltInProviderKeys.ITUNES ->
                viewModel.loadAppleHomeEditorial(force)
            BuiltInProviderKeys.DISCOGS ->
                viewModel.discogsNativeCatalog.loadHomeEditorial(force)
            BuiltInProviderKeys.APPLE_MUSIC_WEB ->
                viewModel.loadAmsHomeEditorial(force)
        }
    }

    fun maybeStartApple(
        viewModel: MainViewModel,
        ordered: List<ProviderKey>,
        @Suppress("UNUSED_PARAMETER") mbLoading: Boolean,
        visibleHomeShelves: List<HomeShelf>,
        releasesHeading: String,
        discogsState: UiState<List<HomeShelf>> = UiState.Loading,
    ) {
        // Compose may have captured the old Loading=false before the
        // primary provider's LaunchedEffect starts its IO request. Always
        // dispatch the preferred MB adapter FIRST and read its current flow
        // after that synchronous admission/loading transition. This is
        // idempotent while active or in the existing positive/negative TTL.
        if (ordered.firstOrNull() == BuiltInProviderKeys.MUSICBRAINZ) {
            viewModel.loadCanonicalWorldwideReleases()
        }
        if (shouldStartAppleHomeEditorial(
            ordered,
            viewModel.canonicalWorldwideLoading.value,
            visibleHomeShelves, releasesHeading, discogsState,
            viewModel.appleMusicWebEditorialUi.state.value.home,
        )) viewModel.loadAppleHomeEditorial()
    }

    fun maybeStartDiscogs(
        viewModel: MainViewModel,
        ordered: List<ProviderKey>,
        mbLoading: Boolean,
        homeShelves: List<HomeShelf>,
        releasesHeading: String,
        appleState: UiState<List<ItunesEditorialRelease>>,
    ) {
        if (shouldStartDiscogsHomeEditorial(
            ordered, mbLoading, homeShelves, releasesHeading, appleState,
            viewModel.appleMusicWebEditorialUi.state.value.home,
        )) viewModel.discogsNativeCatalog.loadHomeEditorial()
    }

    fun maybeStartAms(
        viewModel: MainViewModel,
        ordered: List<ProviderKey>,
        homeShelves: List<HomeShelf>,
        releasesHeading: String,
    ) {
        if (shouldStartAmsHomeEditorial(ordered, viewModel.canonicalWorldwideLoading.value,
            homeShelves, releasesHeading, viewModel.appleHomeReleases.value,
            viewModel.discogsNativeCatalog.homeEditorial.value))
            viewModel.loadAmsHomeEditorial()
    }

    fun maybeStartAfterDiscogsMiss(
        viewModel: MainViewModel,
        ordered: List<ProviderKey>,
        discogsState: UiState<List<HomeShelf>>,
    ) {
        if (ordered.firstOrNull() != BuiltInProviderKeys.DISCOGS ||
            !editorialPrimaryMiss(discogsState)) return
        when (ordered.getOrNull(1)) {
            BuiltInProviderKeys.MUSICBRAINZ -> viewModel.loadCanonicalWorldwideReleases()
            BuiltInProviderKeys.ITUNES -> viewModel.loadAppleHomeEditorial()
            BuiltInProviderKeys.APPLE_MUSIC_WEB -> viewModel.loadAmsHomeEditorial()
        }
    }

    fun maybeStartMusicBrainz(
        viewModel: MainViewModel,
        ordered: List<ProviderKey>,
        appleState: UiState<List<ItunesEditorialRelease>>,
        discogsState: UiState<List<HomeShelf>> = UiState.Loading,
    ) {
        // Preferred MB is dispatched by preferred(); this only starts it
        // after every earlier provider in an arbitrary three-way order misses.
        if (ordered.firstOrNull() != BuiltInProviderKeys.MUSICBRAINZ &&
            shouldStartMusicBrainzHomeEditorial(ordered, appleState, discogsState,
                viewModel.appleMusicWebEditorialUi.state.value.home)
        ) viewModel.loadCanonicalWorldwideReleases()
    }
}
