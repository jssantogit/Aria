package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.amsSearchRow
import com.music.bitchord.ui.amsSearchTrack
import com.music.bitchord.ui.components.BrowseActionsSheet
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.HeldContextMenu
import com.music.bitchord.ui.components.HeldItem
import com.music.bitchord.ui.components.LongPressOrigin
import com.music.bitchord.ui.components.SongActionsPresentation
import dev.chrisbanes.haze.HazeState

internal data class AmsSearchHeldTarget(
    val row: AppleEditorialSearchRow? = null,
    val track: AppleEditorialTrackSearchRow? = null,
) {
    val id get() = row?.browseId ?: track?.key.orEmpty()
    val title get() = row?.title ?: track?.title.orEmpty()
    val credit get() = row?.artistName ?: track?.artistName.orEmpty()
    val art get() = row?.artworkUri ?: track?.artworkUri
    val storeUrl get() = appleMusicWebVerifiedRouteUrl(row?.appleStoreUrl ?: track?.appleStoreUrl,id)
    val type get() = if (row?.kind == AppleEditorialSearchKind.ARTIST) BrowseType.ARTIST
        else if (row != null) BrowseType.ALBUM else BrowseType.OTHER
}

internal class AmsSearchHeldState {
    var target by mutableStateOf<AmsSearchHeldTarget?>(null)
        private set
    var origin by mutableStateOf<HeldItem?>(null)
        private set
    val visible get() = target != null && origin != null
    fun hold(row: AppleEditorialSearchRow) {
        if (!row.browseId.startsWith("apple_music_web:")) return
        origin = LongPressOrigin.consume()
        target = AmsSearchHeldTarget(row=row)
    }
    fun holdTrack(row: AppleEditorialTrackSearchRow) {
        if (!row.key.startsWith("apple_music_web:")) return
        origin = LongPressOrigin.consume()
        target = AmsSearchHeldTarget(track=row)
    }
    fun dismiss() { target = null }
}

/** A dialog can outlive menu dismissal, but not the admission of its exact target. */
internal fun <T : Any> admittedHeldAction(target: T?, allowed: (T) -> Boolean): T? =
    target?.takeIf(allowed)

/** Local entity actions outlive the lifted card and never imply a MediaAsset. */
private data class AmsHeldRating(val spec: CanonicalCatalogEntityMenuSpec, val artwork: String?)

@Composable
private fun AmsHeldRatingDialog(rating: AmsHeldRating?, isCurrent: () -> Boolean, dismiss: () -> Unit) {
    if (rating != null) CanonicalCatalogEntityRatingDialog(
        target = rating.spec.ratingTarget, title = rating.spec.ratingLabel,
        artworkUrl = rating.artwork, onDismiss = dismiss, isCurrent = isCurrent,
    )
}

@Composable
private fun AmsHeldTrackShare(
    title: String, credit: String, sourceUrl: String?,
    allowed: () -> Boolean, dismiss: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    return {
        if (allowed()) {
            dismiss()
            val text = buildString {
                append(title)
                if (credit.isNotBlank()) append(" — ").append(credit)
                sourceUrl?.let { append("\n").append(it) }
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(share, "Compartilhar catálogo"))
        }
    }
}

/** The same lifted bitmap, neutral blur and local entity actions used for MB. */
@Composable
internal fun AmsSearchHeldMenu(
    held: AmsSearchHeldState,
    viewModel: MainViewModel,
    hazeState: HazeState,
    bottomChromeClearance: Dp,
    searchRouteActive: Boolean,
    onPreviewPlay: ((com.music.bitchord.data.model.Song) -> Unit)? = null,
    onPreviewNext: ((com.music.bitchord.data.model.Song) -> Unit)? = null,
    onPreviewQueue: ((com.music.bitchord.data.model.Song) -> Unit)? = null,
) {
    val session = viewModel.appleMusicWebSearchUi
    val snapshot by session.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val previewPolicy = com.music.bitchord.data.providers.ProviderRuntime.state?.collectAsStateWithLifecycle()?.value
    val previewAllowed = previewPolicy != null && amsPreviewPlaybackAllowed(com.music.bitchord.data.providers.ProviderRuntime.gateOrNull())
    val routeActive by androidx.compose.runtime.rememberUpdatedState(searchRouteActive)
    var pendingTarget by remember { mutableStateOf<AmsSearchHeldTarget?>(null) }
    var rating by remember { mutableStateOf<AmsHeldRating?>(null) }
    var recordingRating by remember { mutableStateOf<CatalogMetadataRecordingLabel?>(null) }
    var recordingPlaylist by remember { mutableStateOf<CatalogMetadataRecordingLabel?>(null) }
    var collection by remember { mutableStateOf<AmsSearchHeldTarget?>(null) }
    fun allowed(target: AmsSearchHeldTarget): Boolean {
        val live = session.state.value
        return routeActive && live.selected && session.current() && live.sections.values.any { section ->
            section.items.any { item ->
                (target.row != null && amsSearchRow(item) == target.row) ||
                    (target.track != null && amsSearchTrack(item) == target.track)
            }
        }
    }
    val admitted = admittedHeldAction(pendingTarget) { snapshot.selected && allowed(it) }
    LaunchedEffect(pendingTarget, admitted) {
        if (admitted == null) {
            pendingTarget = null; rating = null; recordingRating = null
            recordingPlaylist = null; collection = null
        }
    }
    if (admitted != null) {
        AmsHeldRatingDialog(rating, { allowed(admitted) }) { rating = null; pendingTarget = null }
        recordingRating?.let { CanonicalMetadataRecordingRatingDialog(it.recordingId,{ recordingRating=null; pendingTarget=null },it, isCurrent = { allowed(admitted) }) }
        recordingPlaylist?.let { CanonicalMetadataRecordingPlaylistPicker(it.recordingId,it.title,it.artist, isCurrent = { allowed(admitted) }) { recordingPlaylist=null; pendingTarget=null } }
        collection?.let { selected ->
            CanonicalSearchMetadataPlaylistPicker(
                CatalogSearchPlaylistTarget(null,selected.id,selected.title,selected.credit),
                onDismiss={ collection=null; pendingTarget=null },
                isCurrent={ allowed(admitted) },
                loadSourceLabels={ if (allowed(selected)) viewModel.loadAmsCollectionLabels(selected.id) else null },
            )
        }
    }
    val target = held.target?.takeIf { snapshot.selected && allowed(it) }
    LaunchedEffect(target,held.target) {
        if (target == null && held.target != null) held.dismiss()
    }
    fun open(item: AmsSearchHeldTarget) {
        if (!allowed(item)) return
        held.dismiss()
        item.row?.let(viewModel::openAppleEditorialEntry)
        item.track?.let(viewModel::openAppleEditorialTrackAlbum)
    }
    HeldContextMenu(
        item=target,held=held.origin,hazeState=hazeState,
        bottomChromeClearance=bottomChromeClearance,hostProvidesBlur=true,
        onDismiss=held::dismiss,onPreviewClick=::open,
    ) { item ->
        val recording = amsLocalRecordingLabel(item.id,item.title,item.credit)
        val preview = item.track?.let(::amsSearchPreviewSong).takeIf { previewAllowed }
        fun action(callback: ((com.music.bitchord.data.model.Song) -> Unit)?): (() -> Unit)? =
            if (callback == null || preview == null) null else ({
                if (allowed(item) && amsPreviewPlaybackAllowed(com.music.bitchord.data.providers.ProviderRuntime.gateOrNull())) {
                    held.dismiss(); callback(preview)
                }
            })
        if (canonicalCatalogEntityMenuSpec(item.id,item.storeUrl) != null) {
            CanonicalCatalogEntityMenuBody(
                browseId=item.id,title=item.title,subtitle=item.credit,artworkUrl=item.art,
                sourceStoreUrl=item.storeUrl,onDismiss=held::dismiss,
                onRate={ pendingTarget=item; rating=AmsHeldRating(it,item.art) },
                onOpen={ open(item) },onOpenLabel="Ver detalhes",isCurrent={ allowed(item) },
                onAddAmsCollection={ pendingTarget=item; collection=item },
            )
        } else BrowseActionsSheet(
            target=BrowseTarget(browseId=item.id,title=item.title,subtitle=item.credit,
                thumbnailUrl=item.art,type=item.type,fromCard=false),
            presentation=SongActionsPresentation.Menu,
            onPlay=action(onPreviewPlay),onPlayNext=action(onPreviewNext),onAddToQueue=action(onPreviewQueue),
            onOpenLabel=if (item.track != null) "Abrir álbum" else "Ver detalhes",
            onOpen={ open(item) },
            onEditRating=recording?.let { { if (allowed(item)) { held.dismiss(); pendingTarget=item; recordingRating=it } } },
            onAddToPlaylist=recording?.let { { if (allowed(item)) { held.dismiss(); pendingTarget=item; recordingPlaylist=it } } },
            onShare=AmsHeldTrackShare(item.title,item.credit,item.storeUrl,{ allowed(item) },held::dismiss),
            onViewSource=item.storeUrl?.let { link ->
                { if (allowed(item)) { held.dismiss(); runCatching { uri.openUri(link) }; Unit } }
            },
            viewSourceLabel="Abrir no Apple Music",
        )
    }
}

/** Context is captured with the lifted card, not reconstructed by title. */
internal data class AmsShelfHoldScope(val tab: Int, val genre: String?, val detail: String?, val chart: String? = null)
internal data class AmsShelfHeldTarget(
    val scope: AmsShelfHoldScope,
    val card: com.music.bitchord.data.model.ShelfItem? = null,
    val track: NativeMetadataTrack? = null,
) {
    init { require((card != null) != (track != null)) }
}
internal class AmsShelfHeldState {
    var target by mutableStateOf<AmsShelfHeldTarget?>(null)
        private set
    var origin by mutableStateOf<HeldItem?>(null)
        private set
    val visible get() = target != null && origin != null
    fun hold(card: com.music.bitchord.data.model.ShelfItem, scope: AmsShelfHoldScope) {
        val song = scope.detail == null && com.music.bitchord.ui.amsSongStorefront(card.browseId) != null &&
            appleMusicWebVerifiedRouteUrl(card.appleStoreUrl,card.browseId) != null
        if ((!song && com.music.bitchord.ui.AppleMusicWebNativeRoute.parse(card.browseId) == null) ||
            card.videoId != null || card.title.isBlank()) return
        origin = LongPressOrigin.consume()
        if (origin == null) return
        target = AmsShelfHeldTarget(scope,card=card)
    }
    fun holdTrack(track: NativeMetadataTrack, scope: AmsShelfHoldScope) {
        if (scope.detail == null || com.music.bitchord.ui.amsSongStorefront(track.key) == null) return
        origin = LongPressOrigin.consume()
        if (origin == null) return
        target = AmsShelfHeldTarget(scope,track=track)
    }
    fun dismiss() { target = null }
}

/** Use the same lifted native menu as search and MB, not the toolbar popup. */
@Composable
internal fun AmsShelfHeldMenu(
    held: AmsShelfHeldState,
    allowed: (AmsShelfHeldTarget) -> Boolean,
    onOpen: (AmsShelfHeldTarget) -> Unit,
    hazeState: HazeState,
    bottomChromeClearance: Dp,
    loadCollection: (suspend (String) -> List<CatalogMetadataRecordingLabel>?)? = null,
    onPreviewPlay: ((com.music.bitchord.data.model.Song) -> Unit)? = null,
    onPreviewNext: ((com.music.bitchord.data.model.Song) -> Unit)? = null,
    onPreviewQueue: ((com.music.bitchord.data.model.Song) -> Unit)? = null,
) {
    val latestAllowed by androidx.compose.runtime.rememberUpdatedState(allowed)
    val latestOpen by androidx.compose.runtime.rememberUpdatedState(onOpen)
    val uri = LocalUriHandler.current
    val previewPolicy = com.music.bitchord.data.providers.ProviderRuntime.state?.collectAsStateWithLifecycle()?.value
    val previewAllowed = previewPolicy != null && amsPreviewPlaybackAllowed(com.music.bitchord.data.providers.ProviderRuntime.gateOrNull())
    var pendingTarget by remember { mutableStateOf<AmsShelfHeldTarget?>(null) }
    var rating by remember { mutableStateOf<AmsHeldRating?>(null) }
    var recordingRating by remember { mutableStateOf<CatalogMetadataRecordingLabel?>(null) }
    var recordingPlaylist by remember { mutableStateOf<CatalogMetadataRecordingLabel?>(null) }
    var collection by remember { mutableStateOf<com.music.bitchord.data.model.ShelfItem?>(null) }
    val admitted = admittedHeldAction(pendingTarget, latestAllowed)
    LaunchedEffect(pendingTarget, admitted) {
        if (admitted == null) {
            pendingTarget = null; rating = null; recordingRating = null
            recordingPlaylist = null; collection = null
        }
    }
    if (admitted != null) {
        AmsHeldRatingDialog(rating, { latestAllowed(admitted) }) { rating = null; pendingTarget = null }
        recordingRating?.let { CanonicalMetadataRecordingRatingDialog(it.recordingId,{ recordingRating=null; pendingTarget=null },it, isCurrent = { latestAllowed(admitted) }) }
        recordingPlaylist?.let { CanonicalMetadataRecordingPlaylistPicker(it.recordingId,it.title,it.artist, isCurrent = { latestAllowed(admitted) }) { recordingPlaylist=null; pendingTarget=null } }
        collection?.let { selected ->
            CanonicalSearchMetadataPlaylistPicker(
                CatalogSearchPlaylistTarget(null,selected.browseId.orEmpty(),selected.title,selected.subtitle),
                onDismiss={ collection=null; pendingTarget=null },
                isCurrent={ latestAllowed(admitted) },
                loadSourceLabels={ if (latestAllowed(admitted)) loadCollection?.invoke(selected.browseId.orEmpty()) else null },
            )
        }
    }
    val target = held.target?.takeIf(latestAllowed)
    LaunchedEffect(target,held.target) {
        if (target == null && held.target != null) held.dismiss()
    }
    fun open(item: AmsShelfHeldTarget) {
        if (!latestAllowed(item)) { held.dismiss(); return }
        held.dismiss()
        latestOpen(item)
    }
    HeldContextMenu(
        item=target,held=held.origin,hazeState=hazeState,
        bottomChromeClearance=bottomChromeClearance,hostProvidesBlur=true,
        onDismiss=held::dismiss,onPreviewClick=if (target?.card != null ||
            target?.track?.let { amsDetailCanOpenTrackAlbum(target.scope.detail,it) } == true) ::open else null,
    ) { item ->
        val card = item.card
        val track = item.track
        val id = card?.browseId ?: track?.key
        val route = com.music.bitchord.ui.AppleMusicWebNativeRoute.parse(id)
        val song = com.music.bitchord.ui.amsSongStorefront(id) != null
        val canOpenAlbum = track?.let { amsDetailCanOpenTrackAlbum(item.scope.detail,it) } == true
        val link = appleMusicWebVerifiedRouteUrl(card?.appleStoreUrl ?: track?.sourceStoreUrl,id)
        val recording = amsLocalRecordingLabel(id,card?.title ?: track?.title.orEmpty(),card?.subtitle ?: track?.credit.orEmpty())
        val preview = (track?.let { amsHeldPreviewSong(it,item.scope.detail) }
            ?: card?.takeIf { item.scope.detail == null && item.scope.chart == null }
                ?.let(::amsShelfPreviewSong)).takeIf { previewAllowed }
        fun action(callback: ((com.music.bitchord.data.model.Song) -> Unit)?): (() -> Unit)? =
            if (callback == null || preview == null) null else ({
                if (latestAllowed(item) && amsPreviewPlaybackAllowed(com.music.bitchord.data.providers.ProviderRuntime.gateOrNull())) {
                    held.dismiss(); callback(preview)
                }
            })
        if (id != null && card != null && canonicalCatalogEntityMenuSpec(id,link) != null) {
            CanonicalCatalogEntityMenuBody(
                browseId=id,title=card.title,subtitle=card.subtitle.orEmpty(),artworkUrl=card.thumbnailUrl,
                sourceStoreUrl=link,onDismiss=held::dismiss,
                onRate={ pendingTarget=item; rating=AmsHeldRating(it,card.thumbnailUrl) },
                onOpen={ open(item) },onOpenLabel="Ver detalhes",isCurrent={ latestAllowed(item) },
                onAddAmsCollection=loadCollection?.let { { pendingTarget=item; collection=card } },
            )
        } else BrowseActionsSheet(
            target=BrowseTarget(browseId=id,title=card?.title ?: track?.title.orEmpty(),
                subtitle=card?.subtitle ?: track?.credit.orEmpty(),
                thumbnailUrl=card?.thumbnailUrl ?: track?.artworkUri,
                type=when {
                    route?.kind == com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind.ARTIST -> BrowseType.ARTIST
                    route?.kind == com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind.PLAYLIST -> BrowseType.PLAYLIST
                    card != null && !song -> BrowseType.ALBUM
                    else -> BrowseType.OTHER
                },fromCard=false),
            presentation=SongActionsPresentation.Menu,
            onPlay=action(onPreviewPlay),onPlayNext=action(onPreviewNext),onAddToQueue=action(onPreviewQueue),
            onOpenLabel=if (canOpenAlbum) "Abrir álbum" else "Ver detalhes",
            onOpen=if ((card != null && !song) || canOpenAlbum) ({ open(item) }) else null,
            onEditRating=recording?.let { { if (latestAllowed(item)) { held.dismiss(); pendingTarget=item; recordingRating=it } } },
            onAddToPlaylist=recording?.let { { if (latestAllowed(item)) { held.dismiss(); pendingTarget=item; recordingPlaylist=it } } },
            onShare=AmsHeldTrackShare(card?.title ?: track?.title.orEmpty(),
                card?.subtitle ?: track?.credit.orEmpty(),link,{ latestAllowed(item) },held::dismiss),
            onViewSource=link?.let { url ->
                { if (latestAllowed(item)) { held.dismiss(); runCatching { uri.openUri(url) }; Unit } }
            },
            viewSourceLabel="Abrir no Apple Music",
        )
    }
}


/** The source-specific route never changes the native held-card presentation. */
internal class ProviderShelfHeldState {
    var target by mutableStateOf<com.music.bitchord.data.model.ShelfItem?>(null)
        private set
    var origin by mutableStateOf<HeldItem?>(null)
        private set
    val visible get() = target != null && origin != null

    fun hold(card: com.music.bitchord.data.model.ShelfItem) {
        val captured = LongPressOrigin.consume()
        if (captured == null || card.videoId != null ||
            canonicalCatalogEntityMenuSpec(card.browseId, card.appleStoreUrl) == null ||
            com.music.bitchord.ui.providerHeldOverflowRoute(card.browseId, true, true) == null
        ) {
            captured?.releaseLift()
            return
        }
        origin = captured
        target = card
    }

    fun dismiss() { target = null }

    fun reject() {
        origin?.releaseLift()
        origin = null
        target = null
    }
}

@Composable
internal fun ProviderShelfHeldMenu(
    held: ProviderShelfHeldState,
    allowed: (com.music.bitchord.data.model.ShelfItem) -> Boolean,
    openFor: (com.music.bitchord.data.model.ShelfItem) -> (() -> Unit)?,
    hazeState: HazeState,
    bottomChromeClearance: Dp,
) {
    val latestAllowed by androidx.compose.runtime.rememberUpdatedState(allowed)
    val latestOpenFor by androidx.compose.runtime.rememberUpdatedState(openFor)
    fun open(card: com.music.bitchord.data.model.ShelfItem) {
        if (!latestAllowed(card)) return
        val action = latestOpenFor(card) ?: return
        held.dismiss()
        action()
    }
    var ratingCard by remember(held) { mutableStateOf<com.music.bitchord.data.model.ShelfItem?>(null) }
    var rating by remember(held) { mutableStateOf<AmsHeldRating?>(null) }
    val target = held.target?.takeIf(latestAllowed)
    val ratingAllowed = ratingCard?.let(latestAllowed) == true
    LaunchedEffect(target, held.target, ratingAllowed) {
        if (target == null && held.target != null) held.reject()
        if (!ratingAllowed) { ratingCard = null; rating = null }
    }
    AmsHeldRatingDialog(rating.takeIf { ratingAllowed }, { ratingCard?.let(latestAllowed) == true }) {
        rating = null
        ratingCard = null
    }
    HeldContextMenu(
        item = target, held = held.origin, hazeState = hazeState,
        bottomChromeClearance = bottomChromeClearance, hostProvidesBlur = true,
        onDismiss = held::dismiss,
        onPreviewClick = if (target?.let(latestOpenFor) != null) ::open else null,
    ) { card ->
        CanonicalCatalogEntityMenuBody(
            browseId = card.browseId.orEmpty(), title = card.title,
            subtitle = card.subtitle.orEmpty(), artworkUrl = card.thumbnailUrl,
            sourceStoreUrl = card.appleStoreUrl, onDismiss = held::dismiss,
            onRate = { ratingCard = card; rating = AmsHeldRating(it, card.thumbnailUrl) },
            isCurrent = { latestAllowed(card) },
            onOpen = if (latestOpenFor(card) != null) ({ open(card) }) else null,
            onOpenLabel = "Ver detalhes",
        )
    }
}
