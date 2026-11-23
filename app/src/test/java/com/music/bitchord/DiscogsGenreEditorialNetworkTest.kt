package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.providers.*
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

    private fun release(masterId: Long) = DiscogsRecord(
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
                    return DiscogsLookupResult.Found(release(77L))
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
}
