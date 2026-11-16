package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A preview must hide its source only while a successful image copy is lifted. */
class HeldItemLiftStateTest {
    @Test fun liftingHidesSourceButDismissRestoresIt() {
        val held = HeldItem(Rect(0f, 0f, 40f, 40f))
        assertFalse(held.isLifted)
        held.markLifted()
        assertTrue(held.isLifted)
        held.releaseLift()
        assertFalse(held.isLifted)
    }

    @Test fun aPendingHoldDrawsTheCapturedLayerInsteadOfDrawingTheSourceTwice() {
        org.junit.Assert.assertEquals(HeldCaptureFrame.CAPTURED, heldCaptureFrame(
            lifted = false, capturePending = true,
        ))
    }

    @Test fun anActiveLiftHidesSourceAndAnOrdinaryFrameKeepsItsOriginalPaint() {
        org.junit.Assert.assertEquals(HeldCaptureFrame.HIDDEN, heldCaptureFrame(
            lifted = true, capturePending = false,
        ))
        org.junit.Assert.assertEquals(HeldCaptureFrame.HIDDEN, heldCaptureFrame(
            lifted = true, capturePending = true,
        ))
        org.junit.Assert.assertEquals(HeldCaptureFrame.SOURCE, heldCaptureFrame(
            lifted = false, capturePending = false,
        ))
    }

    @Test fun delayedBitmapCannotRehideSourceAfterEarlyDismiss() {
        val held = HeldItem(Rect(0f, 0f, 40f, 40f))
        held.releaseLift()
        held.markLifted()
        assertFalse(held.isLifted)
    }
}
