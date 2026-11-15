package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntRect
import com.music.bitchord.ui.components.CompactRatingStyle
import com.music.bitchord.ui.components.LocalCompactRatingStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Rating popover is transient and must not steal the entire album toolbar. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeFrostedRatingInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dismissingAnchoredRatingRestoresAlbumToolbarInteractions() {
        val editing = mutableStateOf(true)
        var otherClicks = 0
        compose.setContent {
            MaterialTheme {
                TextButton(onClick = { otherClicks++ }) { Text("Other album action") }
                if (editing.value) {
                    CanonicalRecordingFeedbackPanel(
                        rating = 4.0,
                        onSave = {},
                        onDismiss = { editing.value = false },
                        anchorBounds = IntRect(24, 96, 72, 144),
                    )
                }
            }
        }
        compose.onNodeWithTag("native-rating-anchored-card").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("native-rating-anchored-card").assertDoesNotExist()
        compose.onNodeWithText("Other album action").performClick()
        compose.runOnIdle { assertEquals(1, otherClicks) }
    }

    @Test fun numericStyleUsesExistingV3DigitVectorsInTheEditor() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCompactRatingStyle provides CompactRatingStyle.NUMERIC) {
                    CanonicalRecordingFeedbackPanel(
                        rating = 4.0,
                        onSave = {},
                        onDismiss = {},
                        anchorBounds = IntRect(24, 96, 72, 144),
                    )
                }
            }
        }
        compose.onNodeWithTag("compact-rating-numeric", useUnmergedTree = true).assertExists()
    }
}
