package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsKind
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsLink
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsNativeArtistRelease
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsRecord
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.discogsAlbumArtistRelatedShelf
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
        // Provenance lives in the exact source route and attribution; it is
        // not a redundant genre/type label in the native artist header.
        assertEquals("Artista", page.page.subtitle)
        assertEquals(
            listOf("Álbuns", "Singles e EPs", "Álbuns ao vivo",
                "Coletâneas", "Outros lançamentos"),
            page.page.sections.map { it.title },
        )
        assertEquals(
            listOf("discogs:release:1"),
            page.page.sections.first().items.map { it.browseId },
        )
        assertEquals(
            setOf("discogs:release:5", "discogs:release:6"),
            page.page.sections.last().items.map { it.browseId }.toSet(),
        )
        assertTrue(page.page.sections.flatMap { it.items }
            .all { it.videoId == null && it.thumbnailUrl == null })
        assertTrue(page.hasMore)
        assertTrue(page.incomplete)
        assertEquals("https://www.discogs.com/artist/8303009", page.attributionUrl)
        assertTrue((page.page.songs as UiState.Success).data.isEmpty())
    }


    @Test fun releaseMoreByArtistOnlyUsesExactDiscogsCreditAndVerifiedEditions() {
        val opened = DiscogsRecord(
            link = DiscogsLink(DiscogsKind.RELEASE, 456L),
            title = "Current", artistNames = listOf("Artist"),
            artistIds = listOf(artist.artistId), country = "BR", year = 2025,
            labels = emptyList(), barcodes = emptyList(),
            formats = listOf("Album"), tracklist = listOf("1" to "First"),
            unofficial = false,
            masterId = 12L,
        )
        val verified = listOf(
            release(456L, "Current", "Album").copy(masterId = 12L),
            release(600L, "Other edition of same master", "Album").copy(masterId = 12L),
            release(601L, "Other official work", "EP"),
            release(602L, "Another official work", "CD"),
            release(603L, "Other artist", "Album").copy(artistId = 1234L),
        )
        val enriched = discogsReleasePage(artist, opened, verified)
        assertEquals(listOf("Mais de Artist"),
            enriched.page.sections.map { it.title })
        assertEquals(listOf("discogs:release:601", "discogs:release:602"),
            enriched.page.sections.single().items.map { it.browseId })
        assertTrue(enriched.page.sections.single().items.all {
            it.videoId == null && it.browseId.orEmpty().startsWith("discogs:release:")
        })
        assertEquals(listOf("First"), enriched.tracks.map { it.title })
        assertNull(discogsAlbumArtistRelatedShelf(
            opened.copy(artistIds = listOf(artist.artistId, 1234L)),
            artist.name, verified,
        ))
        assertNull(discogsAlbumArtistRelatedShelf(
            opened, "Unrelated", verified,
        ))
        assertNull(discogsAlbumArtistRelatedShelf(
            opened.copy(unofficial = true), artist.name, verified,
        ))
        assertNull(discogsAlbumArtistRelatedShelf(
            opened, artist.name, verified.filter { it.artistId != artist.artistId },
        ))
        assertTrue(discogsReleasePage(artist, opened).page.sections.isEmpty())
    }

    @Test fun otherReleasesOnlyCollectUnconfirmedOrConflictingFormats() {
        val page = discogsArtistPage(artist, listOf(
            release(1, "Plain Album", "CD", "Album"),
            release(2, "Plain EP", "EP"),
            release(3, "Unknown", "Vinyl"),
            release(4, "Conflicting", "Album", "EP"),
            release(5, "Live Compilation", "Album", "Live", "Compilation"),
        ), hasMore = false, incomplete = false)
        assertEquals(listOf("discogs:release:1"),
            page.page.sections.single { it.title == "Álbuns" }.items.map { it.browseId })
        assertEquals(listOf("discogs:release:2"),
            page.page.sections.single { it.title == "Singles e EPs" }.items.map { it.browseId })
        assertEquals(setOf("discogs:release:3", "discogs:release:4",
            "discogs:release:5"),
            page.page.sections.single { it.title == "Outros lançamentos" }
                .items.map { it.browseId }.toSet())
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
        assertEquals("Artist",
            discogsReleasePage("Artist", verified).albumArtistDisplayName)
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
