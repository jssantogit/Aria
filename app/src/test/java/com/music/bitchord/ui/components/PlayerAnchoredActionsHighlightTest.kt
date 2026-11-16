package com.music.bitchord.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import com.music.bitchord.ui.player.originalPortraitNowPlayingOverflowGlyph
import org.junit.Test

/** The expanded player menu's highlighted ⋯ sits on its real launcher,
 * even when a dialog window has a nonzero screen origin. */
class PlayerAnchoredActionsHighlightTest {
    @Test fun activeOverflowUsesSharedWhiteHighlightWhileInactiveHasNone() {
        assertEquals(heldContextSelectionOutline(), com.music.bitchord.nativeCollectionOverflowOutline(true))
        assertEquals(null, com.music.bitchord.nativeCollectionOverflowOutline(false))
    }

    @Test fun highlightedMenuUsesExactlyTheOriginalPlayerVector() {
        assertSame(originalPortraitNowPlayingOverflowGlyph(), playerAnchoredMenuHighlightGlyph())
    }

    @Test fun buttonHighlightFollowsExactLauncherCoordinates() {
        assertEquals(
            IntOffset(318, 64),
            playerAnchoredMenuHighlightPosition(
                anchor = IntRect(328, 88, 368, 128),
                overlayOrigin = IntOffset(10, 24),
                overlaySize = IntSize(400, 800),
            ),
        )
    }
    @Test fun outOfBoundsAnchorCannotDrawAnOffscreenSelectedButton() {
        assertEquals(
            IntOffset(360, 0),
            playerAnchoredMenuHighlightPosition(
                anchor = IntRect(900, -500, 940, -460),
                overlayOrigin = IntOffset.Zero,
                overlaySize = IntSize(400, 800),
            ),
        )
    }
}
