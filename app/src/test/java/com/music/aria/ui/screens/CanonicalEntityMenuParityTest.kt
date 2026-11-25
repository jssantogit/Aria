package com.music.aria.ui.screens

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.music.aria.R
import com.music.aria.data.model.BrowseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Shared geometry is independent of source; only verified capabilities vary. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalEntityMenuParityTest {
    @get:Rule val compose = createComposeRule()

    private val mbid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    @Test fun catalogArtistAndReleaseKeepExactKindsWithoutInventingAQueue() {
        val artist = canonicalCatalogEntityMenuSpec("catalog:ARTIST:$mbid")!!
        assertEquals(BrowseType.ARTIST, artist.browseType)
        assertEquals("Avaliar artista", artist.ratingLabel)
        assertEquals("https://musicbrainz.org/artist/$mbid", artist.musicBrainzUrl)
        assertNull(artist.playlistKind)

        val release = canonicalCatalogEntityMenuSpec("catalog:RELEASE:$mbid")!!
        assertEquals(BrowseType.ALBUM, release.browseType)
        assertEquals(CanonicalSearchKind.RELEASE, release.playlistKind)
        assertEquals("https://musicbrainz.org/release/$mbid", release.musicBrainzUrl)

        val group = canonicalCatalogEntityMenuSpec("catalog:RELEASE_GROUP:$mbid")!!
        assertEquals(CanonicalSearchKind.RELEASE_GROUP, group.playlistKind)
        assertEquals("https://musicbrainz.org/release-group/$mbid", group.musicBrainzUrl)

        assertNull(canonicalCatalogEntityMenuSpec("catalog:RECORDING:$mbid"))
        assertNull(canonicalCatalogEntityMenuSpec("catalog:ARTIST:NewJeans"))
        assertNull(canonicalCatalogEntityMenuSpec("local:artist:$mbid"))
        assertNull(canonicalCatalogEntityMenuSpec(null))
    }

    @Test fun onlineArtistSharesLibraryRowsButNeverDisplaysQueueOrArtistImport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var ratingTaps = 0
        compose.setContent {
            MaterialTheme {
                CanonicalCatalogEntityMenuBody(
                    browseId = "catalog:ARTIST:$mbid",
                    title = "NewJeans",
                    subtitle = "",
                    artworkUrl = null,
                    onDismiss = {},
                    onRate = { ratingTaps++ },
                    onAddVerifiedRecordings = { error("artist import must never be exposed") },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.play_next)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.add_to_queue)).assertDoesNotExist()
        compose.onNodeWithText("Adicionar à playlist").assertDoesNotExist()
        compose.onNodeWithText("Editar avaliação").assertExists().performClick()
        compose.onNodeWithText("Ver no MusicBrainz").assertExists()
        compose.runOnIdle { assertEquals(1, ratingTaps) }
    }

    @Test fun onlineAlbumCanImportVerifiedRecordingMetadataButNeverQueuesAudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var imported: CanonicalSearchKind? = null
        compose.setContent {
            MaterialTheme {
                CanonicalCatalogEntityMenuBody(
                    browseId = "catalog:RELEASE_GROUP:$mbid",
                    title = "Get Up",
                    subtitle = "NewJeans",
                    artworkUrl = null,
                    onDismiss = {},
                    onRate = {},
                    onAddVerifiedRecordings = { imported = it.playlistKind },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.play_next)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.add_to_queue)).assertDoesNotExist()
        compose.onNodeWithText("Adicionar à playlist").assertExists().performClick()
        compose.runOnIdle { assertEquals(CanonicalSearchKind.RELEASE_GROUP, imported) }
    }
}
