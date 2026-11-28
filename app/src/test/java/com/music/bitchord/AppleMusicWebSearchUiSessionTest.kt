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

    @Test fun expansionUsesExactArtistAndKeepsTypedRowsIndependent(): Unit = runBlocking {
        val caps = setOf(cap,ProviderCapability.EDITORIAL_DISCOGRAPHY)
        val g = ProviderPolicyGate(ProviderCapabilityPolicy(
            listOf(ProviderDescriptor(ams,caps,ProviderReadiness.READY)),
            caps.associateWith { listOf(ProviderChoice(ams,true,0)) }))
        val artist = item(CatalogSearchKind.ARTIST,"10").copy(title="Exact Artist")
        val unrelated = item(CatalogSearchKind.TRACK,"30").copy(artistNativeId="99")
        val supplement = item(CatalogSearchKind.TRACK,"31")
        var requests = 0
        val session = AppleMusicWebSearchUiSession(this,{g},{listOf(ams)}, { req ->
            val rows = when (req.kind) {
                CatalogSearchKind.ARTIST -> listOf(artist)
                CatalogSearchKind.TRACK -> listOf(unrelated)
                else -> emptyList()
            }
            ProviderCallResult.Success(CatalogSearchPage(ams,cap,"br",req.kind,rows,req.page,false,false,null))
        },{},readArtistProducts={ primary ->
            assertEquals(artist,primary)
            requests++
            listOf(supplement,unrelated,supplement)
        })
        session.select("exact artist")
        yield(); yield()
        assertEquals(1,requests)
        val snapshot = session.state.value
        assertEquals(listOf("30"),(snapshot.tracks as UiState.Success).data.map { it.key.substringAfterLast(':') })
        assertEquals(listOf("31","30"),(snapshot.mixedTracks as UiState.Success).data.map { it.key.substringAfterLast(':') })
        assertTrue(session.admittedItems().contains(supplement))
        session.clear()
        assertFalse(session.admittedItems().contains(supplement))
        session.select("unmatched")
        yield(); yield()
        assertEquals(1,requests)
        assertTrue(session.state.value.artistProducts.isEmpty())
    }

    @Test fun mixedDedupRetainsPlayableMetadataAndRejectsContradictoryArtist() {
        val textual = item(CatalogSearchKind.TRACK,"30").copy(artistNativeId=null,hasAudioPreview=true,artworkUrl="https://example.com/art.jpg")
        val supplement = textual.copy(artistNativeId="10",hasAudioPreview=false,artworkUrl=null)
        val combined = amsSearchWithArtistProducts(listOf(textual),listOf(supplement),CatalogSearchKind.TRACK).single()
        assertEquals("10",combined.artistNativeId)
        assertTrue(combined.hasAudioPreview)
        assertEquals(textual.artworkUrl,combined.artworkUrl)
        assertTrue(amsSearchWithArtistProducts(listOf(textual.copy(hasAudioPreview=false)),listOf(supplement.copy(hasAudioPreview=true)),CatalogSearchKind.TRACK).single().hasAudioPreview)
        val conflicting = textual.copy(artistNativeId="99")
        assertEquals(listOf(conflicting),amsSearchWithArtistProducts(listOf(conflicting),listOf(supplement),CatalogSearchKind.TRACK))
        assertNull(textual.artistNativeId)
    }

    @Test fun exactArtistRelationshipRejectsContradictoryNativeIdentity() {
        val artist = item(CatalogSearchKind.ARTIST,"10")
        val kind = com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind.SONG
        val song = com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebItem("30",kind,"br","Track")
        assertEquals("10",amsArtistSearchProduct(artist,song,kind)?.artistNativeId)
        assertNull(amsArtistSearchProduct(artist,song.copy(artistId="99"),kind))
        assertNull(amsArtistSearchProduct(artist,song.copy(storefront="us"),kind))
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
