package com.music.aria

import com.music.aria.data.catalog.metadata.discogs.*
import com.music.aria.data.providers.*
import com.music.aria.ui.screens.DiscogsNativeArtistSearchRow
import com.music.aria.ui.screens.discogsSearchDistinctArtists
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

    @Test fun homonymSelectionUsesExactReleaseCreditNotSuffixOrSearchTitle() = runBlocking {
        val rows = listOf(
            DiscogsNativeArtistSearchRow(1, "Example"),
            DiscogsNativeArtistSearchRow(2, "Example (2)"),
        )
        val reads = mutableListOf<Long>()
        val resolved = discogsResolveArtistSearchHomonyms("Example", rows,
            stillCurrent = { true },
            searchReleases = {
                DiscogsNativeReleaseSearchResult.Success(DiscogsNativeReleaseSearchPage(
                    listOf(
                        DiscogsNativeReleaseSearchHit(9, "Other - Example"),
                        DiscogsNativeReleaseSearchHit(10, "Example - Work"),
                        DiscogsNativeReleaseSearchHit(11, "Example - Work"),
                        DiscogsNativeReleaseSearchHit(12, "Example - Another"),
                        DiscogsNativeReleaseSearchHit(13, "Example - Later"),
                    ), null, false,
                ))
            },
            readRelease = { id ->
                reads += id
                val body = if (id == 10L)
                    """{"id":999,"title":"Wrong","artists":[{"id":1,"name":"Example"}]}"""
                else """{"id":$id,"title":"Verified","master_id":$id,
                    "artists":[{"id":2,"name":"Example (2)"}]}"""
                DiscogsLookupResult.Found(requireNotNull(DiscogsRecordParser.parse(
                    DiscogsLink(DiscogsKind.RELEASE, if (id == 10L) 999 else id), body)))
            },
        )
        assertEquals(listOf(10L, 11L, 12L), reads)
        assertTrue(resolved.corroboratedSelection)
        assertEquals(listOf(rows[1]), discogsSearchDistinctArtists("Example", resolved.rows))
    }

    @Test fun homonymResolutionNeverContactsSourceForExplicitSuffixOrRevokedLease() = runBlocking {
        val rows = listOf(
            DiscogsNativeArtistSearchRow(1, "Example"),
            DiscogsNativeArtistSearchRow(2, "Example (2)"),
        )
        var searches = 0
        suspend fun resolve(query: String, authorized: Boolean) = discogsResolveArtistSearchHomonyms(
            query, rows, stillCurrent = { authorized },
            searchReleases = { searches++; DiscogsNativeReleaseSearchResult.Unavailable },
            readRelease = { error("Unexpected hydration") },
        )
        assertEquals(rows, resolve("Example (2)", true).rows)
        assertEquals(rows, resolve("Example", false).rows)
        assertEquals(0, searches)
    }

    @Test fun homonymEvidenceCannotSurviveMidReadRevocationOrDuplicatePressings() = runBlocking {
        val rows = listOf(
            DiscogsNativeArtistSearchRow(1, "Example"),
            DiscogsNativeArtistSearchRow(2, "Example (2)"),
        )
        var current = true
        var revoke = false
        suspend fun resolve() = discogsResolveArtistSearchHomonyms("Example", rows,
            stillCurrent = { current },
            searchReleases = {
                DiscogsNativeReleaseSearchResult.Success(DiscogsNativeReleaseSearchPage(
                    (10L..12L).map { DiscogsNativeReleaseSearchHit(it, "Example - Work") },
                    null, false,
                ))
            },
            readRelease = { id ->
                if (revoke) current = false
                val artist = if (id == 12L) 1 else 2
                val name = if (artist == 1) "Example" else "Example (2)"
                DiscogsLookupResult.Found(requireNotNull(DiscogsRecordParser.parse(
                    DiscogsLink(DiscogsKind.RELEASE, id),
                    """{"id":$id,"title":"Work","master_id":$artist,
                        "artists":[{"id":$artist,"name":"$name"}]}""",
                )))
            },
        )
        // Two pressings from the same Master do not outweigh one independent work.
        assertEquals(rows, resolve().rows)
        revoke = true
        val revoked = resolve()
        assertEquals(rows, revoked.rows)
        assertFalse(revoked.corroboratedSelection)
        assertEquals(1, revoked.detailReads)
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
