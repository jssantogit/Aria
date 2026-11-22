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
        val result = source.load(17)
        assertTrue(result is DiscogsNativeArtistLoad.Success)
        val page = result as DiscogsNativeArtistLoad.Success
        assertEquals(listOf(50L, 51L), page.releases.map { it.releaseId })
        assertTrue(page.hasMore)
        assertFalse(page.incomplete)
        assertEquals(listOf("artist:17:1", "master:90", "release:50", "release:51"), visited)
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
