package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

/** The same lifted bitmap, neutral blur and action rows used for MB.
 * AMS has no verified MediaAsset, rating or local pin capability here.
 */
@Composable
internal fun AmsSearchHeldMenu(
    held: AmsSearchHeldState,
    viewModel: MainViewModel,
    hazeState: HazeState,
    bottomChromeClearance: Dp,
) {
    val session = viewModel.appleMusicWebSearchUi
    val snapshot by session.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    fun allowed(target: AmsSearchHeldTarget): Boolean {
        val live = session.state.value
        return live.selected && session.current() && live.sections.values.any { section ->
            section.items.any { item ->
                (target.row != null && amsSearchRow(item) == target.row) ||
                    (target.track != null && amsSearchTrack(item) == target.track)
            }
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
        BrowseActionsSheet(
            target=BrowseTarget(browseId=item.id,title=item.title,subtitle=item.credit,
                thumbnailUrl=item.art,type=item.type,fromCard=false),
            presentation=SongActionsPresentation.Menu,
            onOpenLabel=if (item.track != null) "Abrir álbum" else "Ver detalhes",
            onOpen={ open(item) },
            onViewSource=item.storeUrl?.let { link ->
                { if (allowed(item)) { held.dismiss(); runCatching { uri.openUri(link) }; Unit } }
            },
            viewSourceLabel="Abrir no Apple Music",
        )
    }
}

/** Context is captured with the lifted card, not reconstructed by title. */
internal data class AmsShelfHoldScope(val tab: Int, val genre: String?, val detail: String?)
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
        if (com.music.bitchord.ui.AppleMusicWebNativeRoute.parse(card.browseId) == null ||
            card.videoId != null || card.title.isBlank()) return
        origin = LongPressOrigin.consume()
        if (origin == null) return
        target = AmsShelfHeldTarget(scope,card=card)
    }
    fun holdTrack(track: NativeMetadataTrack, scope: AmsShelfHoldScope) {
        if (scope.detail == null || !track.key.startsWith("apple_music_web:") ||
            appleMusicWebVerifiedRouteUrl(track.sourceStoreUrl,track.key) == null) return
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
) {
    val latestAllowed by androidx.compose.runtime.rememberUpdatedState(allowed)
    val latestOpen by androidx.compose.runtime.rememberUpdatedState(onOpen)
    val uri = LocalUriHandler.current
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
        onDismiss=held::dismiss,onPreviewClick=if (target?.card != null) ::open else null,
    ) { item ->
        val card = item.card
        val track = item.track
        val id = card?.browseId ?: track?.key
        val route = com.music.bitchord.ui.AppleMusicWebNativeRoute.parse(id)
        val link = appleMusicWebVerifiedRouteUrl(card?.appleStoreUrl ?: track?.sourceStoreUrl,id)
        BrowseActionsSheet(
            target=BrowseTarget(browseId=id,title=card?.title ?: track?.title.orEmpty(),
                subtitle=card?.subtitle ?: track?.credit.orEmpty(),
                thumbnailUrl=card?.thumbnailUrl ?: track?.artworkUri,
                type=when {
                    route?.kind == com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind.ARTIST -> BrowseType.ARTIST
                    card != null -> BrowseType.ALBUM
                    else -> BrowseType.OTHER
                },fromCard=false),
            presentation=SongActionsPresentation.Menu,
            onOpenLabel="Ver detalhes",
            onOpen=if (card != null) ({ open(item) }) else null,
            onViewSource=link?.let { url ->
                { if (latestAllowed(item)) { held.dismiss(); runCatching { uri.openUri(url) }; Unit } }
            },
            viewSourceLabel="Abrir no Apple Music",
        )
    }
}
