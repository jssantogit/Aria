package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.ui.*
import org.junit.Assert.*
import org.junit.Test

class AmsTrackStyleProjectionTest {
    @Test fun artistMotionAllowsOnlyKnownPublicAppleVisualHosts() {
        val url = "https://mvod.itunes.apple.com/itunes-assets/example/playlist.m3u8"
        assertEquals(url,com.music.bitchord.data.canvas.appleEditorialMotionUrl(url))
        val imageCdn = "https://is1-ssl.mzstatic.com/video/example.mp4"
        assertEquals(imageCdn,com.music.bitchord.data.canvas.appleEditorialMotionUrl(imageCdn))
        for (bad in listOf("https://example.org/movie.m3u8","https://mvod.itunes.apple.com.evil.org/movie.m3u8",
            "http://mvod.itunes.apple.com/movie.m3u8","https://mvod.itunes.apple.com/movie.m4a",
            "https://mvod.itunes.apple.com:8080/movie.m3u8","https://mvod.itunes.apple.com/movie.m3u8#fragment"))
            assertNull(com.music.bitchord.data.canvas.appleEditorialMotionUrl(bad))
    }

    @Test fun observedPortugueseLabelsAreRecognizedWithoutBroadParentAliases() {
        assertTrue(appleMusicWebGenreNameMatches("Fusão", "jazz fusion"))
        assertTrue(appleMusicWebGenreNameMatches("Bop", "bebop"))
        assertTrue(appleMusicWebGenreNameMatches("Jungle/Drum'n'bass", "drum and bass"))
        assertTrue(appleMusicWebGenreNameMatches("Era barroca", "baroque"))
        assertFalse(appleMusicWebGenreNameMatches("Metal", "death metal"))
        assertFalse(appleMusicWebGenreNameMatches("Death Metal/Black Metal", "death metal"))
        assertFalse(appleMusicWebGenreNameMatches("Alternativo", "alternative rock"))
    }

    @Test fun tallEditorialArtworkKeepsAspectAndRejectsSquareOrForeignArt() {
        val template = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/{w}x{h}bb.jpg"
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/a/800x1600bb.jpg",appleMusicWebTallHeroUrl(template,1000,2000))
        assertNull(appleMusicWebTallHeroUrl(template,1000,1000))
        assertNull(appleMusicWebTallHeroUrl(template.replace("is1-ssl.mzstatic.com","example.org"),1000,2000))
    }
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
