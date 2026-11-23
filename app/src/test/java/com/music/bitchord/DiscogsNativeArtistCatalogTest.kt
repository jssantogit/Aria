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
        assertTrue(rows.single().tracklist.isEmpty())
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
        val retried = source.load(17, 1) as DiscogsNativeArtistLoad.Success
        assertEquals(listOf(50L, 51L), retried.releases.map { it.releaseId })
        assertFalse(retried.incomplete)
        assertFalse(retried.retryCurrentPage)
        assertTrue(retried.hasMore)

        val finalPage = source.load(17, 2) as DiscogsNativeArtistLoad.Success
        assertEquals(listOf(52L), finalPage.releases.map { it.releaseId })
        assertFalse(finalPage.retryCurrentPage)
        assertFalse(finalPage.hasMore)
        assertEquals(listOf(1, 1, 2), requestedPages)
        assertEquals(listOf(50L, 51L, 50L, 51L, 52L), releaseCalls)
        assertEquals(listOf(50L, 51L, 52L), discogsMergeArtistPageReleases(
            partial.releases + retried.releases + finalPage.releases,
        ).map { it.releaseId })
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
