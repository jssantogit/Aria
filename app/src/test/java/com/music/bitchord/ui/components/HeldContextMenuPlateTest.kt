package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class HeldContextMenuPlateTest {
    @Test fun neutralPagesKeepReadableDarkAndLightPlates() {
        assertEquals(Color.Black.copy(alpha = 0.97f),
            heldContextPlateColor(Color.Black, null))
        assertEquals(Color.White.copy(alpha = 0.97f),
            heldContextPlateColor(Color.White, null))
    }

    @Test fun liftedItemNeverReplacesItsSourceWithAnUncapturedBlackPlate() {
        assertEquals(false, heldPreviewCanPaint(hasImage = false, lifted = false))
        assertEquals(false, heldPreviewCanPaint(hasImage = false, lifted = true))
        assertEquals(false, heldPreviewCanPaint(hasImage = true, lifted = false))
        assertEquals(true, heldPreviewCanPaint(hasImage = true, lifted = true))
    }

    @Test fun contextualActionsSampleBackgroundUnlessBlurIsDisabled() {
        assertEquals(true, heldActionsUseHaze(reduceDynamicBlur = false))
        assertEquals(false, heldActionsUseHaze(reduceDynamicBlur = true))
    }

    @Test fun artworkPagesTintBothLiftedPreviewAndActionPanel() {
        val tealAlbumSurface = Color(0xFF214D4C)
        assertEquals(tealAlbumSurface.copy(alpha = 0.97f),
            heldContextPlateColor(Color(0xFF0E3333), tealAlbumSurface))
    }
}
