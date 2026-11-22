package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRecording
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.*
import org.junit.Test

class CanonicalWeeklyChartArtistsShelfTest {
    @Test fun onlyExactArtistMbidsOnWeeklyRecordedRankingsAreAccepted() {
        val a = "11111111-1111-4111-8111-111111111111"
        val b = "22222222-2222-4222-8222-222222222222"
        val c = "33333333-3333-4333-8333-333333333333"
        val known = listOf(
            CanonicalCatalogSearchRow(a, CanonicalSearchKind.ARTIST, "A", null),
            CanonicalCatalogSearchRow(b, CanonicalSearchKind.ARTIST, "B", null),
            CanonicalCatalogSearchRow(c, CanonicalSearchKind.ARTIST, "C", null),
        )
        val songs = listOf(
            ListenBrainzSitewideRecording(c, 100, listOf(b)),
            ListenBrainzSitewideRecording(b, 50, listOf(a)),
            ListenBrainzSitewideRecording(a, 0, listOf(c)),
        )
        val shelf = canonicalWeeklyChartArtistsShelf(known, songs)!!
        assertEquals("Artistas nas paradas", shelf.title)
        assertEquals(listOf("catalog:ARTIST:$b", "catalog:ARTIST:$a"),
            shelf.items.map { it.browseId })
        assertTrue(shelf.items.all { it.videoId == null })
        assertNull(canonicalWeeklyChartArtistsShelf(known, emptyList()))
    }
}
