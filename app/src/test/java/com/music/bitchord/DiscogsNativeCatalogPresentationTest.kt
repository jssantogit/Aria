package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsKind
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsLink
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsNativeArtistRelease
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsRecord
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.discogsArtistPage
import com.music.bitchord.ui.discogsReleasePage
import com.music.bitchord.ui.screens.DiscogsNativeArtistSearchRow
import com.music.bitchord.ui.screens.nativeArtistAllowsNameBasedAuxiliaryVisuals
import org.junit.Assert.*
import org.junit.Test

class DiscogsNativeCatalogPresentationTest {
    @Test fun discogsArtistCannotInheritUnverifiedAppleArtOrNameBasedSignature() {
        assertFalse(nativeArtistAllowsNameBasedAuxiliaryVisuals("discogs:artist:123"))
        assertFalse(nativeArtistAllowsNameBasedAuxiliaryVisuals("discogs:release:456"))
        assertTrue(nativeArtistAllowsNameBasedAuxiliaryVisuals(
            "catalog:ARTIST:00000000-0000-4000-8000-000000000001"))
    }

    private val artist = DiscogsNativeArtistSearchRow(8303009L, "Artist")

    private fun release(
        id: Long, title: String, vararg types: String,
    ) = DiscogsNativeArtistRelease(
        artistId = artist.artistId, releaseId = id, masterId = null,
        title = title, year = 2024, formats = types.toList(),
        tracklist = emptyList(),
    )

    @Test fun nativeArtistShelvesKeepOnlySourceEvidenceAndRealDiscogsIds() {
        val page = discogsArtistPage(artist, listOf(
            release(1, "Normal", "Album"),
            release(2, "Live Work", "Album", "Live"),
            release(3, "Collection", "Album", "Compilation"),
            release(4, "An EP", "EP"),
            release(5, "Greatest Hits Live", "CD"),
            release(6, "Mixed", "Album", "Compilation", "Live"),
        ), hasMore = true, incomplete = true)
        assertEquals("discogs:artist:8303009", page.page.browseId)
        assertEquals(BrowseType.ARTIST, page.page.type)
        assertEquals(
            listOf("Álbuns", "Singles e EPs", "Álbuns ao vivo",
                "Coletâneas", "Outros lançamentos"),
            page.page.sections.map { it.title },
        )
        assertEquals(
            listOf("discogs:release:1", "discogs:release:6"),
            page.page.sections.first().items.map { it.browseId },
        )
        assertEquals("Greatest Hits Live",
            page.page.sections.last().items.single().title)
        assertTrue(page.page.sections.flatMap { it.items }
            .all { it.videoId == null && it.thumbnailUrl == null })
        assertTrue(page.hasMore)
        assertTrue(page.incomplete)
        assertEquals("https://www.discogs.com/artist/8303009", page.attributionUrl)
        assertTrue((page.page.songs as UiState.Success).data.isEmpty())
    }

    @Test fun discogsAlbumCreditOnlyNavigatesWithOneProvenArtist() {
        val verified = DiscogsRecord(
            link = DiscogsLink(DiscogsKind.RELEASE, 456L),
            title = "Edition", artistNames = listOf("Artist"),
            country = "BR", year = 2024, labels = emptyList(),
            barcodes = emptyList(), formats = listOf("CD"),
            tracklist = emptyList(), unofficial = false,
            artistIds = listOf(8303009L),
        )
        assertEquals("discogs:artist:8303009",
            discogsReleasePage("Artist", verified).albumArtistBrowseId)
        assertNull(discogsReleasePage("Artist", verified.copy(
            artistIds = listOf(8303009L, 92L),
        )).albumArtistBrowseId)
        assertNull(discogsReleasePage("Unrelated", verified).albumArtistBrowseId)
    }

    @Test fun sourceReleaseTracklistIsNonPlayableAndHasNoSyntheticMusicBrainzIdentity() {
        val record = DiscogsRecord(
            link = DiscogsLink(DiscogsKind.RELEASE, 456L),
            title = "The Album",
            artistNames = listOf("Artist"),
            country = "BR",
            year = 2025,
            labels = emptyList(),
            barcodes = emptyList(),
            formats = listOf("CD", "Album"),
            tracklist = listOf("A1" to "Track A", "2" to "Track B"),
            unofficial = false,
            artistIds = listOf(8303009L),
        )
        val page = discogsReleasePage(artist, record)
        assertEquals("discogs:release:456", page.page.browseId)
        assertEquals(BrowseType.ALBUM, page.page.type)
        assertEquals(listOf("Track A", "Track B"), page.tracks.map { it.title })
        assertEquals(listOf(null, 2), page.tracks.map { it.trackNumber })
        assertEquals(listOf("discogs:track:456:0", "discogs:track:456:1"),
            page.tracks.map { it.key })
        assertTrue((page.page.songs as UiState.Success).data.isEmpty())
        assertEquals("https://www.discogs.com/release/456", page.attributionUrl)
    }
}
