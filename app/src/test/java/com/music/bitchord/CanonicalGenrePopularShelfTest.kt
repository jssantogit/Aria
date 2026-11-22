package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityEvidence
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.*
import org.junit.Test

class CanonicalGenrePopularShelfTest {
    @Test fun measuredListenersRankOnlyExactTaggedRecordingMbids() {
        val a = "11111111-1111-4111-8111-111111111111"
        val b = "22222222-2222-4222-8222-222222222222"
        val unknown = "33333333-3333-4333-8333-333333333333"
        val snapshot = CanonicalGenrePopularSnapshot(
            tag = "jazz",
            rows = listOf(
                CanonicalCatalogSearchRow(a, CanonicalSearchKind.RECORDING, "Song A", "Artist"),
                CanonicalCatalogSearchRow(b, CanonicalSearchKind.RECORDING, "Song B", "Artist"),
            ),
            observations = listOf(
                ListenBrainzPopularityEvidence.Known(a, listens = 80, listeners = 20),
                ListenBrainzPopularityEvidence.Known(b, listens = 60, listeners = 50),
                ListenBrainzPopularityEvidence.Unknown(unknown),
            ),
        )
        val shelf = canonicalGenrePopularShelf(snapshot)!!
        assertEquals("Músicas populares", shelf.title)
        assertEquals(listOf("catalog:RECORDING:$b", "catalog:RECORDING:$a"),
            shelf.items.map { it.browseId })
        assertTrue(shelf.items.all { it.videoId == null })
        assertNull(canonicalGenrePopularShelf(snapshot.copy(observations = emptyList())))
    }
}
