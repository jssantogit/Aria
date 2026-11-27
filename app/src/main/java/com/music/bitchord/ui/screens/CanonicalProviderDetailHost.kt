package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import com.music.bitchord.data.model.ShelfItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebArtistShelf
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
    onSectionItemLongPress: ((ShelfItem) -> Unit)? = null,
    onMetadataTrackHold: ((NativeMetadataTrack) -> Unit)? = null,
) {
    val isDiscogs = routeKey.startsWith("discogs:")
    val isAms = routeKey.startsWith("apple_music_web:")
    val artistState = appleArtistDetail as? UiState.Success
    val liveApplePage = if (!isDiscogs && (!isAms || appleReadAllowed)) appleSharedVisual?.takeIf {
        it.page.browseId == routeKey
    } else null
    val previousApplePage = remember(routeKey) {
        mutableStateOf<AppleSharedDetail?>(null)
    }
    if (liveApplePage != null) previousApplePage.value = liveApplePage
    val applePage = liveApplePage ?: previousApplePage.value.takeIf {
        !isDiscogs && !isAms && appleReadAllowed
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
                else if (routeKey.contains(":album:") || routeKey.contains(":playlist:")) appleAlbumDetail
                else appleArtistDetail) is UiState.Error
            ) "Não foi possível carregar os detalhes." else "Carregando…",
        )
        return
    }

    val uriHandler = LocalUriHandler.current
    // One bounded continuation per deliberate scroll to the end. Never
    // eagerly drain the entire Apple catalog merely because a short first
    // page happens to fit the screen; navigation cancels this collector.
    val appleArtist = if (!isDiscogs && !isAms) artistState?.data?.takeIf {
        it.artist.browseId == routeKey
    } else null
    val canContinueApple = appleArtist?.hasMore == true &&
        appleArtist.loadingMore == false && !searching
    LaunchedEffect(routeKey, canContinueApple, appleArtist?.albums?.size) {
        if (canContinueApple) {
            snapshotFlow {
                val layout = listState.layoutInfo
                listState.isScrollInProgress &&
                    layout.totalItemsCount > 0 &&
                    (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >=
                    layout.totalItemsCount - 3
            }.first { it }
            viewModel.loadMoreAppleEditorialArtist()
        }
    }
    // Discogs artist pagination uses the exact same native shelf scroll as
    // Apple, rather than a second provider-specific footer navigation.
    val canContinueDiscogs = isDiscogs &&
        discogsPage?.page?.type == com.music.bitchord.data.model.BrowseType.ARTIST &&
        discogsPage?.hasMore == true && !searching
    LaunchedEffect(routeKey, canContinueDiscogs, discogsPage?.page?.sections) {
        if (canContinueDiscogs) {
            snapshotFlow {
                val layout = listState.layoutInfo
                listState.isScrollInProgress &&
                    layout.totalItemsCount > 0 &&
                    (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >=
                    layout.totalItemsCount - 3
            }.first { it }
            viewModel.discogsNativeCatalog.loadNextArtistPage()
        }
    }
    val amsStack by viewModel.appleMusicWebDetailNavigation.stack.collectAsStateWithLifecycle()
    val amsEntry = amsStack.lastOrNull()?.takeIf { isAms && it.route.browseId == routeKey }
    val amsPaging = applePage?.amsArtistPaging
    val canContinueAms = amsEntry != null && appleReadAllowed &&
        amsPaging?.hasMore == true && !searching
    val amsShelfView = when (activeShelf?.title) {
        "Álbuns" -> AppleMusicWebArtistShelf.STUDIO_ALBUMS
        "Singles e EPs" -> AppleMusicWebArtistShelf.SINGLES_AND_EPS
        "Álbuns essenciais" -> AppleMusicWebArtistShelf.ESSENTIAL_ALBUMS
        "Álbuns ao vivo" -> AppleMusicWebArtistShelf.LIVE_ALBUMS
        "Coletâneas" -> AppleMusicWebArtistShelf.COMPILATIONS
        "Playlists" -> AppleMusicWebArtistShelf.ARTIST_PLAYLISTS
        "Com participação em" -> AppleMusicWebArtistShelf.APPEARS_ON
        else -> null
    }
    com.music.bitchord.ui.CanonicalGenreGranularPagingEffect(
        tag=routeKey,enabled=canContinueAms && activeShelf == null,
        hasMore=amsPaging?.hasMore == true,loading=amsEntry?.pagingLoading == true,
        listState=listState,onLoadMore={ viewModel.appleMusicWebDetailNavigation.loadNextArtist(routeKey) },
    )
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
            } else if (isAms) {
                if (targetId == applePage?.albumArtistBrowseId && artistName.isNotBlank() &&
                    viewModel.appleMusicWebDetailNavigation.openRelated(targetId)) {
                    onActiveShelfChange(null)
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
        sourceFeaturedRelease = applePage?.featuredRelease,
        onArtistReleaseSearch = if (appleArtist != null)
            viewModel::searchMoreAppleEditorialArtist else null,
        onArtistReleaseLoadMore = when {
            canContinueApple -> viewModel::loadMoreAppleEditorialArtist
            canContinueAms && amsShelfView != null && amsEntry?.pagingLoading == false &&
                amsPaging?.pending?.any { it.view == amsShelfView } == true ->
                ({ viewModel.appleMusicWebDetailNavigation.loadNextArtist(routeKey,amsShelfView) })
            else -> null
        },
        onSectionItemLongPress = onSectionItemLongPress,
        onCanonicalFeaturedReleaseOpen = if (isDiscogs || isAms) null
            else viewModel::openCanonicalNativeDetail,
        onSectionItemClick = { item ->
            if (isDiscogs) {
                item.browseId?.takeIf { it.startsWith("discogs:release:") }
                    ?.let { releaseId ->
                        onActiveShelfChange(null)
                        viewModel.discogsNativeCatalog.openRelease(releaseId)
                    }
            } else if (isAms) {
                if (viewModel.appleMusicWebDetailNavigation.openRelated(item.browseId)) {
                    onActiveShelfChange(null)
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
        sourceArtistTracksRanked = applePage?.sourceArtistTracksRanked == true,
        onMetadataTrackHold = if (isAms) onMetadataTrackHold else null,
        onMetadataTrackOpenAlbum = if (isAms && appleReadAllowed &&
            (page.type == com.music.bitchord.data.model.BrowseType.ARTIST ||
                page.type == com.music.bitchord.data.model.BrowseType.PLAYLIST)) ({ track ->
            viewModel.appleMusicWebDetailNavigation.openTrackAlbum(track) {
                android.widget.Toast.makeText(viewModel.getApplication<android.app.Application>(),
                    "Não foi possível identificar o álbum.",android.widget.Toast.LENGTH_SHORT).show()
            }
        }) else null,
        sourceAlbumMetadata = if (isAms) applePage?.sourceAlbumMetadata else null,
        verifiedAppleArtistMbid = applePage?.verifiedArtistMbid,
        artistHeroOverride = applePage?.artistHeroUrl,
        songSort = songSort,
        footerContent = { palette ->
            if (isDiscogs) {
                discogsPage?.let { detail ->
                    discogsNativeDetailExtras(
                        page = detail,
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
                        )
                    }
                }
            } else if (applePage != null &&
                ((!isAms && applePage.notice != null) || applePage.attribution != null)
            ) {
                item(key = "apple:source:notes") {
                    NativeCatalogDetailNotes(
                        notice = applePage.notice.takeUnless { isAms },
                        attribution = applePage.attribution,
                        palette = palette,
                    )
                }
            }
        },
        contentPadding = contentPadding,
    )
}
