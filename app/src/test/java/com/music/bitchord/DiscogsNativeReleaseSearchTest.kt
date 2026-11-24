package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscogsNativeReleaseSearchTest {
    @Test fun typedResultsKeepDiscogsReleaseIdsAndDropConflictingSnapshots() {
        val page = DiscogsNativeReleaseSearchParsing.page("""
            {"pagination":{"page":1,"pages":4},"results":[
                {"type":"release","id":20,"title":"Artist - Album"},
                {"type":"release","id":20,"title":"Artist - Album"},
                {"type":"master","id":30,"title":"Artist - Master"},
                {"type":"release","id":40,"title":"First"},
                {"type":"release","id":40,"title":"Contradiction"},
                {"type":"release","id":50,"title":"Another"}
            ]}""", 1)
        assertNotNull(page)
        assertEquals(listOf("discogs:release:20", "discogs:release:50"),
            page?.releases?.map { it.browseId })
        assertEquals(2, page?.nextPage)
        assertTrue(page?.coverageUncertain == true)
        assertNull(DiscogsNativeReleaseSearchParsing.page(
            """{"pagination":{"page":1,"pages":1},"results":null}""", 1))
        assertNull(DiscogsNativeReleaseSearchParsing.page(
            """{"pagination":{"page":2,"pages":3},"results":[]}""", 1))
    }

    @Test fun multidiscDiscoveryBalancesBothIndexesAndNeverExceedsEightUniqueDetails() {
        fun hits(ids: LongRange) = ids.map { DiscogsNativeReleaseSearchHit(it, "Edition $it") }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L),
            discogsMultidiscSearchCandidates(hits(1L..7L), hits(3L..9L))
                .map { it.releaseId })
        assertEquals((10L..17L).toList(),
            discogsMultidiscSearchCandidates(emptyList(), hits(10L..20L)).map { it.releaseId })
        assertEquals((1L..8L).toList(),
            discogsMultidiscSearchCandidates(hits(1L..10L), emptyList()).map { it.releaseId })
    }

    @Test fun cdEditionFilterCannotBeUsedWithArtistEntity() = runBlocking {
        val transport = KtorDiscogsNativeArtistSearchTransport(
            entity = "artist", cdEditionsOnly = true,
        )
        assertTrue(runCatching {
            transport.search("Artist", 1, "A".repeat(32), "BitChord/Test")
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun userConsentAndTokenAreNeededBeforeReleaseSearchNetwork() = runBlocking {
        val key = BuiltInProviderKeys.DISCOGS
        fun gate(enabled: Boolean) = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(
                key, setOf(ProviderCapability.CATALOG_SEARCH), ProviderReadiness.READY,
            )),
            choices = mapOf(ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(key, enabled, 0),
            )),
        ))
        var requests = 0
        val transport = object : DiscogsNativeArtistSearchTransport {
            override suspend fun search(term: String, page: Int,
                token: String, agent: String): DiscogsHttpResponse {
                requests++
                return DiscogsHttpResponse(200, """
                    {"pagination":{"page":1,"pages":1},"results":[
                        {"type":"release","id":123,"title":"An Album"}
                    ]}""")
            }
        }
        val blocked = DiscogsNativeReleaseSearchClient(
            enabled = true, tokenProvider = { "abcdefghijklmnopqrstuvwxyz123456" },
            admission = RateLimitedProviderTransport { gate(false) },
            transport = transport,
        )
        assertEquals(DiscogsNativeReleaseSearchResult.Disabled,
            blocked.search("Album"))
        val noToken = DiscogsNativeReleaseSearchClient(
            enabled = true,
            admission = RateLimitedProviderTransport { gate(true) },
            transport = transport,
        )
        assertEquals(DiscogsNativeReleaseSearchResult.AuthenticationRequired,
            noToken.search("Album"))
        assertEquals(0, requests)
        val allowedGate = gate(true)
        val enabled = DiscogsNativeReleaseSearchClient(
            enabled = true, tokenProvider = { "abcdefghijklmnopqrstuvwxyz123456" },
            admission = RateLimitedProviderTransport { allowedGate },
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            transport = transport,
        )
        val result = enabled.search(" Album ")
        val page = (result as DiscogsNativeReleaseSearchResult.Success).page
        assertEquals(listOf("discogs:release:123"), page.releases.map { it.browseId })
        assertEquals(1, requests)
    }

    @Test fun noSearchProviderCanPromoteDiscogsReleaseToRecordedMusic() {
        val result = DiscogsNativeReleaseSearchHit(19, "Release")
        assertEquals("discogs:release:19", result.browseId)
        assertFalse(result.browseId.startsWith("catalog:"))
        assertFalse(result.browseId.startsWith("itunes:"))
    }
}
