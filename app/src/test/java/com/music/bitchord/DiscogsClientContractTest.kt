package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.providers.BuiltInProviderDescriptors
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.RateLimitedProviderTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscogsClientContractTest {
    @Test fun editionGateBlocksAllRealLookupEntryPointsBeforeHttp() = runBlocking {
        val discogs = BuiltInProviderKeys.DISCOGS
        val descriptors = listOf(BuiltInProviderDescriptors.discogs(
            tokenConfigured = true, implemented = true,
        ))
        fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
            descriptors = descriptors,
            choices = mapOf(ProviderCapability.EDITION_METADATA to listOf(
                ProviderChoice(discogs, enabled, 0),
            )),
        )
        val gate = ProviderPolicyGate(policy(false))
        var contacts = 0
        val client = DiscogsClient(
            enabled = true,
            transport = object : DiscogsTransport {
                override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                    contacts++
                    return DiscogsHttpResponse(404, "")
                }
            },
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0),
            admission = RateLimitedProviderTransport { gate },
        )
        assertEquals(DiscogsLookupResult.Disabled, client.fetchMaster(10))
        assertEquals(DiscogsLookupResult.Disabled, client.fetchRelease(10))
        assertEquals(DiscogsMasterVersionsResult.Disabled, client.fetchMasterVersions(10))
        assertEquals(0, contacts)
        gate.replace(policy(true))
        assertEquals(DiscogsLookupResult.Missing, client.fetchRelease(10))
        assertEquals(1, contacts)
        gate.replace(policy(false))
        assertEquals(DiscogsLookupResult.Disabled, client.fetchRelease(10))
        assertEquals(1, contacts)
    }

    @Test fun productionDisabledByDefaultNeverCallsTheNetwork() = runBlocking {
        var requests = 0
        val client = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                requests++
                return DiscogsHttpResponse(200, "{}")
            }
        })
        assertEquals(DiscogsLookupResult.Disabled, client.fetchRelease(42))
        assertEquals(0, requests)
    }

    @Test fun exactEditionRequestIsBoundedAndNeverDownloadsImagesOrMarketplaceData() = runBlocking {
        val calls = mutableListOf<String>()
        val transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                calls += path
                assertTrue(userAgent.startsWith("BitChord/"))
                return DiscogsHttpResponse(200, """{"id":42,"title":"Album",
                    "artists":[{"name":"Artist"}],"images":[{"uri":"https://restricted.example/art.jpg"}],
                    "marketplace":{"price":999}}""")
            }
        }
        val client = DiscogsClient(transport = transport, enabled = true)
        val result = client.fetchRelease(42)
        assertTrue(result is DiscogsLookupResult.Found)
        val record = (result as DiscogsLookupResult.Found).record
        assertEquals(listOf("Artist"), record.artistNames)
        assertFalse(record.toString().contains("restricted.example"))
        assertEquals(listOf("releases/42"), calls)
        assertEquals(DiscogsLookupResult.InvalidId, client.fetchRelease(-2))
    }

    @Test fun rateLimitAndAuthenticationFailuresAreNotConflatedWithNotFound() = runBlocking {
        val responses = ArrayDeque(listOf(
            DiscogsHttpResponse(429, "", retryAfterSeconds = 12),
            DiscogsHttpResponse(401, ""),
            DiscogsHttpResponse(403, ""),
            DiscogsHttpResponse(404, ""),
            DiscogsHttpResponse(503, ""),
            DiscogsHttpResponse(200, "<html>forbidden</html>"),
        ))
        val client = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse =
                responses.removeFirst()
        }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L))
        assertEquals(DiscogsLookupResult.Throttled(12), client.fetchRelease(42))
        assertEquals(DiscogsLookupResult.Unauthorized, client.fetchRelease(42))
        assertEquals(DiscogsLookupResult.Unauthorized, client.fetchRelease(42))
        assertEquals(DiscogsLookupResult.Missing, client.fetchRelease(42))
        assertEquals(DiscogsLookupResult.Unavailable, client.fetchRelease(42))
        assertEquals(DiscogsLookupResult.Malformed, client.fetchRelease(42))
    }

    @Test fun cancellationIsNotReportedAsAnUnavailableRemoteService() = runBlocking {
        val client = DiscogsClient(transport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                throw CancellationException("navigated back")
            }
        }, enabled = true)
        assertTrue(runCatching { client.fetchRelease(1) }.exceptionOrNull()
            is CancellationException)
    }

    @Test fun limiterSpacesRequestsWithoutPerEndpointBypass() = runBlocking {
        var now = 0L
        val starts = mutableListOf<Long>()
        val limiter = DiscogsRateLimiter(
            nowMillis = { now }, delayMillis = { now += it }, minimumIntervalMs = 3_000L,
        )
        repeat(3) {
            limiter.runLimited { starts += now }
        }
        assertEquals(listOf(0L, 3_000L, 6_000L), starts)
    }

    @Test fun sharedDefaultBudgetStaysBelowAnonymousRollingMinuteLimit() = runBlocking {
        // 25 anonymous requests/minute: ensure the twenty-fifth request
        // cannot begin within the first rolling minute, even if transport
        // responses are instantaneous. Authenticated searches still share
        // the same conservative limiter with anonymous Master/Release reads.
        var now = 0L
        val starts = mutableListOf<Long>()
        val limiter = DiscogsRateLimiter(
            nowMillis = { now }, delayMillis = { now += it },
        )
        repeat(25) { limiter.runLimited { starts += now } }
        assertEquals(25, starts.size)
        assertEquals(0L, starts.first())
        assertTrue(starts.last() >= 60_000L)
        assertTrue(starts.count { it < 60_000L } <= 24)
        assertEquals(
            24L * DISCOGS_SAFE_MIN_INTERVAL_MS, starts.last(),
        )
    }

    @Test fun credentialedDiscogsRequestsUseFasterSharedButBoundedSchedule() = runBlocking {
        var now = 0L
        val starts = mutableListOf<Long>()
        val limiter = DiscogsRateLimiter(
            nowMillis = { now }, delayMillis = { now += it },
        )
        repeat(60) {
            limiter.runHttpLimited(authenticated = true) {
                starts += now
                DiscogsHttpResponse(200, "")
            }
        }
        assertEquals(60, starts.size)
        assertEquals(0L, starts.first())
        assertEquals(59L * DISCOGS_SAFE_AUTH_INTERVAL_MS, starts.last())
        assertTrue(starts.count { it < 60_000L } <= 55)
        assertTrue(starts.zipWithNext().all { (a, b) ->
            b - a >= DISCOGS_SAFE_AUTH_INTERVAL_MS
        })
    }

    @Test fun anonymousAndCredentialedCallsShareOneClockWithDistinctBudgets() = runBlocking {
        var now = 0L
        val all = mutableListOf<Long>()
        val anonymous = mutableListOf<Long>()
        val limiter = DiscogsRateLimiter(
            nowMillis = { now }, delayMillis = { now += it },
        )
        repeat(120) { index ->
            val hasToken = index % 3 != 0
            limiter.runHttpLimited(authenticated = hasToken) {
                all += now
                if (!hasToken) anonymous += now
                DiscogsHttpResponse(200, "")
            }
        }
        assertTrue(all.zipWithNext().all { (a, b) ->
            b - a >= DISCOGS_SAFE_AUTH_INTERVAL_MS
        })
        assertTrue(anonymous.zipWithNext().all { (a, b) ->
            b - a >= DISCOGS_SAFE_MIN_INTERVAL_MS
        })
        // Sliding windows, not just minute buckets from time zero.
        assertTrue(all.all { t -> all.count { it >= t && it < t + 60_000 } <= 55 })
        assertTrue(anonymous.all { t ->
            anonymous.count { it >= t && it < t + 60_000 } <= 24
        })
    }

    @Test fun rejectedCredentialsDemoteScheduleUntilAuthenticatedSuccess() = runBlocking {
        var now = 0L
        val starts = mutableListOf<Long>()
        val limiter = DiscogsRateLimiter(
            nowMillis = { now }, delayMillis = { now += it },
        )
        for (status in listOf(401, 200, 200)) {
            limiter.runHttpLimited(authenticated = true) {
                starts += now
                DiscogsHttpResponse(status, "")
            }
        }
        assertEquals(listOf(0L, DISCOGS_SAFE_MIN_INTERVAL_MS,
            DISCOGS_SAFE_MIN_INTERVAL_MS + DISCOGS_SAFE_AUTH_INTERVAL_MS), starts)
    }

    @Test fun realHydrationClientForwardsOnlyValidCredentialsToOptInTransport() = runBlocking {
        var credential: String? = "A".repeat(32)
        val authenticatedCalls = mutableListOf<Pair<String, String>>()
        val anonymousCalls = mutableListOf<String>()
        val transport = object : DiscogsTransport {
            override val supportsTokenAuthorization: Boolean get() = true
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                anonymousCalls += path
                return DiscogsHttpResponse(404, "")
            }
            override suspend fun getAuthenticated(
                path: String, userAgent: String, token: String,
            ): DiscogsHttpResponse {
                authenticatedCalls += path to token
                return DiscogsHttpResponse(404, "")
            }
        }
        val client = DiscogsClient(
            transport = transport, enabled = true,
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            tokenProvider = { credential },
        )
        assertEquals(DiscogsLookupResult.Missing, client.fetchMaster(71))
        assertEquals(listOf("masters/71"), authenticatedCalls.map { it.first })
        assertEquals(listOf("A".repeat(32)), authenticatedCalls.map { it.second })
        credential = "bad invalid token"
        assertEquals(DiscogsLookupResult.Missing, client.fetchRelease(72))
        credential = null
        assertEquals(DiscogsLookupResult.Missing, client.fetchRelease(73))
        assertEquals(listOf("releases/72", "releases/73"), anonymousCalls)
        // The default mock transport intentionally cannot claim authenticated
        // source quota and never receives a personal token.
        var fallback = 0
        val anonymousTransport = object : DiscogsTransport {
            override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                fallback++
                return DiscogsHttpResponse(404, "")
            }
        }
        val fallbackClient = DiscogsClient(
            transport = anonymousTransport, enabled = true,
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
            tokenProvider = { "A".repeat(32) },
        )
        assertEquals(DiscogsLookupResult.Missing, fallbackClient.fetchMaster(74))
        assertEquals(1, fallback)
    }

    @Test fun tokenBearingTransportNeverUsesInjectedForeignBaseUrl() = runBlocking {
        val foreign = KtorDiscogsTransport(baseUrl = "https://not-discogs.example")
        val error = runCatching {
            foreign.getAuthenticated("masters/71", "test", "A".repeat(32))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun sourceOnlyCacheFailsClosedOnExpiredEvidenceAndClockRollback() {
        val link = DiscogsLink(DiscogsKind.RELEASE, 42)
        val record = DiscogsRecord(link, "Album", listOf("Artist"), "KR", 2024,
            emptyList(), emptyList(), emptyList(), emptyList(), false)
        val cache = DiscogsMemoryEvidenceStore(maxEntries = 2)
        val first = DiscogsObservation("541adf5a-48d4-4f7b-a7bb-afd3cea5af27",
            DiscogsKind.RELEASE, record, 1_000L)
        cache.put(first)
        assertEquals(first, cache.get(first.canonicalMbid, link, 1_001L))
        assertNull(cache.get(first.canonicalMbid, link, 999L))
        assertNull(cache.get(first.canonicalMbid, link,
            first.fetchedAtMillis + DiscogsObservation.TTL_MILLIS))
    }
}
