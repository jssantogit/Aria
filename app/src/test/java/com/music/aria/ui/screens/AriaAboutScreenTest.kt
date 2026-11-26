package com.music.aria.ui.screens

import android.app.Application
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.aria.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AriaAboutScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun androidAppInfoTargetsInstalledVariant() {
        for (pkg in listOf("com.music.aria", "com.dev.aria")) {
            val intent = ariaAppInfoIntent(pkg)
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
            assertEquals("package:$pkg", intent.dataString)
        }
    }

    @Test fun aboutDisplaysInstalledIdentityAndOffersSystemInfo() {
        var opened = 0
        val resources = RuntimeEnvironment.getApplication().resources
        compose.setContent {
            MaterialTheme {
                Column {
                    AriaAboutContent(
                        appLabel = "Aria Dev",
                        version = "9.9-fixture",
                        packageName = "com.dev.aria",
                        onAppInfo = { opened++ },
                    )
                }
            }
        }
        compose.onNodeWithTag("aria-about-icon").assertExists()
        compose.onNodeWithText("Aria Dev").assertExists()
        compose.onNodeWithText(resources.getString(R.string.about_version_format, "9.9-fixture")).assertExists()
        compose.onNodeWithText("com.dev.aria", substring = true).assertExists()
        compose.onNodeWithText(resources.getString(R.string.about_android_app_info), substring = true).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
