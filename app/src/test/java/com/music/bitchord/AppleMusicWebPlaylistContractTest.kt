package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.*
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

    @Test fun editorialPlaylistRouteIsExplicitAndPrivateRoutesRemainDenied() {
        val root = "/v1/catalog/br/playlists/pl.abc123"
        val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
        val search = ProviderCapability.CATALOG_SEARCH
        for (path in listOf(root,"$root/tracks","$root/relationships/tracks")) {
            assertTrue(AppleMusicWebOperationRegistry.allows(editorial,path))
            assertFalse(AppleMusicWebOperationRegistry.allows(search,path))
        }
        for (path in listOf("$root/relationships/library","/v1/me/library/playlists","$root/playback"))
            assertFalse(AppleMusicWebOperationRegistry.allows(editorial,path))
    }

    @Test fun exactCuratorPlaylistRoomKeepsSharedGenresIsolated() {
        val curator = AppleMusicWebCuratorRef("976439585","br","Apple Music Soul e funk")
        val room = AppleMusicWebRoomRef("br",null,"456","Playlists",curator.id)
        val playlist = AppleMusicWebItem("pl.abc123",AppleMusicWebKind.PLAYLIST,"br","Soul",
            genreNames=listOf("Soul"),artistName="Apple Music")
        val other = playlist.copy(id="pl.def456",title="Funk",genreNames=listOf("Funk"))
        val page = AppleMusicWebRoomPage(room,AppleMusicWebKind.PLAYLIST,
            listOf(playlist,other,playlist.copy(storefront="us")),3,0,0,null)
        val shelf = projectAppleMusicWebGenrePlaylistRoom(curator,"soul",page)!!
        assertEquals(listOf(playlist.scopedId),shelf.items.map { it.browseId })
        assertNull(projectAppleMusicWebGenrePlaylistRoom(curator,"soul",
            page.copy(room=room.copy(curatorId="999"))))
        assertNull(projectAppleMusicWebGenrePlaylistRoom(curator,"soul",
            page.copy(room=room.copy(title="Lançamentos"))))
        val extra = shelf.items.single().copy(browseId="apple_music_web:br:playlist:pl.xyz999")
        val paging = AmsEditorialPaging(additional=listOf(extra))
        assertEquals(listOf(shelf.items.single(),extra),
            amsGenreWithPlaylistContinuation(listOf(shelf),paging).single().items)
        assertNull(amsEditorialContinuationShelf(listOf(shelf),paging,true))
    }

    @Test fun missingRoomCoversHydrateOnlyExactLinkedPlaylistsInOneRequest(): Unit = runBlocking {
        val room = AppleMusicWebRoomRef("br",null,"456","Playlists","123")
        val calls = mutableListOf<String>()
        val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { cap,path,args ->
            assertEquals(ProviderCapability.EDITORIAL_DISCOGRAPHY,cap)
            calls += path
            if (path.endsWith("/contents")) """{"data":[
              {"id":"pl.abc123","type":"playlists","attributes":{"name":"Keep original title"}},
              {"id":"pl.def456","type":"playlists","attributes":{"name":"Already covered","artwork":{"url":"https://is1-ssl.mzstatic.com/image/thumb/x/{w}x{h}{c}.{f}"}}}
            ]}""" else {
                assertEquals("/v1/catalog/br/playlists",path)
                assertEquals(mapOf("ids" to "pl.abc123"),args)
                """{"data":[{"id":"pl.abc123","type":"playlists","attributes":{"name":"Different localized title","artwork":{"url":"https://is1-ssl.mzstatic.com/image/thumb/y/{w}x{h}{c}.{f}"}}}]}"""
            }
        })
        val result = catalog.roomContents(room,AppleMusicWebKind.PLAYLIST)
        assertEquals(2,calls.size)
        assertEquals(listOf("pl.abc123","pl.def456"),result.items.map { it.id })
        assertEquals("Keep original title",result.items.first().title)
        assertTrue(result.items.all { appleMusicWebArtworkUrl(it.artworkUrl) != null })
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/y/600x600bb.jpg",
            appleMusicWebArtworkUrl(result.items.first().artworkUrl))
        assertNull(appleMusicWebArtworkUrl("https://evil.example/{w}x{h}.{f}"))
        assertNull(appleMusicWebArtworkUrl("https://is1-ssl.mzstatic.com/image/{unknown}.{f}"))
    }

    @Test fun hydrationRejectsForeignIdentityRatherThanBorrowingArtwork(): Unit = runBlocking {
        val room = AppleMusicWebRoomRef("br",null,"456","Playlists","123")
        val catalog = AppleMusicWebCatalog(AppleMusicWebTransport { _,path,_ ->
            if (path.endsWith("/contents")) """{"data":[{"id":"pl.abc123","type":"playlists","attributes":{"name":"One"}}]}"""
            else """{"data":[{"id":"pl.foreign","type":"playlists","attributes":{"name":"Other"}}]}"""
        })
        try {
            catalog.roomContents(room,AppleMusicWebKind.PLAYLIST)
            fail("Foreign cover accepted")
        } catch (_: AppleMusicWebProtocolException) {}
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
