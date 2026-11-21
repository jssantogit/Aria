package com.music.bitchord.diagnostics

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The Settings-hosted diagnostics route must not lose its own heading. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DiagnosticsPanelHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun embeddedDiagnosticsRetainsTheTitleBelowGlobalBackButton() {
        compose.setContent {
            MaterialTheme { DiagnosticsPanelHeader(embedded = true, onBack = {}) }
        }
        compose.onNodeWithTag("diagnostics-page-title").assertExists()
        compose.onNodeWithText("Logs").assertExists()
    }
}
