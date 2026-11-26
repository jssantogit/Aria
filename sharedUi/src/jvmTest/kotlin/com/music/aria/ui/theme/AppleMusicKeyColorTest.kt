package com.music.aria.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicKeyColorTest {
    @Test fun selectedUiUsesApprovedNotationAmberTokens() {
        assertEquals(Color(0xFFB74D0A), AriaLightAccent)
        assertEquals(Color(0xFFFFB747), AriaDarkAccent)
        assertEquals(AriaLightAccent, ariaAccentColor(dark = false))
        assertEquals(AriaDarkAccent, ariaAccentColor(dark = true))
        assertEquals(AriaLightAccent, appleMusicKeyColor(dark = false))
        assertEquals(AriaDarkAccent, appleMusicKeyColor(dark = true))
        assertEquals(AriaLightAccent, AppleMusicLightKey)
        assertEquals(AriaDarkAccent, AppleMusicDarkKey)
    }

    @Test fun amberInkAndFilledControlsMeetMinimumTextContrast() {
        for (dark in listOf(false, true)) {
            val accent = ariaAccentColor(dark)
            val foreground = ariaOnAccentColor(dark)
            val canvas = if (dark) Color.Black else Color.White
            fun ratio(a: Color, b: Color): Float {
                val low = minOf(a.luminance(), b.luminance())
                val high = maxOf(a.luminance(), b.luminance())
                return (high + 0.05f) / (low + 0.05f)
            }
            assertTrue(ratio(accent, foreground) >= 4.5f)
            assertTrue(ratio(accent, canvas) >= 4.5f)
            val neutralPlate = if (dark) Color(0xFF29292D) else Color(0xFFF2F2F7)
            assertTrue(ratio(accent, neutralPlate) >= 4.5f)
        }
    }
}
