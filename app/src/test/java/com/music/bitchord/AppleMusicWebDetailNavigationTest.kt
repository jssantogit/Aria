package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebOutcome
import com.music.bitchord.data.model.*
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.*
import com.music.bitchord.ui.screens.AppleSharedDetail
import com.music.bitchord.ui.screens.NativeMetadataTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebDetailNavigationTest {
    private val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val capability = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
        listOf(ProviderDescriptor(key,setOf(capability),ProviderReadiness.READY)),
        mapOf(capability to listOf(ProviderChoice(key,true,0))),
    ))
    private fun lease() = ProviderReadLease.acquire(gate,listOf(capability to key))
    private val artist = "apple_music_web:br:artist:10"
    private val album = "apple_music_web:br:album:20"
    private fun card(id: String, title: String = "Title") =
        ShelfItem(title=title,subtitle="",thumbnailUrl=null,videoId=null,browseId=id)
    private fun page(id: String, items: List<ShelfItem> = emptyList()) = AppleSharedDetail(
        page = DetailPage(browseId=id,title="Title",subtitle="",thumbnailUrl=null,
            songs=UiState.Success(emptyList()),type=if (id == artist) BrowseType.ARTIST else BrowseType.ALBUM,
            sections=if (items.isEmpty()) emptyList() else listOf(HomeShelf("Álbuns",items))),
        albumArtistBrowseId=if (id == album) artist else null,
        albumArtistDisplayName=if (id == album) "Artist" else null,
    )

    @Test fun exactShelfNavigationAndBackReuseSourceSnapshot(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val nav = AppleMusicWebDetailNavigation(this,::lease) { id ->
            calls += id
            ProviderCallResult.Success(AppleMusicWebOutcome.Complete(
                if (id == artist) page(id,listOf(card(album))) else page(id)))
        }
        assertTrue(nav.openRoot(card(artist)))
        yield()
        assertEquals(artist,nav.stack.value.last().visible()?.page?.browseId)
        assertFalse(nav.openRelated("itunes:br:album:20"))
        assertFalse(nav.openRelated("apple_music_web:us:album:20"))
        assertFalse(nav.openRelated("apple_music_web:br:album:99"))
        assertTrue(nav.openRelated(album))
        yield()
        assertEquals(album,nav.stack.value.last().visible()?.page?.browseId)
        assertTrue(nav.back())
        assertEquals(artist,nav.stack.value.last().visible()?.page?.browseId)
        assertEquals(listOf(artist,album),calls)
        assertTrue(nav.openRelated(album))
        yield()
        assertTrue(nav.openRelated(artist))
        assertEquals(listOf(artist),nav.stack.value.map { it.route.browseId })
        assertEquals(listOf(artist,album,album),calls)
        assertTrue(nav.back())
        assertTrue(nav.stack.value.isEmpty())
    }

    @Test fun revokedAncestorCannotBeBypassedByStillAuthorizedChild(): Unit = runBlocking {
        val chart = ProviderCapability.CHART_MOST_PLAYED
        fun policy(chartEnabled: Boolean) = ProviderCapabilityPolicy(
            listOf(ProviderDescriptor(key,setOf(capability,chart),ProviderReadiness.READY)),
            mapOf(capability to listOf(ProviderChoice(key,true,0)),
                chart to listOf(ProviderChoice(key,chartEnabled,0))),
        )
        val owner = ProviderPolicyGate(policy(true))
        var first = true
        val nav = AppleMusicWebDetailNavigation(this,{
            ProviderReadLease.acquire(owner,listOf((if (first) chart else capability) to key))
        }) { id ->
            ProviderCallResult.Success(AppleMusicWebOutcome.Complete(
                if (id == artist) page(id,listOf(card(album))) else page(id)))
        }
        assertTrue(nav.openRoot(card(artist))); yield()
        first = false
        assertTrue(nav.openRelated(album)); yield()
        assertEquals(2,nav.stack.value.size)
        owner.replace(policy(false))
        assertFalse(nav.stack.value.first().lease.isCurrent())
        assertTrue(nav.stack.value.last().lease.isCurrent())
        assertFalse(nav.openRelated(artist))
        assertTrue(nav.stack.value.isEmpty())
    }

    @Test fun trackAlbumNavigationPreservesSnapshotAndRejectsForeignMembership(): Unit = runBlocking {
        val song = NativeMetadataTrack("apple_music_web:br:song:30","Song","Artist",null,null)
        val parent = page(artist).copy(metadataTracks=listOf(song))
        val calls = mutableListOf<String>()
        var target: String? = album
        var unavailable = 0
        val nav = AppleMusicWebDetailNavigation(this,::lease,resolveSongAlbum={ key ->
            calls += key
            target
        }) { id ->
            ProviderCallResult.Success(AppleMusicWebOutcome.Complete(if (id == artist) parent else page(id)))
        }
        assertTrue(nav.openRoot(card(artist))); yield()
        assertFalse(nav.openTrackAlbum(song.copy(title="Foreign")))
        assertFalse(nav.openTrackAlbum(song.copy(key="apple_music_web:us:song:30")))
        assertTrue(nav.openTrackAlbum(song)); yield(); yield()
        assertEquals(album,nav.stack.value.last().route.browseId)
        assertFalse(nav.openTrackAlbum(song))
        assertTrue(nav.back())
        assertEquals(parent,nav.stack.value.last().visible())
        target = "apple_music_web:us:album:20"
        assertTrue(nav.openTrackAlbum(song){ unavailable++ }); yield()
        assertEquals(1,unavailable)
        assertEquals(artist,nav.stack.value.last().route.browseId)
        assertEquals(listOf(song.key,song.key),calls)
    }

    @Test fun trackAlbumResolutionCannotReopenClosedPage(): Unit = runBlocking {
        val song = NativeMetadataTrack("apple_music_web:br:song:30","Song","Artist",null,null)
        val pending = CompletableDeferred<Unit>()
        val nav = AppleMusicWebDetailNavigation(this,::lease,resolveSongAlbum={
            pending.await()
            album
        }) { id -> ProviderCallResult.Success(AppleMusicWebOutcome.Complete(
            page(id).copy(metadataTracks=listOf(song)))) }
        assertTrue(nav.openRoot(card(artist))); yield()
        assertTrue(nav.openTrackAlbum(song)); yield()
        assertTrue(nav.back())
        pending.complete(Unit); yield()
        assertTrue(nav.stack.value.isEmpty())
    }

    @Test fun rejectsUnadmittedAndWrongIdentityAndCancelsClosedRoute(): Unit = runBlocking {
        var allowed = false
        val pending = CompletableDeferred<Unit>()
        val nav = AppleMusicWebDetailNavigation(this,{ if (allowed) lease() else null }) { id ->
            if (id == artist) pending.await()
            ProviderCallResult.Success(AppleMusicWebOutcome.Complete(page(artist)))
        }
        assertFalse(nav.openRoot(card(artist)))
        assertFalse(nav.openRoot(card("itunes:br:artist:10")))
        assertTrue(nav.stack.value.isEmpty())
        allowed = true
        assertTrue(nav.openRoot(card(artist)))
        yield()
        assertTrue(nav.back())
        pending.complete(Unit)
        yield()
        assertTrue(nav.stack.value.isEmpty())
        assertTrue(nav.openRoot(card(album)))
        yield()
        assertTrue(nav.stack.value.last().state is UiState.Error)
        assertEquals(album,nav.stack.value.last().visible()?.page?.browseId)
    }
}
