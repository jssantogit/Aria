package com.music.aria

import com.music.aria.data.catalog.metadata.discogs.DiscogsKind
import com.music.aria.data.catalog.metadata.discogs.DiscogsLink
import com.music.aria.data.catalog.metadata.discogs.DiscogsNativeArtistRelease
import com.music.aria.data.catalog.metadata.discogs.DiscogsRecord
import com.music.aria.data.model.BrowseType
import com.music.aria.data.model.UiState
import com.music.aria.ui.discogsAlbumArtistRelatedShelf
import com.music.aria.ui.discogsArtistPage
import com.music.aria.ui.discogsReleasePage
import com.music.aria.ui.discogsReleaseMetadataFacts
import com.music.aria.ui.discogsMasterVersionsShelf
import com.music.aria.ui.discogsVerifiedVersionSelection
import com.music.aria.ui.screens.DiscogsNativeArtistSearchRow
import com.music.aria.ui.screens.nativeArtistAllowsNameBasedAuxiliaryVisuals
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


    @Test fun artistHeaderHidesDiscogsNumberButKeepsOriginalArtistId() {
        val selected = DiscogsNativeArtistSearchRow(31L, "Illit (2)")
        val page = discogsArtistPage(selected, emptyList(), false, false)
        assertEquals("Illit", page.page.title)
        assertEquals("discogs:artist:31", page.page.browseId)
        assertEquals("https://www.discogs.com/artist/31", page.attributionUrl)
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

    @Test fun versionsRailKeepsOnlyHydratedExactMasterReleaseIds() {
        val original = DiscogsRecord(
            link = DiscogsLink(DiscogsKind.RELEASE, 700),
            title = "Album", artistNames = listOf("Artist"),
            country = "US", year = 2022,
            labels = emptyList(), barcodes = emptyList(),
            formats = listOf("CD", "Album"),
            tracklist = emptyList(), unofficial = false,
            masterId = 90, artistIds = listOf(artist.artistId),
        )
        val version = original.copy(
            link = DiscogsLink(DiscogsKind.RELEASE, 701),
            country = "Japan", formats = listOf("Vinyl", "Album"),
        )
        val unrelated = version.copy(
            link = DiscogsLink(DiscogsKind.RELEASE, 702), masterId = 91,
        )
        val unofficial = version.copy(
            link = DiscogsLink(DiscogsKind.RELEASE, 703), unofficial = true,
        )
        val silent = version.copy(
            link = DiscogsLink(DiscogsKind.RELEASE, 704),
            country = null, year = null, formats = emptyList(),
        )
        val shelf = discogsMasterVersionsShelf(
            original, listOf(version, version, unrelated, unofficial, silent, original),
        )!!
        assertEquals("Versões", shelf.title)
        assertEquals(listOf("discogs:release:701"),
            shelf.items.map { it.browseId })
        assertEquals("Album", shelf.items.single().title)
        assertTrue(shelf.items.single().subtitle.contains("Japan"))
        assertTrue(shelf.items.all { it.videoId == null && it.thumbnailUrl == null })
        val page = discogsReleasePage(
            artist, original, versionRecords = listOf(version, unrelated),
            versionsIncomplete = true,
        )
        assertEquals(original.masterId, page.versionMasterId)
        assertEquals(listOf("Versões"), page.page.sections.map { it.title })
        assertTrue(page.incomplete)
        assertEquals(701L, discogsVerifiedVersionSelection(
            page, "discogs:release:701",
        )?.releaseId)
        assertNull(discogsVerifiedVersionSelection(
            page, "discogs:release:702",
        ))
        assertNull(discogsVerifiedVersionSelection(
            page, "discogs:release:0701",
        ))
        assertNull(discogsVerifiedVersionSelection(
            page, "catalog:RELEASE_GROUP:701",
        ))
        assertNull(discogsMasterVersionsShelf(original.copy(masterId = null),
            listOf(version)))
        assertFalse(discogsReleasePage(artist, original).incomplete)
    }

    @Test fun failedVersionIndexKeepsAlbumIncompleteWithoutInventingVersionCards() {
        val source = DiscogsRecord(
            link = DiscogsLink(DiscogsKind.RELEASE, 700L),
            title = "Verified Album",
            artistNames = listOf("Artist"),
            artistIds = listOf(artist.artistId),
            country = "Brazil",
            year = 2025,
            labels = emptyList(),
            barcodes = emptyList(),
            formats = listOf("Album", "CD"),
            tracklist = emptyList(),
            unofficial = false,
            masterId = 90L,
        )
        val initial = discogsReleasePage(artist, source)
        assertFalse(initial.incomplete)
        val interrupted = discogsReleasePage(
            artist, source, versionRecords = emptyList(),
            versionsIncomplete = true,
        )
        assertTrue(interrupted.incomplete)
        assertTrue(interrupted.page.sections.none { it.title == "Versões" })
        assertEquals("discogs:release:700", interrupted.page.browseId)
        assertEquals(90L, interrupted.versionMasterId)
        assertEquals(initial.editionFacts, interrupted.editionFacts)
        // A partially verified index must never authorize clicking an
        // imaginary edition.
        assertNull(discogsVerifiedVersionSelection(
            interrupted, "discogs:release:701",
        ))
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

    @Test fun nativeAlbumFactsUseOnlyItsExactHydratedDiscogsRelease() {
        val edition = DiscogsRecord(
            link = DiscogsLink(DiscogsKind.RELEASE, 902L),
            title = "Source Album",
            artistNames = listOf("Credited Artist"),
            country = "Brazil",
            year = 2023,
            labels = listOf("Label A" to "ABC-001", "Label A" to "ABC-001"),
            barcodes = emptyList(),
            formats = listOf("CD", "Album", "CD"),
            tracklist = emptyList(),
            unofficial = false,
        )
        val facts = discogsReleasePage("Credited Artist", edition).editionFacts
        assertEquals(listOf(
            "Ano" to "2023",
            "País" to "Brazil",
            "Formatos" to "CD · Album",
            "Gravadoras" to "Label A",
            "Nº de catálogo" to "ABC-001",
        ), facts)
        assertTrue(discogsReleaseMetadataFacts(edition.copy(
            country = null, year = null, labels = emptyList(),
            formats = emptyList(),
        )).isEmpty())
        // Informational facts are never fictional tracks or media assets.
        val page = discogsReleasePage("Credited Artist", edition)
        assertTrue((page.page.songs as UiState.Success).data.isEmpty())
        assertTrue(page.tracks.isEmpty())
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
