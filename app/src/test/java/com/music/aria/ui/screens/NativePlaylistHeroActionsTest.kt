package com.music.aria.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * A saved playlist remains pinnable even if it contains no playable assets.
 * A metadata-only collection must never expose fake Play/Shuffle controls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativePlaylistHeroActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourcedPlaylistSummaryUsesNativeFooterAndSkipsMissingMetadata() {
        val palette = com.music.aria.ui.theme.ArtworkPalette(
            background = androidx.compose.ui.graphics.Color.Black,
            wash = androidx.compose.ui.graphics.Color.Black,
            elevated = androidx.compose.ui.graphics.Color.DarkGray,
            accent = androidx.compose.ui.graphics.Color.White,
            onBackground = androidx.compose.ui.graphics.Color.White,
            onBackgroundVariant = androidx.compose.ui.graphics.Color.LightGray,
            divider = androidx.compose.ui.graphics.Color.Gray,
        )
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    NativePlaylistDetailFooter("2 faixas · 7min", palette)
                    NativePlaylistDetailFooter("", palette)
                }
            }
        }
        compose.onNodeWithTag("native-playlist-detail-summary").assertIsDisplayed()
        compose.onNodeWithText("2 faixas · 7min").assertIsDisplayed()
    }

    @Test fun metadataOnlyPlaylistHasPinButNoPlaybackActions() {
        var pins = 0
        var playback = 0
        compose.setContent {
            MaterialTheme {
                NativePlaylistHeroActions(
                    canPlay = false,
                    isPinned = false,
                    onTogglePin = { pins++ },
                    onPlay = { playback++ },
                    onShuffle = { playback++ },
                )
            }
        }
        compose.onNodeWithTag("playlist-hero-pin-only").assertIsDisplayed()
        compose.onNodeWithTag("playlist-hero-playback-actions").assertDoesNotExist()
        compose.onNodeWithContentDescription("Fixar playlist na Biblioteca").performClick()
        compose.runOnIdle {
            assertEquals(1, pins)
            assertEquals(0, playback)
        }
    }

    @Test fun playablePlaylistPreservesFullNativeControlsAndPin() {
        var pins = 0
        compose.setContent {
            MaterialTheme {
                NativePlaylistHeroActions(
                    canPlay = true,
                    isPinned = true,
                    onTogglePin = { pins++ },
                    onPlay = {},
                    onShuffle = {},
                )
            }
        }
        compose.onNodeWithTag("playlist-hero-pin-only").assertDoesNotExist()
        compose.onNodeWithTag("playlist-hero-playback-actions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Desafixar playlist da Biblioteca").performClick()
        compose.runOnIdle { assertEquals(1, pins) }
    }
}
