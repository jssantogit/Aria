package com.music.bitchord.ui.components

import androidx.compose.ui.unit.dp

/**
 * Shared loading/loaded geometry of the Apple-style public album controls.
 * The skeleton exposed the intended compact proportions; keep the live
 * Shuffle / Play / Library row on those exact same measurements.
 *
 * This is a visual layout contract. The clickable hit targets and actions are
 * still owned by the actual controls.
 */
object DetailActionLayoutMetrics {
    val albumSide = 50.dp
    val albumPlay = 130.dp
    val albumGap = 10.dp
}
