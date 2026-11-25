package com.music.aria.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/**
 * One compact ⋯ menu for the app's floating top bars and the playlist wizard.
 * The root Settings launcher is the geometry source of truth: 200dp, 54dp rows,
 * 18dp corners, trailing icons, neutral frosted material and anchored fade.
 *
 * Do not let the number or wording of actions grow the plate to the oversized
 * held-context menu. Full held-item menus intentionally use a separate style.
 */
@Composable
internal fun NativeToolbarOverflowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    NativeCompactOverflowMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        minMenuWidth = APPLE_SETTINGS_SINGLE_ACTION_WIDTH,
        maxMenuWidth = APPLE_SETTINGS_SINGLE_ACTION_WIDTH,
        homePlateStyle = APPLE_SETTINGS_SINGLE_ACTION_USES_LARGE_ROWS,
        content = content,
    )
}
