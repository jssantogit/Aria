package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Online release/ReleaseGroup and online artist are metadata-only entities.
 * Both reuse CanonicalCatalogEntityMenuBody and the same frosted native menu
 * frame as source-owned Library entries, with no fabricated queue callbacks.
 */
@Composable
internal fun CanonicalCatalogReleaseOverflowMenu(
    browseId: String,
    title: String,
    subtitle: String,
    cover: String?,
    @Suppress("UNUSED_PARAMETER") plateTint: Color? = null,
    expanded: Boolean,
    onDismiss: () -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState,
    anchorBounds: androidx.compose.ui.unit.IntRect? = null,
    sourceStoreUrl: String? = null,
    onSettings: () -> Unit,
    loadAmsLabels: (suspend () -> List<CatalogMetadataRecordingLabel>?)? = null,
) {
    val spec = remember(browseId,sourceStoreUrl) {
        canonicalCatalogEntityMenuSpec(browseId,sourceStoreUrl)
    }
    var ratingOpen by remember(browseId) { mutableStateOf(false) }
    var playlistOpen by remember(browseId) { mutableStateOf(false) }
    if (playlistOpen && loadAmsLabels != null) {
        CanonicalSearchMetadataPlaylistPicker(
            target=CatalogSearchPlaylistTarget(null,browseId,title,subtitle),
            onDismiss={ playlistOpen=false },loadSourceLabels=loadAmsLabels,
        )
    } else if (playlistOpen && spec?.playlistKind != null) {
        CanonicalSearchMetadataPlaylistPicker(
            target = CatalogSearchPlaylistTarget(
                kind = spec.playlistKind,
                id = spec.ratingTarget.entityId,
                title = title,
                artist = subtitle,
            ),
            onDismiss = { playlistOpen = false },
        )
    }
    if (ratingOpen && spec != null) {
        CanonicalCatalogEntityRatingDialog(
            target = spec.ratingTarget,
            title = spec.ratingLabel,
            onDismiss = { ratingOpen = false },
        )
    }

    if (expanded) {
        com.music.bitchord.FrostedMenuPanel(
            hazeState = hazeState,
            anchorBounds = anchorBounds,
            onDismiss = onDismiss,
        ) {
            if (spec != null) {
                CanonicalCatalogEntityMenuBody(
                    browseId = browseId,
                    title = title,
                    subtitle = subtitle,
                    artworkUrl = cover,
                    sourceStoreUrl = sourceStoreUrl,
                    onDismiss = onDismiss,
                    onRate = { ratingOpen = true },
                    onAddVerifiedRecordings = { playlistOpen = true },
                    onAddAmsCollection = loadAmsLabels?.let { { playlistOpen=true } },
                )
            }
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
