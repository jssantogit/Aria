package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.providers.*
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.ui.DiscogsNativeRoute
import com.music.bitchord.ui.discogsArtistPage
import com.music.bitchord.ui.discogsEditorialShelves
import com.music.bitchord.ui.discogsNativeDetailRouteKey
import com.music.bitchord.ui.screens.DiscogsNativeArtistSearchRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Bounded Discogs-only editorial reads; no MusicBrainz record is needed. */
class DiscogsGenreEditorialNetworkTest {
    private fun access() : RateLimitedProviderTransport {
        val provider = BuiltInProviderKeys.DISCOGS
        val caps = setOf(
            ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
        )
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(provider, caps, ProviderReadiness.READY)),
            choices = caps.associateWith { listOf(ProviderChoice(provider, true, 0)) },
        ))
        return RateLimitedProviderTransport { gate }
    }

    private fun master(id: Long, style: String) = DiscogsMasterRecord(
        link = DiscogsLink(DiscogsKind.MASTER, id),
        title = "Source Master",
        artistNames = listOf("Artist"),
        year = 2024,
        mainReleaseId = 502L,
        genres = listOf("Pop"),
        styles = listOf(style),
    )

    private fun verifiedEdition(masterId: Long) = DiscogsRecord(
        link = DiscogsLink(DiscogsKind.RELEASE, 502L),
        title = "Source Album",
        artistNames = listOf("Verified Artist"),
        country = "Brazil",
        year = 2024,
        labels = emptyList(),
        barcodes = emptyList(),
        formats = listOf("CD", "Album"),
        tracklist = listOf("1" to "Track"),
        unofficial = false,
        masterId = masterId,
        artistIds = listOf(99L),
    )

    @Test fun sourceIndexHintsAreBoundedObservationsNotVerifiedCatalogEvidence() {
        val result = DiscogsEditorialMasterParsing.page(
            """{"pagination":{"page":1,"pages":1,"per_page":12},
                "results":[
                    {"id":9,"type":"master","main_release":501,
                     "genre":["Rock"],"style":["Alternative Rock"]},
                    {"id":10,"type":"master","main_release":"invalid",
                     "genre":["Pop"],"style":[]},
                    {"id":9,"type":"master","main_release":999,
                     "genre":["Jazz"],"style":["Ambient"]},
                    {"id":11,"type":"release","main_release":600}
                ]}""", 1,
        )!!
        assertEquals(listOf(9L, 10L), result.masterIds)
        assertFalse(result.hasNext)
        assertEquals(501L, result.hints[9L]?.mainReleaseId)
        assertEquals(listOf("Rock"), result.hints[9L]?.genres)
        assertEquals(listOf("Alternative Rock"), result.hints[9L]?.styles)
        assertNull(result.hints[10L]?.mainReleaseId)
        assertEquals(emptyList<String>(), result.hints[10L]?.styles)
        assertNull(result.hints[11L])
        // No hint is ever sufficient to fabricate a verified Release or
        // change cursor/identity: only this source index's typed IDs survive.
        val missing = DiscogsEditorialMasterParsing.page(
            """{"pagination":{"page":1,"pages":1,"per_page":12},
                "results":[{"id":12,"type":"master"}]}""", 1,
        )!!
        assertEquals(DiscogsEditorialMasterIndexHint(null, null, null),
            missing.hints[12L])
        assertNull(DiscogsEditorialMasterParsing.page(
            """{"pagination":{"page":1,"pages":1,"per_page":12},
                "results":[{"id":13,"type":"master","genre":["Valid",
                    "bad\u0001control"]}]}""", 1,
        )?.hints?.get(13L)?.genres)
    }

    @Test fun partialFirstIndexPageFillsBudgetOnNextPageWithoutDuplicateHydration() = runBlocking {
        val calls = mutableListOf<String>()
        val source = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    calls += "index:$page"
                    val ids = if (page == 1) listOf(77L, 88L) else listOf(77L, 99L)
                    val rows = ids.joinToString(",") { id ->
                        """{"id":$id,"type":"master"}"""
                    }
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":$page,"pages":2,"per_page":12},"results":[$rows]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    calls += "master:$id"
                    return DiscogsLookupResult.FoundMaster(
                        master(id, if (id == 88L) "Ambient" else "K-Pop")
                            .copy(mainReleaseId = id + 500L),
                    )
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    calls += "release:$id"
                    return DiscogsLookupResult.Found(
                        verifiedEdition(id - 500L).copy(
                            link = DiscogsLink(DiscogsKind.RELEASE, id),
                        ),
                    )
                }
            },
        )
        val result = source.discover(
            DiscogsEditorialQuery("K-Pop", true), maxReleases = 2,
        ) as DiscogsEditorialResult.Verified
        assertEquals(listOf("discogs:release:577", "discogs:release:599"),
            result.releases.map { it.browseId })
        assertEquals(listOf(
            "index:1", "master:77", "release:577", "master:88",
            "index:2", "master:99", "release:599",
        ), calls)
    }

    @Test fun finalCandidateInTwelveRowIndexPageMustReachCanonicalEditorial() = runBlocking {
        val visitedMasters = mutableListOf<Long>()
        val source = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    assertEquals(1, page)
                    val rows = (1L..12L).joinToString(",") {
                        """{"id":$it,"type":"master"}"""
                    }
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},"results":[$rows]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    visitedMasters += id
                    return DiscogsLookupResult.FoundMaster(
                        master(id, if (id == 12L) "K-Pop" else "Ambient"),
                    )
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    assertEquals(502L, id)
                    return DiscogsLookupResult.Found(verifiedEdition(12L))
                }
            },
        )
        val result = source.discover(
            DiscogsEditorialQuery("K-Pop", isStyle = true), maxReleases = 1,
        ) as DiscogsEditorialResult.Verified
        assertEquals((1L..12L).toList(), visitedMasters)
        assertEquals(listOf("discogs:release:502"),
            result.releases.map { it.browseId })
    }

    @Test fun secondPageCanRescueUnrelatedFirstPage() = runBlocking {
        val visited = mutableListOf<String>()
        val source = DiscogsNativeGenreEditorial(
            enabled = true,
            tokenProvider = { "A".repeat(32) },
            admission = access(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery,
                    token: String,
                    userAgent: String,
                    page: Int,
                ): DiscogsHttpResponse {
                    visited += "index:$page"
                    val id = if (page == 1) 42 else 77
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":$page,"pages":2,"per_page":12},
                            "results":[{"id":$id,"type":"master"}]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    visited += "master:$id"
                    return DiscogsLookupResult.FoundMaster(
                        master(id, if (id == 77L) "K-Pop" else "Ambient"),
                    )
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    visited += "release:$id"
                    return DiscogsLookupResult.Found(verifiedEdition(77L))
                }
            },
        )
        val result = source.discover(DiscogsEditorialQuery("K-Pop", true))
        assertTrue(result is DiscogsEditorialResult.Verified)
        val row = (result as DiscogsEditorialResult.Verified).releases.single()
        assertEquals("discogs:release:502", row.browseId)
        assertEquals(listOf(99L to "Verified Artist"), row.creditedArtists)
        assertEquals(listOf(
            "index:1", "master:42", "index:2", "master:77", "release:502",
        ), visited)
    }

    @Test fun sourceOnlyKpopSearchToArtistToReleaseUsesCanonicalDiscogsIdentity() = runBlocking {
        val calls = mutableListOf<String>()
        val editions = object : DiscogsNativeEditionReader {
            override suspend fun master(id: Long): DiscogsLookupResult {
                calls += "master:$id"
                return DiscogsLookupResult.FoundMaster(master(id, "K-Pop"))
            }

            override suspend fun release(id: Long): DiscogsLookupResult {
                calls += "release:$id"
                return DiscogsLookupResult.Found(verifiedEdition(77L))
            }
        }
        val genre = DiscogsNativeGenreEditorial(
            enabled = true,
            tokenProvider = { "A".repeat(32) },
            admission = access(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    assertEquals("K-Pop", query.sourceName)
                    assertTrue(query.isStyle)
                    calls += "genre:$page"
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},
                            "results":[{"id":77,"type":"master"}]}""")
                }
            },
            editions = editions,
        )
        val result = genre.discover(DiscogsEditorialQuery("K-Pop", isStyle = true))
        assertTrue(result is DiscogsEditorialResult.Verified)
        val release = (result as DiscogsEditorialResult.Verified).releases.single()
        assertEquals("discogs:release:502", release.browseId)

        val shelves = discogsEditorialShelves(listOf(release))
        val artistCard = shelves.single { it.title == "Artistas" }.items.single()
        val albumCard = shelves.single { it.title == "Álbuns" }.items.single()
        assertEquals("discogs:artist:99", artistCard.browseId)
        assertEquals("discogs:release:502", albumCard.browseId)
        assertEquals(albumCard.browseId, discogsNativeDetailRouteKey(
            DiscogsNativeRoute.RELEASE, albumCard.browseId,
        ))
        assertNull(albumCard.videoId)
        assertNull(albumCard.thumbnailUrl)

        val artistCatalog = DiscogsNativeArtistCatalog(
            enabled = true,
            admission = access(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(
                    artistId: Long, page: Int, userAgent: String,
                ): DiscogsHttpResponse {
                    assertEquals(99L, artistId)
                    calls += "artist:$page"
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1},"releases":[
                            {"id":77,"role":"Main","type":"master","title":"Source Album"}
                        ]}""")
                }
            },
            editions = editions,
        )
        val verifiedArtistReleases = artistCatalog.load(99L) as DiscogsNativeArtistLoad.Success
        assertFalse(verifiedArtistReleases.incomplete)
        assertFalse(verifiedArtistReleases.hasMore)
        val artistPage = discogsArtistPage(
            DiscogsNativeArtistSearchRow(99L, "Verified Artist"),
            verifiedArtistReleases.releases,
            hasMore = false, incomplete = false,
        )
        assertEquals(BrowseType.ARTIST, artistPage.page.type)
        assertEquals(artistCard.browseId, artistPage.page.browseId)
        assertEquals("discogs:release:502",
            artistPage.page.sections.single { it.title == "Álbuns" }
                .items.single().browseId)
        assertEquals("https://www.discogs.com/artist/99", artistPage.attributionUrl)
        assertEquals(listOf("genre:1", "master:77", "release:502",
            "artist:1", "master:77", "release:502"), calls)
    }

    @Test fun rateLimitAfterVerifiedMasterKeepsSourceOnlyGenrePartialCatalog() = runBlocking {
        val visited = mutableListOf<String>()
        val source = DiscogsNativeGenreEditorial(
            enabled = true,
            tokenProvider = { "A".repeat(32) },
            admission = access(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    visited += "genre:$page"
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},
                            "results":[{"id":77,"type":"master"},
                                       {"id":78,"type":"master"}]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    visited += "master:$id"
                    return if (id == 77L)
                        DiscogsLookupResult.FoundMaster(master(id, "K-Pop"))
                    else DiscogsLookupResult.Throttled(20L)
                }

                override suspend fun release(id: Long): DiscogsLookupResult {
                    visited += "release:$id"
                    return DiscogsLookupResult.Found(verifiedEdition(77L))
                }
            },
        )
        val result = source.discover(DiscogsEditorialQuery("K-Pop", true))
        assertTrue(result is DiscogsEditorialResult.Verified)
        assertEquals(listOf("discogs:release:502"),
            (result as DiscogsEditorialResult.Verified).releases.map { it.browseId })
        assertEquals(listOf("genre:1", "master:77", "release:502",
            "master:78"), visited)
    }

    @Test fun duplicateMasterOnSecondIndexPageRetriesTransientUnavailableRead() = runBlocking {
        val calls = mutableListOf<String>()
        var masterReads = 0
        val source = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    calls += "index:$page"
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":$page,"pages":2,"per_page":12},
                            "results":[{"id":77,"type":"master"}]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    calls += "master:$id"
                    masterReads++
                    return if (masterReads == 1) DiscogsLookupResult.Unavailable
                    else DiscogsLookupResult.FoundMaster(master(id, "K-Pop"))
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    calls += "release:$id"
                    return DiscogsLookupResult.Found(verifiedEdition(77L))
                }
            },
        )
        val result = source.discover(DiscogsEditorialQuery("K-Pop", true))
        assertTrue(result is DiscogsEditorialResult.Verified)
        assertEquals(listOf("discogs:release:502"),
            (result as DiscogsEditorialResult.Verified).releases.map { it.browseId })
        assertEquals(listOf("index:1", "master:77",
            "index:2", "master:77", "release:502"), calls)
    }

    @Test fun duplicateMasterRetriesWhenItsFirstReleaseHydrationFails() = runBlocking {
        val calls = mutableListOf<String>()
        var releaseReads = 0
        val source = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse {
                    calls += "index:$page"
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":$page,"pages":2,"per_page":12},
                            "results":[{"id":77,"type":"master"}]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    calls += "master:$id"
                    return DiscogsLookupResult.FoundMaster(master(id, "K-Pop"))
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    calls += "release:$id"
                    releaseReads++
                    return if (releaseReads == 1) DiscogsLookupResult.Malformed
                    else DiscogsLookupResult.Found(verifiedEdition(77L))
                }
            },
        )
        val result = source.discover(DiscogsEditorialQuery("K-Pop", true))
        assertTrue(result is DiscogsEditorialResult.Verified)
        assertEquals(listOf("discogs:release:502"),
            (result as DiscogsEditorialResult.Verified).releases.map { it.browseId })
        // The first Master was valid source evidence; a failed Release
        // must be retried, but the Master need not be downloaded again.
        assertEquals(listOf("index:1", "master:77", "release:502",
            "index:2", "release:502"), calls)
    }

    @Test fun failedMasterHydrationIsUnavailableNotProofOfEmptyDiscogsGenre() = runBlocking {
        val source = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { "A".repeat(32) },
            admission = access(), limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ): DiscogsHttpResponse = DiscogsHttpResponse(200,
                    """{"pagination":{"page":1,"pages":1,"per_page":12},
                        "results":[{"id":77,"type":"master"}]}""")
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long) = DiscogsLookupResult.Unavailable
                override suspend fun release(id: Long) = DiscogsLookupResult.Unavailable
            },
        )
        assertEquals(DiscogsEditorialResult.Unavailable,
            source.discover(DiscogsEditorialQuery("K-Pop", true)))
    }

    @Test fun authenticatedSearchThrottleIsNotRenderedAsAnEmptyCatalog() = runBlocking {
        val source = DiscogsNativeGenreEditorial(
            enabled = true,
            tokenProvider = { "A".repeat(32) },
            admission = access(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(
                    query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int,
                ) = DiscogsHttpResponse(429, "", retryAfterSeconds = 15)
            },
        )
        assertEquals(DiscogsEditorialResult.Throttled(15),
            source.discover(DiscogsEditorialQuery("K-Pop", true)))
    }
    @Test fun shelfArtworkReusesHydratedEditionOnlyWithinCurrentTokenPolicyAndFreshness() = runBlocking {
        val provider = BuiltInProviderKeys.DISCOGS
        val caps = setOf(ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY)
        fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(provider, caps, ProviderReadiness.READY)),
            choices = caps.associateWith { listOf(ProviderChoice(provider, enabled, 0)) },
        )
        val gate = ProviderPolicyGate(policy(true))
        var token = "A".repeat(32)
        var now = 10_000L
        var reads = 0
        val record = verifiedEdition(77L).copy(
            barcodes = listOf("8801234567893"),
            formats = listOf("File", "FLAC", "Album"),
        )
        val source = DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { token },
            admission = RateLimitedProviderTransport { gate },
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            clockMs = { now },
            transport = object : DiscogsGenreSearchTransport {
                override suspend fun search(query: DiscogsEditorialQuery, token: String,
                    userAgent: String, page: Int): DiscogsHttpResponse {
                    reads++
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1,"per_page":12},
                            "results":[{"id":77,"type":"master"}]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    reads++
                    return DiscogsLookupResult.FoundMaster(master(id, "K-Pop"))
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    reads++
                    return DiscogsLookupResult.Found(record)
                }
            },
        )
        assertNull(source.cachedArtworkEvidenceForRelease(502L))
        assertTrue(source.discover(DiscogsEditorialQuery("K-Pop", true), maxReleases = 1)
            is DiscogsEditorialResult.Verified)
        assertEquals(3, reads)
        assertEquals(record, source.cachedArtworkEvidenceForRelease(502L))
        assertNull(source.cachedArtworkEvidenceForRelease(77L))
        token = "B".repeat(32)
        assertNull(source.cachedArtworkEvidenceForRelease(502L))
        token = "A".repeat(32)
        gate.replace(policy(false))
        assertNull(source.cachedArtworkEvidenceForRelease(502L))
        gate.replace(policy(true))
        now += 120_001L
        assertNull(source.cachedArtworkEvidenceForRelease(502L))
        assertEquals(3, reads)
    }

    @Test fun shelfArtworkBatchBoundsRequestsAndOnlyPublishesExactProof() = runBlocking {
        val reads = mutableListOf<String>()
        val published = mutableListOf<String>()
        val ids = listOf("discogs:artist:4", "itunes:br:album:4", "discogs:release:01",
            "discogs:release:1", "discogs:release:1") +
            (2..22).map { "discogs:release:$it" }
        com.music.bitchord.ui.resolveDiscogsEditorialArtworkBatch(
            ids, stillCurrent = { true },
            resolve = { id ->
                reads += id
                val releaseId = id.substringAfterLast(':').toLong()
                if (releaseId == 2L) error("individual lookup failed")
                com.music.bitchord.data.catalog.metadata.DiscogsVerifiedAppleArtwork(
                    if (releaseId == 3L) 99L else releaseId,
                    "itunes:br:album:1", "https://example.test/art", "https://example.test/product",
                )
            },
            onVerified = { id, _ -> published += id },
        )
        assertEquals((1..16).map { "discogs:release:$it" }, reads)
        assertEquals((1..16).filter { it != 2 && it != 3 }.map { "discogs:release:$it" }, published)
    }

    @Test fun shelfArtworkBatchDropsLateProofAndStopsWhenSurfaceChanges() = runBlocking {
        var current = true
        var reads = 0
        var published = 0
        com.music.bitchord.ui.resolveDiscogsEditorialArtworkBatch(
            listOf("discogs:release:1", "discogs:release:2"), stillCurrent = { current },
            resolve = {
                reads++
                current = false
                com.music.bitchord.data.catalog.metadata.DiscogsVerifiedAppleArtwork(
                    1L, "itunes:br:album:1", "https://example.test/art", "https://example.test/product",
                )
            },
            onVerified = { _, _ -> published++ },
        )
        assertEquals(1, reads)
        assertEquals(0, published)
    }

    @Test fun shelfArtworkBatchPropagatesCancellation() = runBlocking {
        var reads = 0
        try {
            com.music.bitchord.ui.resolveDiscogsEditorialArtworkBatch(
                listOf("discogs:release:1", "discogs:release:2"), stillCurrent = { true },
                resolve = {
                    reads++
                    throw kotlinx.coroutines.CancellationException("surface closed")
                },
                onVerified = { _, _ -> fail("Cancelled lookup cannot publish") },
            )
            fail("Cancellation was swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(1, reads)
        }
    }

    @Test fun shelfArtworkBatchDoesNotSpendCapacityOnIneligibleEditions() = runBlocking {
        val reads = mutableListOf<String>()
        val eligibilityChecks = mutableListOf<String>()
        com.music.bitchord.ui.resolveDiscogsEditorialArtworkBatch(
            (1..40).map { "discogs:release:$it" },
            stillCurrent = { true },
            resolve = { id -> reads += id; null },
            onVerified = { _, _ -> fail("No proof returned") },
            eligible = { id ->
                eligibilityChecks += id
                id.substringAfterLast(':').toInt() > 20
            },
        )
        assertEquals((21..36).map { "discogs:release:$it" }, reads)
        assertEquals((1..36).map { "discogs:release:$it" }, eligibilityChecks)
    }

    @Test fun shelfArtworkBudgetCountsOnlyActualAttemptsAcrossManyIneligibleCards() = runBlocking {
        val attempts = mutableListOf<String>()
        val calls = mutableListOf<String>()
        com.music.bitchord.ui.resolveDiscogsEditorialArtworkBatch(
            (1..100).map { "discogs:release:$it" },
            stillCurrent = { true },
            resolve = { id -> calls += id; null },
            onVerified = { _, _ -> fail("No proof returned") },
            eligible = { id -> id.substringAfterLast(':').toInt() >= 90 },
            onAttempt = { attempts += it },
            maximumAttempts = 3,
        )
        assertEquals((90..92).map { "discogs:release:$it" }, attempts)
        assertEquals(attempts, calls)
    }

}
