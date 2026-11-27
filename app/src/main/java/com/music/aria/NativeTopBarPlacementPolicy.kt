package com.music.aria

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.aria.ui.components.HomeFloatingActionRestOffset

/**
 * One physical toolbar lane for the complete app: circular Back/actions and
 * Search's editable field all rest at Home's +34dp Y. Only the Home overflow
 * retracts to the toolbar's base lane when its large heading scrolls away.
 *
 * FrostedTopBar owns the status-bar inset; callers must never apply it twice.
 */
internal fun nativeTopBarActionsOffset(
    homeRootVisible: Boolean,
    homeSettingsOffset: Dp,
): Dp = if (homeRootVisible) homeSettingsOffset else HomeFloatingActionRestOffset

/** Back circles share the same Y as their trailing actions. */
internal fun nativeTopBarBackOffset(): Dp = HomeFloatingActionRestOffset

/** The playlist list starts beneath the floating circles already. */
internal fun playlistLibraryHeadingExtraTopPadding(): Dp = 8.dp
