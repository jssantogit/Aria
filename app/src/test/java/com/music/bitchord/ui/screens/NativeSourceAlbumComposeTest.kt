package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.UiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Verify real Compose rendering, not merely an adapter that returns track DTOs. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeSourceAlbumComposeTest {
    @get:Rule val compose = createComposeRule()

    private fun renderSourceAlbum(route: String, tracks: List<NativeMetadataTrack>) {
        compose.setContent {
            MaterialTheme {
                DetailScreen(
                    page = DetailPage(
                        browseId = route,
                        title = "Album",
                        subtitle = "Artist · 2024",
                        thumbnailUrl = null,
                        type = BrowseType.ALBUM,
                        songs = UiState.Success(emptyList()),
                    ),
                    currentSong = null,
                    isPlaying = false,
                    onSongClick = { _, _ -> error("Metadata must not play") },
                    onSongLongPress = { error("Metadata must not offer Song menu") },
                    onSongSwipe = { error("Metadata must not queue") },
                    onShuffle = { error("Metadata must not shuffle") },
                    onSectionItemClick = {},
                    onArtistClick = { _, _ -> },
                    onAddSuggested = {},
                    metadataTracks = tracks,
                    contentPadding = PaddingValues(bottom = 0.dp),
                )
            }
        }
    }

    @Test fun appleAlbumShowsSourceTracksInsideSameNativeAlbumFrame() {
        renderSourceAlbum("itunes:br:album:101", listOf(
            NativeMetadataTrack("itunes:br:track:1", "How Sweet", "Artist", 1, 1),
            NativeMetadataTrack("itunes:br:track:2", "Bubble Gum", "Artist", 2, 1),
        ))
        compose.onNodeWithTag("native-album-detail-frame").assertExists()
        compose.onNodeWithText("How Sweet").assertExists()
        compose.onNodeWithText("Bubble Gum").assertExists()
        compose.onNodeWithTag("album-hero-playback-actions").assertDoesNotExist()
    }

    @Test fun discogsReleaseShowsActualNonPlayableTrackInNativeAlbumFrame() {
        renderSourceAlbum("discogs:release:987", listOf(
            NativeMetadataTrack("discogs:track:987:0", "Side A", "Artist", null, null),
        ))
        compose.onNodeWithTag("native-album-detail-frame").assertExists()
        compose.onNodeWithText("Side A").assertExists()
        compose.onNodeWithTag("album-hero-playback-actions").assertDoesNotExist()
    }
}
