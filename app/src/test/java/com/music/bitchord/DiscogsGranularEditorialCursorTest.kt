package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test

/** Source-owned Discogs cursor tests, without publishing names or real tokens. */
class DiscogsGranularEditorialCursorTest {
    private fun access(): RateLimitedProviderTransport {
        val key = BuiltInProviderKeys.DISCOGS
        val caps = setOf(ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY)
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(key, caps, ProviderReadiness.READY)),
            choices = caps.associateWith { listOf(ProviderChoice(key, true, 0)) },
        ))
        return RateLimitedProviderTransport { gate }
    }
    private fun master(id: Long) = DiscogsMasterRecord(
        link = DiscogsLink(DiscogsKind.MASTER, id),
        title = "Master", artistNames = listOf("Artist"),
        year = 2024, mainReleaseId = id + 1000,
        genres = listOf("Rock"), styles = listOf("Indie Rock"),
    )
    private fun release(id: Long) = DiscogsRecord(
        link = DiscogsLink(DiscogsKind.RELEASE, id + 1000),
        title = "Release", artistNames = listOf("Artist"),
        country = "BR", year = 2024, labels = emptyList(),
        barcodes = emptyList(), formats = listOf("Album"),
        tracklist = listOf("1" to "Song"), unofficial = false,
        masterId = id, artistIds = listOf(99L),
    )
    private fun reader(onMaster: (Long) -> DiscogsLookupResult) =
        object : DiscogsNativeEditionReader {
            override suspend fun master(id: Long): DiscogsLookupResult = onMaster(id)
            override suspend fun release(id: Long): DiscogsLookupResult =
                DiscogsLookupResult.Found(this@DiscogsGranularEditorialCursorTest.release(id - 1000))
        }

    @Test fun loadsThirtyHundredAndThreeHundredCandidatesWithRealCursor() = runBlocking {
        for (total in listOf(30, 100, 300)) {
            val indexPages = mutableListOf<Int>()
            val checked = mutableListOf<Long>()
            val client = DiscogsNativeGenreEditorial(
                enabled = true, tokenProvider = { "A".repeat(32) },
                admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
                transport = object : DiscogsGenreSearchTransport {
                    override suspend fun search(
                        query: DiscogsEditorialQuery, token: String,
                        userAgent: String, page: Int,
                    ): DiscogsHttpResponse {
                        indexPages += page
                        val pages = (total + 11) / 12
                        val ids = ((page - 1) * 12 + 1..minOf(page * 12, total))
                            .joinToString(",") { """{"id":$it,"type":"master"}""" }
                        return DiscogsHttpResponse(200,
                            """{"pagination":{"page":$page,"pages":$pages,"per_page":12},"results":[""" +
                                ids + "]}")
                    }
                },
                editions = reader { id ->
                    checked += id
                    DiscogsLookupResult.FoundMaster(master(id))
                },
            )
            val query = DiscogsEditorialQuery("Rock", false)
            val seen = mutableListOf<DiscogsEditorialCandidate>()
            var cursor: DiscogsEditorialCursor? = DiscogsEditorialCursor()
            var batches = 0
            while (cursor != null) {
                val result = client.discoverGranular(query, cursor, maxCandidates = 4)
                assertTrue(result is DiscogsGranularRead.Page)
                val page = result as DiscogsGranularRead.Page
                assertFalse(page.stoppedByPageLimit)
                assertTrue(page.consumedCandidates in 1..4)
                seen += page.releases
                cursor = page.nextCursor
                batches++
                assertTrue(batches <= 100)
            }
            assertEquals(total, seen.size)
            assertEquals(total, seen.map { it.masterId }.distinct().size)
            assertEquals((1L..total.toLong()).toList(), checked)
            assertEquals((total + 11) / 12, indexPages.distinct().size)
            assertTrue(indexPages.maxOrNull()!! > 2)
        }
    }

    @Test fun twelfthCandidateSurvivesFourCandidateViewport() = runBlocking {
        val hydrated = mutableListOf<Long>()
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    val ids = (1..12).joinToString(",") {
                        """{"id":$it,"type":"master"}"""
                    }
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},"results":[""" +
                            ids + "]}")
                }
            },
            editions = reader { id ->
                hydrated += id
                DiscogsLookupResult.FoundMaster(
                    master(id).copy(genres = if (id == 12L)
                        listOf("Rock") else listOf("Jazz")),
                )
            },
        )
        var cursor: DiscogsEditorialCursor? = DiscogsEditorialCursor()
        val accepted = mutableListOf<DiscogsEditorialCandidate>()
        repeat(3) {
            val page = client.discoverGranular(
                DiscogsEditorialQuery("Rock", false), cursor!!, maxCandidates = 4,
            ) as DiscogsGranularRead.Page
            accepted += page.releases
            cursor = page.nextCursor
        }
        assertNull(cursor)
        assertEquals((1L..12L).toList(), hydrated)
        assertEquals(listOf(12L), accepted.map { it.masterId })
    }

    @Test fun transientFailureRetriesSameSourcePositionWithoutLosingEarlierReleases() =
        runBlocking {
            val reads = mutableListOf<Long>()
            var failSecond = true
            val client = DiscogsNativeGenreEditorial(
                enabled = true, tokenProvider = { "A".repeat(32) },
                admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
                transport = object : DiscogsGenreSearchTransport {
                    override suspend fun search(
                        query: DiscogsEditorialQuery, token: String,
                        userAgent: String, page: Int,
                    ): DiscogsHttpResponse = DiscogsHttpResponse(
                        200, """{"pagination":{"page":1,"pages":1,"per_page":12},
                            "results":[{"id":1,"type":"master"},{"id":2,"type":"master"},
                                       {"id":3,"type":"master"}]}""",
                    )
                },
                editions = reader { id ->
                    reads += id
                    if (id == 2L && failSecond) {
                        failSecond = false
                        DiscogsLookupResult.Unavailable
                    } else DiscogsLookupResult.FoundMaster(master(id))
                },
            )
            val query = DiscogsEditorialQuery("Rock", false)
            val first = client.discoverGranular(query) as DiscogsGranularRead.Interrupted
            assertEquals(listOf(1L), first.releases.map { it.masterId })
            assertEquals(1, first.retryCursor.candidatePosition)
            val second = client.discoverGranular(
                query, first.retryCursor, knownMasterIds = setOf(1L),
            ) as DiscogsGranularRead.Page
            assertEquals(listOf(2L, 3L), second.releases.map { it.masterId })
            assertNull(second.nextCursor)
            assertEquals(listOf(1L, 2L, 2L, 3L), reads)
        }

    @Test fun shiftingIndexCannotReusePartialCursor() = runBlocking {
        var changed = false
        var hydration = 0
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    val first = if (changed) 99 else 1
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":2,"per_page":12},
                           "results":[{"id":$first,"type":"master"},
                                      {"id":2,"type":"master"}]}""")
                }
            },
            editions = reader { id ->
                hydration++
                DiscogsLookupResult.FoundMaster(master(id))
            },
        )
        val query = DiscogsEditorialQuery("Rock", false)
        val first = client.discoverGranular(query, maxCandidates = 1)
            as DiscogsGranularRead.Page
        assertEquals(1, hydration)
        changed = true
        val next = client.discoverGranular(query, first.nextCursor!!, maxCandidates = 1)
            as DiscogsGranularRead.Interrupted
        assertEquals(1, hydration)
        assertEquals(first.nextCursor, next.retryCursor)
    }

    @Test fun streamsOnlyFullyVerifiedReleasesBeforeChunkCompletes() = runBlocking {
        val events = mutableListOf<String>()
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse = DiscogsHttpResponse(
                    200, """{"pagination":{"page":1,"pages":1,"per_page":12},
                        "results":[{"id":1,"type":"master"},
                                   {"id":2,"type":"master"},
                                   {"id":3,"type":"master"}]}""",
                )
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    events += "master:$id"
                    // An unverified source Master must never be streamed.
                    return if (id == 2L) DiscogsLookupResult.Missing
                        else DiscogsLookupResult.FoundMaster(
                            this@DiscogsGranularEditorialCursorTest.master(id),
                        )
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    events += "release:$id"
                    return DiscogsLookupResult.Found(
                        this@DiscogsGranularEditorialCursorTest.release(id - 1000L),
                    )
                }
            },
        )
        val emitted = mutableListOf<Long>()
        val result = client.discoverGranular(
            query = DiscogsEditorialQuery("Rock", false),
            maxCandidates = 3,
            onVerified = { candidate ->
                events += "emit:${candidate.masterId}"
                emitted += candidate.masterId
            },
        ) as DiscogsGranularRead.Page
        assertEquals(listOf(1L, 3L), emitted)
        assertEquals(result.releases.map { it.masterId }, emitted)
        assertEquals(3, result.consumedCandidates)
        assertEquals(listOf(
            "master:1", "release:1001", "emit:1",
            "master:2", "master:3", "release:1003", "emit:3",
        ), events)
    }

    @Test fun freshHomeContinuationReusesRecentSourceIndexButStillVerifiesNewRelease() =
        runBlocking {
            var indexReads = 0
            val masterReads = mutableListOf<Long>()
            val releaseReads = mutableListOf<Long>()
            val client = DiscogsNativeGenreEditorial(
                enabled = true, tokenProvider = { "A".repeat(32) },
                admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
                transport = object : DiscogsGenreSearchTransport {
                    override suspend fun search(
                        query: DiscogsEditorialQuery, token: String,
                        userAgent: String, page: Int,
                    ): DiscogsHttpResponse {
                        indexReads++
                        return DiscogsHttpResponse(200,
                            """{"pagination":{"page":1,"pages":1,"per_page":12},
                                "results":[{"id":1,"type":"master"},{"id":2,"type":"master"}]}""")
                    }
                },
                editions = object : DiscogsNativeEditionReader {
                    override suspend fun master(id: Long): DiscogsLookupResult {
                        masterReads += id
                        return DiscogsLookupResult.FoundMaster(
                            this@DiscogsGranularEditorialCursorTest.master(id),
                        )
                    }
                    override suspend fun release(id: Long): DiscogsLookupResult {
                        releaseReads += id
                        return DiscogsLookupResult.Found(
                            this@DiscogsGranularEditorialCursorTest.release(id - 1000),
                        )
                    }
                },
            )
            val query = DiscogsEditorialQuery("Rock", false)
            val initial = client.discover(query, maxReleases = 1) as
                DiscogsEditorialResult.Verified
            assertEquals(listOf(1L), initial.releases.map { it.masterId })
            val more = client.discoverGranular(
                query, knownMasterIds = setOf(1L), maxCandidates = 2,
            ) as DiscogsGranularRead.Page
            assertEquals(1, indexReads)
            assertEquals(listOf(2L), more.releases.map { it.masterId })
            assertEquals(listOf(1L, 2L), masterReads)
            assertEquals(listOf(1001L, 1002L), releaseReads)
            assertNull(more.nextCursor)
        }

    @Test fun overlappingFreshIndexReadsShareOneFetchButSignedCursorRereads() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var indexReads = 0
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    indexReads++
                    if (indexReads == 1) {
                        entered.complete(Unit)
                        resume.await()
                    }
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},
                            "results":[{"id":1,"type":"master"},{"id":2,"type":"master"}]}""")
                }
            },
            editions = reader { id -> DiscogsLookupResult.FoundMaster(master(id)) },
        )
        val query = DiscogsEditorialQuery("Rock", false)
        val landing = async(start = CoroutineStart.UNDISPATCHED) {
            client.discover(query, maxReleases = 1)
        }
        entered.await()
        val scroll = async(start = CoroutineStart.UNDISPATCHED) {
            client.discoverGranular(query, maxCandidates = 1)
        }
        resume.complete(Unit)
        assertTrue(landing.await() is DiscogsEditorialResult.Verified)
        val page = scroll.await() as DiscogsGranularRead.Page
        assertEquals(1, indexReads)
        val next = client.discoverGranular(
            query, requireNotNull(page.nextCursor), setOf(1L),
        ) as DiscogsGranularRead.Page
        assertEquals(listOf(2L), next.releases.map { it.masterId })
        assertEquals(2, indexReads)
    }

    @Test fun expiredIndexIsRequeriedInsteadOfReplayingOldCandidates() = runBlocking {
        var nowMs = 0L
        var indexReads = 0
        var changed = false
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            clockMs = { nowMs },
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    indexReads++
                    val candidate = if (changed) 2L else 1L
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},
                            "results":[{"id":$candidate,"type":"master"}]}""")
                }
            },
            editions = reader { DiscogsLookupResult.FoundMaster(master(it)) },
        )
        val query = DiscogsEditorialQuery("Rock", false)
        assertEquals(1, (client.discover(query, maxReleases = 1) as
            DiscogsEditorialResult.Verified).releases.size)
        nowMs = 120_001L
        changed = true
        val more = client.discoverGranular(query) as DiscogsGranularRead.Page
        assertEquals(2, indexReads)
        assertEquals(listOf(2L), more.releases.map { it.masterId })
    }

    @Test fun revokedDiscogsTokenBlocksCachedIndexBeforeAnyReuse() = runBlocking {
        var token: String? = "A".repeat(32)
        var indexReads = 0
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { token },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    indexReads++
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},
                            "results":[{"id":1,"type":"master"}]}""")
                }
            },
            editions = reader { DiscogsLookupResult.FoundMaster(master(it)) },
        )
        val query = DiscogsEditorialQuery("Rock", false)
        assertTrue(client.discover(query, maxReleases = 1)
            is DiscogsEditorialResult.Verified)
        // A different syntactically valid token MUST contact the provider
        // again rather than receiving the old credential's cached index.
        token = "B".repeat(32)
        val changedCredential = client.discoverGranular(query)
        assertTrue(changedCredential is DiscogsGranularRead.Page)
        assertEquals(2, indexReads)
        token = null
        val denied = client.discoverGranular(query) as DiscogsGranularRead.Interrupted
        assertEquals(DiscogsEditorialResult.Unauthorized, denied.failure)
        assertEquals(2, indexReads)
    }

    @Test fun overlappingGenreAndStyleReuseOnlyVerifiedMasterReleasePair() = runBlocking {
        var indexReads = 0
        val masterReads = mutableListOf<Long>()
        val releaseReads = mutableListOf<Long>()
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    indexReads++
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},
                            "results":[{"id":1,"type":"master"}]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    masterReads += id
                    return DiscogsLookupResult.FoundMaster(
                        this@DiscogsGranularEditorialCursorTest.master(id),
                    )
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    releaseReads += id
                    return DiscogsLookupResult.Found(
                        this@DiscogsGranularEditorialCursorTest.release(id - 1000L),
                    )
                }
            },
        )
        val initial = client.discover(DiscogsEditorialQuery("Rock", false), 1)
            as DiscogsEditorialResult.Verified
        assertEquals(listOf(1L), initial.releases.map { it.masterId })
        // The other source predicate is independently fetched, but its exact
        // already-verified Master/Release pair never spends two extra HTTP reads.
        val same = client.discoverGranular(
            DiscogsEditorialQuery("Indie Rock", true, requiredGenre = "Rock"),
            maxCandidates = 1,
        ) as DiscogsGranularRead.Page
        assertEquals(listOf(1L), same.releases.map { it.masterId })
        assertEquals(2, indexReads)
        assertEquals(listOf(1L), masterReads)
        assertEquals(listOf(1001L), releaseReads)
        // Cached evidence is NOT permission to fabricate a different Style.
        val other = client.discoverGranular(
            DiscogsEditorialQuery("Ambient", true), maxCandidates = 1,
        ) as DiscogsGranularRead.Page
        assertTrue(other.releases.isEmpty())
        assertEquals(3, indexReads)
        // A verified Master is still reusable; the mismatched Style must
        // never trigger Release hydration or publish a false candidate.
        assertEquals(listOf(1L), masterReads)
        assertEquals(listOf(1001L), releaseReads)
    }

    @Test fun landingCheckpointSkipsSettledPrefixButRetriesUnresolvedPosition() = runBlocking {
        for (transient in listOf(false, true)) {
            val reads = mutableListOf<Long>()
            var indexReads = 0
            var failed = false
            val client = DiscogsNativeGenreEditorial(
                enabled = true, tokenProvider = { "A".repeat(32) },
                admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
                transport = object : DiscogsGenreSearchTransport {
                    override suspend fun search(
                        query: DiscogsEditorialQuery, token: String,
                        userAgent: String, page: Int,
                    ): DiscogsHttpResponse {
                        indexReads++
                        val ids = (1..8).joinToString(",") {
                            """{"id":$it,"type":"master"}"""
                        }
                        return DiscogsHttpResponse(200,
                            """{"pagination":{"page":1,"pages":1,"per_page":12},"results":[$ids]}""")
                    }
                },
                editions = reader { id ->
                    reads += id
                    if (transient && id == 2L && !failed) {
                        failed = true
                        DiscogsLookupResult.Unavailable
                    } else DiscogsLookupResult.FoundMaster(
                        master(id).copy(genres = if (id == 1L) listOf("Jazz") else listOf("Rock")),
                    )
                },
            )
            val query = DiscogsEditorialQuery("Rock", false)
            var cursor: DiscogsEditorialCursor? = null
            val initial = client.discoverDistinct(
                query, 2, emptySet(), onCheckpoint = { cursor = it },
            ) as DiscogsEditorialResult.Verified
            val saved = requireNotNull(cursor)
            assertTrue(saved.landingCheckpoint)
            assertEquals(if (transient) 1 else 3, saved.candidatePosition)
            val next = client.discoverGranular(
                query, saved, initial.releases.map { it.masterId }.toSet(),
                maxCandidates = 4,
            ) as DiscogsGranularRead.Page
            assertEquals(2, indexReads)
            assertEquals(1, reads.count { it == 1L })
            if (transient) {
                assertEquals(2, reads.count { it == 2L })
                assertEquals(listOf(2L, 5L), next.releases.map { it.masterId })
            } else {
                assertEquals(listOf(4L, 5L, 6L, 7L), next.releases.map { it.masterId })
            }
        }
    }

    @Test fun shiftedLandingCheckpointRestartsWithoutAdmittingUnverifiedRows() = runBlocking {
        var shifted = false
        val reads = mutableListOf<Long>()
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    val ids = (if (shifted) listOf(3, 1, 2) else listOf(1, 2))
                        .joinToString(",") { """{"id":$it,"type":"master"}""" }
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},"results":[$ids]}""")
                }
            },
            editions = reader { id ->
                reads += id
                DiscogsLookupResult.FoundMaster(master(id))
            },
        )
        val query = DiscogsEditorialQuery("Rock", false)
        var cursor: DiscogsEditorialCursor? = null
        client.discoverDistinct(query, 1, emptySet(), onCheckpoint = { cursor = it })
        shifted = true
        val restart = client.discoverGranular(query, requireNotNull(cursor), setOf(1L))
            as DiscogsGranularRead.Page
        assertEquals(0, restart.consumedCandidates)
        assertEquals(DiscogsEditorialCursor(), restart.nextCursor)
        assertTrue(restart.releases.isEmpty())
        assertEquals(listOf(1L), reads)
        val next = client.discoverGranular(query, requireNotNull(restart.nextCursor), setOf(1L))
            as DiscogsGranularRead.Page
        assertEquals(listOf(3L, 2L), next.releases.map { it.masterId })
        assertEquals(listOf(1L, 3L, 2L), reads)
    }

    @Test fun overlappingLandingPredicatesFillSlotsWithNewVerifiedMasters() = runBlocking {
        val masterReads = mutableListOf<Long>()
        val releaseReads = mutableListOf<Long>()
        val indexReads = mutableListOf<Pair<String, Int>>()
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    indexReads += query.sourceName to page
                    val ids = if (!query.isStyle) listOf(1L, 2L)
                        else if (page == 1) (1L..12L).toList()
                        else listOf(13L, 14L, 15L)
                    val pages = if (query.isStyle) 2 else 1
                    val rows = ids.joinToString(",") { """{"id":$it,"type":"master"}""" }
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":$page,"pages":$pages,"per_page":12},"results":[$rows]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    masterReads += id
                    return DiscogsLookupResult.FoundMaster(
                        this@DiscogsGranularEditorialCursorTest.master(id).copy(
                            styles = if (id in 3L..12L) listOf("Other") else listOf("Indie Rock"),
                        ),
                    )
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    releaseReads += id
                    return DiscogsLookupResult.Found(
                        this@DiscogsGranularEditorialCursorTest.release(id - 1000),
                    )
                }
            },
        )
        val initial = client.discover(DiscogsEditorialQuery("Rock", false), 2)
            as DiscogsEditorialResult.Verified
        val emitted = mutableListOf<Long>()
        val additional = client.discoverDistinct(
            DiscogsEditorialQuery("Indie Rock", true, "Rock"), 2,
            initial.releases.map { it.masterId }.toSet(),
            onVerified = { emitted += it.masterId },
        ) as DiscogsEditorialResult.Verified
        assertEquals(listOf(1L, 2L), initial.releases.map { it.masterId })
        assertEquals(listOf(13L, 14L), additional.releases.map { it.masterId })
        assertEquals(listOf(13L, 14L), emitted)
        assertEquals((1L..14L).toList(), masterReads)
        assertEquals(listOf(1001L, 1002L, 1013L, 1014L), releaseReads)
        assertEquals(listOf("Rock" to 1, "Indie Rock" to 1, "Indie Rock" to 2), indexReads)
    }

    @Test fun verifiedPairExpiresBeforeItCanBeReusedAcrossPredicates() = runBlocking {
        var nowMs = 0L
        var masterReads = 0
        var releaseReads = 0
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            clockMs = { nowMs },
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse = DiscogsHttpResponse(200,
                    """{"pagination":{"page":1,"pages":1,"per_page":12},
                        "results":[{"id":1,"type":"master"}]}""")
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    masterReads++
                    return DiscogsLookupResult.FoundMaster(
                        this@DiscogsGranularEditorialCursorTest.master(id),
                    )
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    releaseReads++
                    return DiscogsLookupResult.Found(
                        this@DiscogsGranularEditorialCursorTest.release(id - 1000L),
                    )
                }
            },
        )
        assertTrue(client.discover(DiscogsEditorialQuery("Rock", false), 1)
            is DiscogsEditorialResult.Verified)
        nowMs = 120_001L
        val fresh = client.discoverGranular(
            DiscogsEditorialQuery("Indie Rock", true, "Rock"),
        ) as DiscogsGranularRead.Page
        assertEquals(1, fresh.releases.size)
        assertEquals(2, masterReads)
        assertEquals(2, releaseReads)
    }

    @Test fun verifiedMasterWithWrongFirstGenreCanServeAnotherRealStyle() = runBlocking {
        val masterReads = mutableListOf<Long>()
        val releaseReads = mutableListOf<Long>()
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse = DiscogsHttpResponse(200,
                    """{"pagination":{"page":1,"pages":1,"per_page":12},
                        "results":[{"id":1,"type":"master"}]}""")
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    masterReads += id
                    return DiscogsLookupResult.FoundMaster(
                        this@DiscogsGranularEditorialCursorTest.master(id),
                    )
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    releaseReads += id
                    return DiscogsLookupResult.Found(
                        this@DiscogsGranularEditorialCursorTest.release(id - 1000L),
                    )
                }
            },
        )
        val rejected = client.discoverGranular(
            DiscogsEditorialQuery("Ambient", isStyle = true),
            maxCandidates = 1,
        ) as DiscogsGranularRead.Page
        assertTrue(rejected.releases.isEmpty())
        assertEquals(listOf(1L), masterReads)
        assertTrue(releaseReads.isEmpty())
        val verified = client.discoverGranular(
            DiscogsEditorialQuery("Indie Rock", isStyle = true, requiredGenre = "Rock"),
            maxCandidates = 1,
        ) as DiscogsGranularRead.Page
        assertEquals(listOf(1L), verified.releases.map { it.masterId })
        assertEquals(listOf(1L), masterReads)
        assertEquals(listOf(1001L), releaseReads)
    }

    @Test fun tokenRotationInvalidatesMasterEvidenceEvenWhenIndexIsFresh() = runBlocking {
        var token = "A".repeat(32)
        var masterReads = 0
        var releaseReads = 0
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { token },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse = DiscogsHttpResponse(200,
                    """{"pagination":{"page":1,"pages":1,"per_page":12},
                        "results":[{"id":1,"type":"master"}]}""")
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    masterReads++
                    return DiscogsLookupResult.FoundMaster(
                        this@DiscogsGranularEditorialCursorTest.master(id),
                    )
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    releaseReads++
                    return DiscogsLookupResult.Found(
                        this@DiscogsGranularEditorialCursorTest.release(id - 1000L),
                    )
                }
            },
        )
        val first = client.discoverGranular(DiscogsEditorialQuery("Ambient", true),
            maxCandidates = 1) as DiscogsGranularRead.Page
        assertTrue(first.releases.isEmpty())
        token = "B".repeat(32)
        val next = client.discoverGranular(DiscogsEditorialQuery("Indie Rock", true),
            maxCandidates = 1) as DiscogsGranularRead.Page
        assertEquals(listOf(1L), next.releases.map { it.masterId })
        assertEquals(2, masterReads)
        assertEquals(1, releaseReads)
    }

    @Test fun initialSamplerReusesIndexAndPairWithoutNewReads() = runBlocking {
        var token = "A".repeat(32)
        var now = 0L
        var indexes = 0
        var masters = 0
        var releases = 0
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { token },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            clockMs = { now },
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    indexes++
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},
                            "results":[{"id":1,"type":"master"}]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    masters++
                    return DiscogsLookupResult.FoundMaster(
                        this@DiscogsGranularEditorialCursorTest.master(id))
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    releases++
                    return DiscogsLookupResult.Found(
                        this@DiscogsGranularEditorialCursorTest.release(id - 1000L))
                }
            },
        )
        val query = DiscogsEditorialQuery("Rock", false)
        repeat(2) {
            val rows = client.discover(query, maxReleases = 1)
                as DiscogsEditorialResult.Verified
            assertEquals(listOf(1L), rows.releases.map { it.masterId })
        }
        assertEquals(1, indexes)
        assertEquals(1, masters)
        assertEquals(1, releases)
        // Credential rotation cannot replay source evidence from a prior
        // personal token, even if the snapshot is still inside its TTL.
        token = "B".repeat(32)
        assertTrue(client.discover(query, maxReleases = 1)
            is DiscogsEditorialResult.Verified)
        assertEquals(2, indexes)
        assertEquals(2, masters)
        assertEquals(2, releases)
        now = 120_001L
        assertTrue(client.discover(query, maxReleases = 1)
            is DiscogsEditorialResult.Verified)
        assertEquals(3, indexes)
        assertEquals(3, masters)
        assertEquals(3, releases)
    }

    @Test fun newInitialSamplerReusesIndexFoundByGranularButNotPartialCursor() = runBlocking {
        var calls = 0
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    calls++
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},
                            "results":[{"id":1,"type":"master"},
                                       {"id":2,"type":"master"}]}""")
                }
            },
            editions = reader { id -> DiscogsLookupResult.FoundMaster(master(id)) },
        )
        val q = DiscogsEditorialQuery("Rock", false)
        val first = client.discoverGranular(q, maxCandidates = 1)
            as DiscogsGranularRead.Page
        assertEquals(1, calls)
        assertEquals(1, first.consumedCandidates)
        val initial = client.discover(q, maxReleases = 2)
            as DiscogsEditorialResult.Verified
        assertEquals(2, initial.releases.size)
        assertEquals(1, calls)
        // In-progress cursor MUST still read the real page to detect drift.
        val next = client.discoverGranular(q, first.nextCursor!!, maxCandidates = 1)
            as DiscogsGranularRead.Page
        assertEquals(1, next.consumedCandidates)
        assertEquals(2, calls)
    }

    @Test fun initialSamplerStreamsVerifiedReleasesBeforeItsWholeIndexFinishes() = runBlocking {
        val events = mutableListOf<String>()
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse = DiscogsHttpResponse(
                    200, """{"pagination":{"page":1,"pages":1,"per_page":12},
                        "results":[{"id":1,"type":"master"},
                                   {"id":2,"type":"master"},
                                   {"id":3,"type":"master"}]}""",
                )
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    events += "master:$id"
                    val native = this@DiscogsGranularEditorialCursorTest.master(id)
                    return DiscogsLookupResult.FoundMaster(
                        if (id == 2L) native.copy(genres = listOf("Jazz"))
                        else native,
                    )
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    events += "release:$id"
                    return DiscogsLookupResult.Found(
                        this@DiscogsGranularEditorialCursorTest.release(id - 1000L),
                    )
                }
            },
        )
        val emitted = mutableListOf<Long>()
        val result = client.discover(
            query = DiscogsEditorialQuery("Rock", false),
            maxReleases = 2,
            onVerified = { candidate ->
                events += "emit:${candidate.masterId}"
                emitted += candidate.masterId
            },
        ) as DiscogsEditorialResult.Verified
        assertEquals(listOf(1L, 3L), emitted)
        assertEquals(result.releases.map { it.masterId }, emitted)
        assertEquals(listOf(
            "master:1", "release:1001", "emit:1",
            "master:2", "master:3", "release:1003", "emit:3",
        ), events)
    }

    @Test fun genreGestureReusesSingleIndexWhileScanningEightPositions() = runBlocking {
        val indexPages = mutableListOf<Int>()
        val masterReads = mutableListOf<Long>()
        val releaseReads = mutableListOf<Long>()
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    indexPages += page
                    val ids = (1..12).joinToString(",") {
                        """{"id":$it,"type":"master"}"""
                    }
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},"results":[""" +
                            ids + "]}")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    masterReads += id
                    return DiscogsLookupResult.FoundMaster(
                        this@DiscogsGranularEditorialCursorTest.master(id).copy(
                            genres = if (id == 5L || id == 9L)
                                listOf("Rock") else listOf("Jazz"),
                        ),
                    )
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    releaseReads += id
                    return DiscogsLookupResult.Found(
                        this@DiscogsGranularEditorialCursorTest.release(id - 1000L),
                    )
                }
            },
        )
        val query = DiscogsEditorialQuery("Rock", false)
        val first = client.discoverGranular(
            query, maxCandidates = 12, stopAfterVerifiedChunk = 4,
        ) as DiscogsGranularRead.Page
        // The first accepted Master is at position 5: finish its four-item
        // chunk, stop at position 8; never read positions 9-12 early.
        assertEquals(8, first.consumedCandidates)
        assertEquals(listOf(5L), first.releases.map { it.masterId })
        assertEquals(listOf(1), indexPages)
        assertEquals((1L..8L).toList(), masterReads)
        assertEquals(listOf(1005L), releaseReads)
        assertEquals(8, first.nextCursor!!.candidatePosition)

        val second = client.discoverGranular(
            query, first.nextCursor!!,
            knownMasterIds = setOf(5L),
            maxCandidates = 12, stopAfterVerifiedChunk = 4,
        ) as DiscogsGranularRead.Page
        assertEquals(4, second.consumedCandidates)
        assertEquals(listOf(9L), second.releases.map { it.masterId })
        assertNull(second.nextCursor)
        // Across gestures, a partially consumed index is refetched so that
        // its signature can detect source changes. No stale cursor bypass.
        assertEquals(listOf(1, 1), indexPages)
        assertEquals((1L..12L).toList(), masterReads)
        assertEquals(listOf(1005L, 1009L), releaseReads)
    }

    @Test fun productiveGestureFillsEightNewReleasesWithoutLosingLaterPositions() = runBlocking {
        var indexReads = 0
        val masterReads = mutableListOf<Long>()
        val emitted = mutableListOf<Long>()
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    indexReads++
                    val rows = (1..12).joinToString(",") { """{"id":$it,"type":"master"}""" }
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},"results":[$rows]}""")
                }
            },
            editions = reader { id ->
                masterReads += id
                DiscogsLookupResult.FoundMaster(master(id))
            },
        )
        val query = DiscogsEditorialQuery("Rock", false)
        val first = client.discoverGranular(
            query, maxCandidates = 12,
            knownMasterIds = setOf(1L, 2L),
            maxVerifiedReleases = DISCOGS_SCROLL_VERIFIED_TARGET,
            onVerified = { emitted += it.masterId },
        ) as DiscogsGranularRead.Page
        assertEquals((3L..10L).toList(), emitted)
        assertEquals(emitted, first.releases.map { it.masterId })
        assertEquals(10, first.consumedCandidates)
        assertEquals(8, first.hydratedCandidates)
        assertEquals(1, indexReads)
        val next = client.discoverGranular(
            query, requireNotNull(first.nextCursor),
            knownMasterIds = setOf(1L, 2L) + emitted,
            maxCandidates = 12,
        ) as DiscogsGranularRead.Page
        assertEquals(listOf(11L, 12L), next.releases.map { it.masterId })
        assertEquals((3L..12L).toList(), masterReads)
        assertEquals(2, indexReads)
        assertNull(next.nextCursor)
    }

    @Test fun emptyGenreGestureExaminesAtMostTwelveWithOneSourceIndexRead() = runBlocking {
        var indexReads = 0
        var masterReads = 0
        val client = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    indexReads++
                    val ids = (1..12).joinToString(",") {
                        """{"id":$it,"type":"master"}"""
                    }
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},"results":[""" +
                            ids + "]}")
                }
            },
            editions = reader { id ->
                masterReads++
                DiscogsLookupResult.FoundMaster(master(id))
            },
        )
        val sample = client.discoverGranular(
            DiscogsEditorialQuery("Rock", false),
            knownMasterIds = (1L..12L).toSet(), maxCandidates = 12,
            stopAfterVerifiedChunk = 4,
        ) as DiscogsGranularRead.Page
        assertEquals(12, sample.consumedCandidates)
        assertTrue(sample.releases.isEmpty())
        assertNull(sample.nextCursor)
        assertEquals(1, indexReads)
        assertEquals(0, masterReads)
    }

}
