package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebBatch
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebBatchResult
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCacheContract
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCatalog
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCompleteness
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebHttpException
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebOutcome
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebTransport
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderCapabilityRegistry
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class AppleMusicWebFoundationTest {
    @Test fun defaultInventoryIsBlockedEvenWhenUserExplicitlyEnablesAllCapabilities() {
        val inventory = ProviderCapabilityRegistry.inventory()
        val rows = inventory.filter { it.key == BuiltInProviderKeys.APPLE_MUSIC_WEB }
        assertEquals(setOf(ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ProviderCapability.CHART_MOST_PLAYED), rows.map { it.capability }.toSet())
        assertTrue(rows.all { it.readiness == ProviderReadiness.BLOCKED })
        val choices = rows.groupBy { it.capability }.mapValues { (_, items) ->
            listOf(ProviderChoice(items.first().key, enabled = true, order = 0))
        }
        val policy = ProviderCapabilityPolicy(ProviderCapabilityRegistry.descriptorsFrom(inventory), choices)
        val gate = ProviderPolicyGate(policy)
        assertNull(gate.open(ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.APPLE_MUSIC_WEB))
        assertNull(gate.open(ProviderCapability.EDITORIAL_DISCOGRAPHY, BuiltInProviderKeys.APPLE_MUSIC_WEB))
        assertNull(gate.open(ProviderCapability.CHART_MOST_PLAYED, BuiltInProviderKeys.APPLE_MUSIC_WEB))
    }

    @Test fun cacheKeysSeparateMarketLanguageCapabilityAndNeverContainCredentials() {
        val path = "/v1/catalog/br/search"
        val args = mapOf("term" to "Música", "types" to "songs")
        val search = ProviderCapability.CATALOG_SEARCH
        val key = AppleMusicWebCacheContract.key(search, "br", "pt-BR", path, args)
        assertNotNull(key)
        assertEquals(key, AppleMusicWebCacheContract.key(search, "br", "pt-BR", path, args.entries.reversed().associate { it.key to it.value }))
        assertNotEquals(key, AppleMusicWebCacheContract.key(search, "br", "en-US", path, args))
        assertNotEquals(key, AppleMusicWebCacheContract.key(ProviderCapability.EDITORIAL_DISCOGRAPHY, "br", "pt-BR", path, args))
        assertNotEquals(key, AppleMusicWebCacheContract.key(search, "us", "pt-BR", "/v1/catalog/us/search", args))
        assertFalse(key!!.contains("Música"))
        assertNull(AppleMusicWebCacheContract.key(search, "br", "pt-BR", path, args + ("access_token" to "private")))
        assertNull(AppleMusicWebCacheContract.key(search, "br", "pt-BR", "/us/404", args))
    }

    @Test fun cacheRegistrySeparatesPublicEditorialAndRankingsFromPrivateRoutes() {
        val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
        val charts = ProviderCapability.CHART_MOST_PLAYED
        val search = ProviderCapability.CATALOG_SEARCH
        fun key(cap: ProviderCapability,path: String,args: Map<String,String>,market:String="br") =
            AppleMusicWebCacheContract.key(cap,market,"pt-BR",path,args)
        val room = "/v1/editorial/br/rooms/123/contents"
        assertNotNull(key(editorial,room,mapOf("limit" to "25")))
        assertNull(key(search,room,mapOf("limit" to "25")))
        assertNull(key(editorial,room,mapOf("limit" to "25","session" to "private")))
        val curator = "/v1/catalog/br/apple-curators/976439543"
        assertNotNull(key(editorial,curator,mapOf("platform" to "web","include" to "grouping,playlists")))
        assertNull(key(editorial,"/v1/catalog/br/library",emptyMap()))
        val chart = "/v1/catalog/br/charts"
        val args = mapOf("chart" to "most-played","types" to "songs","limit" to "25")
        assertNotNull(key(charts,chart,args))
        assertNull(key(editorial,chart,args))
        assertNull(key(charts,chart,args + ("unexpected" to "field")))
        assertNotEquals(key(charts,chart,args),key(charts,chart,args + ("genre" to "21")))
        assertNotNull(key(charts,"/v1/catalog/us/charts",mapOf("chartId" to "119","limit" to "200"),"us"))
        assertNull(key(charts,chart,mapOf("chartId" to "119","limit" to "200")))
        assertNull(key(editorial,"/v1/catalog/br/albums/123/relationships/stream",emptyMap()))
        assertEquals(900_000L,AppleMusicWebCacheContract.maxAgeMs(charts,chart,args))
    }

    @Test fun truncatedAlbumIsPartialNotComplete() = runBlocking {
        val transport = AppleMusicWebTransport { _, path, params ->
            if (path.endsWith("/relationships/tracks")) {
                """{"data":[],"next":"/v1/catalog/br/albums/10/relationships/tracks?offset=3"}"""
            } else {
                """{"data":[{"id":"10","type":"albums","attributes":{"name":"Record"},
                "relationships":{"tracks":{"data":[{"id":"1","type":"songs","attributes":{"name":"One"}}],
                "next":"/v1/catalog/br/albums/10/relationships/tracks?offset=2"}}}]}"""
            }
        }
        val result = AppleMusicWebCatalog(transport).albumOutcome("10", "br")
        assertTrue(result is AppleMusicWebOutcome.Partial)
        val partial = result as AppleMusicWebOutcome.Partial
        assertEquals(AppleMusicWebCompleteness.UPSTREAM_INTERRUPTED, partial.reason)
        assertEquals(listOf("1"), partial.value.tracks.map { it.id })
    }

    @Test fun legacyAlbumValueDoesNotSilentlyDiscardPartialStatus() = runBlocking {
        val transport = AppleMusicWebTransport { _, path, _ ->
            if (path.endsWith("/relationships/tracks")) {
                """{"data":[],"next":"/v1/catalog/br/albums/10/relationships/tracks?offset=4"}"""
            } else {
                """{"data":[{"id":"10","type":"albums","attributes":{"name":"Record"},
                  "relationships":{"tracks":{"data":[{"id":"1","type":"songs","attributes":{"name":"One"}}],
                  "next":"/v1/catalog/br/albums/10/relationships/tracks?offset=2"}}}]}"""
            }
        }
        try {
            AppleMusicWebCatalog(transport).album("10", "br")
            fail("Callers of the non-typed API must not mistake an incomplete album for complete")
        } catch (_: com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebProtocolException) {
            // albumOutcome is the opt-in API for the partial tracks.
        }
    }

    @Test fun blockedAlbumPageDoesNotConvertToSilentPartial() = runBlocking {
        val transport = AppleMusicWebTransport { _, path, _ ->
            if (path.endsWith("/relationships/tracks")) throw AppleMusicWebHttpException(403)
            """{"data":[{"id":"10","type":"albums","attributes":{"name":"Record"},
                "relationships":{"tracks":{"data":[{"id":"1","type":"songs","attributes":{"name":"One"}}],
                "next":"/v1/catalog/br/albums/10/relationships/tracks?offset=2"}}}]}"""
        }
        try {
            AppleMusicWebCatalog(transport).albumOutcome("10", "br")
            fail("403 must propagate, not become a usable partial")
        } catch (exc: AppleMusicWebHttpException) {
            assertEquals(403, exc.status)
        }
    }

    @Test fun failedBatchItemDoesNotCancelSuccessfulSiblingsOrReorderResults() = runBlocking {
        val results = AppleMusicWebBatch(maxConcurrency = 2).fetch(
            listOf("one", "missing", "three")
        ) { key ->
            if (key == "missing") throw AppleMusicWebHttpException(404)
            "value:$key"
        }
        assertEquals(listOf("one", "missing", "three"), results.map { it.input })
        assertTrue(results[0] is AppleMusicWebBatchResult.Success)
        assertTrue(results[1] is AppleMusicWebBatchResult.Failure)
        assertEquals(404, (results[1] as AppleMusicWebBatchResult.Failure).httpStatus)
        assertEquals("value:three", (results[2] as AppleMusicWebBatchResult.Success).value)
    }

    @Test fun batchConcurrencyIsBoundedAndCancellationIsNeverSwallowed() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val barrier = CompletableDeferred<Unit>()
        val job = async {
            AppleMusicWebBatch(maxConcurrency = 2).fetch((1..8).map(Int::toString)) { input ->
                val running = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, running) }
                try {
                    barrier.await()
                    input
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        withTimeout(2_000) {
            while (peak.get() < 2) kotlinx.coroutines.yield()
        }
        assertEquals(2, peak.get())
        job.cancel()
        barrier.complete(Unit)
        try {
            job.await()
            fail("Cancellation must propagate to the caller")
        } catch (_: CancellationException) {
            assertTrue(active.get() <= 2)
        }
    }

    @Test fun batchUnexpectedProgrammingErrorsPropagate() = runBlocking {
        try {
            AppleMusicWebBatch().fetch(listOf("first")) { throw IllegalStateException("bug") }
            fail("Programming error must not be flattened into an empty result")
        } catch (exc: IllegalStateException) {
            assertEquals("bug", exc.message)
        }
    }
}
