package com.music.aria

import com.music.aria.data.catalog.metadata.discogs.DiscogsEditorialCandidate
import com.music.aria.data.catalog.metadata.discogs.DiscogsEditorialMasterParsing
import com.music.aria.data.catalog.metadata.discogs.DiscogsKind
import com.music.aria.data.catalog.metadata.discogs.DiscogsLink
import com.music.aria.data.catalog.metadata.discogs.DiscogsMasterRecord
import com.music.aria.data.catalog.metadata.discogs.discogsEditorialMasterMatches
import com.music.aria.ui.discogsEditorialQueryForTag
import com.music.aria.ui.discogsEditorialQueriesForTag
import com.music.aria.ui.discogsMultiStylePageBudget
import com.music.aria.ui.discogsHomeDiscoveryTags
import com.music.aria.ui.discogsEditorialShelves
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
        // Discovery + album/EP classification can share a verified Master.
        // That is not a duplicate artist or edition *within* any shelf.
        assertEquals(4, items.map { it.browseId }.distinct().size)
        assertTrue(shelves.all { shelf ->
            shelf.items.map { it.browseId }.distinct().size == shelf.items.size
        })
        assertEquals(listOf("discogs:release:502"),
            shelves.single { it.title == "Álbuns" }.items.map { it.browseId })
        assertEquals(setOf("discogs:release:504", "discogs:release:505"),
            shelves.single { it.title == "Singles e EPs" }
                .items.map { it.browseId }.toSet())
        assertTrue(items.all { it.browseId!!.startsWith("discogs:release:") })
        assertTrue(items.all { it.videoId == null && it.thumbnailUrl == null })
        assertFalse(shelves.any { it.title.contains("Em alta") ||
            it.title.contains("Mais tocadas") })
    }
    @Test fun umbrellaGenresUseOnlyTypedExactDiscogsStyles() {
        val metal = discogsEditorialQueriesForTag("metal")
        assertTrue(metal.size >= 4)
        assertTrue(metal.all { it.isStyle && it.sourceName.isNotBlank() })
        assertTrue(metal.any { it.sourceName == "Death Metal" })
        val rnb = discogsEditorialQueriesForTag("rnb")
        assertTrue(rnb.any { it.sourceName == "Rhythm & Blues" })
        assertTrue(rnb.all { it.isStyle })
        val folk = discogsEditorialQueriesForTag("folk")
        assertTrue(folk.any { it.sourceName == "Folk" })
        assertEquals(listOf("K-Pop"),
            discogsEditorialQueriesForTag("k-pop").map { it.sourceName })
        val conscious = discogsEditorialQueriesForTag("conscious hip hop").single()
        assertEquals("Conscious", conscious.sourceName)
        assertEquals("Hip Hop", conscious.requiredGenre)
        assertTrue(conscious.isStyle)
        val hipHopMaster = DiscogsMasterRecord(
            link = DiscogsLink(DiscogsKind.MASTER, 44),
            title = "Conscious Release", artistNames = listOf("Artist"),
            year = 2024, mainReleaseId = 502,
            genres = listOf("Hip Hop"), styles = listOf("Conscious"),
        )
        assertTrue(discogsEditorialMasterMatches(hipHopMaster, conscious))
        assertFalse(discogsEditorialMasterMatches(
            hipHopMaster.copy(genres = listOf("Electronic")), conscious,
        ))
        assertTrue(discogsEditorialQueriesForTag("not-a-real-genre").isEmpty())
    }

    @Test fun discogsOnlyHomeReservesDiscoverySlotsForKpopMetalRnbAndFolk() {
        assertEquals(9, discogsHomeDiscoveryTags.distinct().size)
        assertTrue(discogsHomeDiscoveryTags.containsAll(
            listOf("pop", "rock", "hip-hop", "electronic", "jazz",
                "k-pop", "metal", "rnb", "folk"),
        ))
        // Every Home category must map to an actual typed Discogs query.
        assertTrue(discogsHomeDiscoveryTags.all {
            discogsEditorialQueriesForTag(it).isNotEmpty()
        })
        val budgets = mutableListOf<Int>()
        var verifiedMasters = 0
        for ((index, _) in discogsHomeDiscoveryTags.withIndex()) {
            val budget = discogsMultiStylePageBudget(
                verifiedMasters = verifiedMasters,
                remainingQueries = discogsHomeDiscoveryTags.size - index - 1,
                totalSlots = 10,
            ).coerceAtMost(2)
            budgets += budget
            verifiedMasters += budget
        }
        assertEquals(listOf(2, 1, 1, 1, 1, 1, 1, 1, 1), budgets)
        assertEquals(10, verifiedMasters)

        // A sparse early category gives unused slots to later categories,
        // rather than reporting a fake release in order to fill capacity.
        assertEquals(2, discogsMultiStylePageBudget(
            verifiedMasters = 1, remainingQueries = 7, totalSlots = 10,
        ).coerceAtMost(2))
    }

    @Test fun umbrellaGenreSourceSamplingReservesSlotsForOtherVerifiedStyles() {
        // Five actual source Styles for Metal, not a fabricated umbrella
        // Discogs Genre. The first matching Style may not starve the rest.
        var accepted = 0
        val budgets = (0 until 5).map { index ->
            discogsMultiStylePageBudget(
                verifiedMasters = accepted, remainingQueries = 4 - index,
            ).also { accepted += it }
        }
        assertEquals(listOf(2, 1, 1, 1, 1), budgets)
        assertEquals(6, accepted)
        assertEquals(6, discogsMultiStylePageBudget(0, 0))
        assertEquals(0, discogsMultiStylePageBudget(6, 3))

        // If the first queried Style yields nothing, later real Styles
        // can use the unclaimed slots without inventing releases.
        assertEquals(3, discogsMultiStylePageBudget(0, 3))
        assertEquals(4, discogsMultiStylePageBudget(0, 2))
    }

    @Test fun sparseVerifiedCatalogRetainsAlbumWithoutFalselyCallingYearOnlyReleaseNew() {
        val one = DiscogsEditorialCandidate(
            42, 502, "Verified Album", "Verified Artist", 2025, listOf("Album"),
        )
        val shelves = discogsEditorialShelves(listOf(one))
        assertEquals(listOf("discogs:release:502"),
            shelves.single { it.title == "Para descobrir" }.items.map { it.browseId })
        assertEquals(listOf("discogs:release:502"),
            shelves.single { it.title == "Álbuns" }.items.map { it.browseId })
        assertFalse(shelves.any { it.title == "Lançamentos" ||
            it.title == "Singles e EPs" || it.title == "Mais para explorar" })
    }

    @Test fun currentYearAloneStillCannotProveDiscogsGenreLaunchFreshness() {
        val candidates = listOf(
            DiscogsEditorialCandidate(42, 502, "2026 release", "Artist", 2026, listOf("Album")),
            DiscogsEditorialCandidate(43, 503, "2024 release", "Artist", 2024, listOf("EP")),
        )
        val shelves = discogsEditorialShelves(candidates, home = false)
        assertTrue(shelves.any { it.title == "Para descobrir" })
        assertTrue(shelves.any { it.title == "Álbuns" })
        assertTrue(shelves.any { it.title == "Singles e EPs" })
        assertFalse(shelves.any { it.title == "Lançamentos" || it.title == "Em alta" })
        assertTrue(shelves.flatMap { it.items }.all {
            it.videoId == null && it.thumbnailUrl == null &&
                it.browseId.orEmpty().startsWith("discogs:release:")
        })
    }

    @Test fun genreArtistShelfUsesOnlyVerifiedSourceIdsWithoutPlayback() {
        val releases = listOf(
            DiscogsEditorialCandidate(10, 100, "One", "ILLIT (2)", 2024,
                listOf("Album"), creditedArtists = listOf(55L to "ILLIT (2)")),
            DiscogsEditorialCandidate(11, 101, "Two", "ILLIT (2)", 2023,
                listOf("Album"), creditedArtists = listOf(55L to "ILLIT (2)")),
            DiscogsEditorialCandidate(12, 102, "Other", "Other", 2022,
                listOf("Album"), creditedArtists = listOf(0L to "Spoof")),
            DiscogsEditorialCandidate(13, 103, "Same stage name", "ILLIT", 2021,
                listOf("Album"), creditedArtists = listOf(56L to "ILLIT")),
        )
        val artistShelf = discogsEditorialShelves(releases)
            .single { it.title == "Artistas" }
        assertEquals(1, artistShelf.items.size)
        // Two Discogs IDs survive internally, but this shelf must not
        // display two visually identical ILLIT cards.
        assertEquals("ILLIT", artistShelf.items.single().title)
        assertEquals("discogs:artist:55", artistShelf.items.single().browseId)
        assertNull(artistShelf.items.single().videoId)
    }

}
