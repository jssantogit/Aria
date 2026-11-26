package com.music.aria.ui.screens

import com.music.aria.data.catalog.CanonicalBrowseTrack
import com.music.aria.data.settings.SongSort
import com.music.aria.data.model.BrowseType
import com.music.aria.data.LocalGroups
import com.music.aria.download.Downloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

/** A real album detail follows DetailScreen release grammar, not Library rows. */
class CanonicalAlbumDetailParityTest {
    @Test fun albumMetricsRemainScopedToAlbumPresentation() {
        assertEquals(0.66f, ALBUM_SLEEVE_FRACTION, 0.0001f)
        assertEquals(50.dp, nativeReleaseSideButtonSize(true))
        assertEquals(50.dp, nativeReleaseSideButtonSize(false))
    }

    @Test fun albumAndPlaylistCoverUseTheExactSameNativeViewportSizing() {
        // Every source adapter supplies the same viewport width/height to
        // nativeReleaseSleeveSize; this function never reads a provider key.
        // Portrait is width-limited, landscape is height-limited.
        assertEquals(360.dp * 0.66f, nativeReleaseSleeveSize(360.dp, 800.dp))
        assertEquals(180.dp, nativeReleaseSleeveSize(800.dp, 300.dp))
        assertEquals(360.dp * 0.66f, nativeReleaseSleeveSize(360.dp, 500.dp))
    }

    @Test fun downloadsFolderAndAllItsReleaseRoutesUseTheAlbumHeroGeometry() {
        // local:downloads is a folder, not a playlist or album type. Its
        // downloaded release pages use the typed id or an album tag grouping.
        assertTrue(nativeReleaseUsesAlbumGeometry("local:downloads", BrowseType.OTHER))
        assertTrue(nativeReleaseUsesAlbumGeometry(
            Downloads.pageIdFor("release-id"), BrowseType.OTHER,
        ))
        assertTrue(nativeReleaseUsesAlbumGeometry(
            LocalGroups.albumPageId("local:downloads", "Live: 2026"), BrowseType.OTHER,
        ))
        assertTrue(nativeReleaseUsesAlbumGeometry("catalog:RELEASE:example", BrowseType.ALBUM))
        assertTrue(nativeReleaseUsesAlbumGeometry("local:owned-playlist", BrowseType.PLAYLIST))
        // Do not turn the Local Music/Cache folders and artist groups into albums.
        assertFalse(nativeReleaseUsesAlbumGeometry("local:all", BrowseType.OTHER))
        assertFalse(nativeReleaseUsesAlbumGeometry("local:cache", BrowseType.OTHER))
        assertFalse(nativeReleaseUsesAlbumGeometry(
            LocalGroups.artistPageId("local:downloads", "Singer"), BrowseType.ARTIST,
        ))
    }

    @Test fun albumHeaderCaptionRequiresVerifiedReleaseYearAndSourceGenre() {
        val known = canonicalAlbumFooterMetadata("2017-09-15", listOf(track("id", "Song")))
        assertEquals("2017", known.year)
        assertEquals(null, known.genre)
        assertEquals("2017", nativeAlbumHeaderCaption(known))
        assertEquals("Metal · 2017", nativeAlbumHeaderCaption(known.copy(genre = "Metal")))
        assertEquals("", nativeAlbumHeaderCaption(null))
        assertEquals(null, canonicalAlbumFooterMetadata("unknown", emptyList()).year)
        assertEquals(null, canonicalAlbumFooterMetadata("2017-02-30", emptyList()).year)
        assertEquals("2017", canonicalAlbumFooterMetadata("2017", emptyList()).year)
    }

    @Test fun canonicalAlbumRowsOmitRepeatedArtistButRetainGuestCredits() {
        assertEquals(null, nativeAlbumTrackSecondaryLine("Déyyess", " DÉYYESS "))
        assertEquals(null, nativeAlbumTrackSecondaryLine("", "Déyyess"))
        assertEquals("Guest", nativeAlbumTrackSecondaryLine(" Guest ", "Déyyess"))
        assertEquals("Guest", nativeAlbumTrackSecondaryLine("Guest", ""))
    }

    @Test fun missingReleaseCreditUsesOnlyUnanimousVerifiedAlbumArtists() {
        val illit = listOf(track("one", "Super in Love"), track("two", "More"))
            .map { it.copy(artistDisplay = "ILLIT") }
        assertEquals("ILLIT", nativeAlbumDisplayCredit(null, illit))
        assertEquals("ILLIT", nativeAlbumDisplayCredit("  ", illit))
        assertEquals("Official Credit", nativeAlbumDisplayCredit(" Official Credit ", illit))
        assertEquals(null, nativeAlbumTrackSecondaryLine("ILLIT", nativeAlbumDisplayCredit(null, illit)))

        val mixed = illit + track("guest", "Duet").copy(artistDisplay = "Guest Artist")
        assertEquals("", nativeAlbumDisplayCredit(null, mixed))
        assertEquals("ILLIT", nativeAlbumTrackSecondaryLine("ILLIT", nativeAlbumDisplayCredit(null, mixed)))
        assertEquals("", nativeAlbumDisplayCredit(null, emptyList()))
        assertEquals("", nativeAlbumDisplayCredit(null, illit + track("unknown", "Untitled")
            .copy(artistDisplay = "")))
    }

    @Test fun emptyNativeAlbumSearchUsesSameFeedbackInRootAndNestedRoutes() {
        assertEquals(
            "Nenhuma música corresponde à pesquisa.",
            nativeAlbumEmptyMessage("missing", hasPlayableTracks = true),
        )
        assertEquals(
            "Nenhuma música nesta seleção.",
            nativeAlbumEmptyMessage("", hasPlayableTracks = false),
        )
        assertEquals(
            "Nenhuma música nesta seleção.",
            nativeAlbumEmptyMessage("missing", hasPlayableTracks = false),
        )
    }


    private fun track(id: String, title: String) = CanonicalBrowseTrack(
        recordingId = id,
        title = title,
        artistDisplay = "Example artist",
        durationMs = 180_000L,
        artworkUri = null,
        availableAssets = emptyList(),
    )

    @Test fun albumUsesRecordedReleaseRunningOrderNotLibraryAlphabeticalOrder() {
        val tracks = listOf(track("a", "Alpha"), track("b", "Beta"), track("c", "Charlie"))
        assertEquals(
            listOf("c", "a", "b"),
            canonicalAlbumDetailTrackOrder(tracks, listOf("c", "a", "b"))
                .map { it.recordingId },
        )
    }

    @Test fun missingReleaseSequenceRetainsExistingMembershipWithoutDroppingTracks() {
        val tracks = listOf(track("b", "Beta"), track("a", "Alpha"))
        assertEquals(
            listOf("a", "b"),
            canonicalAlbumDetailTrackOrder(tracks, listOf("a", "unknown", "b"))
                .map { it.recordingId },
        )
        assertEquals(tracks, canonicalAlbumDetailTrackOrder(tracks, emptyList()))
    }

    @Test fun verifiedReleaseEvidenceAppearsAsTypeYearAndPlayableTrackCount() {
        val meta = canonicalAlbumDetailMeta(
            primaryType = "EP",
            releaseDate = "2025-06-23",
            tracks = listOf(track("a", "One"), track("b", "Two")),
        )
        assertTrue(meta.startsWith("EP · 2025 · 2 faixas · "))
    }

    @Test fun missingEvidenceNeverInventsAnAlbumTypeOrYear() {
        val meta = canonicalAlbumDetailMeta(
            primaryType = null,
            releaseDate = "not a date",
            tracks = listOf(track("a", "One")),
        )
        assertTrue(meta.startsWith("1 faixa · "))
        assertFalse(meta.contains("2025"))
        assertFalse(meta.contains("EP"))
    }


    @Test fun releaseSortKeepsNativeOrderUntilUserExplicitlyChangesIt() {
        val runningOrder = listOf(track("b", "Beta"), track("a", "Alpha"))
        assertEquals(
            listOf("b", "a"),
            canonicalAlbumDetailSort(runningOrder, SongSort.DEFAULT).map { it.recordingId },
        )
        assertEquals(
            listOf("a", "b"),
            canonicalAlbumDetailSort(runningOrder, SongSort.TITLE_ASC).map { it.recordingId },
        )
        assertEquals(
            listOf("b", "a"),
            canonicalAlbumDetailSort(runningOrder, SongSort.TITLE_DESC).map { it.recordingId },
        )
    }

    @Test fun displaySortAndSearchNeverRenumberTheOriginalAlbum() {
        val original = listOf(track("b", "Beta"), track("a", "Alpha"))
        val numbers = canonicalAlbumDetailTrackNumbers(original)
        assertEquals(1, numbers["b"])
        assertEquals(2, numbers["a"])
        val ascending = canonicalAlbumDetailSort(original, SongSort.TITLE_ASC)
        assertEquals(listOf(2, 1), ascending.map { numbers[it.recordingId] })
    }

    @Test fun epSuffixIsOnlyHiddenWhenVerifiedSubtypeAgrees() {
        assertEquals("ALLDAY PROJECT", canonicalAlbumDisplayTitle("ALLDAY PROJECT - EP", "EP"))
        assertEquals("ALLDAY PROJECT - EP", canonicalAlbumDisplayTitle("ALLDAY PROJECT - EP", null))
        assertEquals("ALLDAY PROJECT - EP", canonicalAlbumDisplayTitle("ALLDAY PROJECT - EP", "Album"))
        assertEquals("A - EP Collection", canonicalAlbumDisplayTitle("A - EP Collection", "EP"))
    }
    @Test fun musicBrainzReleaseCreditNavigatesOnlyWithOneVerifiedLeadArtist() {
        val releaseId = "a8f4b8f4-661e-4553-a524-3ba91d562ed0"
        val artistId = "0249c38a-4bd0-4c7a-ac07-80bb8063d469"
        val credit = com.music.aria.data.catalog.MusicBrainzReleaseArtistCreditEntity(
            releaseId = releaseId,
            position = 0,
            musicBrainzArtistId = artistId,
            name = "SAY MY NAME",
            joinPhrase = null,
        )
        assertEquals(
            "catalog:ARTIST:$artistId",
            verifiedCatalogReleaseLeadArtistRoute("catalog:RELEASE:$releaseId", listOf(credit)),
        )
        assertEquals(null, verifiedCatalogReleaseLeadArtistRoute(
            "catalog:RELEASE_GROUP:$releaseId", listOf(credit),
        ))
        assertEquals(null, verifiedCatalogReleaseLeadArtistRoute(
            "catalog:RELEASE:$releaseId",
            listOf(credit, credit.copy(musicBrainzArtistId = "b8f4b8f4-661e-4553-a524-3ba91d562ed0")),
        ))
    }

    @Test fun localAlbumCreditOpensOnlyTheArtistInTheSameLocalFolder() {
        assertEquals(
            "local:group:artist:all:SAY MY NAME",
            detailAlbumArtistBrowseId("local:group:album:all:&Our Vibe", null, "SAY MY NAME"),
        )
        assertEquals(
            "UC12345678901234567890",
            detailAlbumArtistBrowseId("catalog:RELEASE:abc", "UC12345678901234567890", "SAY MY NAME"),
        )
        assertEquals(
            null,
            detailAlbumArtistBrowseId("catalog:RELEASE:abc", null, "SAY MY NAME"),
        )
        assertEquals(
            null,
            detailAlbumArtistBrowseId("local:group:album:all:&Our Vibe", null, ""),
        )
    }

    @Test fun sharedNativeAlbumRowsKeepOriginalNumbersAfterSearchingOrSorting() {
        val original = mapOf("song-c" to 3, "song-a" to 1, "song-b" to 2)
        assertEquals(3, nativeCanonicalAlbumRowNumber("song-c", 0, original))
        assertEquals(1, nativeCanonicalAlbumRowNumber("song-a", 1, original))
        assertEquals(2, nativeCanonicalAlbumRowNumber("song-b", 2, original))
        // Only truly missing evidence falls back to the visible position.
        assertEquals(4, nativeCanonicalAlbumRowNumber("unknown", 3, original))
    }

    @Test fun nestedNativeAlbumSearchDoesNotChangeReleaseTrackOrderOrNumbers() {
        val ordered = listOf(
            track("recording-c", "Third"),
            track("recording-a", "First"),
            track("recording-b", "Second"),
        )
        val filtered = nativeCanonicalAlbumVisibleTracks(ordered, "first")
        assertEquals(listOf("recording-a"), filtered.map { it.recordingId })
        assertEquals(
            2,
            nativeCanonicalAlbumRowNumber(
                filtered.single().recordingId,
                0,
                canonicalAlbumDetailTrackNumbers(ordered),
            ),
        )
        assertEquals(ordered, nativeCanonicalAlbumVisibleTracks(ordered, ""))
    }

}
