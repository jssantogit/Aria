package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.AppleExploreGenrePolicy
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow

/**
 * The genre dispatcher follows the SAME ordered provider chain as Home.
 * In particular, first -> second -> third is valid for every permutation;
 * a loading predecessor, or an MB result from an older tag, is never a miss.
 */
internal fun shouldDispatchCanonicalGenreSource(
    tag: String,
    target: ProviderKey,
    ordered: List<ProviderKey>,
    mbResultTag: String?,
    mbState: UiState<List<CanonicalCatalogSearchRow>>,
    appleByTag: Map<String, UiState<List<ItunesEditorialRelease>>>,
    discogsByTag: Map<String, UiState<List<HomeShelf>>>,
): Boolean {
    if (target !in ordered) return false
    val preceding = ordered.takeWhile { it != target }
    return preceding.all { source ->
        when (source) {
            BuiltInProviderKeys.MUSICBRAINZ -> {
                if (mbResultTag != tag) false
                else when (mbState) {
                    is UiState.Error -> true
                    is UiState.Success -> musicBrainzGenreEditorialShelves(
                        mbState.data, releasesHeading = "Lançamentos",
                    ).isEmpty()
                    UiState.Loading -> false
                }
            }
            BuiltInProviderKeys.ITUNES ->
                !AppleExploreGenrePolicy.supports(tag) ||
                    editorialPrimaryMiss(appleByTag[tag])
            BuiltInProviderKeys.DISCOGS -> editorialPrimaryMiss(discogsByTag[tag])
            else -> false
        }
    }
}
