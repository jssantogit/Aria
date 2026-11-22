package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityEvidence
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow

/** Distinct provider-owned signals; no duplication of all-time and WEEK charts. */
internal data class CanonicalHomeCommunityShelves(
    val community: HomeShelf?,
    val weeklyArtists: HomeShelf?,
    val weeklyTrending: HomeShelf?,
)

@Composable
internal fun rememberCanonicalHomeCommunityShelves(
    cachedRows: List<CanonicalCatalogSearchRow>,
    publicPopularity: List<ListenBrainzPopularityEvidence>,
    sitewide: CanonicalSitewideDiscoveryState,
    nowMs: Long,
    authorized: Boolean,
    communityHeading: String,
    trendingHeading: String,
): CanonicalHomeCommunityShelves = remember(
    cachedRows, publicPopularity, sitewide, nowMs, authorized, communityHeading,
    trendingHeading,
) {
    if (!authorized) return@remember CanonicalHomeCommunityShelves(null, null, null)
    val fresh = canonicalSitewideDiscoveryIsFresh(sitewide, nowMs)
    val weekly = if (fresh) canonicalSitewideCommunityShelf(
        rows = cachedRows + sitewide.verifiedRows,
        rankings = sitewide.recordings,
        heading = trendingHeading,
    ) else null
    val community = weekly ?: canonicalPublicPopularityShelf(
        rows = cachedRows, evidence = publicPopularity, heading = communityHeading,
    )
    val artists = if (fresh) canonicalWeeklyChartArtistsShelf(
        rows = cachedRows, rankings = sitewide.recordings,
    ) else null
    CanonicalHomeCommunityShelves(community, artists, weekly)
}

/**
 * Home section order and gating live outside the 64 KiB BitChordApp method.
 * Preserves independent editorial, chart, community, personal and local data.
 */
@Composable
internal fun rememberCanonicalHomeDisplayShelves(
    local: List<HomeShelf>,
    selected: CatalogEditorialSelection,
    playlists: HomeShelf?,
    territories: HomeShelf?,
    community: HomeShelf?,
    personal: HomeShelf?,
    weeklyArtists: HomeShelf?,
): List<HomeShelf> = remember(
    local, selected, playlists, territories, community, personal, weeklyArtists,
) {
    projectCanonicalHomeDisplay(
        canonicalHomeShelves = local,
        selection = selected,
        publicPlaylistShelf = playlists,
        countryChartShelf = territories,
        communityShelf = community,
        personalRecommendationShelf = personal,
        chartArtistsShelf = weeklyArtists,
    )
}
