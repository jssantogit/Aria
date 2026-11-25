package com.music.aria.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.aria.R
import com.music.aria.playback.PlaybackRecoveryNotice
import com.music.aria.playback.PlaybackRecoveryReason
import com.music.aria.playback.PlaybackRecoveryStatus
import com.music.aria.ui.theme.AriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Recovery actions preserve explicit retry and dismiss when styled with key blue. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaybackRecoveryBannerTest {
    @get:Rule val compose = createComposeRule()

    private val notice = PlaybackRecoveryNotice(
        entryId = "entry-1", recordingId = "recording-1",
        mediaAssetId = "asset-1", providerKey = "server",
        reason = PlaybackRecoveryReason.REMOTE_UNAVAILABLE,
    )

    @Test fun nativeRecoveryActionsKeep44DpTargets() {
        compose.setContent {
            AriaTheme {
                PlaybackRecoveryBanner(notice, onRetry = {}, onDismiss = {})
            }
        }
        compose.onNodeWithTag("playback-recovery-retry").assertHeightIsAtLeast(44.dp)
        compose.onNodeWithTag("playback-recovery-dismiss").assertHeightIsAtLeast(44.dp)
    }

    @Test fun retryIsUserInitiatedAndDismissRemainsIndependent() {
        var retry = 0
        var dismiss = 0
        compose.setContent {
            AriaTheme {
                PlaybackRecoveryBanner(notice, onRetry = { retry++ }, onDismiss = { dismiss++ })
            }
        }
        val res = RuntimeEnvironment.getApplication().resources
        compose.onNodeWithText(res.getString(R.string.playback_recovery_retry)).performClick()
        compose.onNodeWithText(res.getString(R.string.playback_recovery_dismiss)).performClick()
        compose.runOnIdle {
            assertEquals(1, retry)
            assertEquals(1, dismiss)
        }
    }

    @Test fun retryIsDisabledWhileAnotherAttemptIsInFlight() {
        var retries = 0
        compose.setContent {
            AriaTheme {
                PlaybackRecoveryBanner(
                    notice.copy(status = PlaybackRecoveryStatus.RETRYING),
                    onRetry = { retries++ }, onDismiss = {},
                )
            }
        }
        val res = RuntimeEnvironment.getApplication().resources
        compose.onNodeWithText(res.getString(R.string.playback_recovery_retrying))
            .assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, retries) }
    }
}
