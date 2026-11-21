package com.music.bitchord.ui.player

import org.junit.Assert.assertSame
import org.junit.Test

class NowPlayingNativeOverflowGlyphTest {
    @Test fun portraitAppleQueueOverflowUsesOneCompleteOriginalVector() {
        assertSame(AppleQueueVectors.More, nativePortraitAppleQueueOverflowGlyph())
        assertSame(AppleQueueVectors.More, originalPortraitNowPlayingOverflowGlyph())
        // The complete original has the puck path and the dots path.
        // MorePlain is only for the legacy CircleGlyph with its own puck.
        org.junit.Assert.assertEquals(2, nativePortraitAppleQueueOverflowGlyph().root.size)
        org.junit.Assert.assertEquals(1, AppleQueueVectors.MorePlain.root.size)
    }

    @Test fun legacyLandscapeCircleUsesOnlyUnplatteredDots() {
        assertSame(AppleQueueVectors.MorePlain, nativeLandscapePlayerOverflowGlyph())
    }

    @Test fun fallbackNowPlayingCircleHasTheSameUnplatteredOverflowAsTheQueue() {
        assertSame(AppleQueueVectors.MorePlain, nativeNowPlayingOverflowGlyph())
    }
}
