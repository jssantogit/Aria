package com.music.aria

import com.music.aria.data.settings.SongSort
import com.music.aria.ui.screens.NativeMetadataTrack
import com.music.aria.ui.screens.canonicalMetadataAlbumVisibleIndices
import com.music.aria.ui.screens.nativeMetadataAlbumTrackNumber
import com.music.aria.ui.screens.nativeMetadataAlbumTrackSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Source-only Apple/Discogs album rows use the canonical non-playable track list. */
class NativeMetadataAlbumPresentationTest {
    private val tracks = listOf(
        NativeMetadataTrack("itunes:br:track:10", "Intro", "Artist", 1, 1),
        NativeMetadataTrack("itunes:br:track:11", "Finale", "Guest", 2, 1),
        NativeMetadataTrack("itunes:br:track:12", "Encore", "Artist", 1, 2),
    )

    @Test fun metadataFilteringAndSortingKeepTheOriginalSourcePositions() {
        val rows = tracks.map { it.title to it.credit }
        assertEquals(listOf(0, 1, 2),
            canonicalMetadataAlbumVisibleIndices(rows, "", SongSort.DEFAULT))
        assertEquals(listOf(2, 1, 0),
            canonicalMetadataAlbumVisibleIndices(rows, "", SongSort.TITLE_ASC))
        assertEquals(listOf(1),
            canonicalMetadataAlbumVisibleIndices(rows, "guest", SongSort.DEFAULT))
        assertEquals(2, nativeMetadataAlbumTrackNumber(tracks[1], 1))
        assertEquals(1, nativeMetadataAlbumTrackNumber(tracks[2], 2))
    }

    @Test fun exactVinylAndNestedPositionsAndDurationRemainVisibleAfterSorting() {
        val vinyl = NativeMetadataTrack("discogs:track:55:0", "Movement", "Guest", null, null,
            sourcePosition = "A3.2", durationMs = 183000L, workTitle = "Suite")
        assertEquals("A3.2", com.music.aria.ui.screens.nativeMetadataAlbumTrackPositionLabel(vinyl, 17))
        assertEquals("Guest · Suite · 3:03", nativeMetadataAlbumTrackSubtitle(vinyl, "Artist"))
        val cd = vinyl.copy(sourcePosition = "2-1", trackNumber = 1, discNumber = 2,
            credit = "Artist", durationMs = null, workTitle = null)
        assertEquals("2-1", com.music.aria.ui.screens.nativeMetadataAlbumTrackPositionLabel(cd, 5))
        assertEquals("Disco 2", nativeMetadataAlbumTrackSubtitle(cd, "Artist"))
    }

    @Test fun metadataOnlyTrackHasNoInventedArtistOrPlayableIdentity() {
        assertNull(nativeMetadataAlbumTrackSubtitle(tracks[0], "Artist"))
        assertEquals("Guest", nativeMetadataAlbumTrackSubtitle(tracks[1], "Artist"))
        assertEquals("Disco 2", nativeMetadataAlbumTrackSubtitle(tracks[2], "Artist"))
        val discogs = NativeMetadataTrack("discogs:track:55:0", "Side A", null, null, null)
        assertEquals(1, nativeMetadataAlbumTrackNumber(discogs, 0))
        assertNull(nativeMetadataAlbumTrackSubtitle(discogs, ""))
    }
}
