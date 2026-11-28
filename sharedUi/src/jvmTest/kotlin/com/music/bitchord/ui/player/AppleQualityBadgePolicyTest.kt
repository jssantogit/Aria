package com.music.bitchord.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleQualityBadgePolicyTest {
    @org.junit.Test fun previewLyricsAreUnavailableWithoutAffectingFullTracks() {
        org.junit.Assert.assertFalse(playerLyricsAllowed(true))
        org.junit.Assert.assertTrue(playerLyricsAllowed(false))
    }
    @org.junit.Test fun qualityForegroundUsesOnlyNeutralContrast() {
        org.junit.Assert.assertFalse(playerQualityUsesDarkForeground(0f))
        org.junit.Assert.assertTrue(playerQualityUsesDarkForeground(1f))
        org.junit.Assert.assertFalse(playerQualityUsesDarkForeground(Float.NaN))
    }

    @Test fun losslessAndAtmosAreNeverInferredFromRequestedQuality() {
        assertEquals(AppleQualityBadgeKind.NONE, appleQualityBadgeKind(null, false, false, false, false, false))
        assertEquals(AppleQualityBadgeKind.DOLBY_ATMOS, appleQualityBadgeKind(true, false, true, false, false, false))
        assertEquals(AppleQualityBadgeKind.HI_RES, appleQualityBadgeKind(true, true, false, false, false, false))
        assertEquals(AppleQualityBadgeKind.LOSSLESS, appleQualityBadgeKind(true, false, false, false, false, false))
    }

    @Test fun measuredLossyIsDescribedWithoutClaimingLossless() {
        assertEquals(AppleQualityBadgeKind.HIGH, appleQualityBadgeKind(false, false, false, true, false, false))
        assertEquals(AppleQualityBadgeKind.MEDIUM, appleQualityBadgeKind(false, false, false, false, true, false))
        assertEquals(AppleQualityBadgeKind.NONE, appleQualityBadgeKind(false, false, false, false, false, false))
    }

    @Test fun previewOverridesMeasuredQualityAndPendingUpgrade() {
        assertEquals(AppleQualityBadgeKind.PREVIEW,
            appleQualityBadgeKind(true, true, true, true, true, true, isAudioPreview = true))
        assertEquals(AppleQualityBadgeKind.PREVIEW,
            appleQualityBadgeKind(null, false, false, false, false, false, isAudioPreview = true))
        assertEquals(AppleQualityBadgeKind.HIGH,
            appleQualityBadgeKind(false, false, false, true, false, false, isAudioPreview = false))
    }

    @Test fun unavailableStatsRemainsUnknownRatherThanInventingQuality() {
        assertEquals(AppleQualityBadgeKind.NONE, appleQualityBadgeKind(null, false, false, false, false, false))
        assertEquals(AppleQualityBadgeKind.LOADING, appleQualityBadgeKind(null, false, false, false, false, true))
    }
}
