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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.catalog.metadata.DiscogsAppleArtworkResolver
import com.music.bitchord.data.catalog.metadata.DiscogsVerifiedAppleArtwork
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.LocalUriHandler
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.ui.DiscogsNativePage
import com.music.bitchord.ui.DiscogsNativeRoute
import com.music.bitchord.ui.discogsNativeDetailRouteKey
import com.music.bitchord.ui.discogsNativeArtistCanContinue
import com.music.bitchord.ui.discogsNativePageDisplayAllowed
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
) {
    val isDiscogs = routeKey.startsWith("discogs:")
    val policySnapshot = ProviderRuntime.state?.collectAsStateWithLifecycle()?.value
    val gate = ProviderRuntime.gateOrNull()
    val detailTicket = if (policySnapshot != null) gate?.open(
        ProviderCapability.EDITORIAL_DISCOGRAPHY,
        if (isDiscogs) BuiltInProviderKeys.DISCOGS else BuiltInProviderKeys.ITUNES,
    ) else null
    val detailAuthorized = detailTicket != null && gate?.accept(detailTicket) == true
    val searchAuthorized = policySnapshot != null && gate?.open(
        ProviderCapability.CATALOG_SEARCH,
        if (isDiscogs) BuiltInProviderKeys.DISCOGS else BuiltInProviderKeys.ITUNES,
    ) != null
    val artistState = appleArtistDetail as? UiState.Success
    val liveApplePage = if (!isDiscogs) appleSharedVisual?.takeIf {
        it.page.browseId == routeKey &&
            appleNativeDetailDisplayAllowed(it, detailAuthorized, searchAuthorized)
    } else null
    val previousApplePage = remember(routeKey, gate, detailTicket, appleReadAllowed) {
        mutableStateOf<AppleSharedDetail?>(null)
    }
    if (liveApplePage != null && appleReadAllowed && detailTicket != null)
        previousApplePage.value = liveApplePage
    val applePage = liveApplePage ?: previousApplePage.value.takeIf {
        !isDiscogs && appleReadAllowed && detailTicket != null &&
            gate?.accept(detailTicket) == true
    }

    val liveDiscogsPage = if (isDiscogs) discogsNativeVisual?.takeIf {
        it.page.browseId == routeKey &&
            discogsNativePageDisplayAllowed(it, detailAuthorized, searchAuthorized)
    } else null
    val previousDiscogsPage = remember(routeKey, gate, detailTicket, discogsReadAllowed) {
        mutableStateOf<DiscogsNativePage?>(null)
    }
    if (liveDiscogsPage != null && discogsReadAllowed && detailTicket != null)
        previousDiscogsPage.value = liveDiscogsPage
    val discogsPage = liveDiscogsPage ?: previousDiscogsPage.value.takeIf {
        isDiscogs && discogsReadAllowed && discogsRoute != null &&
            detailTicket != null && gate?.accept(detailTicket) == true
    }

    // The live exact Discogs edition supplies metadata, not artwork authority.
    // Key state by the release and artwork lease so toggling Capas removes the
    // old image immediately and starts a new lookup when re-enabled.
    val artTicket = if (policySnapshot != null && discogsReadAllowed)
        gate?.open(ProviderCapability.RELEASE_ARTWORK, BuiltInProviderKeys.ITUNES)
    else null
    val artworkRecord = liveDiscogsPage?.artworkEvidence?.takeIf {
        discogsReadAllowed && routeKey == "discogs:release:${it.link.id}"
    }
    val artworkResolver = remember { DiscogsAppleArtworkResolver() }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val artworkDimensions = remember(appContext) {
        com.music.bitchord.data.catalog.metadata.AppleHighResolutionArtwork(
            persistence = com.music.bitchord.data.catalog.metadata
                .AndroidAppleArtworkProbePersistence(appContext),
        )
    }
    var coverProof by remember(routeKey, artworkRecord, gate, artTicket, detailTicket) {
        mutableStateOf<DiscogsVerifiedAppleArtwork?>(null)
    }
    LaunchedEffect(routeKey, artworkRecord, gate, artTicket, detailTicket) {
        if (artworkRecord == null || artTicket == null || detailTicket == null)
            return@LaunchedEffect
        fun current() = ProviderRuntime.gateOrNull() === gate &&
            gate != null && gate.accept(artTicket) && gate.accept(detailTicket)
        coverProof = try {
            withContext(Dispatchers.IO) {
                val proof = withTimeoutOrNull(12_000L) {
                    artworkResolver.resolve(artworkRecord)
                }?.takeIf { current() && it.discogsReleaseId == artworkRecord.link.id }
                    ?: return@withContext null
                val upgraded = withTimeoutOrNull(12_000L) {
                    artworkDimensions.upgrade(proof.artworkUrl, 1024)
                }
                if (!current()) return@withContext null
                proof.copy(artworkUrl = upgraded ?: proof.artworkUrl)
            }?.takeIf { current() }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
    }
    val verifiedCover = coverProof?.takeIf {
        artTicket != null && detailTicket != null &&
            ProviderRuntime.gateOrNull() === gate &&
            gate != null && gate.accept(artTicket) && gate.accept(detailTicket) &&
            artworkRecord?.link?.id == it.discogsReleaseId
    }
    val page = discogsPage?.page?.let {
        if (verifiedCover != null) it.copy(thumbnailUrl = verifiedCover.artworkUrl) else it
    } ?: applePage?.page
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
    // One bounded continuation per deliberate scroll to the end. Never
    // eagerly drain the entire Apple catalog merely because a short first
    // page happens to fit the screen; navigation cancels this collector.
    val appleArtist = if (!isDiscogs && appleReadAllowed && detailAuthorized) artistState?.data?.takeIf {
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
    val canContinueDiscogs = isDiscogs && detailAuthorized &&
        discogsNativeArtistCanContinue(discogsPage, searching)
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
        sourceFeaturedRelease = applePage?.featuredRelease,
        onArtistReleaseSearch = if (appleArtist != null)
            viewModel::searchMoreAppleEditorialArtist else null,
        onArtistReleaseLoadMore = if (canContinueApple)
            viewModel::loadMoreAppleEditorialArtist else null,
        onSectionItemLongPress = onSectionItemLongPress,
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
                val matched = appleNativeShelfEntryForRoute(
                    routeKey, item.browseId, appleReadAllowed && detailAuthorized,
                    artistState?.data, (appleAlbumDetail as? UiState.Success)?.data,
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
        sourceArtworkStoreUrl = verifiedCover?.storeUrl,
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
                appleArtist?.let { artistPage ->
                    item(key = "apple:source:paging") {
                        AppleArtistCatalogExtras(
                            state = artistPage,
                            palette = palette,
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
