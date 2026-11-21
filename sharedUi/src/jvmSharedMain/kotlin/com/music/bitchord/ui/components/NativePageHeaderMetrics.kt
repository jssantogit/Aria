package com.music.bitchord.ui.components

import androidx.compose.ui.unit.dp

/**
 * Shared positions for large in-page headings beneath floating top controls.
 * The global top bar owns its 40dp circles; these offsets apply only to the
 * scrollable heading, never to the status bar or the action hit targets.
 *
 * Physical video review (21 Nov 2026): the old Home title was lower than
 * Explore/Library, while pushed Settings/Genre titles touched the Back puck.
 */
object NativePageHeaderMetrics {
    /** Bring Home's resting title into the same root band as Explore/Library. */
    val homeRootTitleLift = 48.dp

    /** Extra clearance for the title beneath a pushed page's floating Back. */
    val pushedTitleClearance = 28.dp

    /** Indent a pushed category title to the native Library category gutter. */
    val pushedTitleStartInset = 16.dp

    /** Standard title top padding inside a pushed Settings page. */
    val settingsPushedTitleTopPadding = 8.dp + pushedTitleClearance
}
