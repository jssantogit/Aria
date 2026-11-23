package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.ui.DiscogsGenrePageSnapshot
import com.music.bitchord.ui.advanceDiscogsGenrePage
import com.music.bitchord.ui.discogsShouldContinueEmptyCandidateScan
import com.music.bitchord.ui.discogsGenreContinuationShelf
import com.music.bitchord.ui.canonicalGenreShowsDiscogsReleases
import com.music.bitchord.ui.projectCanonicalGenreState
import org.junit.Assert.*
import org.junit.Test

class DiscogsGenreGranularPagingTest {
    private fun release(master: Long) = DiscogsEditorialCandidate(
        masterId = master, releaseId = master + 1000,
        title = "Verified album", artist = "Verified artist",
        year = 2024, formats = listOf("Album"),
        creditedArtists = listOf(99L to "Verified artist"),
    )

    @Test fun emptyCandidatePagesAreBoundedAndDoNotStallOnAlreadyKnownMasters() {
        val initial = DiscogsGenrePageSnapshot(
            tag = "rock", cursor = DiscogsEditorialCursor(),
            knownMasterIds = setOf(1L, 2L, 3L, 4L),
        )
        val first = advanceDiscogsGenrePage(
            initial, "rock", queryCount = 1,
            read = DiscogsGranularRead.Page(
                releases = emptyList(), nextCursor = DiscogsEditorialCursor(1, 4, 99),
                consumedCandidates = 4, hydratedCandidates = 0,
            ),
        )!!
        assertTrue(discogsShouldContinueEmptyCandidateScan(initial, first, 1))
        val second = advanceDiscogsGenrePage(
            first, "rock", queryCount = 1,
            read = DiscogsGranularRead.Page(
                releases = emptyList(), nextCursor = DiscogsEditorialCursor(1, 8, 99),
                consumedCandidates = 4, hydratedCandidates = 4,
            ),
        )!!
        assertTrue(discogsShouldContinueEmptyCandidateScan(initial, second, 2))
        assertFalse(discogsShouldContinueEmptyCandidateScan(initial, second, 3))
        assertFalse(discogsShouldContinueEmptyCandidateScan(initial, second, 1, maxBatches = 1))
        val accepted = advanceDiscogsGenrePage(
            second, "rock", queryCount = 1,
            read = DiscogsGranularRead.Page(
                releases = listOf(release(12L)), nextCursor = null,
                consumedCandidates = 4, hydratedCandidates = 4,
            ),
        )!!
        assertFalse(discogsShouldContinueEmptyCandidateScan(initial, accepted, 1))
        assertFalse(discogsShouldContinueEmptyCandidateScan(initial,
            second.copy(failed = true), 1))
        assertFalse(discogsShouldContinueEmptyCandidateScan(initial,
            second.copy(tag = "pop"), 1))
        assertFalse(discogsShouldContinueEmptyCandidateScan(initial,
            second.copy(cursor = null), 1))
    }

    @Test fun partialFailureAppendsEvidenceAndRetriesSamePosition() {
        val starting = DiscogsGenrePageSnapshot(
            tag = "rock", queryIndex = 0, cursor = DiscogsEditorialCursor(),
            knownMasterIds = setOf(1L), loading = true,
        )
        val interrupted = advanceDiscogsGenrePage(
            starting, "rock", queryCount = 2,
            read = DiscogsGranularRead.Interrupted(
                listOf(release(2L)), DiscogsEditorialCursor(1, 2, 123),
                DiscogsEditorialResult.Throttled(60L),
            ),
        )!!
        assertEquals(listOf(2L), interrupted.releases.map { it.masterId })
        assertEquals(setOf(1L, 2L), interrupted.knownMasterIds)
        assertEquals(2, interrupted.cursor!!.candidatePosition)
        assertEquals(0, interrupted.queryIndex)
        assertTrue(interrupted.failed)
        assertFalse(interrupted.loading)
        val resumed = advanceDiscogsGenrePage(
            interrupted, "rock", queryCount = 2,
            read = DiscogsGranularRead.Page(
                releases = listOf(release(2L), release(3L)),
                nextCursor = DiscogsEditorialCursor(2),
                consumedCandidates = 2, hydratedCandidates = 1,
            ),
        )!!
        assertEquals(listOf(2L, 3L), resumed.releases.map { it.masterId })
        assertFalse(resumed.failed)
        assertEquals(2, resumed.cursor!!.page)
    }

    @Test fun completingSourceStyleContinuesToNextWithoutChangingExistingCards() {
        val initial = DiscogsGenrePageSnapshot(
            tag = "metal", queryIndex = 0, cursor = DiscogsEditorialCursor(),
        )
        val afterFirstStyle = advanceDiscogsGenrePage(
            initial, "metal", queryCount = 3,
            read = DiscogsGranularRead.Page(
                releases = listOf(release(10L)), nextCursor = null,
                consumedCandidates = 1, hydratedCandidates = 1,
            ),
        )!!
        assertEquals(1, afterFirstStyle.queryIndex)
        assertEquals(DiscogsEditorialCursor(), afterFirstStyle.cursor)
        val afterSecondStyle = advanceDiscogsGenrePage(
            afterFirstStyle, "metal", queryCount = 3,
            read = DiscogsGranularRead.Page(
                releases = listOf(release(20L)), nextCursor = null,
                consumedCandidates = 1, hydratedCandidates = 1,
            ),
        )!!
        val finished = advanceDiscogsGenrePage(
            afterSecondStyle, "metal", queryCount = 3,
            read = DiscogsGranularRead.Page(
                releases = listOf(release(30L)), nextCursor = null,
                consumedCandidates = 1, hydratedCandidates = 1,
            ),
        )!!
        assertEquals(listOf(10L, 20L, 30L), finished.releases.map { it.masterId })
        assertNull(finished.cursor)
        assertFalse(finished.hasMore)
        assertNull(advanceDiscogsGenrePage(finished, "rock", 3,
            DiscogsGranularRead.Page(emptyList(), null, 0, 0)))
    }

    @Test fun appendShelfIsOnlyDiscogsAndDoesNotRepeatLandingCards() {
        val landing = listOf(
            HomeShelf("Álbuns", listOf(
                ShelfItem("Landing", "Artist", null, null, "discogs:release:1001"),
            )),
            HomeShelf("Artistas", listOf(
                ShelfItem("Artist", "Artista", null, null, "discogs:artist:99"),
            )),
        )
        val paging = DiscogsGenrePageSnapshot(
            tag = "rock",
            cursor = DiscogsEditorialCursor(4),
            knownMasterIds = setOf(1L, 2L),
            releases = listOf(release(1L), release(2L)),
        )
        val extra = discogsGenreContinuationShelf("rock", true, landing, paging)!!
        assertEquals("Mais do gênero", extra.title)
        assertEquals(listOf("discogs:release:1002"), extra.items.map { it.browseId })
        assertTrue(extra.items.all { it.thumbnailUrl == null && it.videoId == null })
        assertNull(discogsGenreContinuationShelf("rock", false, landing, paging))
        assertNull(discogsGenreContinuationShelf("pop", true, landing, paging))
        assertTrue(canonicalGenreShowsDiscogsReleases(UiState.Success(landing)))
        assertFalse(canonicalGenreShowsDiscogsReleases(UiState.Success(listOf(
            HomeShelf("Artistas", landing[1].items),
        ))))
    }

    @Test fun canonicalGenreProjectionDoesNotMixDiscogsPagingIntoMbEditorial() {
        val original = listOf(HomeShelf("Álbuns", listOf(
            ShelfItem("Discogs", "Artist", null, null, "discogs:release:1001"),
        )))
        val paging = DiscogsGenrePageSnapshot(
            tag = "rock", cursor = DiscogsEditorialCursor(2),
            knownMasterIds = setOf(1L, 2L),
            releases = listOf(release(1L), release(2L)),
        )
        fun project(order: List<com.music.bitchord.data.providers.ProviderKey>) =
            projectCanonicalGenreState(
                tag = "rock", genrePlaylists = emptyList(),
                playlistHeading = "Playlists", appleByTag = emptyMap(),
                musicBrainzRows = UiState.Success(emptyList()),
                ordered = order, releasesHeading = "Lançamentos",
                discogsByTag = mapOf("rock" to UiState.Success(original)),
                discogsPaging = paging,
            )
        val discogs = (project(listOf(BuiltInProviderKeys.DISCOGS))
            as UiState.Success).data
        assertEquals("Mais do gênero", discogs.last().title)
        assertEquals(listOf("discogs:release:1002"),
            discogs.last().items.map { it.browseId })
        val onlyMb = project(listOf(BuiltInProviderKeys.MUSICBRAINZ))
        assertFalse((onlyMb as? UiState.Success)?.data.orEmpty().any {
            it.title == "Mais do gênero"
        } == true)
    }
}
