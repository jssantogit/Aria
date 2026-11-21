package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCapturedSwipeRowBackgroundTest {
    @Test fun restingTrackNeverShowsAnOpaqueSwipeBacking() {
        val page = Color(0xFF16494A)
        assertEquals(Color.Transparent, nativeSwipeRowCoverAtOffset(page, 0f))
        assertEquals(Color.Transparent, nativeSwipeRowCoverAtOffset(page, 0.2f))
        assertEquals(Color.Transparent, nativeSwipeRowCoverAtOffset(page, Float.NaN))
        assertEquals(Color.Transparent,
            nativeCapturedSwipeRowBackground(
                nativeSwipeRowCoverAtOffset(page, 0f), Color.Transparent,
            ),
        )
    }

    @Test fun swipingEitherDirectionKeepsTheSolidQueueRevealCover() {
        val page = Color(0xFF16494A)
        assertEquals(page, nativeSwipeRowCoverAtOffset(page, 0.6f))
        assertEquals(page, nativeSwipeRowCoverAtOffset(page, -180f))
        val selected = Color.White.copy(alpha = 0.14f)
        assertEquals(selected.compositeOver(page),
            nativeCapturedSwipeRowBackground(nativeSwipeRowCoverAtOffset(page, 40f), selected))
    }

    @Test fun unselectedSwipeRowKeepsThePageCoverInsideItsSnapshot() {
        val cover = Color(0xFF16494A)
        assertEquals(cover, nativeCapturedSwipeRowBackground(cover, Color.Transparent))
    }

    @Test fun selectedSwipeRowCapturesThePageCoverAndSelectionTintTogether() {
        val cover = Color(0xFF16494A)
        val selected = Color.White.copy(alpha = 0.14f)
        assertEquals(selected.compositeOver(cover), nativeCapturedSwipeRowBackground(cover, selected))
    }
}
