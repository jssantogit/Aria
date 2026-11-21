package com.music.bitchord

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.HomeFloatingActionRestOffset

/**
 * Home at rest is the established canonical Y for all round top controls.
 * Preserve it on nested routes too; only Search's inline field owns the
 * compact base lane, and Home retracts its launcher while scrolling.
 *
 * FrostedTopBar owns the status-bar inset; this policy adds only the
 * page-specific offset and must never add system insets a second time.
 */
internal fun nativeTopBarActionsOffset(
    homeRootVisible: Boolean,
    homeSettingsOffset: Dp,
    searchRootVisible: Boolean,
): Dp = when {
    homeRootVisible -> homeSettingsOffset
    searchRootVisible -> 0.dp
    else -> HomeFloatingActionRestOffset
}

/** The Back circle shares the approved Home Y with trailing top actions. */
internal fun nativeTopBarBackOffset(): Dp = HomeFloatingActionRestOffset

/** Playlists still reserve clearance below the floating Home-aligned Back. */
internal fun playlistLibraryHeadingExtraTopPadding(): Dp =
    8.dp + HomeFloatingActionRestOffset
