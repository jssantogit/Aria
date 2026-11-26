package com.music.aria.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared geometry for every floating Back/action row and the large titles.
 * The neutral 40dp buttons are aligned once at the host; screen-specific
 * titles use the safe inset that ALREADY includes the full button bounds.
 *
 * Physical AtHeart reference (21 Nov 2026): move the complete 40dp
 * action row 8dp upward without shrinking its touch target.
 */
object NativePageHeaderMetrics {
    /** The single rest offset consumed by the global and standalone bars. */
    val floatingActionsRestOffset = 18.dp

    /** Extra breathing room after the bar's existing 12dp safety clearance. */
    val pushedTitleTopGap = 8.dp

    /** Historical callers should not reapply the floating action offset. */
    val pushedTitleClearance = 0.dp

    /** Native Library category and pushed Settings headings share this gap. */
    val settingsPushedTitleTopPadding = pushedTitleTopGap

    /** Keep the category title aligned with Library's 32dp leading gutter. */
    val pushedTitleStartInset = 16.dp

    /** Safe title origin, not a second status-bar or action-row offset. */
    fun categoryTitleTopInset(safeContentTop: Dp): Dp =
        safeContentTop + pushedTitleTopGap
}
