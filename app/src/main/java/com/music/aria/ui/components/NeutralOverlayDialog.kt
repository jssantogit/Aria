package com.music.aria.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.aria.data.settings.AppSettings
import dev.chrisbanes.haze.HazeDialog
import dev.chrisbanes.haze.HazeState

/**
 * Shared Android window host for genuinely backdrop-blurred dialogs.
 *
 * Haze 1.3's ordinary Dialog cannot composite the prior Compose window's
 * source, even when its child has the same HazeState. HazeDialog on the
 * Android 1.5.x runtime connects those window layers explicitly. All clients
 * retain their own card geometry, actions and scrims; the host only selects
 * the rendering path. Reduced blur/missing source remains an ordinary Dialog
 * and the neutral plate itself stays fully readable.
 */
@Composable
fun NeutralOverlayDialog(
    onDismissRequest: () -> Unit,
    hazeState: HazeState?,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val reduceBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    if (hazeState != null && !reduceBlur) {
        HazeDialog(
            hazeState = hazeState,
            onDismissRequest = onDismissRequest,
            properties = properties,
            content = content,
        )
    } else {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = properties,
            content = content,
        )
    }
}
