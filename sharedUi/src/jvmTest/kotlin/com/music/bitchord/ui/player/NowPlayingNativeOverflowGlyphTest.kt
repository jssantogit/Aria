package com.music.bitchord.ui.player

import org.junit.Assert.assertSame
import org.junit.Test

class NowPlayingNativeOverflowGlyphTest {
    @Test fun portraitAppleQueueOverflowUsesOriginalAmDecompDotsWithoutBakedCircle() {
        assertSame(AppleQueueVectors.MorePlain, nativePortraitAppleQueueOverflowGlyph())
        assertSame(originalNowPlayingOverflowGlyph(), originalPortraitNowPlayingOverflowGlyph())
        org.junit.Assert.assertEquals(0.18f, portraitOverflowPuckColor(selected = false).alpha, 0.005f)
        org.junit.Assert.assertEquals(0.38f, portraitOverflowPuckColor(selected = true).alpha, 0.005f)
    }

    @Test fun landscapePlayerOverflowHasNoInnerPlatterOrMaterialVariant() {
        assertSame(AppleQueueVectors.MorePlain, nativeLandscapePlayerOverflowGlyph())
    }

    @Test fun fallbackNowPlayingCircleHasTheSameUnplatteredOverflowAsTheQueue() {
        assertSame(AppleQueueVectors.MorePlain, nativeNowPlayingOverflowGlyph())
    }
}
