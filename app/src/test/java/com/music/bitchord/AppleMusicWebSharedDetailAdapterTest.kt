package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.AppleMusicWebNativeDetail
import com.music.bitchord.ui.screens.appleMusicWebAsSharedDetail
import com.music.bitchord.ui.screens.appleMusicWebVerifiedStoreUrl
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebSharedDetailAdapterTest {
    private val artist = AppleMusicWebItem("10",AppleMusicWebKind.ARTIST,"br","Artist")
    private val album = AppleMusicWebItem("20",AppleMusicWebKind.ALBUM,"br","Album",
        artistName="Artist",releaseDate="2025-01-02",storeUrl="https://music.apple.com/br/album/album/20")
    private val song = AppleMusicWebItem("30",AppleMusicWebKind.SONG,"br","Song",
        artistName="Artist",trackNumber=1,discNumber=2,durationMillis=123456)
    @Test fun albumPreservesExactMetadataAndNeverCreatesPlayableSongs() {
        val video = song.copy(id="31",kind=AppleMusicWebKind.MUSIC_VIDEO)
        val foreign = song.copy(id="32",storefront="us")
        val source = AppleMusicWebNativeDetail.Album(AppleMusicWebAlbum(album,
            listOf(song,video,foreign,song),listOf(artist),false))
        val detail = appleMusicWebAsSharedDetail(album.scopedId,source)!!
        assertEquals(BrowseType.ALBUM,detail.page.type)
        assertEquals(album.scopedId,detail.page.browseId)
        assertEquals("Artist • 2025",detail.page.subtitle)
        assertEquals(artist.scopedId,detail.albumArtistBrowseId)
        assertEquals("Artist",detail.albumArtistDisplayName)
        assertTrue((detail.page.songs as UiState.Success).data.isEmpty())
        val track = detail.metadataTracks.single()
        assertEquals(song.scopedId,track.key)
        assertEquals(123456L,track.durationMs)
        assertEquals(2,track.discNumber)
        assertNull(detail.verifiedArtistMbid)
        assertNull(appleMusicWebAsSharedDetail("itunes:br:album:20",source))
        assertNull(appleMusicWebAsSharedDetail("apple_music_web:us:album:20",source))
        assertNull(appleMusicWebAsSharedDetail("apple_music_web:br:artist:20",source))
        assertNull(appleMusicWebAsSharedDetail("apple_music_web:br:album:21",source))
        assertNotNull(appleMusicWebAsSharedDetail(album.scopedId,source,true)?.notice)
        val wrong = source.copy(value=source.value.copy(artists=listOf(artist.copy(title="Namesake"))))
        assertNull(appleMusicWebAsSharedDetail(album.scopedId,wrong)?.albumArtistBrowseId)
    }

    @Test fun artistUsesNativeMembershipAndDoesNotInventReleaseTypes() {
        val short = album.copy(id="21",title="Short")
        val conflict = album.copy(id="22",title="Conflict")
        val similar = artist.copy(id="11",title="Related")
        val source = AppleMusicWebNativeDetail.Artist(AppleMusicWebArtist(artist,
            listOf(song),listOf(album,conflict),listOf(short,conflict),
            listOf(artist,similar,similar.copy(storefront="us")),
            featuredAlbums=listOf(album)))
        val detail = appleMusicWebAsSharedDetail(artist.scopedId,source)!!
        assertEquals(BrowseType.ARTIST,detail.page.type)
        val shelves = detail.page.sections.associate { it.title to it.items.map { card -> card.browseId } }
        assertEquals(listOf(album.scopedId),shelves["Álbuns"])
        assertEquals(listOf(short.scopedId),shelves["Singles e EPs"])
        assertEquals(listOf(conflict.scopedId),shelves["Outros lançamentos"])
        assertEquals(listOf(similar.scopedId),shelves["Artistas semelhantes"])
        assertEquals(song.scopedId,detail.metadataTracks.single().key)
        assertTrue(detail.page.sections.flatMap { it.items }.all { it.videoId == null })
        assertTrue((detail.page.songs as UiState.Success).data.isEmpty())
        assertNull(detail.verifiedArtistMbid)
    }

    @Test fun storeLinkMustBelongToExactNativeProduct() {
        assertEquals(album.storeUrl,appleMusicWebVerifiedStoreUrl(album))
        for (url in listOf("https://music.apple.com/us/album/album/20",
            "https://music.apple.com/br/album/album/21",
            "https://music.apple.com/br/artist/artist/20",
            "https://music.apple.com/br/album/album/20?token=private",
            "https://music.apple.com.evil.test/br/album/album/20")) {
            assertNull(appleMusicWebVerifiedStoreUrl(album.copy(storeUrl=url)))
        }
    }
}
