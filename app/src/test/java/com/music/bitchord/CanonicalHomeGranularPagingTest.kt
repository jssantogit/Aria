package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.ui.*
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual MusicBrainz source cursor, never a fabricated display-row offset. */
class CanonicalHomeGranularPagingTest {
    private fun id(n: Int) = "%08d-2222-4222-8222-222222222222".format(n)
    private val date = "2026-11-23"

    private fun row(n: Int) = CanonicalCatalogSearchRow(
        musicBrainzId = id(n), kind = CanonicalSearchKind.RELEASE_GROUP,
        title = "Source album $n", subtitle = "Artist",
        releaseKind = "Album", releaseDate = "2026-11-01",
    )
    private fun scan(
        rows: List<CanonicalCatalogSearchRow> = emptyList(),
        next: Int? = null, failed: Boolean = false,
    ) = MusicBrainzRecentEditorialScan(
        rows = rows, attempts = 1, sourcePositions = 100,
        parsedRows = 100, cursorRemaining = next != null,
        sourceFailure = failed, nextOffset = next,
    )

    @Test fun realScannerTraversesThirtyHundredAndThreeHundredRawPositionsWithoutLosingLateAlbum() =
        runBlocking {
            for (target in listOf(30, 100, 300)) {
                val offsets = mutableListOf<Int>()
                val source = MusicBrainzCatalogSearch(MusicBrainzClient(
                    transport = object : MusicBrainzTransport {
                        override suspend fun get(
                            path: String, parameters: Map<String, String>,
                            userAgent: String,
                        ): String {
                            assertEquals("release-group", path)
                            val offset = parameters["offset"]!!.toInt()
                            val limit = parameters["limit"]!!.toInt()
                            offsets += offset
                            val rows = (offset until minOf(offset + limit, target)).map { n ->
                                val kind = if (n == target - 1) "Album" else "Single"
                                """{"id":"${id(n + 1)}","title":"Source $n","primary-type":"$kind","first-release-date":"2026-11-01","artist-credit":[{"name":"Artist"}]}"""
                            }
                            return """{"count":$target,"offset":$offset,"release-groups":[${rows.joinToString(",")}]}"""
                        }
                    },
                ))
                val narrow = scanMusicBrainzRecentEditorial(
                    source, "2026-10-24", date, maxPages = 1,
                )
                var current = initialCanonicalHomePageSnapshot(date, narrow, null, null)
                val firstIds = narrow.rows.map { it.musicBrainzId }.toSet()
                var rawPositions = narrow.sourcePositions
                while (current.nextWindow == 0) {
                    val offset = requireNotNull(current.offsets[0])
                    val next = scanMusicBrainzRecentEditorial(
                        source, "2026-10-24", date, maxPages = 1,
                        startOffset = offset,
                    )
                    rawPositions += next.sourcePositions
                    current = advanceCanonicalHomePage(current, 0, offset, next)!!
                    assertFalse(current.failed)
                    assertTrue(offsets.size <= 4)
                }
                assertEquals(target, rawPositions)
                assertEquals((target + 99) / 100, offsets.size)
                assertEquals((0 until target step 100).toList(), offsets)
                val first = listOf(HomeShelf("Lançamentos", narrow.rows.map { sourceRow ->
                    ShelfItem(sourceRow.title, sourceRow.subtitle.orEmpty(), null, null,
                        "catalog:RELEASE_GROUP:${sourceRow.musicBrainzId}")
                }))
                val extra = canonicalHomeContinuationShelf(
                    BuiltInProviderKeys.MUSICBRAINZ, first, current,
                )
                val sourceIds = firstIds + current.rows.map { it.musicBrainzId }
                assertTrue(id(target) in sourceIds)
                if (target <= 100) assertNull(extra) else assertNotNull(extra)
                assertEquals(1, current.nextWindow)
            }
        }

    @Test fun initialWindowsRememberWhatWasReadAndOnlyRetryRealSourceOffsets() {
        val landing = scan(listOf(row(1)), next = 300)
        val wide = scan(listOf(row(2)), next = 200)
        val historic = scan(listOf(row(3)), next = null)
        val started = initialCanonicalHomePageSnapshot(date, landing, wide, historic)
        assertEquals(listOf(300, 200, null), started.offsets)
        assertEquals(0, started.nextWindow)
        val result = advanceCanonicalHomePage(
            started.copy(loading = true), 0, 300,
            scan(listOf(row(4)), next = null),
        )!!
        assertEquals(listOf(null, 200, null), result.offsets)
        assertEquals(1, result.nextWindow)
        val failed = advanceCanonicalHomePage(result, 1, 200,
            scan(next = 200, failed = true))!!
        assertEquals(200, failed.offsets[1])
        assertTrue(failed.failed)
        assertFalse(failed.loading)
        assertNull(advanceCanonicalHomePage(result, 0, 300,
            scan(next = 400)))
        assertNull(advanceCanonicalHomePage(result, 1, 100,
            scan(next = 400)))
        assertEquals("2025-11-23", canonicalHomeWindowDates(date, 2)?.first)
        assertEquals("2026-08-24", canonicalHomeWindowDates(date, 2)?.second)
    }

    @Test fun homeContinuationPreservesSourceChoiceAllPreviousShelvesAndExactIds() {
        val original = listOf(
            HomeShelf("Lançamentos", listOf(
                ShelfItem("First", "Artist", null, null,
                    "catalog:RELEASE_GROUP:${id(1)}"),
            )),
            HomeShelf("Comunidade", listOf(
                ShelfItem("A favourite", "Artist", null, null, "local:playlist:7"),
            )),
        )
        val state = CanonicalHomePageSnapshot(
            todayIsoDate = date, offsets = listOf(100, 0, 0),
            rows = listOf(row(1), row(2), row(2)),
        )
        val extra = canonicalHomeContinuationShelf(
            BuiltInProviderKeys.MUSICBRAINZ, original, state,
        )!!
        assertEquals("Mais do catálogo", extra.title)
        assertEquals(listOf("catalog:RELEASE_GROUP:${id(2)}"),
            extra.items.map { it.browseId })
        assertTrue(extra.items.all { it.videoId == null })
        assertNull(canonicalHomeContinuationShelf(
            BuiltInProviderKeys.ITUNES, original, state,
        ))
        assertNull(canonicalHomeContinuationShelf(
            BuiltInProviderKeys.DISCOGS, original, state,
        ))
        val selection = CatalogEditorialSelection(
            BuiltInProviderKeys.MUSICBRAINZ,
            com.music.bitchord.data.model.UiState.Success(original.take(1)),
        )
        val combined = projectCanonicalHomeDisplay(
            canonicalHomeShelves = emptyList(), selection = selection,
            publicPlaylistShelf = null, communityShelf = original[1],
            musicBrainzPaging = state,
        )
        assertEquals(listOf(
            "Lançamentos", "Comunidade", "Explore gêneros", "Mais do catálogo",
        ), combined.map { it.title })
    }
}
