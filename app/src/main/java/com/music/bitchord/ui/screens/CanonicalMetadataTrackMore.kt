package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.theme.ArtworkPalette

/** One source-neutral metadata row menu for verified MusicBrainz recordings.
 * Unlike playback, rating and external lookup do not require a MediaAsset. */
@Composable
internal fun CanonicalMetadataTrackMore(
    recordingId: String?,
    title: String,
    artist: String,
    palette: ArtworkPalette,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onRate: (String) -> Unit,
    onAddToPlaylist: (String, String, String) -> Unit,
    tag: String,
    showButton: Boolean = true,
) {
    if (recordingId == null || !isVerifiedCatalogRecordingId(recordingId)) return
    Box {
        if (showButton) RowMoreButton(
            onClick = onOpen,
            tint = palette.onBackgroundVariant,
            contentDescription = "Ações da faixa",
            modifier = Modifier.testTag(tag),
        )
        com.music.bitchord.ui.components.NativeCompactOverflowMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            plateTint = palette.elevated,
        ) {
            CanonicalMetadataRecordingHeldActions(
                recordingId = recordingId,
                title = title,
                artist = artist,
                onDismiss = onDismiss,
                onRate = onRate,
                onAddToPlaylist = onAddToPlaylist,
            )
        }
    }
}

/** Actions for a verified catalog RecordingId. May be hosted in the shared
 * frosted HeldContextMenu, including metadata-only tracks without MediaAsset.
 * No provider availability or playback is inferred from the MBID. */
@Composable
internal fun CanonicalMetadataRecordingHeldActions(
    recordingId: String,
    title: String,
    artist: String,
    onDismiss: () -> Unit,
    onRate: (String) -> Unit,
    onAddToPlaylist: (String, String, String) -> Unit,
) {
    if (!isVerifiedCatalogRecordingId(recordingId)) return
    val context = LocalContext.current.applicationContext
            com.music.bitchord.ui.components.NativeCompactMenuItem(
                label = "Avaliar faixa",
                icon = com.music.bitchord.ui.icons.PlaylistEditorIcons.RatingPlain,
                onClick = {
                    onDismiss()
                    onRate(recordingId)
                },
            )
            com.music.bitchord.ui.components.NativeCompactMenuItem(
                label = "Adicionar à playlist",
                icon = com.music.bitchord.ui.icons.PlaylistEditorIcons.Add,
                onClick = {
                    onDismiss()
                    onAddToPlaylist(recordingId, title, artist)
                },
            )
            com.music.bitchord.ui.components.NativeCompactMenuItem(
                label = "Ver no MusicBrainz",
                icon = com.music.bitchord.ui.icons.PlaylistEditorIcons.Link,
                onClick = {
                    onDismiss()
                    val url = "https://musicbrainz.org/recording/$recordingId"
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            )

}
