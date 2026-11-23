package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.CanonicalGenrePageSnapshot
import com.music.bitchord.ui.appendCanonicalGenrePage
import com.music.bitchord.ui.canonicalGenreContinuationShelf
import com.music.bitchord.ui.canonicalGenreNearEnd
import com.music.bitchord.ui.canonicalGenreShowsMusicBrainzReleases
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.*
import org.junit.Test

class CanonicalGenreGranularPagingTest {
    private fun id(index: Int) = "%08d-1111-4111-8111-111111111111".format(index)
    private fun hit(index: Int, type: String = "Album") = MusicBrainzReleaseGroupHit(
        musicBrainzId = id(index), title = "Source release $index",
        primaryType = type, firstReleaseDate = "2026-11-01", artistDisplay = "Source artist",
    )
    private val today = "2026-11-23"

    @Test fun fullSixtyRowPageKeepsLateAlbumAndAdvancesByRawSourcePositions() {
        val first = CanonicalGenrePageSnapshot(tag = "rock", nextOffset = 60, loading = true)
        val results = (61..120).map { hit(it, if (it == 120) "Album" else "Single") }
        val next = appendCanonicalGenrePage(
            first, "rock", 60, 60,
            MusicBrainzCatalogPage(results, total = 240, offset = 60, consumedCount = 60),
            today,
        )
        assertNotNull(next)
        assertEquals(120, next!!.nextOffset)
        assertEquals(60, next.rows.size)
        assertTrue(next.rows.any { it.musicBrainzId == id(120) })
        assertFalse(next.loading)
        assertFalse(next.failed)
    }

    @Test fun duplicateMusicBrainzIdentitiesDoNotDuplicateCardsAcrossPages() {
        val existing = CanonicalCatalogSearchRow(
            musicBrainzId = id(61), kind = CanonicalSearchKind.RELEASE_GROUP,
            title = "Already seen", subtitle = "Artist", releaseKind = "Album",
        )
        val previous = CanonicalGenrePageSnapshot(
            tag = "rock", nextOffset = 120, rows = listOf(existing),
        )
        val next = appendCanonicalGenrePage(
            previous, "rock", 120, 60,
            MusicBrainzCatalogPage(
                items = listOf(hit(61), hit(121)),
                total = 122, offset = 120, consumedCount = 2,
            ), today,
        )!!
        assertNull(next.nextOffset)
        assertEquals(listOf(id(61), id(121)), next.rows.map { it.musicBrainzId })
        val original = listOf(HomeShelf("Álbuns", listOf(
            ShelfItem("Already shown", "Artist", null, null,
                "catalog:RELEASE_GROUP:${id(61)}"),
        )))
        val shelf = canonicalGenreContinuationShelf(
            "rock", true, original, next,
        )!!
        assertEquals(listOf("catalog:RELEASE_GROUP:${id(121)}"),
            shelf.items.map { it.browseId })
        assertNull(canonicalGenreContinuationShelf("rock", false, original, next))
        assertNull(canonicalGenreContinuationShelf("pop", true, original, next))
    }

    @Test fun failuresOrMismatchedSourceOffsetsCannotAdvanceOrPolluteAnotherGenre() {
        val previous = CanonicalGenrePageSnapshot(tag = "rock", nextOffset = 60)
        val valid = MusicBrainzCatalogPage(listOf(hit(61)), total = 100, offset = 60)
        assertNull(appendCanonicalGenrePage(previous, "pop", 60, 60, valid, today))
        assertNull(appendCanonicalGenrePage(previous, "rock", 30, 60, valid, today))
        assertNull(appendCanonicalGenrePage(
            previous, "rock", 60, 60,
            MusicBrainzCatalogPage(listOf(hit(61)), total = 100, offset = 30), today,
        ))
        assertNull(appendCanonicalGenrePage(
            previous, "rock", 60, 60,
            MusicBrainzCatalogPage(emptyList(), total = 100, offset = 60, consumedCount = 0),
            today,
        ))
        val exhausted = appendCanonicalGenrePage(
            previous, "rock", 60, 60,
            MusicBrainzCatalogPage(emptyList(), total = 60, offset = 60, consumedCount = 0),
            today,
        )!!
        assertNull(exhausted.nextOffset)
        assertTrue(exhausted.rows.isEmpty())
    }

    @Test fun scrollTriggerRequiresActualMovementAndBoundedVisibleDistance() {
        assertFalse(canonicalGenreNearEnd(false, 99, 100))
        assertFalse(canonicalGenreNearEnd(true, -1, 100))
        assertFalse(canonicalGenreNearEnd(true, 2, 100))
        assertFalse(canonicalGenreNearEnd(true, 2, 0))
        assertTrue(canonicalGenreNearEnd(true, 97, 100))
        assertTrue(canonicalGenreNearEnd(true, 0, 1))
    }

    @Test fun onlyVisibleMusicBrainzReleaseShelvesCanActivatePagination() {
        val mbRelease = UiState.Success(listOf(HomeShelf("Álbuns", listOf(
            ShelfItem("Album", "Artist", null, null, "catalog:RELEASE_GROUP:${id(1)}"),
        ))))
        assertTrue(canonicalGenreShowsMusicBrainzReleases(mbRelease))
        assertFalse(canonicalGenreShowsMusicBrainzReleases(UiState.Loading))
        assertFalse(canonicalGenreShowsMusicBrainzReleases(UiState.Success(listOf(
            HomeShelf("Artistas", listOf(
                ShelfItem("Artist", null, null, null, "catalog:ARTIST:${id(1)}"),
            )),
        ))))
        assertFalse(canonicalGenreShowsMusicBrainzReleases(UiState.Success(listOf(
            HomeShelf("Álbuns", listOf(
                ShelfItem("Apple", "Artist", null, null, "itunes:br:album:123"),
            )),
        ))))
    }
}
