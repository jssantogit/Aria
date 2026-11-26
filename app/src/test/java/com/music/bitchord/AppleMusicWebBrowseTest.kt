package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebBrowseTest {
    private val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val cap = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private fun gate(enabled: Boolean = true) = ProviderPolicyGate(ProviderCapabilityPolicy(
        listOf(ProviderDescriptor(key, setOf(cap), ProviderReadiness.READY)),
        mapOf(cap to listOf(ProviderChoice(key, enabled, 0))),
    ))
    private val grouping = """{"data":[{"relationships":{"tabs":{"data":[{"relationships":{"children":{"data":[
        {"attributes":{"name":"Lançamentos recentes"},"relationships":{"room":{"data":[{"id":"123","type":"rooms"}]}}},
        {"attributes":{"name":"Other"},"relationships":{"room":{"data":[{"id":"456"}]}}}
    ]}}}]}}}]}"""

    @Test fun linkedEmptyRoomIsDiscoverableAndAmbiguityIsNotGuessed() = runBlocking {
        var requests = 0
        val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { capability, path, args ->
            requests++
            assertEquals(cap, capability)
            assertEquals("/v1/editorial/br/groupings", path)
            assertEquals("music", args["name"])
            grouping
        })
        val denied = AppleMusicWebEditorialGateway(catalog, gate(false))
        assertEquals(ProviderCallResult.Denied, denied.browseRooms("br"))
        assertEquals(0, requests)
        val rooms = catalog.browseRooms("br")
        val shelf = AppleMusicWebHomeShelf.RECENT_RELEASES
        val resolved = resolveAppleMusicWebHomeRoom(rooms, "br", shelf)!!
        assertEquals("123", resolved.id)
        assertNull(resolveAppleMusicWebHomeRoom(rooms + resolved.copy(id = "777"), "br", shelf))
        assertNull(resolveAppleMusicWebHomeRoom(rooms, "fr", shelf))
        assertNull(resolveAppleMusicWebHomeRoom(rooms, "br", AppleMusicWebHomeShelf.TRENDING_SONGS))
    }

    @Test fun roomRetainsOnlyRequestedMetadataAndReportsUnresolvedRows() = runBlocking {
        val room = AppleMusicWebRoomRef("br", null, "123", "Recent Releases")
        val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { _, path, _ ->
            assertEquals("/v1/editorial/br/rooms/123/contents", path)
            """{"data":[{"id":"7","type":"albums","attributes":{"name":"Album"}},
                {"id":"8","type":"albums"},{"id":"pl.abc","type":"playlists"},{"id":"9","type":"music-videos"}]}"""
        })
        val gateway = AppleMusicWebEditorialGateway(catalog, gate())
        val outcome = (gateway.roomContents(room, AppleMusicWebKind.ALBUM)
            as ProviderCallResult.Success).value.outcome as AppleMusicWebOutcome.Partial
        assertEquals(listOf("7"), outcome.value.items.map { it.id })
        assertEquals(4, outcome.value.sourceRows)
        assertEquals(2, outcome.value.excludedRows)
        assertEquals(1, outcome.value.unresolvedReferences)
    }

    @Test fun wrongRoomOrStorefrontCursorFailsClosed() = runBlocking {
        val room = AppleMusicWebRoomRef("br", null, "123", "Recent Releases")
        for (next in listOf("/v1/editorial/fr/rooms/123/contents?offset=25",
            "/v1/editorial/br/rooms/456/contents?offset=25")) {
            val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { _, _, _ ->
                """{"data":[],"next":"$next"}"""
            })
            assertTrue(runCatching { catalog.roomContents(room, AppleMusicWebKind.ALBUM) }.isFailure)
        }
        assertFalse(AppleMusicWebOperationRegistry.allows(ProviderCapability.CATALOG_SEARCH, "/v1/editorial/br/groupings"))
    }

    @Test fun liveBrowseRooms() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AMS_BROWSE_LIVE") == "1")
        val gate = gate()
        val gateway = AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
            admission = RateLimitedProviderTransport { gate },
        )), gate)
        var failures = 0
        for (market in listOf("br", "fr", "us")) {
            val result = gateway.browseRooms(market)
            val rooms = ((result as? ProviderCallResult.Success)?.value?.outcome as? AppleMusicWebOutcome.Complete)?.value
            if (rooms.isNullOrEmpty()) failures++
            println("AMS_BROWSE|market=$market|rooms=" + (rooms?.size ?: 0))
            var resolved = 0
            for (shelf in AppleMusicWebHomeShelf.entries) {
                val room = resolveAppleMusicWebHomeRoom(rooms.orEmpty(), market, shelf)
                if (room == null) {
                    println("AMS_ROOM|market=$market|shelf=" + shelf.name + "|status=absent_or_ambiguous")
                    continue
                }
                resolved++
                val outcome = (gateway.roomContents(room, shelf.kind) as? ProviderCallResult.Success)?.value?.outcome
                val page = when (outcome) {
                    is AppleMusicWebOutcome.Complete -> outcome.value
                    is AppleMusicWebOutcome.Partial -> outcome.value
                    else -> null
                }
                if (outcome !is AppleMusicWebOutcome.Complete) failures++
                println("AMS_ROOM|market=$market|shelf=" + shelf.name + "|raw=" + (page?.sourceRows ?: 0) +
                    "|rows=" + (page?.items?.size ?: 0) + "|excluded=" + (page?.excludedRows ?: 0) +
                    "|unresolved=" + (page?.unresolvedReferences ?: 0) + "|complete=" + (outcome is AppleMusicWebOutcome.Complete))
            }
            if (resolved == 0) failures++
        }
        assertEquals(0, failures)
    }
}
