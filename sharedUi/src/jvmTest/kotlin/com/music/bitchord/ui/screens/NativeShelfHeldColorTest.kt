package com.music.bitchord.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The held artwork is the original, never the transient touch press wash. */
class NativeShelfHeldColorTest {
    @Test fun normalTouchMayDarkenArtworkButLongPressRestoresOriginalColors() {
        assertFalse(nativeShelfPressInkVisible(pressed = false, heldActionOpen = false))
        assertTrue(nativeShelfPressInkVisible(pressed = true, heldActionOpen = false))
        assertFalse(nativeShelfPressInkVisible(pressed = true, heldActionOpen = true))
        assertFalse(nativeShelfPressInkVisible(pressed = false, heldActionOpen = true))
    }
}
