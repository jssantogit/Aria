package com.music.bitchord.ui.components

import androidx.compose.ui.unit.dp

/**
 * Shared loaded/skeleton geometry of the album's play capsule.
 *
 * The Library > Songs header already matches the Apple reference: its two
 * ActionPills share the content width after 2 * PAGE_GUTTER and a 12dp gap.
 * Use the SAME play width in the album hero; only clamp it when two 50dp
 * side controls and the album's existing gutters would otherwise overflow.
 * This is BitChord's cross-screen visual contract, not an unverified Apple
 * measurement in dp. Loading and loaded album controls must use it together.
 */
object DetailActionLayoutMetrics {
    val albumSide = 50.dp
    val albumGap = 10.dp
    val libraryCategoryGap = 12.dp
    val artistSkeletonPlay = 130.dp // Artist loading skeleton is a separate layout.

    fun libraryCategoryPlayWidth(viewportWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
        ((viewportWidth - PAGE_GUTTER * 2 - libraryCategoryGap) / 2f)
            .coerceAtLeast(0.dp)

    fun albumPlayWidth(viewportWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
        val albumRowLimit = viewportWidth.coerceAtMost(420.dp)
        val albumAvailable = (albumRowLimit - (PAGE_GUTTER + 8.dp) * 2 -
            albumSide * 2 - albumGap * 2).coerceAtLeast(0.dp)
        return libraryCategoryPlayWidth(viewportWidth).coerceAtMost(albumAvailable)
    }
}
