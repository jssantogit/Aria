package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscogsNativeArtistSearchTest {
    @Test fun onlyExactTypedDiscogsArtistsSurviveAndConflictingIdsAreDiscarded() {
        val result = DiscogsNativeArtistSearchParsing.page("""
            {"pagination":{"page":1,"pages":5},"results":[
                {"type":"artist","id":12,"title":"Artist"},
                {"type":"artist","id":12,"title":"Artist"},
                {"type":"release","id":13,"title":"Album"},
                {"type":"artist","id":14,"title":"First"},
                {"type":"artist","id":14,"title":"Another"},
                {"type":"artist","id":15,"title":"Other"}
            ]}""", 1)
        assertNotNull(result)
        assertEquals(listOf("discogs:artist:12", "discogs:artist:15"),
            result?.artists?.map { it.browseId })
        assertEquals(2, result?.nextPage)
        assertTrue(result?.coverageUncertain == true)
        assertNull(DiscogsNativeArtistSearchParsing.page(
            """{"pagination":{"page":2,"pages":1},"results":[]}""", 1))
        assertNull(DiscogsNativeArtistSearchParsing.page(
            """{"pagination":{"page":1,"pages":1},"results":null}""", 1))
    }

    @Test fun noConsentOrCredentialsMeansNoNetworkAndNoMusicBrainzFallback() = runBlocking {
        val provider = BuiltInProviderKeys.DISCOGS
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(provider,
                setOf(ProviderCapability.CATALOG_SEARCH), ProviderReadiness.READY)),
            choices = mapOf(ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(provider, false, 0))),
        ))
        var calls = 0
        val transport = object : DiscogsNativeArtistSearchTransport {
            override suspend fun search(term: String, page: Int,
                token: String, agent: String): DiscogsHttpResponse {
                calls++
                return DiscogsHttpResponse(200, "{}")
            }
        }
        val client = DiscogsNativeArtistSearchClient(
            transport = transport, enabled = true,
            tokenProvider = { "Abcdefghijklmnopqrstuvwxyz123456" },
            admission = RateLimitedProviderTransport { gate },
        )
        assertEquals(DiscogsNativeArtistSearchResult.Disabled, client.search("Artist"))
        assertEquals(DiscogsNativeArtistSearchResult.InvalidQuery, client.search(""))
        assertEquals(0, calls)
        val allowed = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(provider,
                setOf(ProviderCapability.CATALOG_SEARCH), ProviderReadiness.READY)),
            choices = mapOf(ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(provider, true, 0))),
        ))
        val noToken = DiscogsNativeArtistSearchClient(
            transport = transport, enabled = true,
            admission = RateLimitedProviderTransport { allowed },
        )
        assertEquals(DiscogsNativeArtistSearchResult.AuthenticationRequired,
            noToken.search("Artist"))
        assertEquals(0, calls)
    }

    @Test fun authorizedArtistSearchPreservesDiscogsIdentityAndPageCoverage() = runBlocking {
        val provider = BuiltInProviderKeys.DISCOGS
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(provider,
                setOf(ProviderCapability.CATALOG_SEARCH), ProviderReadiness.READY)),
            choices = mapOf(ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(provider, true, 0))),
        ))
        var sent: String? = null
        val client = DiscogsNativeArtistSearchClient(
            enabled = true,
            tokenProvider = { "abcdefghijklmnopqrstuvwxyz123456" },
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0),
            admission = RateLimitedProviderTransport { gate },
            transport = object : DiscogsNativeArtistSearchTransport {
                override suspend fun search(term: String, page: Int,
                    token: String, agent: String): DiscogsHttpResponse {
                    sent = "$term:$page"
                    return DiscogsHttpResponse(200, """
                        {"pagination":{"page":3,"pages":3},"results":[
                            {"type":"artist","id":123,"title":"New Artist"}
                        ]}""")
                }
            },
        )
        val result = client.search(" New Artist ", 3)
        assertEquals("New Artist:3", sent)
        val page = (result as DiscogsNativeArtistSearchResult.Success).page
        assertEquals(listOf("discogs:artist:123"), page.artists.map { it.browseId })
        assertNull(page.nextPage)
        assertFalse(page.coverageUncertain)
    }
}
