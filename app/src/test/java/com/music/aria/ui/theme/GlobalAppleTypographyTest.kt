package com.music.aria.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.TextStyle
import com.music.aria.ui.components.AppleTextRoles
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cross-screen typography contract, informed by am-descomp's focused Android XML evidence. */
class GlobalAppleTypographyTest {
    @Test fun commonTextRolesFollowOneSFProBasedScale() {
        assertEquals(SFProDisplay, AriaTypography.titleMedium.fontFamily)
        assertEquals(SFProDisplay, AriaTypography.bodyLarge.fontFamily)
        assertEquals(SFProDisplay, AriaTypography.labelLarge.fontFamily)
        assertEquals(28.sp, AriaTypography.headlineLarge.fontSize)
        assertEquals(20.sp, AriaTypography.headlineMedium.fontSize)
        assertEquals(17.sp, AriaTypography.titleMedium.fontSize)
        assertEquals(17.sp, AriaTypography.bodyLarge.fontSize)
        assertEquals(FontWeight.W700, AriaTypography.headlineLarge.fontWeight)
    }

    @Test fun sharedTextRolesReuseTheGlobalThemeWithoutHardcodedDuplicatedInputs() {
        val body = AriaTypography.bodyMedium
        val category = AriaTypography.bodyLarge
        // Ordinary fields and library category names must follow the one theme
        // scale. The root Library title remains a deliberate 36sp role.
        assertEquals(body, AppleTextRoles.formInput(body))
        assertEquals(category, AppleTextRoles.categoryLabel(category))
        assertEquals(36.sp, AppleTextRoles.libraryLandingTitle(
            AriaTypography.headlineLarge,
        ).fontSize)
        assertEquals(FontWeight.W700, AppleTextRoles.libraryLandingTitle(
            AriaTypography.headlineLarge,
        ).fontWeight)
    }
}
