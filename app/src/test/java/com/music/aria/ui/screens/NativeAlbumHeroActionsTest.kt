package com.music.aria.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertWidthIsEqualTo
import com.music.aria.ui.components.DetailActionLayoutMetrics
import com.music.aria.ui.components.detailSkeleton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The album stays pinnable without fabricating playable media assets. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeAlbumHeroActionsTest {
    @get:Rule val compose = createComposeRule()

    // A requested 360dp/393dp layout is constrained by Robolectric's actual
    // viewport (320dp by default). Test the same width the player actually saw.
    private var measuredViewport = 0.dp

    @Composable
    private fun TestViewportColumn(requestedWidth: Dp, content: @Composable ColumnScope.() -> Unit) {
        BoxWithConstraints(Modifier.width(requestedWidth)) {
            measuredViewport = maxWidth
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }

    @Test fun onlineAlbumLoadedPlayWidthMatchesItsLoadingPlaceholder() {
        compose.setContent {
            MaterialTheme {
                TestViewportColumn(360.dp) {
                    NativeReleaseActionRow(
                        onPlay = {}, onShuffle = {}, isLocal = false,
                        compactForAlbum = true, reservePinAction = true,
                        onTogglePin = {},
                    )
                    LazyColumn(Modifier.height(150.dp)) {
                        detailSkeleton(isArtist = false)
                    }
                }
            }
        }
        compose.onNodeWithTag("native-compact-album-play-pill")
            .assertWidthIsEqualTo(DetailActionLayoutMetrics.albumPlayWidth(measuredViewport))
        compose.onNodeWithTag("detail-action-skeleton-play-pill")
            .assertWidthIsEqualTo(DetailActionLayoutMetrics.albumPlayWidth(measuredViewport))
        // The 360dp viewport produces the SAME width as the tested Library
        // Songs ActionPill: (360 - 2 * PAGE_GUTTER - 12) / 2.
        assertEquals(
            DetailActionLayoutMetrics.libraryCategoryPlayWidth(360.dp),
            DetailActionLayoutMetrics.albumPlayWidth(360.dp),
        )
        org.junit.Assert.assertEquals(50.dp, nativeReleaseSideButtonSize(true))
    }

    @Test fun albumPlayMatchesLibrarySongsAcrossPhoneAndNarrowScreenWidths() {
        // The shared metric retains Library > Songs' exact responsive width
        // while it fits beside Shuffle/+; no oversized fixed width on phones.
        for (screenWidth in listOf(320.dp, 360.dp, 393.dp)) {
            assertEquals(
                DetailActionLayoutMetrics.libraryCategoryPlayWidth(screenWidth),
                DetailActionLayoutMetrics.albumPlayWidth(screenWidth),
            )
        }
        // Wide screens preserve the 420dp capped album header, while the
        // Library Songs' two-button row is free to grow independently.
        assertEquals(252.dp, DetailActionLayoutMetrics.albumPlayWidth(600.dp))
    }

    @Test fun localOfflineAlbumUsesLibrarySongsPlayWidthWithoutInventingPinAction() {
        // Local album keeps two real actions, not an unsupported plus control.
        compose.setContent {
            MaterialTheme {
                TestViewportColumn(360.dp) {
                    NativeReleaseActionRow(
                        onPlay = {}, onShuffle = {}, isLocal = true,
                        compactForAlbum = true, onToggleLibrary = null,
                    )
                }
            }
        }
        compose.onNodeWithTag("native-local-album-play-pill")
            .assertWidthIsEqualTo(DetailActionLayoutMetrics.libraryCategoryPlayWidth(measuredViewport))
        compose.onNodeWithContentDescription("Fixar álbum na Biblioteca")
            .assertDoesNotExist()
    }

    @Test fun downloadsFolderKeepsItsRealTwoPillActionsAtLibrarySongsWidth() {
        // Downloads' own root has BrowseType.OTHER; the route policy must
        // select the same 16dp/12dp two-pill layout as Library > Songs.
        compose.setContent {
            MaterialTheme {
                TestViewportColumn(393.dp) {
                    NativeReleaseActionRow(
                        onPlay = {}, onShuffle = {}, isLocal = true,
                        compactForAlbum = nativeReleaseUsesAlbumGeometry(
                            "local:downloads", com.music.aria.data.model.BrowseType.OTHER,
                        ),
                    )
                }
            }
        }
        compose.onNodeWithTag("native-local-album-play-pill")
            .assertWidthIsEqualTo(DetailActionLayoutMetrics.libraryCategoryPlayWidth(measuredViewport))
        compose.onNodeWithContentDescription("Fixar álbum na Biblioteca")
            .assertDoesNotExist()
    }

    @Test fun canonicalPlaylistMatchesAlbumPlayWidthAndKeepsRealPin() {
        var pinTaps = 0
        compose.setContent {
            MaterialTheme {
                TestViewportColumn(360.dp) {
                    NativePlaylistHeroActions(
                        canPlay = true, isPinned = true, onTogglePin = { pinTaps++ },
                        onPlay = {}, onShuffle = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("native-compact-playlist-play-pill")
            .assertWidthIsEqualTo(DetailActionLayoutMetrics.albumPlayWidth(measuredViewport))
        compose.onNodeWithContentDescription("Desafixar playlist da Biblioteca")
            .performClick()
        compose.runOnIdle { assertEquals(1, pinTaps) }
    }

    @Test fun publicPlaylistDisabledPlaybackStillUsesTheAlbumCapsuleSize() {
        compose.setContent {
            MaterialTheme {
                TestViewportColumn(393.dp) {
                    NativeReleaseActionRow(
                        onPlay = {}, onShuffle = {}, isLocal = false,
                        compactForAlbum = true, pinTargetLabel = "playlist",
                        canPlay = false, canShuffle = false,
                        reservePinAction = true, onTogglePin = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("native-compact-playlist-play-pill")
            .assertWidthIsEqualTo(DetailActionLayoutMetrics.albumPlayWidth(measuredViewport))
    }

    @Test fun albumWithPaintedControlsDoesNotShowSecondLoadingActionRow() {
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.height(300.dp)) {
                    detailSkeleton(isArtist = false, showActions = false)
                }
            }
        }
        compose.onNodeWithTag("detail-action-skeleton-play-pill").assertDoesNotExist()
    }

    @Test fun metadataOnlyAlbumRetainsPinWithoutPlayOrShuffle() {
        var pins = 0
        var play = 0
        compose.setContent {
            MaterialTheme {
                NativeAlbumHeroActions(
                    canPlay = false, isPinned = false,
                    onTogglePin = { pins++ },
                    onPlay = { play++ }, onShuffle = { play++ },
                )
            }
        }
        compose.onNodeWithTag("album-hero-pin-only").assertIsDisplayed()
        compose.onNodeWithTag("album-hero-playback-actions").assertDoesNotExist()
        compose.onNodeWithContentDescription("Fixar álbum na Biblioteca").performClick()
        compose.runOnIdle { assertEquals(1, pins); assertEquals(0, play) }
    }

    @Test fun playableAlbumKeepsNativeRowWithItsPin() {
        var pins = 0
        compose.setContent {
            MaterialTheme {
                NativeAlbumHeroActions(
                    canPlay = true, isPinned = true,
                    onTogglePin = { pins++ },
                    onPlay = {}, onShuffle = {},
                )
            }
        }
        compose.onNodeWithTag("album-hero-playback-actions").assertIsDisplayed()
        compose.onNodeWithTag("album-hero-pin-only").assertDoesNotExist()
        compose.onNodeWithContentDescription("Desafixar álbum da Biblioteca").performClick()
        compose.runOnIdle { assertEquals(1, pins) }
    }

    @Test fun absentPinCapabilityDoesNotRenderFakeControl() {
        compose.setContent {
            MaterialTheme {
                NativeAlbumHeroActions(
                    canPlay = false, isPinned = false,
                    onTogglePin = null, onPlay = {}, onShuffle = {},
                )
            }
        }
        compose.onNodeWithTag("album-hero-pin-only").assertDoesNotExist()
        compose.onNodeWithTag("album-hero-playback-actions").assertDoesNotExist()
    }
}
