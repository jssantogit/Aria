package com.music.aria.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.music.aria.diagnostics.DiagnosticCategory
import com.music.aria.diagnostics.Diagnostics
import com.music.aria.playback.CanonicalCollectionIntent

/**
 * The Library route remains painted behind this full-screen Android Dialog.
 * Keep the Dialog/editor's expensive Compose call out of BitChordApp's huge
 * route-host method: inlining it there exceeded the JVM 64 KiB method limit.
 * All playlist editor callbacks and the native wizard remain unchanged.
 */
@Composable
internal fun PlaylistCreatorDialogRoute(
    initialV2Id: String?,
    initialNormalId: String?,
    startOnNormalSongs: Boolean,
    onBack: () -> Unit,
    onCollectionAction: (SmartCollectionActionSource, CanonicalCollectionIntent) -> Unit,
) {
    LaunchedEffect(initialV2Id, initialNormalId) {
        Diagnostics.event(
            category = DiagnosticCategory.UI,
            name = "playlist_wizard_entered",
            metadata = mapOf(
                "mode" to if (initialV2Id != null || initialNormalId != null) "edit" else "create",
            ),
        )
    }
    SmartPlaylistBuilderScreen(
        onBack = onBack,
        initialV2Id = initialV2Id,
        initialNormalId = initialNormalId,
        startOnNormalSongs = startOnNormalSongs,
        onSavedAction = { id, intent ->
            onCollectionAction(SmartCollectionActionSource.SavedDefinition(id), intent)
        },
        onPreviewAction = { query, intent ->
            onCollectionAction(SmartCollectionActionSource.PreviewQuery(query), intent)
        },
        onSavedV2Action = { id, intent ->
            onCollectionAction(SmartCollectionActionSource.SavedV2Definition(id), intent)
        },
        onPreviewV2Action = { query, ownerId, intent ->
            onCollectionAction(SmartCollectionActionSource.PreviewV2Query(query, ownerId), intent)
        },
    )
}
