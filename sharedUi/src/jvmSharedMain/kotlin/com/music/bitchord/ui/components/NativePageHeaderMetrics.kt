package com.music.bitchord.ui.components

import androidx.compose.ui.unit.dp

/**
 * A single geometry contract for the lowered floating actions and large
 * headings. The root tab title band is owned by RootLandingHeaderPolicy;
 * pushed Settings/Genre headings add the SAME resting action offset as the
 * native Library category headings, after the safe floating-control inset.
 */
object NativePageHeaderMetrics {
    /** Home, Back, Search and the independent actions share this resting Y. */
    val floatingActionsRestOffset = 26.dp

    /** Relative to the fully safe top-chrome inset, never the status bar. */
    val pushedTitleClearance = floatingActionsRestOffset

    /** Match the native Library category heading's extra leading inset. */
    val pushedTitleStartInset = 16.dp

    /** Text's inner top margin plus the common pushed-category displacement. */
    val settingsPushedTitleTopPadding = 8.dp + pushedTitleClearance
}
