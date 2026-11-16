package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.ui.theme.rememberArtworkPalette
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Public playlists must honor the top chrome clearance just like Library details. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativePlaylistDetailFramePaddingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun publicPlaylistHeaderStartsBelowTheFloatingTopBar() {
        compose.setContent {
            MaterialTheme {
                NativePlaylistDetailFrame(
                    palette = rememberArtworkPalette(imageUrl = null),
                    listState = rememberLazyListState(),
                    topPadding = 96.dp,
                    bottomPadding = 0.dp,
                ) {
                    item {
                        Box(Modifier.size(100.dp).testTag("playlist-header")) {}
                    }
                }
            }
        }
        val bounds = compose.onNodeWithTag("playlist-header").getUnclippedBoundsInRoot()
        assertTrue("header must clear 96dp top chrome", bounds.top >= 96.dp)
    }
}
