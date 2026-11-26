package com.music.aria.ui.components

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Native content remains painted; holding a card cannot replace it with a bitmap. */
class HeldItemLiftStateTest {
    @Test fun heldRecordingIsReleasedAfterMenuExit() {
        var releases = 0
        val held = HeldItem(
            bounds = Rect(16f, 450f, 420f, 532f),
            onRelease = { releases++ },
        )
        assertEquals(null, held.paintLayer)
        held.releaseLift()
        assertEquals(1, releases)
    }

    @Test fun aHoldOnlyRecordsTheOriginalBounds() {
        val original = Rect(24f, 100f, 344f, 420f)
        val held = HeldItem(original)
        assertEquals(original, held.bounds)
        // A dismissal cannot leave a hidden source or a duplicated snapshot.
        held.releaseLift()
        assertEquals(original, held.bounds)
    }

    @Test fun unclaimedGestureDoesNotKeepRecordingAfterCallbackReturns() {
        var releases = 0
        val item = HeldItem(Rect(0f, 0f, 40f, 40f), onRelease = { releases++ })
        LongPressOrigin.record(item)
        LongPressOrigin.discardUnconsumed(item)
        assertNull(LongPressOrigin.consume())
        assertEquals(1, releases)
    }

    @Test fun aClaimedMenuKeepsRecordingUntilItsOwnDismissal() {
        var releases = 0
        val item = HeldItem(Rect(0f, 0f, 40f, 40f), onRelease = { releases++ })
        LongPressOrigin.record(item)
        assertSame(item, LongPressOrigin.consume())
        LongPressOrigin.discardUnconsumed(item)
        assertEquals(0, releases)
        item.releaseLift()
        assertEquals(1, releases)
    }

    @Test fun aNewGestureReleasesAnUnclaimedPreviousRecording() {
        var releases = 0
        val previous = HeldItem(Rect(0f, 0f, 40f, 40f), onRelease = { releases++ })
        val next = HeldItem(Rect(50f, 0f, 90f, 40f))
        LongPressOrigin.record(previous)
        LongPressOrigin.record(next)
        assertEquals(1, releases)
        LongPressOrigin.discardUnconsumed(previous)
        assertSame(next, LongPressOrigin.consume())
        assertEquals(1, releases)
    }
}
