package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** A star clicked directly on the player, artist, or album is a real
 * launcher: the editor belongs to its bounds, not an unrelated bottom sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalRatingAnchorRoutesTest {
    @get:Rule val compose = createComposeRule()
    @Test fun explicitRatingLauncherUsesCompactAnchoredPlate() {
        compose.setContent {
            MaterialTheme {
                CanonicalRecordingFeedbackPanel(
                    rating = 4.0,
                    onSave = {},
                    onDismiss = {},
                    anchorBounds = IntRect(20, 60, 64, 104),
                )
            }
        }
        compose.onNodeWithTag("native-rating-anchored-card").assertExists()
        compose.onNodeWithTag("native-rating-bottom-sheet").assertDoesNotExist()
    }
    @Test fun anchoredRatingWidthShrinksForMultiWindowInsteadOfOverflowing() {
        assertEquals(300, anchoredRatingCardWidthDp(500))
        assertEquals(288, anchoredRatingCardWidthDp(320))
        assertEquals(208, anchoredRatingCardWidthDp(240))
        assertEquals(168, anchoredRatingCardWidthDp(200))
    }
}
