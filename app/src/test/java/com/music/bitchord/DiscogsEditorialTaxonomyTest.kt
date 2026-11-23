package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialCandidate
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialMasterParsing
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsKind
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsLink
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsMasterRecord
import com.music.bitchord.data.catalog.metadata.discogs.discogsEditorialMasterMatches
import com.music.bitchord.ui.discogsEditorialQueryForTag
import com.music.bitchord.ui.discogsEditorialShelves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscogsEditorialTaxonomyTest {
    @Test fun sourceGenreAndStyleRemainDistinctEvenWhenBrowsingSameUi() {
        val pop = discogsEditorialQueryForTag("pop")
        val kpop = discogsEditorialQueryForTag("k-pop")
        assertEquals("Pop", pop?.sourceName)
        assertFalse(pop!!.isStyle)
        assertEquals("K-Pop", kpop?.sourceName)
        assertTrue(kpop!!.isStyle)
        assertNull(discogsEditorialQueryForTag("not-a-real-genre"))
        // An unproven category never silently becomes a similarly named tag.
        assertNull(discogsEditorialQueryForTag("rnb"))
        val master = DiscogsMasterRecord(
            link = DiscogsLink(DiscogsKind.MASTER, 42),
            title = "Proven Release",
            artistNames = listOf("Artist"),
            year = 2020,
            mainReleaseId = 502,
            genres = listOf("Pop"),
            styles = listOf("K-Pop"),
        )
        assertTrue(discogsEditorialMasterMatches(master, pop))
        assertTrue(discogsEditorialMasterMatches(master, kpop))
        assertFalse(discogsEditorialMasterMatches(
            master, kpop.copy(sourceName = "Pop"),
        ))
        assertFalse(discogsEditorialMasterMatches(
            master, pop.copy(sourceName = "K-Pop"),
        ))
    }

    @Test fun structuredMasterSearchIsOnlyAListOfCandidateIds() {
        val body = """{"pagination":{"page":1,"pages":4},
            "results":[{"id":42,"type":"master","title":"not trusted"},
                       {"id":42,"type":"master"},
                       {"id":55,"type":"release"}]}"""
        assertEquals(listOf(42L), DiscogsEditorialMasterParsing.candidates(body))
        assertNull(DiscogsEditorialMasterParsing.candidates(
            """{"pagination":{"page":2},"results":[]}""",
        ))
        assertNull(DiscogsEditorialMasterParsing.candidates("broken"))
    }

    @Test fun sourceOnlyEditorialShelvesHaveNoPlayabilityOrDuplicateMasters() {
        val input = listOf(
            DiscogsEditorialCandidate(42, 502, "Album", "Artist", 2020, listOf("Album")),
            DiscogsEditorialCandidate(42, 503, "Other edition", "Artist", 2020, listOf("Album")),
            DiscogsEditorialCandidate(43, 504, "Single", "Artist", 2021, listOf("Single")),
            DiscogsEditorialCandidate(44, 505, "EP", "Artist", 2022, listOf("EP")),
            DiscogsEditorialCandidate(45, 506, "Other", "Artist", 2023, listOf("CD")),
        )
        val shelves = discogsEditorialShelves(input)
        assertNotNull(shelves.firstOrNull())
        val items = shelves.flatMap { it.items }
        assertEquals(4, items.size)
        assertEquals(4, items.map { it.browseId }.distinct().size)
        assertTrue(items.all { it.browseId!!.startsWith("discogs:release:") })
        assertTrue(items.all { it.videoId == null && it.thumbnailUrl == null })
        assertFalse(shelves.any { it.title.contains("Em alta") ||
            it.title.contains("Mais tocadas") })
    }
    @Test fun genreArtistShelfUsesOnlyVerifiedSourceIdsWithoutPlayback() {
        val releases = listOf(
            DiscogsEditorialCandidate(10, 100, "One", "ILLIT (2)", 2024,
                listOf("Album"), creditedArtists = listOf(55L to "ILLIT (2)")),
            DiscogsEditorialCandidate(11, 101, "Two", "ILLIT (2)", 2023,
                listOf("Album"), creditedArtists = listOf(55L to "ILLIT (2)")),
            DiscogsEditorialCandidate(12, 102, "Other", "Other", 2022,
                listOf("Album"), creditedArtists = listOf(0L to "Spoof")),
        )
        val artistShelf = discogsEditorialShelves(releases)
            .single { it.title == "Artistas" }
        assertEquals(1, artistShelf.items.size)
        assertEquals("discogs:artist:55", artistShelf.items.single().browseId)
        assertNull(artistShelf.items.single().videoId)
    }

}
