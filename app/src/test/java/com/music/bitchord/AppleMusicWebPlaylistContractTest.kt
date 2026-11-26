package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebPlaylistContractTest {
    @Test fun followsOnlyExactPlaylistTracksAndKeepsRepeatedSongs(): Unit = runBlocking {
        val path = "/v1/catalog/br/playlists/pl.abc123"
        val calls = mutableListOf<String>()
        val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { capability,actual,args ->
            assertEquals(ProviderCapability.EDITORIAL_DISCOGRAPHY,capability)
            calls += actual
            if (actual == path) """{"data":[{"id":"pl.abc123","type":"playlists","attributes":{"name":"Essentials","curatorName":"Apple Music"},
                "relationships":{"tracks":{"data":[{"id":"1","type":"songs","attributes":{"name":"One"}},
                {"id":"9","type":"music-videos","attributes":{"name":"Video"}}],"next":"$path/tracks?offset=2"}}}]}"""
            else {
                assertEquals("$path/tracks",actual)
                assertEquals("2",args["offset"])
                """{"data":[{"id":"2","type":"songs","attributes":{"name":"Two"}},{"id":"1","type":"songs","attributes":{"name":"One"}}]}"""
            }
        })
        val result = catalog.playlist("pl.abc123","br") as AppleMusicWebOutcome.Complete
        assertEquals(listOf("1","2","1"),result.value.tracks.map { it.id })
        assertEquals("Apple Music",result.value.identity.artistName)
        assertEquals(listOf(path,"$path/tracks"),calls)
    }

    @Test fun crossPlaylistCursorIsRejected(): Unit = runBlocking {
        val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { _,_,_ ->
            """{"data":[{"id":"pl.abc123","type":"playlists","attributes":{"name":"Essentials"},
              "relationships":{"tracks":{"data":[],"next":"/v1/catalog/br/playlists/pl.def456/tracks?offset=1"}}}]}"""
        })
        try {
            catalog.playlist("pl.abc123","br")
            fail("Foreign playlist cursor accepted")
        } catch (_: AppleMusicWebProtocolException) { }
    }
}
