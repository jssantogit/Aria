package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class IconSilhouettePressButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pressFeedbackChangesGlyphOnlyWhileReducedMotionKeepsUnitScale() {
        assertEquals(0.90f, iconSilhouettePressScale(true, false), 0.0001f)
        assertEquals(1f, iconSilhouettePressScale(false, false), 0.0001f)
        assertEquals(1f, iconSilhouettePressScale(true, true), 0.0001f)
        assertEquals(0.78f, iconSilhouettePressAlpha(true), 0.0001f)
        assertEquals(1f, iconSilhouettePressAlpha(false), 0.0001f)
    }

    @Test fun transparentTouchTargetIsStillClickableAndCanBeDisabled() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                Box {
                    IconSilhouettePressButton(
                        modifier = Modifier.size(40.dp).testTag("transport-play"),
                        onClick = { clicks++ },
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Reproduzir")
                    }
                }
            }
        }
        compose.onNodeWithTag("transport-play").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
