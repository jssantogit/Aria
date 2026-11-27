package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.ui.*
import org.junit.Assert.*
import org.junit.Test

class AmsTrackStyleProjectionTest {
    @Test fun exactTrackEvidenceDoesNotRequireCuratorOrPromoteParent() {
        val track = AppleMusicWebItem("1",AppleMusicWebKind.SONG,"br","Track",
            albumId="20",storeUrl="https://music.apple.com/br/album/title/20?i=1",
            genreNames=listOf("Jazz Fusion","Jazz"))
        val parent = track.copy(id="2",albumId="21",storeUrl="https://music.apple.com/br/album/title/21?i=2",
            genreNames=listOf("Jazz"))
        val foreign = track.copy(storefront="us")
        val wrongLink = track.copy(albumId="99")
        assertEquals(listOf("20"),amsStyleTrackAlbumIds(listOf(track,parent,foreign,wrongLink,track),"br","jazz fusion"))
        assertTrue(amsStyleTrackAlbumIds(listOf(track),"br","metal").isEmpty())
        val album = AppleMusicWebItem("20",AppleMusicWebKind.ALBUM,"br","Album",artistName="Artist",
            storeUrl="https://music.apple.com/br/album/title/20")
        val shelf = requireNotNull(amsStyleTrackAlbumShelf(listOf("20"),"br",listOf(album,parent,album.copy(storefront="us"))))
        assertEquals("Álbuns do estilo",shelf.title)
        assertEquals(listOf(album.scopedId),shelf.items.map { it.browseId })
        assertNull(amsStyleTrackAlbumShelf(listOf("21"),"br",listOf(album)))
    }
}
