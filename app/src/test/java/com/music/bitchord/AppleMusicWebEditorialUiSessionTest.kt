package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.model.*
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebEditorialUiSessionTest {
    private val ams = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val mb = BuiltInProviderKeys.MUSICBRAINZ
    private val apple = BuiltInProviderKeys.ITUNES
    private val discogs = BuiltInProviderKeys.DISCOGS
    private val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private fun gate() = ProviderPolicyGate(ProviderCapabilityPolicy(
        listOf(ProviderDescriptor(ams,setOf(editorial),ProviderReadiness.READY)),
        mapOf(editorial to listOf(ProviderChoice(ams,true,0))),
    ))
    private val album = AppleMusicWebItem("10",AppleMusicWebKind.ALBUM,"br","Album",artistName="Artist")
    private val room = AppleMusicWebRoomRef("br",null,"123","Lançamentos recentes")
    private fun roomPage(ref: AppleMusicWebRoomRef, items: List<AppleMusicWebItem>) =
        AppleMusicWebRoomPage(ref,AppleMusicWebKind.ALBUM,items,items.size,0,0,null)
    private val shelf = HomeShelf("Lançamentos recentes",listOf(
        ShelfItem(title="Album",subtitle="Artist",thumbnailUrl=null,videoId=null,browseId=album.scopedId)
    ))

    @Test fun roomProjectionUsesOnlyExactGlobalAlbumMembership() {
        val kind = AppleMusicWebHomeShelf.RECENT_RELEASES
        val foreign = album.copy(id="11",storefront="us")
        val song = album.copy(id="12",kind=AppleMusicWebKind.SONG)
        val result = projectAppleMusicWebHomeRoom(room,roomPage(room,listOf(album,foreign,song)),"br",kind)!!
        assertEquals(listOf(album.scopedId),result.items.map { it.browseId })
        assertNull(result.items.single().videoId)
        assertNull(projectAppleMusicWebHomeRoom(room,roomPage(room,listOf(album)),"us",kind))
        val genre = room.copy(genreId="21")
        assertNull(projectAppleMusicWebHomeRoom(genre,roomPage(genre,listOf(album)),"br",kind))
        assertNull(projectAppleMusicWebHomeRoom(room,roomPage(genre,listOf(album)),"br",kind))
        assertNull(projectAppleMusicWebHomeRoom(room,roomPage(room,listOf(album)),"br",kind,setOf(album.scopedId)))
    }

    @Test fun dispatchWaitsForEveryEarlierSourceIncludingAms() {
        val order = listOf(discogs,ams,apple,mb)
        val miss = UiState.Success(emptyList<HomeShelf>())
        val noApple = UiState.Success(emptyList<com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease>())
        assertFalse(shouldStartAmsHomeEditorial(order,false,emptyList(),"Lançamentos",noApple,UiState.Loading))
        assertTrue(shouldStartAmsHomeEditorial(order,false,emptyList(),"Lançamentos",noApple,miss))
        assertFalse(shouldStartAppleHomeEditorial(order,false,emptyList(),"Lançamentos",miss,UiState.Loading))
        assertFalse(shouldStartMusicBrainzHomeEditorial(order,noApple,miss,UiState.Success(listOf(shelf))))
        assertTrue(shouldStartAppleHomeEditorial(order,false,emptyList(),"Lançamentos",miss,miss))
        val selected = projectCanonicalHomeEditorial(emptyList(),noApple,order,false,
            "Lançamentos","Descobrir","2026-11-26",discogsEditorial=miss,
            amsEditorial=UiState.Success(listOf(shelf)))
        assertEquals(ams,selected.provider)
        assertEquals(listOf(shelf),(selected.state as UiState.Success).data)
    }

    @Test fun continuationPreservesInitialRailsAndRetriesExactCursor(): Unit = runBlocking {
        val g = gate()
        val request = AmsEditorialRequest.Room(AppleMusicWebRoomContinuation(room,"cursor"),
            homeKind=AppleMusicWebHomeShelf.RECENT_RELEASES)
        val next = request.copy(position=request.position.copy(cursor="next"))
        val extra = shelf.items.single().copy(browseId="apple_music_web:br:album:11")
        var calls = 0
        val session = AppleMusicWebEditorialUiSession(this,{ g },
            { ProviderCallResult.Success(AppleMusicWebOutcome.Complete(AmsEditorialPage(listOf(shelf),listOf(request)))) },
            { _,_,_ -> error("unexpected genre") },
            readNext = { cursor ->
                calls++
                when (calls) {
                    1 -> { assertEquals(request,cursor); ProviderCallResult.Failed(java.io.IOException("temporary")) }
                    2 -> {
                        assertEquals(request,cursor)
                        ProviderCallResult.Success(AppleMusicWebOutcome.Complete(
                            AmsEditorialPage(listOf(shelf.copy(items=shelf.items+extra)),listOf(next))))
                    }
                    else -> {
                        assertEquals(next,cursor)
                        ProviderCallResult.Success(AppleMusicWebOutcome.Complete(AmsEditorialPage(emptyList())))
                    }
                }
            })
        session.loadHome(); yield()
        assertTrue(session.state.value.paging[null]!!.hasMore)
        session.loadNext(null); yield()
        assertTrue(session.state.value.paging[null]!!.failed)
        assertEquals(listOf(request),session.state.value.paging[null]!!.pending)
        session.loadNext(null); yield()
        assertEquals(listOf(shelf),(session.state.value.home as UiState.Success).data)
        assertEquals(listOf(extra),session.state.value.paging[null]!!.additional)
        assertTrue(session.containsCard(null,extra))
        assertEquals("Mais do catálogo",amsEditorialContinuationShelf(listOf(shelf),session.state.value.paging[null],false)?.title)
        session.loadNext(null); yield()
        assertFalse(session.state.value.paging[null]!!.hasMore)
        session.loadNext(null); yield()
        assertEquals(3,calls)
    }

    @Test fun runtimeAdmissionRefreshAndGateReplacement(): Unit = runBlocking {
        var g: ProviderPolicyGate? = null
        var calls = 0
        val wait = CompletableDeferred<Unit>()
        val session = AppleMusicWebEditorialUiSession(this,{ g },
            { calls++; ProviderCallResult.Success(AppleMusicWebOutcome.Complete(AmsEditorialPage(listOf(shelf)))) },
            { _,_,_ -> wait.await(); ProviderCallResult.Success(AppleMusicWebOutcome.Complete(AmsEditorialPage(listOf(shelf)))) })
        session.loadHome()
        yield()
        assertEquals(0,calls)
        g = gate()
        session.loadHome()
        yield()
        assertEquals(1,calls)
        assertTrue(session.state.value.home is UiState.Success)
        assertTrue(session.containsCard(null,shelf.items.single()))
        assertFalse(session.containsCard("soul",shelf.items.single()))
        assertFalse(session.containsCard(null,shelf.items.single().copy(title="Changed")))

        session.loadHome()
        yield()
        assertEquals(1,calls)
        session.loadHome(force=true)
        yield()
        assertEquals(2,calls)
        session.loadGenre("soul")
        yield()
        assertEquals(UiState.Loading,session.state.value.genres["soul"])
        g = gate()
        assertFalse(session.containsCard(null,shelf.items.single()))
        session.purgeRevoked()
        wait.complete(Unit)
        yield()
        assertEquals(UiState.Loading,session.state.value.home)
        assertTrue(session.state.value.genres.isEmpty())
    }
}
