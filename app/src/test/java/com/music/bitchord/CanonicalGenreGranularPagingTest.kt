package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearch
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzTransport
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.CanonicalGenrePageSnapshot
import com.music.bitchord.ui.appendCanonicalGenrePage
import com.music.bitchord.ui.canonicalGenreContinuationShelf
import com.music.bitchord.ui.canonicalGenreNearEnd
import com.music.bitchord.ui.canonicalPublicGenreRows
import com.music.bitchord.ui.canonicalGenreShowsMusicBrainzReleases
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

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

    @Test fun actualTagQueryTraversesThirtyHundredAndThreeHundredRowsWithoutLosingFirstPageOverflow() = runBlocking {
        for (target in listOf(30, 100, 300)) {
            val offsets = mutableListOf<Int>()
            val source = MusicBrainzCatalogSearch(MusicBrainzClient(
                transport = object : MusicBrainzTransport {
                    override suspend fun get(
                        path: String, parameters: Map<String, String>,
                        userAgent: String,
                    ): String {
                        assertEquals("release-group", path)
                        assertTrue(parameters["query"]!!.contains("tag:\\"rock\\""))
                        val offset = parameters["offset"]!!.toInt()
                        val limit = parameters["limit"]!!.toInt()
                        offsets += offset
                        val releases = (offset until minOf(offset + limit, target)).map { n ->
                            val kind = if (n % 4 == 0) "Album" else "Single"
                            """{"id":"${id(n + 1)}","title":"Source release $n","primary-type":"$kind","first-release-date":"2026-11-01","artist-credit":[{"name":"Source artist"}]}"""
                        }
                        return """{"count":$target,"offset":$offset,"release-groups":[${releases.joinToString(",") }]}"""
                    }
                },
            ))
            val first = (source.searchReleaseGroupsForTag("rock", limit = 60)
                as MusicBrainzCatalogSearchResult.Success).page
            val firstVisible = canonicalPublicGenreRows(first.items, today)
            val initialCards = firstVisible.map { row ->
                ShelfItem(row.title, row.subtitle.orEmpty(), null, null,
                    "catalog:RELEASE_GROUP:${row.musicBrainzId}")
            }
            var paging = CanonicalGenrePageSnapshot(
                tag = "rock", nextOffset = first.nextOffset,
                rows = canonicalPublicGenreRows(first.items, today, maxItems = first.items.size),
            )
            while (paging.nextOffset != null) {
                val offset = requireNotNull(paging.nextOffset)
                val response = (source.searchReleaseGroupsForTag("rock", 60, offset)
                    as MusicBrainzCatalogSearchResult.Success).page
                paging = appendCanonicalGenrePage(
                    paging, "rock", offset, 60, response, today,
                )!!
                assertTrue(offsets.size <= 5)
            }
            val extra = canonicalGenreContinuationShelf(
                "rock", true, listOf(HomeShelf("Álbuns", initialCards)), paging,
            )
            val allIds = initialCards.mapNotNull { it.browseId } +
                extra?.items.orEmpty().mapNotNull { it.browseId }
            assertEquals(target, allIds.distinct().size)
            assertEquals((target + 59) / 60, offsets.size)
        }
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
                ShelfItem("Artist", "", null, null, "catalog:ARTIST:${id(1)}"),
            )),
        ))))
        assertFalse(canonicalGenreShowsMusicBrainzReleases(UiState.Success(listOf(
            HomeShelf("Álbuns", listOf(
                ShelfItem("Apple", "Artist", null, null, "itunes:br:album:123"),
            )),
        ))))
    }
}
