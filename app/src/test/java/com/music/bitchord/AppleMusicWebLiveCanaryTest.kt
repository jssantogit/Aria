package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCatalog
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebPolicyTransport
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.RateLimitedProviderTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebProtocolException
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebHttpException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Network-only canary. Not part of hermetic tests; runs only through the
 * dedicated AMS live workflow with explicit opt-in.
 * Never prints access tokens, raw payloads, or artist names.
 */
class AppleMusicWebLiveCanaryTest {
    @Test fun rotatingPublicSearchAndArtistDetailHonorRealAppleContracts() = runBlocking {
        assumeTrue("Enable only via dedicated live-canary workflow",
            System.getenv("APPLE_MUSIC_WEB_LIVE") == "1")
        val seed = System.getenv("APPLE_MUSIC_WEB_CANARY_SEED")
            ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val queries = listOf("Shakira", "Miles Davis", "Gilberto Gil", "BTS", "Daft Punk")
        val countries = listOf("br", "fr", "us", "jp", "in")
        val capabilityKeys = setOf(
            ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY,
        )
        // An isolated test-only gate does not change app inventory/readiness.
        val policy = ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(
                key = BuiltInProviderKeys.APPLE_MUSIC_WEB,
                capabilities = capabilityKeys,
                readiness = ProviderReadiness.READY,
            )),
            choices = capabilityKeys.associateWith { cap ->
                listOf(ProviderChoice(BuiltInProviderKeys.APPLE_MUSIC_WEB, true, 0))
            },
        )
        val gate = ProviderPolicyGate(policy)
        val catalog = AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
            admission = RateLimitedProviderTransport { gate },
        ))
        // FR was common to both failing rotations; retain it during diagnosis
        // without losing the two normal rotating storefront observations.
        val probes = ((0..1).map { (seed + it) % countries.size } + 1).distinct()
        val failures = mutableListOf<String>()
        for (index in probes) {
            val country = countries[index]
            val query = queries[index]
            var stage = "search"
            try {
                val result = catalog.search(AppleMusicWebKind.ARTIST, query, country, limit = 5)
                assertTrue("No Apple artists returned", result.items.isNotEmpty())
                val first = result.items.first()
                assertTrue(first.scopedId.startsWith("apple_music_web:$country:artist:"))
                assertFalse(first.playable)
                stage = "artist_detail"
                val overview = catalog.artist(first.id, country)
                assertEquals(first.scopedId, overview.identity.scopedId)
                println("AMS_CANARY|storefront=$country|status=success")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val reason = when (failure) {
                    is AppleMusicWebHttpException -> "http_" + failure.status
                    is AppleMusicWebProtocolException -> when (failure.message) {
                        "Missing attributes", "Missing title", "Missing ID",
                        "Missing or ambiguous Apple detail", "Wrong Apple resource identity",
                        "Missing search results envelope", "Missing requested search result type",
                        "Invalid search data", "Search error envelope", "Invalid catalog JSON",
                        "Invalid array", "Invalid catalog item", "Oversized response",
                        "Apple web bundle unavailable", "Apple web token unavailable",
                        "Invalid artist", "Cyclic cursor" -> failure.message.orEmpty()
                        else -> "protocol_other"
                    }
                    is AssertionError -> "contract_assertion"
                    is java.io.IOException -> "io"
                    else -> "unexpected"
                }
                val diagnostic = "AMS_CANARY|storefront=$country|stage=$stage|reason=$reason"
                println(diagnostic)
                failures += diagnostic
            }
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }
}
