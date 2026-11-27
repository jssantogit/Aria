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

    @Test fun trendingUsesOnlyExactEditorialRoomAndVerifiedSongLinks() {
        val kind = AppleMusicWebHomeShelf.TRENDING_SONGS
        val ref = room.copy(title="Músicas em alta")
        val song = AppleMusicWebItem("30",AppleMusicWebKind.SONG,"br","Song",
            artistName="Artist",storeUrl="https://music.apple.com/br/album/title/20?i=30")
        val other = song.copy(id="31",storeUrl="https://music.apple.com/br/song/title/31")
        val wrong = song.copy(id="32",storeUrl="https://music.apple.com/br/song/title/999")
        val page = AppleMusicWebRoomPage(ref,AppleMusicWebKind.SONG,
            listOf(song,wrong,album,song.copy(storefront="us")),4,0,0,null)
        val shelf = projectAppleMusicWebHomeRoom(ref,page,"br",kind)!!
        assertEquals("Músicas em alta",shelf.title)
        assertEquals(listOf(song.scopedId),shelf.items.map { it.browseId })
        assertTrue(shelf.items.all { it.videoId == null })
        val foreign = ref.copy(curatorId="10")
        assertNull(projectAppleMusicWebHomeRoom(foreign,page.copy(room=foreign),"br",kind))
        val extra = projectAppleMusicWebHomeRoom(ref,page.copy(items=listOf(other)),"br",kind)!!.items
        val paging = AmsEditorialPaging(additional=extra,additionalByHeading=mapOf("Músicas em alta" to extra))
        val combined = amsHomeWithTrendingContinuation(listOf(shelf),paging).single()
        assertEquals(listOf(song.scopedId,other.scopedId),combined.items.map { it.browseId })
        assertNull(amsEditorialContinuationShelf(listOf(shelf),paging,false))
    }

    @Test fun newHomePlaylistRailsRetainTypedIdentityAndContinuation() {
        val kind = AppleMusicWebHomeShelf.UPDATED_PLAYLISTS
        val ref = room.copy(title="Playlists atualizadas")
        val playlist = AppleMusicWebItem("pl.123456",AppleMusicWebKind.PLAYLIST,"br","Playlist")
        val other = playlist.copy(id="pl.654321")
        val page = AppleMusicWebRoomPage(ref,kind.kind,listOf(playlist,album,playlist.copy(storefront="us")),3,0,0,null)
        val first = projectAppleMusicWebHomeRoom(ref,page,"br",kind)!!
        assertEquals(listOf(playlist.scopedId),first.items.map { it.browseId })
        val extra = projectAppleMusicWebHomeRoom(ref,page.copy(items=listOf(other)),"br",kind)!!.items
        val paging = AmsEditorialPaging(additional=extra,additionalByHeading=mapOf(first.title to extra))
        assertEquals(listOf(playlist.scopedId,other.scopedId),
            amsHomeWithTrendingContinuation(listOf(first),paging).single().items.map { it.browseId })
        assertNull(projectAppleMusicWebHomeRoom(ref.copy(curatorId="1"),page,"br",kind))
    }

    @Test fun extraGenreRailsRequireCuratorAndRetainTheirHeading() {
        val curator = AppleMusicWebCuratorRef("976439543","br","Apple Music Metal")
        val ref = room.copy(curatorId=curator.id,title="Álbuns essenciais")
        val page = roomPage(ref,listOf(album,album.copy(storefront="us")))
        val projected = projectAmsCuratorExtraRoom(curator,"metal",page)!!
        assertEquals("Álbuns essenciais",projected.title)
        assertEquals(listOf(album.scopedId),projected.items.map { it.browseId })
        assertNull(projectAmsCuratorExtraRoom(curator,"pop",page))
        assertNull(projectAmsCuratorExtraRoom(curator,"metal",page.copy(room=ref.copy(curatorId="999"))))
        assertNull(projectAmsCuratorExtraRoom(curator,"metal",page.copy(room=ref.copy(title="Vídeos"))))
        val paging = AmsEditorialPaging(additional=projected.items,
            additionalByHeading=mapOf(projected.title to projected.items))
        assertEquals(listOf(projected),amsGenreWithPlaylistContinuation(emptyList(),paging))
        assertNull(amsEditorialContinuationShelf(listOf(projected),paging,true))
        val shared = AppleMusicWebCuratorRef("976439585","br","Apple Music Soul e Funk")
        assertNull(projectAmsCuratorExtraRoom(shared,"soul",page.copy(room=ref.copy(curatorId=shared.id))))
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

    @Test fun rejectedStyleIdentityRemainsExcludedUntilNewGeneration(): Unit = runBlocking {
        val g = gate()
        val curator = AppleMusicWebCuratorRef("976439543","br","Apple Music Metal")
        val request = AmsEditorialRequest.Room(
            AppleMusicWebRoomContinuation(room.copy(curatorId=curator.id,title="Lançamentos"),"cursor"),
            curator=curator,tag="heavy metal")
        val rejected = shelf.items.single().copy(browseId="apple_music_web:br:album:11")
        val extra = shelf.items.single().copy(browseId="apple_music_web:br:album:12")
        val rejectedId = requireNotNull(rejected.browseId)
        var loads = 0
        val session = AppleMusicWebEditorialUiSession(this,{ g },
            { error("unexpected home") },
            { _,_,_ ->
                loads++
                ProviderCallResult.Success(AppleMusicWebOutcome.Complete(AmsEditorialPage(
                    listOf(shelf),listOf(request),curator,
                    rejectedIds=if (loads == 1) setOf(rejectedId) else emptySet())))
            },
            readNext = {
                ProviderCallResult.Success(AppleMusicWebOutcome.Complete(AmsEditorialPage(
                    listOf(HomeShelf("Lançamentos",listOf(rejected,extra))),curator=curator)))
            })
        session.loadGenre("heavy metal"); yield()
        session.loadNext("heavy metal"); yield()
        assertEquals(setOf(rejectedId),session.state.value.paging["heavy metal"]!!.rejectedIds)
        assertEquals(listOf(extra),session.state.value.paging["heavy metal"]!!.additional)
        assertFalse(session.containsCard("heavy metal",rejected))
        session.loadGenre("heavy metal",force=true); yield()
        session.loadNext("heavy metal"); yield()
        assertEquals(listOf(rejected,extra),session.state.value.paging["heavy metal"]!!.additional)
        assertTrue(session.containsCard("heavy metal",rejected))
    }

    @Test fun partialRefreshCannotStarveIndependentContinuation(): Unit = runBlocking {
        val g = gate()
        val curator = AppleMusicWebCuratorRef("976439543","br","Apple Music Metal")
        val refresh = AmsEditorialRequest.RefreshGenre("heavy metal",curator)
        val releaseRoom = room.copy(curatorId=curator.id,title="Lançamentos")
        val other = AmsEditorialRequest.Room(AppleMusicWebRoomContinuation(releaseRoom,"cursor"),
            curator=curator,tag="heavy metal")
        val extra = shelf.items.single().copy(browseId="apple_music_web:br:album:11")
        val calls = mutableListOf<AmsEditorialRequest>()
        val session = AppleMusicWebEditorialUiSession(this,{ g },
            { error("unexpected home") },
            { _,_,_ -> ProviderCallResult.Success(AppleMusicWebOutcome.Complete(
                AmsEditorialPage(listOf(shelf),listOf(refresh,other),curator))) },
            readNext = { request ->
                calls += request
                if (request == refresh) ProviderCallResult.Success(AppleMusicWebOutcome.Partial(
                    AmsEditorialPage(listOf(shelf),listOf(refresh),curator),
                    AppleMusicWebCompleteness.UPSTREAM_INTERRUPTED))
                else ProviderCallResult.Success(AppleMusicWebOutcome.Complete(
                    AmsEditorialPage(listOf(HomeShelf("Lançamentos",listOf(extra))),curator=curator)))
            })
        session.loadGenre("heavy metal"); yield()
        session.loadNext("heavy metal"); yield()
        val failed = session.state.value.paging["heavy metal"]!!
        assertTrue(failed.failed)
        assertEquals(0,failed.pages)
        assertEquals(listOf(other,refresh),failed.pending)
        assertTrue(failed.additional.isEmpty())
        session.loadNext("heavy metal"); yield()
        val success = session.state.value.paging["heavy metal"]!!
        assertEquals(listOf(extra),success.additional)
        assertEquals(listOf(refresh),success.pending)
        assertEquals(1,success.pages)
        assertFalse(success.failed)
        assertEquals(listOf(refresh,other),calls)
    }

    @Test fun duplicateContinuationIdCannotPublishRejectedCardUnderAnotherHeading(): Unit = runBlocking {
        val g = gate()
        val request = AmsEditorialRequest.Room(AppleMusicWebRoomContinuation(room,"cursor"),
            homeKind=AppleMusicWebHomeShelf.RECENT_RELEASES)
        val extra = shelf.items.single().copy(browseId="apple_music_web:br:album:11")
        val rejected = extra.copy(title="Conflicting copy",videoId="invalid")
        val session = AppleMusicWebEditorialUiSession(this,{ g },
            { ProviderCallResult.Success(AppleMusicWebOutcome.Complete(AmsEditorialPage(listOf(shelf),listOf(request)))) },
            { _,_,_ -> error("unexpected genre") },
            readNext = {
                ProviderCallResult.Success(AppleMusicWebOutcome.Complete(AmsEditorialPage(listOf(
                    HomeShelf("Lançamentos recentes",listOf(extra)),
                    HomeShelf("Em breve",listOf(rejected))))))
            })
        session.loadHome(); yield()
        session.loadNext(null); yield()
        val paging = session.state.value.paging[null]!!
        assertEquals(listOf(extra),paging.additional)
        assertEquals(mapOf("Lançamentos recentes" to listOf(extra)),paging.additionalByHeading)
        assertFalse(session.containsCard(null,rejected))
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
