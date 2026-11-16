package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeutralRatingActionPlateTest {
    @Test fun ratingPlateNeverUsesArtworkEvenWhenArtworkIsAvailable() {
        assertFalse(neutralRatingPlateUsesArtwork(NativeActionCardTreatment.NEUTRAL_FROSTED))
        assertTrue(neutralRatingPlateUsesArtwork(NativeActionCardTreatment.FROSTED))
        assertFalse(neutralRatingPlateUsesArtwork(NativeActionCardTreatment.SOLID))
    }

    @Test fun ratingPlatePreservesTheSharedOpaqueNeutralBase() {
        assertEquals(Color(0xFF29292D), nativeContextPlateColor(dark = true))
        assertEquals(Color(0xFFF5F5F7), nativeContextPlateColor(dark = false))
        assertEquals(1f, nativeContextPlateColor(dark = true).alpha)
    }
}
