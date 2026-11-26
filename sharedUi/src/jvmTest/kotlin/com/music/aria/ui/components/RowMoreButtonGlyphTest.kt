package com.music.aria.ui.components

import com.music.aria.ui.player.AppleQueueVectors
import org.junit.Assert.assertSame
import org.junit.Test

/** All row-level ⋯ controls must use the same unplattered three-dot vector
 * as the header and native popup launcher, not a Material icon variant.
 */
class RowMoreButtonGlyphTest {
    @Test fun listRowOverflowUsesTheNativeThreeDotVector() {
        assertSame(AppleQueueVectors.MorePlain, nativeRowMoreGlyph())
    }
}
