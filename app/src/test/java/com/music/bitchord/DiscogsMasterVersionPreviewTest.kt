package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Versions are source-native edition navigation, not the exhaustive list
 * required for cross-provider identity certification.
 */
class DiscogsMasterVersionPreviewTest {
    private val paged = """
        {"pagination":{"page":1,"pages":7,"per_page":8,"items":48},
          "versions":[{"id":500},{"id":501},{"id":502}]}""".trimIndent()

    @Test fun largeMasterCanExposeExplicitlyIncompleteFirstPageCandidates() {
        val page = discogsMasterVersionPreviewPage(paged)
        assertNotNull(page)
        assertEquals(listOf(500L, 501L, 502L), page!!.releaseIds)
        assertTrue(page.hasMore)
        assertNull(discogsMasterVersionPreviewPage("""
            {"pagination":{"page":2,"pages":7,"per_page":8,"items":48},
             "versions":[{"id":500}]}"""))
        assertNull(discogsMasterVersionPreviewPage("""
            {"pagination":{"page":1,"pages":1,"per_page":8,"items":2},
             "versions":[{"id":500}]}"""))
        assertNull(discogsMasterVersionPreviewPage("""
            {"pagination":{"page":1,"pages":1,"per_page":8,"items":2},
             "versions":[{"id":500},{"id":500}]}"""))
    }

    @Test fun completeSmallMasterIsNotIncorrectlyMarkedIncomplete() {
        val page = discogsMasterVersionPreviewPage("""
            {"pagination":{"page":1,"pages":1,"per_page":8,"items":2},
             "versions":[{"id":500},{"id":501}]}""")
        assertEquals(listOf(500L, 501L), page?.releaseIds)
        assertFalse(page!!.hasMore)
    }

    @Test fun firstPagePreviewUsesOnlyFixedEndpointAndDoesNotClaimEditionMatch() =
        runBlocking {
            val paths = mutableListOf<String>()
            val client = DiscogsClient(
                enabled = true,
                limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
                transport = object : DiscogsTransport {
                    override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                        paths += path
                        return DiscogsHttpResponse(200, paged)
                    }
                },
            )
            val result = client.fetchMasterVersionPreview(90)
            assertTrue(result is DiscogsMasterVersionPreviewResult.Page)
            assertEquals(listOf(500L, 501L, 502L),
                (result as DiscogsMasterVersionPreviewResult.Page).result.releaseIds)
            assertTrue(result.result.hasMore)
            assertEquals(listOf("masters/90/versions?page=1&per_page=8"), paths)
            assertEquals(DiscogsMasterVersionPreviewResult.Malformed,
                client.fetchMasterVersionPreview(-1))
        }

    @Test fun nativeVersionSampleSharesAppBoundaryAndStopsOnThrottleWithoutDiscardingVerifiedRows() = runBlocking {
        fun edition(id: Long, master: Long = 90, unofficial: Boolean = false) = DiscogsRecord(
            link = DiscogsLink(DiscogsKind.RELEASE, id), title = "Work",
            artistNames = listOf("Artist"), country = "Brazil", year = 2020,
            labels = emptyList(), barcodes = emptyList(), formats = listOf("Album"),
            tracklist = emptyList(), unofficial = unofficial, masterId = master,
            artistIds = listOf(17L),
        )
        val selected = edition(500)
        val requested = mutableListOf<Long>()
        val page = requireNotNull(discogsMasterVersionPreviewPage(paged))
        val partial = com.music.bitchord.ui.sampleDiscogsVerifiedVersions(
            selected, readIndex = { id ->
                assertEquals(90L, id)
                DiscogsMasterVersionPreviewResult.Page(page)
            }, readRelease = { id ->
                requested += id
                if (id == 501L) DiscogsLookupResult.Found(edition(id))
                else DiscogsLookupResult.Throttled(10)
            },
        )
        assertEquals(listOf(501L, 502L), requested)
        assertEquals(listOf(501L), partial.records.map { it.link.id })
        assertTrue(partial.incomplete)
        assertEquals(listOf("discogs:release:501"),
            com.music.bitchord.ui.discogsMasterVersionsShelf(selected, partial.records)
                ?.items?.map { it.browseId })
        assertNull(com.music.bitchord.ui.discogsMasterVersionsShelf(
            selected.copy(unofficial = true), partial.records))
        assertNull(com.music.bitchord.ui.discogsMasterVersionsShelf(selected,
            listOf(edition(503, master = 91), edition(504, unofficial = true))))
    }

}
