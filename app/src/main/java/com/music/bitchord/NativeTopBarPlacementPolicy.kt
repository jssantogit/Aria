package com.music.bitchord

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.HomeFloatingActionRestOffset

/**
 * A compact global toolbar has one default vertical lane for pushed routes.
 * Only the large-heading root tabs deliberately lower their floating actions.
 *
 * Status bar insets belong to FrostedTopBar and are applied exactly once there;
 * this policy computes only the additional page-specific Y displacement.
 */
internal fun nativeTopBarActionsOffset(
    homeRootVisible: Boolean,
    homeSettingsOffset: Dp,
    libraryRootVisible: Boolean,
    exploreRootVisible: Boolean,
): Dp = when {
    homeRootVisible -> homeSettingsOffset
    libraryRootVisible || exploreRootVisible -> HomeFloatingActionRestOffset
    else -> 0.dp
}

/** Back buttons identify pushed pages and must align with their own actions. */
internal fun nativeTopBarBackOffset(): Dp = 0.dp
