package com.music.aria.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Selection uses the Aria amber accent, not Material primary. */
class AppleSelectionAccentTest {
    @Test fun selectedMarkIsAmberInLightAndDarkThemes() {
        val neutral = Color(0xFF8E8E93)
        assertEquals(AriaLightAccent, appleSelectionAccentColor(true, false, neutral))
        assertEquals(AriaDarkAccent, appleSelectionAccentColor(true, true, neutral))
    }

    @Test fun inactiveMarkRemainsNeutralInEitherTheme() {
        val neutral = Color(0xFF8E8E93)
        assertEquals(neutral, appleSelectionAccentColor(false, false, neutral))
        assertEquals(neutral, appleSelectionAccentColor(false, true, neutral))
    }
}
