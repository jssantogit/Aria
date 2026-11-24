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

    @Test fun yearAndMonthPrecisionAreOnlyAllowedInUnrankedHomeContinuation() = runBlocking {
        val source = MusicBrainzCatalogSearch(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>,
                    userAgent: String,
                ): String = """{"count":4,"offset":0,"release-groups":[
                    {"id":"${id(1)}","title":"Year only","primary-type":"Album",
                     "first-release-date":"2026","artist-credit":[{"name":"Artist"}]},
                    {"id":"${id(2)}","title":"Month only","primary-type":"Single",
                     "first-release-date":"2026-11","artist-credit":[{"name":"Artist"}]},
                    {"id":"${id(3)}","title":"Day precise","primary-type":"Album",
                     "first-release-date":"2026-11-01","artist-credit":[{"name":"Artist"}]},
                    {"id":"${id(4)}","title":"Future","primary-type":"Album",
                     "first-release-date":"2027","artist-credit":[{"name":"Artist"}]}
                ]}"""
            },
        ))
        val ordinary = scanMusicBrainzRecentEditorial(
            source, "2026-10-24", date, maxPages = 1,
        )
        assertEquals(listOf(id(3)), ordinary.rows.map { it.musicBrainzId })
        val extra = scanMusicBrainzRecentEditorial(
            source, "2026-10-24", date, maxPages = 1,
            includeCoarseDatesForUnrankedHome = true,
        )
        assertEquals(setOf(id(1), id(2), id(3)),
            extra.rows.map { it.musicBrainzId }.toSet())
        assertFalse(extra.rows.any { it.musicBrainzId == id(4) })
        val year = extra.rows.single { it.musicBrainzId == id(1) }
        val month = extra.rows.single { it.musicBrainzId == id(2) }
        assertEquals("2026", year.releaseDate)
        assertEquals("2026-11", month.releaseDate)
        assertFalse(canonicalHomeReleaseIsRecent(year.releaseDate, date))
        assertFalse(canonicalHomeReleaseIsRecent(month.releaseDate, date))
        val initial = listOf(HomeShelf("Lançamentos", listOf(
            ShelfItem("Day precise", "Artist", null, null,
                "catalog:RELEASE_GROUP:${id(3)}"),
        )))
        val rail = canonicalHomeContinuationShelf(
            BuiltInProviderKeys.MUSICBRAINZ, initial,
            CanonicalHomePageSnapshot(
                todayIsoDate = date,
                rows = extra.rows,
            ),
        )!!
        assertEquals(setOf("catalog:RELEASE_GROUP:${id(1)}",
                           "catalog:RELEASE_GROUP:${id(2)}"),
            rail.items.map { it.browseId }.toSet())
        assertEquals("Mais do catálogo", rail.title)
    }

    @Test fun landingGenreAuditUsesOnlyPositiveSourceTagsAndDistinctDisplayedGroups() = runBlocking {
        val source = MusicBrainzCatalogSearch(MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String = """{"count":3,"offset":0,"release-groups":[
                    {"id":"${id(1)}","title":"First","primary-type":"Album",
                     "first-release-date":"2026-11-01","artist-credit":[{"name":"Artist"}],
                     "tags":[{"name":"Rock","count":2},{"name":"pop","count":1},
                             {"name":"jazz","count":0},{"name":"folk","count":-1}]},
                    {"id":"${id(2)}","title":"Second","primary-type":"Album",
                     "first-release-date":"2026-11-02","artist-credit":[{"name":"Artist"}],
                     "tags":[{"name":"rock","count":1}]},
                    {"id":"${id(3)}","title":"Hidden","primary-type":"Album",
                     "first-release-date":"2026-11-03","artist-credit":[{"name":"Artist"}],
                     "tags":[{"name":"electronic","count":3}]}
                ]}"""
            },
        ))
        val evidence = mutableMapOf<String, Set<String>>()
        scanMusicBrainzRecentEditorial(
            source, "2026-10-24", date, maxPages = 1,
            onSourcePage = { rows -> rows.forEach {
                evidence[it.musicBrainzId] = it.editorialGenreTags.toSet()
            } },
        )
        assertEquals(setOf("rock", "pop"), evidence[id(1)])
        assertEquals(1, musicBrainzHomeVerifiedGenreCount(
            evidence, setOf("catalog:RELEASE_GROUP:${id(1)}"),
        ))
        assertEquals(2, musicBrainzHomeVerifiedGenreCount(
            evidence, setOf("catalog:RELEASE_GROUP:${id(1)}", "catalog:RELEASE_GROUP:${id(2)}"),
        ))
        assertEquals(3, musicBrainzHomeVerifiedGenreCount(
            evidence, (1..3).map { "catalog:RELEASE_GROUP:${id(it)}" }.toSet(),
        ))
    }

    @Test fun alreadyFetchedMusicBrainzHomeOverflowNeverNeedsAnExtraApiRead() {
        val admitted = (1..100).map(::row)
        val nativeLanding = listOf(HomeShelf("Lançamentos",
            admitted.take(40).map {
                ShelfItem(it.title, it.subtitle.orEmpty(), null, null,
                    "catalog:RELEASE_GROUP:${it.musicBrainzId}")
            },
        ))
        val cursor = CanonicalHomePageSnapshot(
            todayIsoDate = date, offsets = listOf(300, null, null),
            rows = admitted,
        )
        val overflow = canonicalHomeContinuationShelf(
            BuiltInProviderKeys.MUSICBRAINZ, nativeLanding, cursor,
        )!!
        assertEquals(60, overflow.items.size)
        assertEquals(
            (41..100).map { "catalog:RELEASE_GROUP:${id(it)}" },
            overflow.items.map { it.browseId },
        )
        assertTrue(overflow.items.all { it.videoId == null })
        assertEquals(300, cursor.offsets.first())
        assertNull(canonicalHomeContinuationShelf(
            BuiltInProviderKeys.DISCOGS, nativeLanding, cursor,
        ))
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
