package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.AppleMusicWebNativeDetail
import com.music.bitchord.ui.screens.appleMusicWebAsSharedDetail
import com.music.bitchord.ui.screens.AmsArtistShelfSnapshot
import com.music.bitchord.ui.screens.appleMusicWebVerifiedRouteUrl
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

    @Test fun supplementalViewsNeverInferTypesOrAcceptForeignArtist() {
        val live = album.copy(id="40",title="Concert")
        val compilation = album.copy(id="41",title="Collection")
        val appearance = album.copy(id="42",title="Guest Release",artistName="Other")
        val conflict = album.copy(id="43",title="Conflicting")
        val extra = AmsArtistShelfSnapshot(artist.scopedId,mapOf(
            AppleMusicWebArtistShelf.LIVE_ALBUMS to listOf(live,conflict),
            AppleMusicWebArtistShelf.COMPILATIONS to listOf(compilation,conflict),
            AppleMusicWebArtistShelf.APPEARS_ON to listOf(appearance,live),
        ),true)
        val source = AppleMusicWebNativeDetail.Artist(AppleMusicWebArtist(
            artist,emptyList(),listOf(album),emptyList(),emptyList()))
        val detail = appleMusicWebAsSharedDetail(artist.scopedId,source,supplemental=extra)!!
        val shelves = detail.page.sections.associate { it.title to it.items.map { card -> card.browseId } }
        assertEquals(listOf(live.scopedId),shelves["Álbuns ao vivo"])
        assertEquals(listOf(compilation.scopedId),shelves["Coletâneas"])
        assertEquals(listOf(appearance.scopedId),shelves["Com participação em"])
        assertEquals(listOf(conflict.scopedId),shelves["Outros lançamentos"])
        assertNotNull(detail.notice)
        val foreign = appleMusicWebAsSharedDetail(artist.scopedId,source,
            supplemental=extra.copy(artistBrowseId="apple_music_web:br:artist:99"))!!
        assertFalse(foreign.page.sections.any { it.title == "Álbuns ao vivo" })
    }

    @Test fun albumRelatedReleasesRequireExactArtistViewAndMatchingCredit() {
        val related = album.copy(id="50",title="Another",artistId=artist.id)
        val other = related.copy(id="51",artistId="99")
        val differentCredit = related.copy(id="52",artistName="Different")
        val foreign = related.copy(id="53",storefront="us")
        val extra = AmsArtistShelfSnapshot(artist.scopedId,mapOf(
            AppleMusicWebArtistShelf.STUDIO_ALBUMS to listOf(album,related,other,differentCredit,foreign),
        ))
        val source = AppleMusicWebNativeDetail.Album(AppleMusicWebAlbum(album,listOf(song),listOf(artist),false))
        val detail = appleMusicWebAsSharedDetail(album.scopedId,source,supplemental=extra)!!
        assertEquals("Mais do artista",detail.page.sections.single().title)
        assertEquals(listOf(related.scopedId),detail.page.sections.single().items.map { it.browseId })
        assertEquals(album.storeUrl,detail.sourceStoreUrl)
        assertNull(appleMusicWebVerifiedRouteUrl(album.storeUrl,"itunes:br:album:20"))
        assertTrue(appleMusicWebAsSharedDetail(album.scopedId,source.copy(
            value=source.value.copy(artists=listOf(artist,artist.copy(id="99")))),
            supplemental=extra)!!.page.sections.isEmpty())
    }

    @Test fun featuredReleaseRequiresExactSourceViewNotFirstShelfGuess() {
        val latest = album.copy(id="99",title="Latest")
        val record = AppleMusicWebArtist(artist,listOf(song),listOf(album),
            emptyList(),emptyList(),latestRelease=latest)
        val source = AppleMusicWebNativeDetail.Artist(record)
        val detail = appleMusicWebAsSharedDetail(artist.scopedId,source)!!
        assertEquals(latest.scopedId,detail.featuredRelease?.browseId)
        assertEquals(latest.scopedId,
            com.music.bitchord.ui.appleMusicWebRelatedTarget(detail,latest.scopedId)?.browseId)
        assertNull(appleMusicWebAsSharedDetail(artist.scopedId,
            source.copy(value=record.copy(latestRelease=null)))?.featuredRelease)
        assertNull(appleMusicWebAsSharedDetail(artist.scopedId,
            source.copy(value=record.copy(latestRelease=latest.copy(storefront="us"))))?.featuredRelease)
    }

    @Test fun rankedArtistSongsUseSourceOrderAndVerifiedReleaseCaptions() {
        val top = song.copy(albumId=album.id,albumName=album.title,
            artworkUrl="https://is1-ssl.mzstatic.com/image/thumb/example/{w}x{h}bb.jpg")
        val second = song.copy(id="31",title="Second",albumId="999",albumName="Other album")
        val record = AppleMusicWebArtist(artist,listOf(top,second),listOf(album),emptyList(),emptyList())
        val page = appleMusicWebAsSharedDetail(artist.scopedId,AppleMusicWebNativeDetail.Artist(record))!!
        assertTrue(page.sourceArtistTracksRanked)
        assertEquals(listOf(top.scopedId,second.scopedId),page.metadataTracks.map { it.key })
        assertEquals("Album · 2025",page.metadataTracks.first().releaseCaption)
        assertEquals("Other album",page.metadataTracks.last().releaseCaption)
        assertNotNull(page.metadataTracks.first().artworkUri)
        val mismatch = record.copy(topSongs=listOf(top.copy(albumName="Different album")))
        assertEquals("Different album",appleMusicWebAsSharedDetail(artist.scopedId,
            AppleMusicWebNativeDetail.Artist(mismatch))!!.metadataTracks.single().releaseCaption)
    }

    @Test fun songLinksRequireExactIdentityAndNoUnrelatedParameters() {
        val key = "apple_music_web:br:song:30"
        assertEquals("https://music.apple.com/br/album/name/20?i=30",
            appleMusicWebVerifiedRouteUrl("https://music.apple.com/br/album/name/20?i=30",key))
        assertNull(appleMusicWebVerifiedRouteUrl("https://music.apple.com/br/album/name/20?i=31",key))
        assertNull(appleMusicWebVerifiedRouteUrl("https://music.apple.com/br/album/name/20?i=30&token=x",key))
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
