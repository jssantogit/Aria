package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class HeldContextMenuPlateTest {
    @Test fun heldSelectionUsesTheOriginalArtworkWithoutDecorativePixelEffects() {
        assertEquals(Color.Transparent, heldPreviewBackingColor())
    }

    @Test fun neutralPagesKeepReadableDarkAndLightPlates() {
        assertEquals(nativeCompactPlateColor(isDark = true, artworkElevation = null),
            heldContextPlateColor(Color.Black, null))
        assertEquals(nativeCompactPlateColor(isDark = false, artworkElevation = null),
            heldContextPlateColor(Color.White, null))
    }

    @Test fun longPressActionsUseFrostedMaterialUnlessBlurIsDisabled() {
        assertEquals(true, heldActionsUseHaze(reduceDynamicBlur = false))
        assertEquals(false, heldActionsUseHaze(reduceDynamicBlur = true))
    }

    @Test fun transparentRowsUseOnlyThePaintedPixelMaskNotAFullWidthCutout() {
        org.junit.Assert.assertTrue(heldSpotlightUsesPixelMask(
            captureAvailable = true, recordingReady = true,
        ))
        org.junit.Assert.assertFalse(heldSpotlightUsesPixelMask(
            captureAvailable = true, recordingReady = false,
        ))
        org.junit.Assert.assertFalse(heldSpotlightUsesPixelMask(
            captureAvailable = false, recordingReady = true,
        ))
        org.junit.Assert.assertFalse(heldSpotlightUsesPixelMask(
            captureAvailable = false, recordingReady = false,
        ))
    }

    @Test fun contextualBackdropOpacityFadesFromClearToTargetWithoutOvershoot() {
        // Applies identically to the pixel-accurate source mask, pending layer
        // and legacy geometric fallback. A menu spring must not drive the scrim.
        assertEquals(0f, heldContextScrimAlpha(0.30f, 0f), 0.0001f)
        assertEquals(0.15f, heldContextScrimAlpha(0.30f, 0.5f), 0.0001f)
        assertEquals(0.30f, heldContextScrimAlpha(0.30f, 1f), 0.0001f)
        assertEquals(0.30f, heldContextScrimAlpha(0.30f, 1.25f), 0.0001f)
        assertEquals(0f, heldContextScrimAlpha(0.30f, -0.2f), 0.0001f)
        assertEquals(0.09f, heldContextScrimAlpha(0.18f, 0.5f), 0.0001f)
    }

    @Test fun backdropHasAsymmetricEasedFadeAndHonorsReducedMotion() {
        assertEquals(220, heldContextScrimFadeDurationMillis(
            opening = true, reduceMotion = false,
        ))
        assertEquals(170, heldContextScrimFadeDurationMillis(
            opening = false, reduceMotion = false,
        ))
        assertEquals(0, heldContextScrimFadeDurationMillis(
            opening = true, reduceMotion = true,
        ))
        assertEquals(0, heldContextScrimFadeDurationMillis(
            opening = false, reduceMotion = true,
        ))
    }

    @Test fun heldSnapshotDoesNotReceiveASecondGreyCardSurface() {
        assertEquals(Color.Transparent, heldPreviewBackingColor())
    }

    @Test fun artworkDoesNotTintTheLongPressActionPlateOrReduceContrast() {
        val tealAlbumSurface = Color(0xFF214D4C)
        assertEquals(nativeCompactPlateColor(isDark = true, artworkElevation = null),
            heldContextPlateColor(Color(0xFF0E3333), tealAlbumSurface))
        assertEquals(nativeCompactPlateColor(isDark = false, artworkElevation = null),
            heldContextPlateColor(Color.White, Color(0xFFFF66BB)))
    }
}
