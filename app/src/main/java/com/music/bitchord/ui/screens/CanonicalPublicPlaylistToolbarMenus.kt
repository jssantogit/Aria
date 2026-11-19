package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.ui.components.BrowseActionsSheet
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.SongActionsPresentation

/** Sort controls are anchored to the sort button, not the overflow button. */
@Composable
internal fun CanonicalPublicPlaylistSortMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    sort: PlaylistLibrarySort,
    onSort: (PlaylistLibrarySort) -> Unit,
) {
    com.music.bitchord.ui.components.NativeCompactOverflowMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        listOf(
            PlaylistLibrarySort.DEFAULT to "Ordem original",
            PlaylistLibrarySort.TITLE_ASC to "Título (A–Z)",
            PlaylistLibrarySort.TITLE_DESC to "Título (Z–A)",
        ).forEach { (option, title) ->
            com.music.bitchord.ui.components.NativeCompactMenuItem(
                label = title,
                selected = sort == option,
                onClick = {
                    onSort(option)
                    onDismiss()
                },
            )
        }
    }
}

/**
 * ListenBrainz public playlists use the same native entity action rows as
 * owned/source-backed playlists. Public MBIDs grant metadata actions and
 * import of verified recording labels, never queue/play capabilities.
 */
@Composable
internal fun CanonicalPublicPlaylistOverflowMenu(
    playlist: ListenBrainzPublicPlaylist,
    expanded: Boolean,
    onDismiss: () -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState,
    anchorBounds: androidx.compose.ui.unit.IntRect? = null,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) {
        LibraryPlaylistPinRepository.get(context.applicationContext)
    }
    val pins by repository.pins.collectAsStateWithLifecycle()
    val pin = remember(playlist.mbid, playlist.title, playlist.creator) {
        LibraryPlaylistPin("public:" + playlist.mbid, playlist.title, playlist.creator)
    }
    val pinned = pins.any { it.id == pin.id }
    val ratingTarget = remember(playlist.mbid) {
        canonicalPublicPlaylistRatingTarget(playlist.mbid)
    }
    var editing by remember(playlist.mbid) { mutableStateOf(false) }
    var importing by remember(playlist.mbid) { mutableStateOf(false) }

    if (editing && ratingTarget != null) {
        CanonicalCatalogEntityRatingDialog(
            target = ratingTarget, title = "Avaliar playlist",
            onDismiss = { editing = false },
        )
    }
    if (importing && ratingTarget != null) {
        CanonicalSearchMetadataPlaylistPicker(
            target = CatalogSearchPlaylistTarget(
                kind = null,
                id = playlist.mbid,
                title = playlist.title,
                artist = playlist.creator,
            ),
            onDismiss = { importing = false },
        )
    }

    if (expanded) {
        com.music.bitchord.FrostedMenuPanel(
            hazeState = hazeState,
            anchorBounds = anchorBounds,
            onDismiss = onDismiss,
        ) {
            BrowseActionsSheet(
                target = BrowseTarget(
                    browseId = "public:" + playlist.mbid,
                    title = playlist.title,
                    subtitle = playlist.creator,
                    type = BrowseType.PLAYLIST,
                    fromCard = false,
                ),
                presentation = SongActionsPresentation.Menu,
                onAddToPlaylist = if (ratingTarget != null) {
                    { onDismiss(); importing = true }
                } else null,
                onEditRating = if (ratingTarget != null) {
                    { onDismiss(); editing = true }
                } else null,
                isPinned = pinned,
                onTogglePin = if (validLibraryPlaylistPin(pin)) {
                    {
                        repository.toggle(pin)
                        onDismiss()
                    }
                } else null,
                onShare = {
                    onDismiss()
                    val shareText = buildString {
                        append(playlist.title).append(" — ").append(playlist.creator)
                        if (ratingTarget != null) {
                            append("\nhttps://listenbrainz.org/playlist/").append(playlist.mbid)
                        }
                    }
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(share, "Compartilhar playlist"))
                },
                onViewSource = if (ratingTarget != null) {
                    {
                        onDismiss()
                        context.startActivity(Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://listenbrainz.org/playlist/${playlist.mbid}"),
                        ))
                    }
                } else null,
                viewSourceLabel = "Ver no ListenBrainz",
                // No onPlay/onShuffle/onPlayNext/onAddToQueue or Download:
                // ListenBrainz playlist metadata is not an audio provider.
            )
            com.music.bitchord.FrostedMenuRow(
                label = "Configurações",
                checked = false,
                icon = Icons.Rounded.Settings,
                onClick = {
                    onDismiss()
                    onSettings()
                },
            )
        }
    }
}
