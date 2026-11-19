package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Shared playlist visual contract: same row, real art, and neutral no-cover fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalPlaylistArtworkParityComposeTest {
    @get:Rule val compose = createComposeRule()

    private val metadataId = "123e4567-e89b-12d3-a456-426614174000"
    private val missingAudio = metadataOnlyUnavailablePlaylistRow(
        metadataId,
        CatalogMetadataRecordingLabel(metadataId, "two mistakes", "Kaylen Naicker"),
    )

    private val first = V2DisplayRow(
        "223e4567-e89b-12d3-a456-426614174000",
        "Pureflow", "LE SSERAFIM", localArtworkUri = "content://album/pureflow",
    )
    private val second = V2DisplayRow(
        "323e4567-e89b-12d3-a456-426614174000",
        "Bad Idea", "SAY MY NAME", localArtworkUri = "content://album/bad-idea",
    )

    @Test fun metadataRowKeepsShared52dpArtworkAndNeverReservesRatingSpace() {
        compose.setContent {
            MaterialTheme {
                CanonicalPlaylistTrackRow(
                    track = missingAudio,
                    onPlay = {},
                    allowUnavailableHold = true,
                )
            }
        }
        compose.onNodeWithTag("native-playlist-missing-artwork", useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithTag("native-detail-track-artwork", useUnmergedTree = true)
            .assertHeightIsEqualTo(52.dp)
        compose.onNodeWithTag("native-detail-rating-slot", useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithText("two mistakes").assertExists()
        compose.onNodeWithText("Kaylen Naicker · Sem áudio").assertExists()
    }

    @Test fun twoTrustedCoversProduceTwoTilesInsteadOfBlankQuadrants() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(192.dp)) {
                    CanonicalPlaylistCoverArt(
                        rows = listOf(first, missingAudio, second),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNodeWithTag("canonical-playlist-cover-two", useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithTag("canonical-playlist-empty-cover", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test fun metadataOnlyEmptyCoverScalesToCompactLibraryCell() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(44.dp)) {
                    CanonicalPlaylistCoverArt(
                        rows = listOf(missingAudio),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNodeWithTag("canonical-playlist-empty-cover", useUnmergedTree = true)
            .assertHeightIsEqualTo(44.dp)
        compose.onNodeWithContentDescription("Capa padrão da playlist").assertExists()
    }
}
