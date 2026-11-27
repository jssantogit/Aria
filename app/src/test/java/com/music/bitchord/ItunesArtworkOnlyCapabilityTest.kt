package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ItunesArtworkOnlyCapabilityTest {
    private val key = BuiltInProviderKeys.ITUNES
    private val art = ProviderCapability.RELEASE_ARTWORK
    private val search = ProviderCapability.CATALOG_SEARCH
    private val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY

    private fun policy(enabled: Set<ProviderCapability>) = ProviderCapabilityPolicy(
        descriptors = listOf(ProviderDescriptor(key, setOf(art, search, editorial), ProviderReadiness.READY)),
        choices = setOf(art, search, editorial).associateWith {
            listOf(ProviderChoice(key, it in enabled, 0))
        },
    )

    @Test fun artworkOnlyAdmitsExactProofButNotCatalogOrSearch() = runBlocking {
        val gate = ProviderPolicyGate(policy(setOf(art)))
        val calls = mutableListOf<Map<String, String>>()
        val transport = KtorItunesCatalogTransport(
            limiter = ItunesCatalogRateLimiter(sleep = {}),
            admission = RateLimitedProviderTransport { gate },
            testExchange = { path, params ->
                assertEquals("lookup", path)
                calls += params
                """{"resultCount":1,"results":[{"wrapperType":"collection","collectionType":"Album","collectionId":101,"artistId":99,"collectionName":"Album","artistName":"Artist","artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/a.jpg"}]}"""
            },
        )
        val client = ItunesCatalogClient(transport, artworkGateProvider = { gate })
        val album = client.lookupAlbumForArtwork(101) as ItunesCatalogResult.Success
        assertNotNull(album.items.single().artworkUrl)
        val upc = client.lookupUpcForArtwork("8801234567890") as ItunesCatalogResult.Success
        assertNotNull(upc.items.single().artworkUrl)
        assertEquals("101", calls[0]["id"])
        assertEquals("8801234567890", calls[1]["upc"])
        assertTrue(client.lookupAlbum(101) is ItunesCatalogResult.Failure)
        assertTrue(client.lookupUpc("8801234567890") is ItunesCatalogResult.Failure)
        assertTrue(client.search("Artist", ItunesCatalogEntity.ARTIST) is ItunesCatalogResult.Failure)
        assertEquals(2, calls.size)
        assertTrue(runCatching { transport.get("search", mapOf("term" to "Artist"), art) }.isFailure)
        assertTrue(runCatching { transport.get("lookup", mapOf("id" to "101", "entity" to "album"), art) }.isFailure)
        assertEquals(2, calls.size)
        gate.replace(policy(setOf(editorial)))
        assertTrue(client.lookupAlbumForArtwork(101) is ItunesCatalogResult.Failure)
        assertTrue(client.lookupUpcForArtwork("8801234567890") is ItunesCatalogResult.Failure)
        assertEquals(2, calls.size)
        val catalog = client.lookupAlbum(101) as ItunesCatalogResult.Success
        assertNull(catalog.items.single().artworkUrl)
        assertEquals(3, calls.size)
    }
}
