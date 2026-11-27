package com.music.aria.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.music.aria.data.catalog.metadata.ItunesEditorialRelease
import com.music.aria.data.model.HomeShelf
import com.music.aria.data.model.ShelfItem
import com.music.aria.data.model.Song
import com.music.aria.data.model.UiState
import com.music.aria.data.providers.ProviderKey
import com.music.aria.download.Downloads
import com.music.aria.download.SavedCollection
import com.music.aria.ui.screens.CanonicalCatalogSearchRow
import com.music.aria.ui.screens.CanonicalServerSearchRow

/**
 * Compose-friendly Home projection. Moving this dense card-building logic out
 * of AriaApp keeps the JVM method below 64 KiB as the catalog grows.
 */
@Composable
internal fun rememberCanonicalCuratedHomeShelves(
    downloads: List<SavedCollection>,
    cached: List<CanonicalCatalogSearchRow>,
    worldwide: List<CanonicalCatalogSearchRow>,
    localSongs: List<Song>,
    serverSongs: List<CanonicalServerSearchRow>,
    trendingMbids: List<String>,
    trendingLocal: List<Song>,
    releasesHeading: String,
    picksHeading: String,
    trendingHeading: String,
    date: String,
): List<HomeShelf> = remember(
    downloads, cached, worldwide, localSongs, serverSongs,
    trendingMbids, trendingLocal, releasesHeading, picksHeading,
    trendingHeading, date,
) {
    canonicalCuratedHomeShelves(
        savedReleases = downloads.asSequence()
            .filterNot { it.playlist }
            .map { collection ->
                ShelfItem(
                    collection.title, collection.subtitle, collection.thumbnailUrl,
                    null, Downloads.pageIdFor(collection.id),
                )
            }.toList(),
        metadata = (cached + worldwide).distinctBy { it.kind to it.musicBrainzId },
        localPicks = localSongs,
        remotePicks = serverSongs,
        trendingRecordingMbids = trendingMbids,
        trendingLocalPicks = trendingLocal,
        releasesHeading = releasesHeading,
        picksHeading = picksHeading,
        trendingHeading = trendingHeading,
        todayIsoDate = date,
    )
}

@Composable
internal fun rememberCanonicalHomeEditorialSelection(
    base: List<HomeShelf>,
    apple: UiState<List<ItunesEditorialRelease>>,
    priority: List<ProviderKey>,
    worldwideLoading: Boolean,
    releasesHeading: String,
    discoveryHeading: String,
    date: String,
    musicBrainzRows: List<CanonicalCatalogSearchRow> = emptyList(),
    picksHeading: String = "Escolha a dedo",
    albumsHeading: String = "Álbuns",
    singlesHeading: String = "Singles e EPs",
    moreHeading: String = "Mais",
    discogsEditorial: UiState<List<HomeShelf>> = UiState.Loading,
): CatalogEditorialSelection = remember(
    base, apple, priority, worldwideLoading, releasesHeading, discoveryHeading, date,
    musicBrainzRows, picksHeading, albumsHeading, singlesHeading, moreHeading,
    discogsEditorial,
) {
    projectCanonicalHomeEditorial(
        base, apple, priority, worldwideLoading, releasesHeading, discoveryHeading, date,
        musicBrainzRows, picksHeading, albumsHeading, singlesHeading, moreHeading,
        discogsEditorial,
    )
}
