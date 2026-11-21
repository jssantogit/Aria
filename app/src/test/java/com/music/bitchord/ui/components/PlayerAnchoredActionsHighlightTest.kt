package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.music.bitchord.ui.player.originalNowPlayingOverflowGlyph
import com.music.bitchord.ui.player.originalPortraitNowPlayingOverflowGlyph
import org.junit.Test

/** The expanded player menu's spotlight reveals its original launcher;
 * all bounds/origins are SCREEN pixels from the two independent dialog windows.
 * Platform-level window dimming must be disabled so the spotlight stays clear. */
class PlayerAnchoredActionsHighlightTest {
    @Test fun expandedCollectionOverflowNeverAddsASecondFillOverItsOriginalButton() {
        assertEquals(Color.Transparent,
            com.music.bitchord.nativeCollectionOverflowActiveFill(true, Color.White))
        assertEquals(Color.Transparent,
            com.music.bitchord.nativeCollectionOverflowActiveFill(false, Color.White))
    }

    @Test fun playerOverflowAndRatingUseTheSameNeutralActionPlate() {
        assertEquals(true, playerAnchoredMenuUsesSharedRatingPlate(highlightLauncher = false))
        assertEquals(true, playerAnchoredMenuUsesSharedRatingPlate(highlightLauncher = true))
    }

    @Test fun animatedMenuPlateNeverMixesItsLabelsWithPlayerControls() {
        // Previously alpha followed an entrance tween 0..1, revealing player
        // controls through menu rows and creating overlapping text/icon ghosts.
        assertEquals(0f, playerAnchoredMenuPlateAlpha(measured = false), 0f)
        assertEquals(1f, playerAnchoredMenuPlateAlpha(measured = true), 0f)
        assertEquals(0.975f, playerAnchoredMenuPlateScale(0f), 0.0001f)
        assertEquals(0.9875f, playerAnchoredMenuPlateScale(0.5f), 0.0001f)
        assertEquals(1f, playerAnchoredMenuPlateScale(1f), 0.0001f)
    }

    @Test fun playerMenuSpotlightUsesOriginalLauncherWithoutRepaintingItsGlyph() {
        // The menu window only masks its scrim. The original player vector
        // (circle + dots) stays in place, with no duplicated overlay glyph.
        val launched = originalPortraitNowPlayingOverflowGlyph()
        val legacyPlain = originalNowPlayingOverflowGlyph()
        assertEquals("apple_queue_more", launched.name)
        assertEquals(2, launched.root.size)
        assertEquals("apple_queue_more_plain", legacyPlain.name)
        assertEquals(1, legacyPlain.root.size)
        assertEquals(launched.defaultWidth, legacyPlain.defaultWidth)
        assertEquals(launched.defaultHeight, legacyPlain.defaultHeight)
        assertEquals(launched.viewportWidth, legacyPlain.viewportWidth, 0.0001f)
        assertEquals(launched.viewportHeight, legacyPlain.viewportHeight, 0.0001f)
        assertEquals(
            androidx.compose.ui.geometry.Rect(318f, 64f, 358f, 104f),
            playerOverflowScrimCutout(
                anchor = IntRect(328, 88, 368, 128),
                overlayOrigin = IntOffset(10, 24),
                overlaySize = IntSize(400, 800),
            ),
        )
    }

    @Test fun missingOrOffscreenPlayerAnchorNeverCutsOutAnUnrelatedControl() {
        assertNull(playerOverflowScrimCutout(
            anchor = null,
            overlayOrigin = IntOffset.Zero,
            overlaySize = IntSize(400, 800),
        ))
        assertNull(playerOverflowScrimCutout(
            anchor = IntRect(900, -500, 940, -460),
            overlayOrigin = IntOffset.Zero,
            overlaySize = IntSize(400, 800),
        ))
        assertNull(playerOverflowScrimCutout(
            anchor = IntRect(10, 10, 10, 10),
            overlayOrigin = IntOffset.Zero,
            overlaySize = IntSize(400, 800),
        ))
    }

    @Test fun ratingDialogsInheritPageHazeButExplicitPlayerCaptureTakesPrecedence() {
        val page = dev.chrisbanes.haze.HazeState()
        val player = dev.chrisbanes.haze.HazeState()
        assertSame(page, nativeContextPlateHazeSource(explicit = null, inherited = page))
        assertSame(player, nativeContextPlateHazeSource(explicit = player, inherited = page))
        assertNull(nativeContextPlateHazeSource(explicit = null, inherited = null))
        val treatment = NativeActionCardTreatment.NEUTRAL_FROSTED
        assertNull(nativeContextPlateBorder(treatment, dark = true))
        assertNull(nativeContextPlateBorder(treatment, dark = false))
        assertEquals(30, nativeContextPlateCornerRadiusDp(treatment))
        assertTrue(expandableActionCardUsesHaze(treatment, hazeAvailable = true, reduceBlur = false))
        assertFalse(expandableActionCardUsesHaze(treatment, hazeAvailable = true, reduceBlur = true))
    }

    @Test fun playerOverflowMenuHasBorderlessNeutralBlurAndOpaqueFallback() {
        val treatment = NativeActionCardTreatment.PLAYER_NEUTRAL_FROSTED
        assertNull(nativeContextPlateBorder(treatment, dark = true))
        assertNull(nativeContextPlateBorder(treatment, dark = false))
        assertEquals(18, nativeContextPlateCornerRadiusDp(treatment))
        assertTrue(expandableActionCardUsesHaze(treatment, hazeAvailable = true, reduceBlur = false))
        assertFalse(expandableActionCardUsesHaze(treatment, hazeAvailable = true, reduceBlur = true))
        assertFalse(expandableActionCardUsesHaze(treatment, hazeAvailable = false, reduceBlur = false))
        assertFalse(neutralRatingPlateUsesArtwork(treatment))
    }

    @Test fun spotlightCutoutInScreenCoordinatesPreservesOnlyTheRealPuck() {
        // The 40dp hit target contains a centered 34dp original vector.
        // Neither the menu nor the scrim should invent a larger pale circle.
        val actualIcon = IntRect(103, 203, 137, 237)
        assertEquals(
            androidx.compose.ui.geometry.Rect(103f, 203f, 137f, 237f),
            playerOverflowScrimCutout(
                anchor = actualIcon,
                overlayOrigin = IntOffset.Zero,
                overlaySize = IntSize(400, 800),
            ),
        )
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
