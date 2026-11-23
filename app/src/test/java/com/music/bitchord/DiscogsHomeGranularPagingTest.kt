package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.ui.*
import org.junit.Assert.*
import org.junit.Test

class DiscogsHomeGranularPagingTest {
    private fun edition(master: Long) = DiscogsEditorialCandidate(
        masterId = master, releaseId = master + 1000L,
        title = "Verified Release", artist = "Source Artist",
        year = 2024, formats = listOf("Album"),
    )

    @Test fun rotatesRealHomeStylesInsteadOfStarvingLaterSources() {
        val queries = discogsHomeGranularQueries()
        assertTrue(queries.size > 9)
        assertEquals(queries.size, queries.distinct().size)
        assertTrue(queries.any { it.sourceName == "Heavy Metal" && it.isStyle })
        assertTrue(queries.any { it.sourceName == "K-Pop" && it.isStyle })
        val first = initialDiscogsHomePageSnapshot(listOf(edition(1L)), 3)
        assertEquals(0, first.nextQueryIndex)
        assertEquals(setOf(1L), first.knownMasterIds)
        val afterFirst = advanceDiscogsHomePage(
            first, 0,
            DiscogsGranularRead.Page(
                listOf(edition(1L), edition(10L)),
                DiscogsEditorialCursor(1, 4, 88), 4, 2,
            ),
        )!!
        assertEquals(1, afterFirst.nextQueryIndex)
        assertEquals(4, afterFirst.cursors[0]!!.candidatePosition)
        assertEquals(listOf(10L), afterFirst.releases.map { it.masterId })
        val afterSecond = advanceDiscogsHomePage(
            afterFirst, 1,
            DiscogsGranularRead.Page(
                listOf(edition(20L)), null, 4, 1,
            ),
        )!!
        assertEquals(2, afterSecond.nextQueryIndex)
        val afterThird = advanceDiscogsHomePage(
            afterSecond, 2,
            DiscogsGranularRead.Page(
                listOf(edition(30L)), null, 4, 1,
            ),
        )!!
        assertEquals(0, afterThird.nextQueryIndex)
        val finished = advanceDiscogsHomePage(
            afterThird, 0,
            DiscogsGranularRead.Page(
                listOf(edition(40L)), null, 4, 1,
            ),
        )!!
        assertNull(finished.nextQueryIndex)
        assertFalse(finished.hasMore)
        assertEquals(listOf(10L, 20L, 30L, 40L),
            finished.releases.map { it.masterId })
    }

    @Test fun throttledSourceRetainsCandidateAndDoesNotAdvanceToWrongGenre() {
        val previous = initialDiscogsHomePageSnapshot(emptyList(), 2)
        val retry = DiscogsEditorialCursor(1, 3, 99)
        val interrupted = advanceDiscogsHomePage(
            previous, 0,
            DiscogsGranularRead.Interrupted(
                listOf(edition(7L)), retry,
                DiscogsEditorialResult.Throttled(20L),
            ),
        )!!
        assertTrue(interrupted.failed)
        assertEquals(0, interrupted.nextQueryIndex)
        assertEquals(retry, interrupted.cursors[0])
        assertEquals(listOf(7L), interrupted.releases.map { it.masterId })
        assertNull(advanceDiscogsHomePage(interrupted, 1,
            DiscogsGranularRead.Page(emptyList(), null, 1, 1)))
        val resumed = advanceDiscogsHomePage(
            interrupted, 0,
            DiscogsGranularRead.Page(
                listOf(edition(7L), edition(8L)), null, 2, 1,
            ),
        )!!
        assertFalse(resumed.failed)
        assertEquals(1, resumed.nextQueryIndex)
        assertEquals(listOf(7L, 8L), resumed.releases.map { it.masterId })
    }

    @Test fun sourceCapIsNotMistakenForCompleteCoverage() {
        val previous = initialDiscogsHomePageSnapshot(emptyList(), 1)
        val ending = advanceDiscogsHomePage(
            previous, 0,
            DiscogsGranularRead.Page(
                releases = emptyList(), nextCursor = null,
                consumedCandidates = 1, hydratedCandidates = 1,
                stoppedByPageLimit = true,
            ),
        )!!
        assertNull(ending.nextQueryIndex)
        assertTrue(ending.sourceCeilingHit)
    }

    @Test fun discogsContinuationSharesHomeWidgetsWithoutLeakingToMbOrApple() {
        val editorial = listOf(
            HomeShelf("Para descobrir", listOf(
                ShelfItem("One", "Artist", null, null, "discogs:release:1001"),
            )),
            HomeShelf("Comunidade", listOf(
                ShelfItem("Other", "Artist", null, null, "listenbrainz:playlist:9"),
            )),
        )
        val state = initialDiscogsHomePageSnapshot(listOf(edition(1L)), 2)
            .copy(releases = listOf(edition(1L), edition(2L)))
        val rail = discogsHomeContinuationShelf(
            BuiltInProviderKeys.DISCOGS, editorial, state,
        )!!
        assertEquals("Mais do catálogo", rail.title)
        assertEquals(listOf("discogs:release:1002"), rail.items.map { it.browseId })
        assertTrue(rail.items.all { it.thumbnailUrl == null && it.videoId == null })
        assertNull(discogsHomeContinuationShelf(BuiltInProviderKeys.MUSICBRAINZ,
            editorial, state))
        assertNull(discogsHomeContinuationShelf(BuiltInProviderKeys.ITUNES,
            editorial, state))
        val selection = CatalogEditorialSelection(
            BuiltInProviderKeys.DISCOGS, UiState.Success(editorial.take(1)),
        )
        val composed = projectCanonicalHomeDisplay(
            canonicalHomeShelves = emptyList(), selection = selection,
            publicPlaylistShelf = null, communityShelf = editorial[1],
            discogsPaging = state,
        )
        assertEquals("Mais do catálogo", composed.last().title)
        assertEquals(listOf("Para descobrir", "Comunidade"), composed.take(2).map { it.title })
    }
}
