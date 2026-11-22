package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.ui.DiscogsNativePage
import com.music.bitchord.ui.DiscogsNativeRoute
import com.music.bitchord.ui.discogsNativeDetailRouteKey
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.AppleCatalogBrowseRoute
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.ui.components.MessageState

/**
 * The ONE first-party provider adapter host for the canonical DetailScreen.
 *
 * This function only selects source-owned DetailPage metadata and remembers
 * the outgoing animation snapshot. It does not implement another artist or
 * album screen, navigation stack, painter, or alternate layout. MusicBrainz
 * already feeds DetailScreen directly; Apple/Discogs use the same compositor.
 *
 * Kept outside BitChordApp to avoid Kotlin's 64 KiB method code limit for the
 * huge Compose activity, especially in independently compiled TV/Wear variants.
 */
@Composable
internal fun CanonicalProviderDetailHost(
    routeKey: String,
    appleSharedVisual: AppleSharedDetail?,
    appleArtistDetail: UiState<AppleEditorialArtistDetailPage>?,
    appleAlbumDetail: UiState<AppleEditorialAlbumDetailPage>?,
    appleReadAllowed: Boolean,
    discogsNativeVisual: DiscogsNativePage?,
    discogsDetail: UiState<DiscogsNativePage>?,
    discogsReadAllowed: Boolean,
    discogsRoute: DiscogsNativeRoute?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    activeShelf: HomeShelf?,
    onActiveShelfChange: (HomeShelf?) -> Unit,
    searching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    songSort: SongSort,
    viewModel: MainViewModel,
) {
    val isDiscogs = routeKey.startsWith("discogs:")
    val artistState = appleArtistDetail as? UiState.Success
    val liveApplePage = if (!isDiscogs) appleSharedVisual?.takeIf {
        it.page.browseId == routeKey
    } else null
    val previousApplePage = remember(routeKey) {
        mutableStateOf<AppleSharedDetail?>(null)
    }
    if (liveApplePage != null) previousApplePage.value = liveApplePage
    val applePage = liveApplePage ?: previousApplePage.value.takeIf {
        !isDiscogs && appleReadAllowed
    }

    val liveDiscogsPage = if (isDiscogs) discogsNativeVisual?.takeIf {
        it.page.browseId == routeKey
    } else null
    val previousDiscogsPage = remember(routeKey) {
        mutableStateOf<DiscogsNativePage?>(null)
    }
    if (liveDiscogsPage != null) previousDiscogsPage.value = liveDiscogsPage
    val discogsPage = liveDiscogsPage ?: previousDiscogsPage.value.takeIf {
        isDiscogs && discogsReadAllowed && discogsRoute != null
    }

    val page = discogsPage?.page ?: applePage?.page
    if (page == null) {
        MessageState(
            if ((if (isDiscogs) discogsDetail
                else if (routeKey.contains(":album:")) appleAlbumDetail
                else appleArtistDetail) is UiState.Error
            ) "Não foi possível carregar os detalhes." else "Carregando…",
        )
        return
    }

    val uriHandler = LocalUriHandler.current
    DetailScreen(
        page = page,
        currentSong = null,
        isPlaying = false,
        listState = listState,
        activeShelf = activeShelf,
        onActiveShelfChange = onActiveShelfChange,
        onSongClick = { _, _ -> },
        onSongLongPress = {},
        onSongSwipe = {},
        onShuffle = {},
        onArtistClick = { targetId, artistName ->
            // Only an exact provider-owned credit ID can open an artist page.
            // Do not search by a similar title or cast to a MusicBrainz ID.
            if (isDiscogs) {
                val artistId = discogsNativeDetailRouteKey(
                    DiscogsNativeRoute.ARTIST, targetId,
                )?.removePrefix("discogs:artist:")?.toLongOrNull()
                if (targetId == discogsPage?.albumArtistBrowseId &&
                    artistId != null && artistName.isNotBlank()
                ) {
                    viewModel.discogsNativeCatalog.openArtist(
                        DiscogsNativeArtistSearchRow(artistId, artistName),
                    )
                }
            } else {
                val route = AppleCatalogBrowseRoute.parse(targetId)
                if (targetId == applePage?.albumArtistBrowseId &&
                    route?.kind == ItunesCatalogItemType.ARTIST &&
                    artistName.isNotBlank()
                ) {
                    viewModel.openAppleEditorialEntry(
                        AppleEditorialSearchRow(
                            browseId = targetId,
                            title = artistName,
                            artistName = artistName,
                            artworkUri = null,
                            releaseDate = null,
                            kind = AppleEditorialSearchKind.ARTIST,
                        ),
                    )
                }
            }
        },
        onAddSuggested = {},
        onCanonicalFeaturedReleaseOpen = if (isDiscogs) null
            else viewModel::openCanonicalNativeDetail,
        onSectionItemClick = { item ->
            if (isDiscogs) {
                item.browseId?.takeIf { it.startsWith("discogs:release:") }
                    ?.let { releaseId ->
                        onActiveShelfChange(null)
                        viewModel.discogsNativeCatalog.openRelease(releaseId)
                    }
            } else {
                val matched = appleNativeShelfEntry(
                    item.browseId,
                    artistState?.data?.albums.orEmpty() +
                        (appleAlbumDetail as? UiState.Success)
                            ?.data?.relatedAlbums.orEmpty(),
                )
                if (matched != null) {
                    onActiveShelfChange(null)
                    viewModel.openAppleEditorialEntry(matched)
                }
            }
        },
        searching = searching,
        onSearchingChange = onSearchingChange,
        metadataTracks = discogsPage?.tracks ?: applePage?.metadataTracks.orEmpty(),
        sourceAlbumArtistBrowseId = discogsPage?.albumArtistBrowseId
            ?: applePage?.albumArtistBrowseId,
        sourceAlbumArtistDisplayName = discogsPage?.albumArtistDisplayName
            ?: applePage?.albumArtistDisplayName,
        appleArtistTracks = applePage?.artistTracks.orEmpty(),
        verifiedAppleArtistMbid = applePage?.verifiedArtistMbid,
        artistHeroOverride = applePage?.artistHeroUrl,
        songSort = songSort,
        footerContent = { palette ->
            if (isDiscogs) {
                discogsPage?.let { detail ->
                    discogsNativeDetailExtras(
                        page = detail,
                        onLoadMore = viewModel.discogsNativeCatalog::loadNextArtistPage,
                        onOpenSource = uriHandler::openUri,
                    )
                }
            } else if (routeKey.startsWith("itunes:") &&
                routeKey.contains(":artist:")
            ) {
                artistState?.data?.let { artistPage ->
                    item(key = "apple:source:paging") {
                        AppleArtistCatalogExtras(
                            state = artistPage,
                            palette = palette,
                            onLoadMore = viewModel::loadMoreAppleEditorialArtist,
                            onSearchMore = viewModel::searchMoreAppleEditorialArtist,
                        )
                    }
                }
            } else if (applePage != null &&
                (applePage.notice != null || applePage.attribution != null)
            ) {
                item(key = "apple:source:notes") {
                    NativeCatalogDetailNotes(
                        notice = applePage.notice,
                        attribution = applePage.attribution,
                        palette = palette,
                    )
                }
            }
        },
        contentPadding = contentPadding,
    )
}
