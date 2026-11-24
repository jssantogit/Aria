package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscogsNativeArtistCatalogTest {
    private fun release(
        id: Long, artistId: Long = 17L, masterId: Long? = null,
        unofficial: Boolean = false,
    ) = DiscogsRecord(
        link = DiscogsLink(DiscogsKind.RELEASE, id),
        title = "Album $id",
        artistNames = listOf("Artist"),
        country = "Brazil",
        year = 2024,
        labels = emptyList(),
        barcodes = emptyList(),
        formats = listOf("CD", "Album"),
        tracklist = listOf("1" to "Track"),
        unofficial = unofficial,
        masterId = masterId,
        artistIds = listOf(artistId),
    )

    @Test fun artistIndexUsesTokenOnlyWithExplicitAuthorizedTransport() = runBlocking {
        var token: String? = "A".repeat(32)
        val authenticatedCalls = mutableListOf<String>()
        val anonymousCalls = mutableListOf<Int>()
        val transport = object : DiscogsPublicArtistTransport {
            override val supportsTokenAuthorization: Boolean get() = true
            private fun indexResponse() = DiscogsHttpResponse(200,
                """{"pagination":{"page":1,"pages":1},"releases":[
                    {"id":50,"type":"release","role":"Main","title":"Album"}
                ]}""")
            override suspend fun list(
                artistId: Long, page: Int, userAgent: String,
            ): DiscogsHttpResponse {
                anonymousCalls += page
                return indexResponse()
            }
            override suspend fun listAuthenticated(
                artistId: Long, page: Int, userAgent: String, token: String,
            ): DiscogsHttpResponse {
                authenticatedCalls += token
                return indexResponse()
            }
        }
        val source = DiscogsNativeArtistCatalog(
            enabled = true, admission = permittedDiscography(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = transport, tokenProvider = { token },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long) = DiscogsLookupResult.Missing
                override suspend fun release(id: Long) =
                    DiscogsLookupResult.Found(this@DiscogsNativeArtistCatalogTest.release(id))
            },
        )
        val first = source.load(17) as DiscogsNativeArtistLoad.Success
        assertEquals(listOf(50L), first.releases.map { it.releaseId })
        assertEquals(listOf("A".repeat(32)), authenticatedCalls)
        assertTrue(anonymousCalls.isEmpty())
        token = "invalid token"
        assertEquals(1, (source.load(17) as DiscogsNativeArtistLoad.Success).releases.size)
        token = null
        assertEquals(1, (source.load(17) as DiscogsNativeArtistLoad.Success).releases.size)
        assertEquals(listOf(1, 1), anonymousCalls)
        assertEquals(1, authenticatedCalls.size)
    }

    @Test fun publicArtistTransportWithoutOptInCannotReceiveSourceToken() = runBlocking {
        var anonymousReads = 0
        val source = DiscogsNativeArtistCatalog(
            enabled = true, admission = permittedDiscography(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            tokenProvider = { "A".repeat(32) },
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(
                    artistId: Long, page: Int, userAgent: String,
                ): DiscogsHttpResponse {
                    anonymousReads++
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":1,"pages":1},"releases":[]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long) = DiscogsLookupResult.Missing
                override suspend fun release(id: Long) = DiscogsLookupResult.Missing
            },
        )
        val result = source.load(17) as DiscogsNativeArtistLoad.Success
        assertTrue(result.releases.isEmpty())
        assertEquals(1, anonymousReads)
    }

    @Test fun sourceScopedArtistCanProduceReleasesWithoutAnyMusicBrainzId() {
        val rows = discogsNativeArtistReleases(17, listOf(
            DiscogsArtistIndexEntry(DiscogsKind.MASTER, 90) to
                release(50, masterId = 90),
            DiscogsArtistIndexEntry(DiscogsKind.RELEASE, 50) to
                release(50, masterId = 90),
            // A distinct physical Release of the SAME proven Master must
            // not create a second visible album card.
            DiscogsArtistIndexEntry(DiscogsKind.RELEASE, 54) to
                release(54, masterId = 90),
            DiscogsArtistIndexEntry(DiscogsKind.RELEASE, 51) to
                release(51, artistId = 18),
            DiscogsArtistIndexEntry(DiscogsKind.MASTER, 91) to
                release(52, masterId = 90),
            DiscogsArtistIndexEntry(DiscogsKind.RELEASE, 53) to
                release(53, unofficial = true),
        ))
        assertEquals(listOf("discogs:release:50"), rows.map { it.browseId })
        assertEquals(90L, rows.single().masterId)
        assertEquals("https://www.discogs.com/release/50", rows.single().attributionUrl)
        assertEquals(listOf("1" to "Track"), rows.single().tracklist)
    }

    @Test fun artistTracksAreBoundedReleaseSamplesWithoutForeignCreditsOrInventedIdentity() {
        val exact = release(50).copy(tracklist = (1..10).map { "$it" to "Track $it" })
        val compilation = release(51).copy(artistIds = listOf(17L, 18L),
            artistNames = listOf("Artist", "Other"))
        val verified = discogsNativeArtistReleases(17, listOf(
            DiscogsArtistIndexEntry(DiscogsKind.RELEASE, 50) to exact,
            DiscogsArtistIndexEntry(DiscogsKind.RELEASE, 51) to compilation,
            DiscogsArtistIndexEntry(DiscogsKind.RELEASE, 52) to release(52, artistId = 18),
        ))
        assertEquals(4, verified.first().tracklist.size)
        assertTrue(verified.last().tracklist.isEmpty())
        val foreign = verified.first().copy(artistId = 18, releaseId = 99)
        val page = com.music.bitchord.ui.discogsArtistPage(
            com.music.bitchord.ui.screens.DiscogsNativeArtistSearchRow(17, "Artist"),
            verified + foreign + verified.first(), false, false,
        )
        assertEquals(listOf("discogs:release:50", "discogs:release:51"),
            page.page.sections.flatMap { it.items }.map { it.browseId })
        assertEquals((0..3).map { "discogs:track:50:$it" }, page.tracks.map { it.key })
        assertTrue(page.tracks.all { it.credit == "Album 50" })
        assertEquals((1..4).map { "Track $it" }, page.tracks.map { it.title })
        assertEquals(emptyList<com.music.bitchord.data.model.Song>(),
            (page.page.songs as com.music.bitchord.data.model.UiState.Success).data)
    }

    private fun permittedDiscography(): RateLimitedProviderTransport {
        val provider = BuiltInProviderKeys.DISCOGS
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(
                provider, setOf(ProviderCapability.EDITORIAL_DISCOGRAPHY),
                ProviderReadiness.READY,
            )),
            choices = mapOf(ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                ProviderChoice(provider, true, 0),
            )),
        ))
        return RateLimitedProviderTransport { gate }
    }

    @Test fun artistPageDedupUsesOnlyProvenMasterAcrossPages() {
        val first = DiscogsNativeArtistRelease(
            artistId = 17L, releaseId = 50L, masterId = 90L,
            title = "An Album", year = 2020, formats = listOf("Album"),
            tracklist = emptyList(),
        )
        val alternate = first.copy(releaseId = 54L)
        val standalone = first.copy(releaseId = 60L, masterId = null)
        assertEquals(listOf(50L, 60L),
            discogsMergeArtistPageReleases(
                listOf(first, alternate, standalone),
            ).map { it.releaseId })
        assertEquals(listOf(60L, 50L),
            discogsMergeArtistPageReleases(
                listOf(standalone, first, alternate),
            ).map { it.releaseId })
        assertEquals(listOf(60L, 61L),
            discogsMergeArtistPageReleases(
                listOf(standalone, standalone.copy(releaseId = 61L)),
            ).map { it.releaseId })
    }

    @Test fun masterHydrationRateLimitIsARealFailureNotEmptyArtist() = runBlocking {
        val source = DiscogsNativeArtistCatalog(
            enabled = true,
            admission = permittedDiscography(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(artistId: Long, page: Int, userAgent: String) =
                    DiscogsHttpResponse(200, """
                        {"pagination":{"page":1,"pages":1},"releases":[
                            {"id":90,"role":"Main","type":"master","title":"Album"}
                        ]}""")
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long) =
                    DiscogsLookupResult.Throttled(15L)
                override suspend fun release(id: Long) = DiscogsLookupResult.Missing
            },
        )
        assertEquals(DiscogsNativeArtistLoad.Throttled(15L), source.load(17))
    }

    @Test fun interruptedEditionHydrationPreservesOnlyVerifiedPartialPage() = runBlocking {
        val source = DiscogsNativeArtistCatalog(
            enabled = true,
            admission = permittedDiscography(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(artistId: Long, page: Int, userAgent: String) =
                    DiscogsHttpResponse(200, """
                        {"pagination":{"page":1,"pages":1},"releases":[
                            {"id":50,"role":"Main","type":"release","title":"Good"},
                            {"id":51,"role":"Main","type":"release","title":"Unavailable"}
                        ]}""")
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long) = DiscogsLookupResult.Missing
                override suspend fun release(id: Long): DiscogsLookupResult = if (id == 50L)
                    DiscogsLookupResult.Found(this@DiscogsNativeArtistCatalogTest.release(id))
                    else DiscogsLookupResult.Throttled(12L)
            },
        )
        val emissions = mutableListOf<List<Long>>()
        val result = source.load(17, onProgress = { rows ->
            emissions += rows.map { it.releaseId }
        }) as DiscogsNativeArtistLoad.Success
        assertEquals(listOf(listOf(50L)), emissions)
        assertEquals(listOf(50L), result.releases.map { it.releaseId })
        assertTrue(result.incomplete)
        assertTrue(result.retryCurrentPage)
        assertFalse(result.hasMore)
    }

    @Test fun interruptedArtistPageIsRetriedBeforeAdvancingAndClosesIncompleteGap() = runBlocking {
        val requestedPages = mutableListOf<Int>()
        val releaseCalls = mutableListOf<Long>()
        var throttleSecondRelease = true
        val source = DiscogsNativeArtistCatalog(
            enabled = true,
            admission = permittedDiscography(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(artistId: Long, page: Int, userAgent: String): DiscogsHttpResponse {
                    requestedPages += page
                    return DiscogsHttpResponse(200, if (page == 1) """
                        {"pagination":{"page":1,"pages":2},"releases":[
                            {"id":50,"role":"Main","type":"release","title":"First"},
                            {"id":51,"role":"Main","type":"release","title":"Second"}
                        ]}""" else """
                        {"pagination":{"page":2,"pages":2},"releases":[
                            {"id":52,"role":"Main","type":"release","title":"Third"}
                        ]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long) = DiscogsLookupResult.Missing
                override suspend fun release(id: Long): DiscogsLookupResult {
                    releaseCalls += id
                    return if (id == 51L && throttleSecondRelease) {
                        throttleSecondRelease = false
                        DiscogsLookupResult.Throttled(9L)
                    } else DiscogsLookupResult.Found(this@DiscogsNativeArtistCatalogTest.release(id))
                }
            },
        )
        val partial = source.load(17, 1) as DiscogsNativeArtistLoad.Success
        assertEquals(listOf(50L), partial.releases.map { it.releaseId })
        assertTrue(partial.incomplete)
        assertTrue(partial.retryCurrentPage)
        assertTrue(partial.hasMore)

        // The session must request page 1 again rather than silently skipping
        // release 51. The duplicate verified release is safe to merge by ID.
        val retried = source.loadDistinct(17, 1, partial.releases)
            as DiscogsNativeArtistLoad.Success
        assertEquals(listOf(51L), retried.releases.map { it.releaseId })
        assertFalse(retried.incomplete)
        assertFalse(retried.retryCurrentPage)
        assertTrue(retried.hasMore)

        val finalPage = source.load(17, 2) as DiscogsNativeArtistLoad.Success
        assertEquals(listOf(52L), finalPage.releases.map { it.releaseId })
        assertFalse(finalPage.retryCurrentPage)
        assertFalse(finalPage.hasMore)
        assertEquals(listOf(1, 1, 2), requestedPages)
        assertEquals(listOf(50L, 51L, 51L, 52L), releaseCalls)
        assertEquals(listOf(50L, 51L, 52L), discogsMergeArtistPageReleases(
            partial.releases + retried.releases + finalPage.releases,
        ).map { it.releaseId })
    }

    @Test fun laterArtistPageSkipsKnownMastersButVerifiesUnrelatedReleases() = runBlocking {
        val masters = mutableListOf<Long>()
        val releases = mutableListOf<Long>()
        val known = DiscogsNativeArtistRelease(
            artistId = 17, releaseId = 50, masterId = 90,
            title = "Known", year = 2024, formats = listOf("Album"), tracklist = emptyList(),
        )
        val source = DiscogsNativeArtistCatalog(
            enabled = true, admission = permittedDiscography(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(artistId: Long, page: Int, userAgent: String) =
                    DiscogsHttpResponse(200, """
                        {"pagination":{"page":2,"pages":3},"releases":[
                        {"id":90,"role":"Main","type":"master","title":"Known"},
                        {"id":50,"role":"Main","type":"release","title":"Known"},
                        {"id":51,"role":"Main","type":"release","title":"Other pressing"},
                        {"id":52,"role":"Main","type":"release","title":"Different artist"},
                        {"id":53,"role":"Main","type":"release","title":"New work"}]}
                    """)
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    masters += id
                    return DiscogsLookupResult.Missing
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    releases += id
                    return DiscogsLookupResult.Found(
                        this@DiscogsNativeArtistCatalogTest.release(
                            id, artistId = if (id == 52L) 18 else 17,
                            masterId = if (id == 51L) 90 else null,
                        ),
                    )
                }
            },
        )
        val result = source.loadDistinct(17, 2, listOf(known))
            as DiscogsNativeArtistLoad.Success
        assertTrue(masters.isEmpty())
        assertEquals(listOf(51L, 52L, 53L), releases)
        assertEquals(listOf(53L), result.releases.map { it.releaseId })
        assertTrue(result.hasMore)
        assertTrue(result.incomplete)
    }

    @Test fun artistWindowCrossesDuplicatePagesAndRetriesOnlyInterruptedPage() = runBlocking {
        val pages = mutableListOf<Int>()
        val reads = mutableListOf<Long>()
        var interrupt = true
        val known = DiscogsNativeArtistRelease(
            artistId = 17, releaseId = 50, masterId = 90,
            title = "Known", year = 2024, formats = listOf("Album"), tracklist = emptyList(),
        )
        val source = DiscogsNativeArtistCatalog(
            enabled = true, admission = permittedDiscography(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(artistId: Long, page: Int, userAgent: String): DiscogsHttpResponse {
                    pages += page
                    val ids = when (page) {
                        1 -> listOf(50)
                        2 -> listOf(51)
                        3 -> listOf(52, 53)
                        else -> listOf(54)
                    }
                    val rows = ids.joinToString(",") {
                        """{"id":$it,"role":"Main","type":"release","title":"Album"}"""
                    }
                    return DiscogsHttpResponse(200,
                        """{"pagination":{"page":$page,"pages":4},"releases":[$rows]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long) = DiscogsLookupResult.Missing
                override suspend fun release(id: Long): DiscogsLookupResult {
                    reads += id
                    if (id == 53L && interrupt) {
                        interrupt = false
                        return DiscogsLookupResult.Throttled(9)
                    }
                    return DiscogsLookupResult.Found(
                        this@DiscogsNativeArtistCatalogTest.release(id),
                    )
                }
            },
        )
        val emitted = mutableListOf<List<Long>>()
        val first = source.loadWindow(17, 1, listOf(known)) { rows ->
            emitted += rows.map { it.releaseId }
        }
        val partial = first.result as DiscogsNativeArtistLoad.Success
        assertEquals(2, first.completedPages)
        assertFalse(first.settledIncomplete)
        assertTrue(partial.retryCurrentPage)
        assertEquals(listOf(51L, 52L), partial.releases.map { it.releaseId })
        assertEquals(listOf(listOf(51L), listOf(51L, 52L)), emitted)
        val second = source.loadWindow(
            17, 1 + first.completedPages, listOf(known) + partial.releases,
        )
        val settled = second.result as DiscogsNativeArtistLoad.Success
        assertEquals(2, second.completedPages)
        assertFalse(settled.retryCurrentPage)
        assertFalse(settled.hasMore)
        assertEquals(listOf(53L, 54L), settled.releases.map { it.releaseId })
        assertEquals(listOf(1, 2, 3, 3, 4), pages)
        assertEquals(listOf(51L, 52L, 53L, 53L, 54L), reads)
    }

    @Test fun unexpectedSourceExceptionDoesNotEscapeArtistLoader() = runBlocking {
        val source = DiscogsNativeArtistCatalog(
            enabled = true,
            admission = permittedDiscography(),
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(artistId: Long, page: Int, userAgent: String) =
                    DiscogsHttpResponse(200, """
                        {"pagination":{"page":1,"pages":1},"releases":[
                            {"id":50,"role":"Main","type":"release","title":"Unavailable"}
                        ]}""")
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long) = DiscogsLookupResult.Missing
                override suspend fun release(id: Long): DiscogsLookupResult =
                    throw IllegalStateException("upstream")
            },
        )
        assertEquals(DiscogsNativeArtistLoad.Unavailable, source.load(17))
    }

    @Test fun missingPermissionFailsClosedBeforeAnyDiscogsTransportCall() = runBlocking {
        var calls = 0
        val provider = BuiltInProviderKeys.DISCOGS
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(provider,
                setOf(ProviderCapability.EDITORIAL_DISCOGRAPHY), ProviderReadiness.READY)),
            choices = mapOf(ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                ProviderChoice(provider, false, 0))),
        ))
        val source = DiscogsNativeArtistCatalog(
            enabled = true,
            admission = RateLimitedProviderTransport { gate },
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(artistId: Long, page: Int,
                    userAgent: String): DiscogsHttpResponse {
                    calls++
                    return DiscogsHttpResponse(200, "{}")
                }
            },
        )
        assertEquals(DiscogsNativeArtistLoad.Disabled, source.load(17))
        assertEquals(0, calls)
        assertEquals(DiscogsNativeArtistLoad.InvalidArtist, source.load(-1))
    }

    @Test fun independentArtistIdPaginatesTypedDiscogsReleasesWithoutMbBrowse() = runBlocking {
        val provider = BuiltInProviderKeys.DISCOGS
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(provider,
                setOf(ProviderCapability.EDITORIAL_DISCOGRAPHY), ProviderReadiness.READY)),
            choices = mapOf(ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                ProviderChoice(provider, true, 0))),
        ))
        val visited = mutableListOf<String>()
        val source = DiscogsNativeArtistCatalog(
            enabled = true,
            admission = RateLimitedProviderTransport { gate },
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(artistId: Long, page: Int,
                    userAgent: String): DiscogsHttpResponse {
                    visited += "artist:$artistId:$page"
                    return DiscogsHttpResponse(200, """
                        {"pagination":{"page":1,"pages":2},"releases":[
                            {"id":90,"role":"Main","type":"master","title":"Album"},
                            {"id":51,"role":"Main","type":"release","title":"Single"},
                            {"id":200,"role":"Producer","type":"release","title":"Credit"}
                        ]}""")
                }
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long): DiscogsLookupResult {
                    visited += "master:$id"
                    return DiscogsLookupResult.FoundMaster(DiscogsMasterRecord(
                        DiscogsLink(DiscogsKind.MASTER, id),
                        "Album", listOf("Artist"), 2024, 50,
                        emptyList(), emptyList(),
                    ))
                }
                override suspend fun release(id: Long): DiscogsLookupResult {
                    visited += "release:$id"
                    return DiscogsLookupResult.Found(release(id,
                        masterId = if (id == 50L) 90 else null))
                }
            },
        )
        val progressive = mutableListOf<List<Long>>()
        val result = source.load(17, onProgress = { items ->
            progressive += items.map { it.releaseId }
        })
        assertEquals(listOf(listOf(50L), listOf(50L, 51L)), progressive)
        assertTrue(result is DiscogsNativeArtistLoad.Success)
        val page = result as DiscogsNativeArtistLoad.Success
        assertEquals(listOf(50L, 51L), page.releases.map { it.releaseId })
        assertTrue(page.hasMore)
        assertFalse(page.incomplete)
        assertEquals(listOf("artist:17:1", "master:90", "release:50", "release:51"), visited)
    }

    @Test fun unverifiedArtistCreditMustNotLookLikeCompleteDiscography() = runBlocking {
        val key = BuiltInProviderKeys.DISCOGS
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(key,
                setOf(ProviderCapability.EDITORIAL_DISCOGRAPHY), ProviderReadiness.READY)),
            choices = mapOf(ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                ProviderChoice(key, true, 0))),
        ))
        val source = DiscogsNativeArtistCatalog(
            enabled = true,
            admission = RateLimitedProviderTransport { gate },
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0),
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(artistId: Long, page: Int,
                    userAgent: String): DiscogsHttpResponse =
                    DiscogsHttpResponse(200, """
                        {"pagination":{"page":1,"pages":1},"releases":[
                            {"id":42,"role":"Main","type":"release","title":"Wrong Artist"}
                        ]}""")
            },
            editions = object : DiscogsNativeEditionReader {
                override suspend fun master(id: Long) = DiscogsLookupResult.Missing
                override suspend fun release(id: Long) =
                    DiscogsLookupResult.Found(release(id, artistId = 999L))
            },
        )
        val emissions = mutableListOf<List<DiscogsNativeArtistRelease>>()
        val result = source.load(17, onProgress = { emissions += it }) as
            DiscogsNativeArtistLoad.Success
        assertTrue(emissions.isEmpty())
        assertTrue(result.releases.isEmpty())
        assertTrue(result.incomplete)
        assertFalse(result.hasMore)
    }

    @Test fun coverageIsExplicitlyIncompleteAfterHundredPageBound() = runBlocking {
        val provider = BuiltInProviderKeys.DISCOGS
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(provider,
                setOf(ProviderCapability.EDITORIAL_DISCOGRAPHY), ProviderReadiness.READY)),
            choices = mapOf(ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                ProviderChoice(provider, true, 0))),
        ))
        val source = DiscogsNativeArtistCatalog(
            enabled = true,
            admission = RateLimitedProviderTransport { gate },
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = object : DiscogsPublicArtistTransport {
                override suspend fun list(artistId: Long, page: Int,
                    userAgent: String): DiscogsHttpResponse =
                    DiscogsHttpResponse(200,
                        """{"pagination":{"page":100,"pages":101},"releases":[]}""")
            },
        )
        val result = source.load(17, 100) as DiscogsNativeArtistLoad.Success
        assertFalse(result.hasMore)
        assertTrue(result.incomplete)
    }
}
