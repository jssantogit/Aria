package com.music.bitchord

import com.music.bitchord.data.model.*
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.*
import com.music.bitchord.ui.screens.AppleSearchSection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebSearchUiSessionTest {
    private val ams = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val cap = ProviderCapability.CATALOG_SEARCH
    private fun gate() = ProviderPolicyGate(ProviderCapabilityPolicy(
        listOf(ProviderDescriptor(ams,setOf(cap),ProviderReadiness.READY)),
        mapOf(cap to listOf(ProviderChoice(ams,true,0))),
    ))
    private fun item(kind: CatalogSearchKind, id: String) =
        CatalogSearchItem(CatalogNativeIdentity(ams,kind,id,"br"),"Title $id","Credit",
            artistNativeId="10",albumNativeId="20",durationMs=1234L)

    @Test fun projectionRetainsNativeKindAndMetadata() {
        val album = amsSearchRow(item(CatalogSearchKind.ALBUM,"20"))!!
        assertEquals("apple_music_web:br:album:20",album.browseId)
        assertEquals("apple_music_web:br:artist:10",album.artistBrowseId)
        val track = amsSearchTrack(item(CatalogSearchKind.TRACK,"30"))!!
        assertEquals("apple_music_web:br:song:30",track.key)
        assertEquals("apple_music_web:br:album:20",track.albumBrowseId)
        assertEquals(1234L,track.durationMs)
        assertNull(amsSearchRow(item(CatalogSearchKind.TRACK,"30")))
        assertNull(amsSearchRow(item(CatalogSearchKind.ALBUM,"20").copy(
            identity=CatalogNativeIdentity(BuiltInProviderKeys.ITUNES,CatalogSearchKind.ALBUM,"20","br"))))
    }

    @Test fun previewEvidenceReachesSearchRowsWithoutChangingIdentityOrDuration() {
        val track = item(CatalogSearchKind.TRACK,"30")
        assertFalse(amsSearchTrack(track)!!.hasAudioPreview)
        val preview = amsSearchTrack(track.copy(hasAudioPreview=true))!!
        assertTrue(preview.hasAudioPreview)
        assertEquals("apple_music_web:br:song:30",preview.key)
        assertEquals(1234L,preview.durationMs)
        assertNull(amsSearchTrack(track.copy(hasAudioPreview=true,
            identity=CatalogNativeIdentity(BuiltInProviderKeys.ITUNES,CatalogSearchKind.TRACK,"30","br"))))
    }

    @Test fun cursorOwnershipAndFilterCoverage(): Unit = runBlocking {
        var g: ProviderPolicyGate? = gate()
        var order = listOf(ams)
        var finished = 0
        val requests = mutableListOf<CatalogSearchRequest>()
        val session = AppleMusicWebSearchUiSession(this,{ g },{ order },{ req ->
            requests += req
            val rows = if (req.kind == CatalogSearchKind.ALBUM)
                listOf(item(req.kind,if (req.page == 1) "20" else "21")) else emptyList()
            val next = if (req.kind == CatalogSearchKind.ALBUM && req.page == 1)
                CatalogSearchContinuation(ams,req.kind,req.query,"br",2,req.limit,"cursor") else null
            ProviderCallResult.Success(CatalogSearchPage(ams,cap,"br",req.kind,rows,
                req.page,next != null,false,next))
        },{ finished++ })
        session.select("query")
        yield()
        assertEquals(3,requests.size)
        assertEquals(1,finished)
        assertEquals(CatalogFilterCoverage.MATCHING,session.state.value.coverage(SearchFilter.ALBUMS))
        assertEquals(CatalogFilterCoverage.EMPTY,session.state.value.coverage(SearchFilter.SONGS))
        session.loadMore(AppleSearchSection.ALBUMS)
        yield()
        assertEquals("cursor",requests.last().continuation?.token)
        assertEquals(2,(session.state.value.albums as UiState.Success).data.size)
        order = emptyList()
        assertFalse(session.current())
        session.clear()
        g = null
        session.select("another")
        assertNull(session.state.value.query)
        assertEquals(4,requests.size)
    }
}
