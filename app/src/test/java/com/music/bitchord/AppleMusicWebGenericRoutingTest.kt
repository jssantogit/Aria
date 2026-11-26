package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCatalog
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebEditorialGateway
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebSearchAdapter
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebTransport
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.CatalogSearchAdapter
import com.music.bitchord.data.providers.CatalogSearchKind
import com.music.bitchord.data.providers.CatalogSearchPage
import com.music.bitchord.data.providers.CatalogSearchRequest
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.orderedCatalogSearchAdapters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebGenericRoutingTest {
    private val apple = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val mb = BuiltInProviderKeys.MUSICBRAINZ
    private val itunes = BuiltInProviderKeys.ITUNES

    private fun gate(
        order: List<ProviderKey>,
        enabled: Set<ProviderKey> = order.toSet(),
    ): ProviderPolicyGate {
        val keys = listOf(mb, itunes, apple)
        val descriptors = keys.map {
            ProviderDescriptor(it, setOf(
                ProviderCapability.CATALOG_SEARCH,
                ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ), ProviderReadiness.READY)
        }
        return ProviderPolicyGate(ProviderCapabilityPolicy(descriptors, mapOf(
            ProviderCapability.CATALOG_SEARCH to order.mapIndexed { i, key ->
                ProviderChoice(key, enabled = key in enabled, order = i)
            },
            ProviderCapability.EDITORIAL_DISCOGRAPHY to keys.mapIndexed { i, key ->
                ProviderChoice(key, enabled = true, order = i)
            },
        )))
    }

    private fun fake(provider: ProviderKey): CatalogSearchAdapter =
        object : CatalogSearchAdapter {
            override val key = provider
            override val kinds = setOf(CatalogSearchKind.ARTIST, CatalogSearchKind.ALBUM)
            override suspend fun search(
                request: CatalogSearchRequest,
            ): ProviderCallResult<CatalogSearchPage> =
                ProviderCallResult.Denied
        }

    @Test fun genericCatalogOrderFollowsUserChoiceNotAnApplePriority() {
        val adapters = listOf(fake(apple), fake(itunes), fake(mb))
        assertEquals(listOf(mb, itunes, apple), orderedCatalogSearchAdapters(
            gate(listOf(mb, itunes, apple)), CatalogSearchKind.ARTIST, adapters,
        ).map { it.key })
        assertEquals(listOf(apple, mb, itunes), orderedCatalogSearchAdapters(
            gate(listOf(apple, mb, itunes)), CatalogSearchKind.ARTIST, adapters,
        ).map { it.key })
    }

    @Test fun disabledOrUnsupportedProviderCannotBeInsertedAsHiddenFallback() {
        val adapters = listOf(fake(mb), fake(apple))
        assertEquals(listOf(mb), orderedCatalogSearchAdapters(
            gate(listOf(apple, mb), enabled = setOf(mb)),
            CatalogSearchKind.ALBUM, adapters,
        ).map { it.key })
        assertTrue(orderedCatalogSearchAdapters(
            null, CatalogSearchKind.ARTIST, adapters,
        ).isEmpty())
        assertTrue(orderedCatalogSearchAdapters(
            gate(listOf(apple, mb)), CatalogSearchKind.TRACK, adapters,
        ).isEmpty())
        assertTrue(orderedCatalogSearchAdapters(
            gate(listOf(apple, mb), enabled = emptySet()),
            CatalogSearchKind.ARTIST, adapters,
        ).isEmpty())
    }

    @Test fun appleWebAdapterPublishesNativeIdentityNeverAnItunesAlias() = runBlocking {
        val calls = mutableListOf<String>()
        val source = AppleMusicWebTransport { capability, path, _ ->
            assertEquals(ProviderCapability.CATALOG_SEARCH, capability)
            calls += path
            """{"results":{"artists":{"data":[{"id":"121","type":"artists",
                "attributes":{"name":"Band","url":"https://music.apple.com/br/artist/id121"}}],
                "next":"/v1/catalog/br/search?offset=9"}}}"""
        }
        val adapter = AppleMusicWebSearchAdapter(AppleMusicWebEditorialGateway(
            AppleMusicWebCatalog(source), gate(listOf(apple, itunes, mb)),
        ))
        val result = adapter.search(CatalogSearchRequest(
            CatalogSearchKind.ARTIST, "Band", "br",
        ))
        assertTrue(result is ProviderCallResult.Success)
        val page = (result as ProviderCallResult.Success).value
        assertEquals(apple, page.provider)
        assertEquals(ProviderCapability.CATALOG_SEARCH, page.capability)
        assertEquals("br", page.storefront)
        assertEquals(apple, page.items.single().identity.provider)
        assertEquals("121", page.items.single().identity.nativeId)
        assertEquals("br", page.items.single().identity.storefront)
        assertEquals(CatalogSearchKind.ARTIST, page.items.single().identity.kind)
        assertTrue(page.hasMore)
        assertFalse(page.partial)
        assertEquals(listOf("/v1/catalog/br/search"), calls)
    }

    @Test fun continuationPublishesUniqueNativeIdsAndPreservesRawOverlap() = runBlocking {
        val policy = gate(listOf(apple))
        val offsets = mutableListOf<String?>()
        val source = AppleMusicWebTransport { _, _, args ->
            offsets += args["offset"]
            fun row(id: String) = """{"id":"$id","type":"artists","attributes":{"name":"Same name"}}"""
            val (ids, next) = when (args["offset"]) {
                null -> listOf("1", "2") to "9"
                "9" -> listOf("1", "2") to "18"
                "18" -> listOf("2", "3") to null
                else -> error("Unexpected cursor")
            }
            val cursor = next?.let { ""","next":"/v1/catalog/br/search?offset=$it"""" }.orEmpty()
            """{"results":{"artists":{"data":[""" + ids.joinToString(",") { row(it) } + "]" + cursor + "}}}"
        }
        val reader = com.music.bitchord.data.providers.CatalogSearchAdapterReader(policy, listOf(
            AppleMusicWebSearchAdapter(AppleMusicWebEditorialGateway(AppleMusicWebCatalog(source), policy)),
        ))
        val request = CatalogSearchRequest(CatalogSearchKind.ARTIST, "Same name", "br")
        val first = (reader.read(apple, request) as ProviderCallResult.Success).value
        val next = (reader.read(apple, request.copy(page = 2, continuation = first.continuation))
            as ProviderCallResult.Success).value
        assertEquals(listOf("1", "2"), first.items.map { it.identity.nativeId })
        assertEquals(listOf("3"), next.items.map { it.identity.nativeId })
        assertEquals(3, next.sourceOverlapCount)
        assertEquals(listOf(null, "9", "18"), offsets)
        assertFalse(next.hasMore)
    }

    @Test fun rankingPreservesAllNativeRowsAndSeparatesExactFromVariantsAndNoise() = runBlocking {
        val policy = gate(listOf(apple))
        val source = AppleMusicWebTransport { _, _, _ ->
            """{"results":{"songs":{"data":[
                {"id":"1","type":"songs","attributes":{"name":"Other Work","artistName":"Band"}},
                {"id":"2","type":"songs","attributes":{"name":"One (Live)","artistName":"Band"}},
                {"id":"3","type":"songs","attributes":{"name":"One (Remastered)","artistName":"Band"}},
                {"id":"4","type":"songs","attributes":{"name":"One","artistName":"Band"}},
                {"id":"5","type":"songs","attributes":{"name":"One","artistName":"Another"}}
            ]}}}"""
        }
        val adapter = AppleMusicWebSearchAdapter(AppleMusicWebEditorialGateway(AppleMusicWebCatalog(source), policy))
        for (query in listOf("One Band", "Band One")) {
            val result = adapter.search(CatalogSearchRequest(CatalogSearchKind.TRACK, query, "br"))
                as ProviderCallResult.Success
            val rows = result.value.items
            assertEquals(listOf("4", "3", "2"), rows.take(3).map { it.identity.nativeId })
            assertEquals(setOf("1", "2", "3", "4", "5"), rows.map { it.identity.nativeId }.toSet())
            assertEquals(listOf(4, 3, 2), rows.take(3).map { it.sourcePosition })
            assertEquals("One (Remastered)", rows[1].title)
        }
    }

    @Test fun artistRankingDoesNotMergeNamesakesOrChangeEqualRankOrder() = runBlocking {
        val policy = gate(listOf(apple))
        val source = AppleMusicWebTransport { _, _, _ ->
            """{"results":{"artists":{"data":[
                {"id":"1","type":"artists","attributes":{"name":"Ghost tribute"}},
                {"id":"2","type":"artists","attributes":{"name":"Ghost"}},
                {"id":"3","type":"artists","attributes":{"name":"GHOST"}},
                {"id":"4","type":"artists","attributes":{"name":"The Ghost"}}
            ]}}}"""
        }
        val adapter = AppleMusicWebSearchAdapter(AppleMusicWebEditorialGateway(AppleMusicWebCatalog(source), policy))
        val page = (adapter.search(CatalogSearchRequest(CatalogSearchKind.ARTIST, "Ghost", "br"))
            as ProviderCallResult.Success).value
        assertEquals(listOf("2", "3", "4", "1"), page.items.map { it.identity.nativeId })
    }

    @Test fun unauthorizedAppleWebAdapterCannotDispatch() = runBlocking {
        var calls = 0
        val adapter = AppleMusicWebSearchAdapter(AppleMusicWebEditorialGateway(
            AppleMusicWebCatalog(AppleMusicWebTransport { _, _, _ ->
                calls++
                "{}"
            }), gate(listOf(apple, mb), enabled = setOf(mb)),
        ))
        assertEquals(ProviderCallResult.Denied, adapter.search(
            CatalogSearchRequest(CatalogSearchKind.ALBUM, "Record", "br"),
        ))
        assertEquals(0, calls)
    }
}
