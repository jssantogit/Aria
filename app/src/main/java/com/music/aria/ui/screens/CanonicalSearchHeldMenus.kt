package com.music.aria.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.aria.data.model.BrowseType
import com.music.aria.sharedui.resources.Res
import com.music.aria.sharedui.resources.apple_search_view_details
import com.music.aria.ui.components.BrowseActionsSheet
import com.music.aria.ui.components.BrowseTarget
import com.music.aria.ui.components.HeldContextMenu
import com.music.aria.ui.components.HeldItem
import com.music.aria.ui.components.SongActionsPresentation
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/** Public catalog metadata is not an audio source, regardless of a Room hit. */
internal fun canonicalSearchHeldQueueAllowed(@Suppress("UNUSED_PARAMETER") row: CanonicalCatalogSearchRow): Boolean =
    false

internal fun canonicalSearchHeldCanAddToPlaylist(kind: CanonicalSearchKind): Boolean =
    kind == CanonicalSearchKind.RECORDING || kind == CanonicalSearchKind.RELEASE ||
        kind == CanonicalSearchKind.RELEASE_GROUP

/** A no-op download row is a false capability and must never be shown. */
internal fun canonicalSearchHeldShowsDownload(@Suppress("UNUSED_PARAMETER") kind: CanonicalSearchKind): Boolean =
    false

private fun canonicalSearchBrowseType(kind: CanonicalSearchKind): BrowseType = when (kind) {
    CanonicalSearchKind.ARTIST -> BrowseType.ARTIST
    CanonicalSearchKind.RELEASE, CanonicalSearchKind.RELEASE_GROUP -> BrowseType.ALBUM
    CanonicalSearchKind.RECORDING -> BrowseType.OTHER
}

/** Long press uses the same label-first, icon-trailing entity rows as detail overflow. */
@Composable
internal fun CanonicalSearchHeldMenus(
    catalogRow: CanonicalCatalogSearchRow?,
    catalogOrigin: HeldItem?,
    publicPlaylist: CanonicalPublicPlaylistSearchRow?,
    publicPlaylistOrigin: HeldItem?,
    hazeState: HazeState,
    bottomChromeClearance: Dp = 100.dp,
    hostProvidesBlur: Boolean = false,
    plateTint: Color? = null,
    onDismissCatalog: () -> Unit,
    onDismissPublicPlaylist: () -> Unit,
    onOpenCatalog: (CanonicalCatalogSearchRow) -> Unit,
    onOpenPublicPlaylist: (CanonicalPublicPlaylistSearchRow) -> Unit,
    onAddCatalogToPlaylist: (CanonicalCatalogSearchRow) -> Unit,
    onAddPublicPlaylistToPlaylist: (CanonicalPublicPlaylistSearchRow) -> Unit,
    onRateCatalog: (CanonicalCatalogSearchRow) -> Unit,
    onRatePublicPlaylist: (CanonicalPublicPlaylistSearchRow) -> Unit,
    onPinCatalog: (CanonicalCatalogSearchRow) -> Unit,
    onPinPublicPlaylist: (CanonicalPublicPlaylistSearchRow) -> Unit,
    onShareCatalog: (CanonicalCatalogSearchRow) -> Unit,
    onSharePublicPlaylist: (CanonicalPublicPlaylistSearchRow) -> Unit,
) {
    val details = stringResource(Res.string.apple_search_view_details)
    val context = LocalContext.current
    val catalogRepository = remember(context) {
        CatalogMetadataPinRepository.get(context.applicationContext)
    }
    val publicRepository = remember(context) {
        LibraryPlaylistPinRepository.get(context.applicationContext)
    }
    val catalogPins by catalogRepository.pins.collectAsStateWithLifecycle()
    val publicPins by publicRepository.pins.collectAsStateWithLifecycle()

    HeldContextMenu(
        item = catalogRow,
        held = catalogOrigin,
        hazeState = hazeState,
        bottomChromeClearance = bottomChromeClearance,
        hostProvidesBlur = hostProvidesBlur,
        plateTint = plateTint,
        onDismiss = onDismissCatalog,
        onPreviewClick = { row -> onDismissCatalog(); onOpenCatalog(row) },
    ) { row ->
        val route = "catalog:${row.kind.name}:${row.musicBrainzId}"
        val verified = isVerifiedCatalogRecordingId(row.musicBrainzId)
        val pin = CatalogMetadataPin(
            browseId = route,
            title = row.title.trim().take(240),
            subtitle = row.subtitle.orEmpty().take(240),
            artworkUrl = trustedCatalogMetadataPinArtwork(row.artworkUri),
        )
        BrowseActionsSheet(
            target = BrowseTarget(
                browseId = route,
                title = row.title,
                subtitle = row.subtitle.orEmpty(),
                type = canonicalSearchBrowseType(row.kind),
                fromCard = false,
            ),
            presentation = SongActionsPresentation.Menu,
            onOpenLabel = details,
            onOpen = { onDismissCatalog(); onOpenCatalog(row) },
            onAddToPlaylist = if (verified && canonicalSearchHeldCanAddToPlaylist(row.kind)) {
                { onDismissCatalog(); onAddCatalogToPlaylist(row) }
            } else null,
            onEditRating = if (verified) {
                { onDismissCatalog(); onRateCatalog(row) }
            } else null,
            isPinned = catalogPins.any { it.browseId == route },
            onTogglePin = if (validCatalogMetadataPin(pin)) {
                { onDismissCatalog(); onPinCatalog(row) }
            } else null,
            onShare = if (verified) {
                { onDismissCatalog(); onShareCatalog(row) }
            } else null,
            // No Play/Next/Queue/Download: a MusicBrainz MBID is metadata.
        )
    }

    HeldContextMenu(
        item = publicPlaylist,
        held = publicPlaylistOrigin,
        hazeState = hazeState,
        bottomChromeClearance = bottomChromeClearance,
        hostProvidesBlur = hostProvidesBlur,
        plateTint = plateTint,
        onDismiss = onDismissPublicPlaylist,
        onPreviewClick = { row -> onDismissPublicPlaylist(); onOpenPublicPlaylist(row) },
    ) { row ->
        val pin = LibraryPlaylistPin("public:${row.mbid}", row.title, row.creator)
        val verified = canonicalPublicPlaylistRatingTarget(row.mbid) != null
        BrowseActionsSheet(
            target = BrowseTarget(
                browseId = "public:${row.mbid}",
                title = row.title,
                subtitle = row.creator,
                type = BrowseType.PLAYLIST,
                fromCard = false,
            ),
            presentation = SongActionsPresentation.Menu,
            onOpenLabel = details,
            onOpen = { onDismissPublicPlaylist(); onOpenPublicPlaylist(row) },
            onAddToPlaylist = if (verified) {
                { onDismissPublicPlaylist(); onAddPublicPlaylistToPlaylist(row) }
            } else null,
            onEditRating = if (verified) {
                { onDismissPublicPlaylist(); onRatePublicPlaylist(row) }
            } else null,
            isPinned = publicPins.any { it.id == pin.id },
            onTogglePin = if (validLibraryPlaylistPin(pin)) {
                { onDismissPublicPlaylist(); onPinPublicPlaylist(row) }
            } else null,
            onShare = if (verified) {
                { onDismissPublicPlaylist(); onSharePublicPlaylist(row) }
            } else null,
            // Public ListenBrainz playlist metadata cannot be played or downloaded.
        )
    }
}
