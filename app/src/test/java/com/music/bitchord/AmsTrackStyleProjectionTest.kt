package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.ui.*
import org.junit.Assert.*
import org.junit.Test

class AmsTrackStyleProjectionTest {
    @Test fun localizedPostStylesPreserveSpecificity() {
        assertTrue(appleMusicWebGenreNameMatches("Pós-punk","post-punk"))
        assertTrue(appleMusicWebGenreNameMatches("Pós-rock","post-rock"))
        val punk = AppleMusicWebItem("pl.397f9243a6f442eca2399c65402677e1",
            AppleMusicWebKind.PLAYLIST,"br","Pós-punk: essenciais",
            artistName="Apple Music: alternativo",playlistType="editorial")
        val rock = punk.copy(id="pl.95ac5e708e874dbd97ceb435554b25ef",
            title="Pós-rock: essenciais",artistName="Apple Music: indie")
        assertTrue(amsVerifiedSearchedStylePlaylist(punk,"post-punk","br"))
        assertTrue(amsVerifiedSearchedStylePlaylist(punk.copy(title="Pós-punk: joias ocultas"),"post-punk","br"))
        assertTrue(amsVerifiedSearchedStylePlaylist(rock,"post-rock","br"))
        assertFalse(amsVerifiedSearchedStylePlaylist(punk,"post-rock","br"))
        assertFalse(amsVerifiedSearchedStylePlaylist(rock,"post-punk","br"))
        assertFalse(amsExactStylePlaylistTitle("Rock: joias ocultas","post-rock"))
        assertFalse(amsExactStylePlaylistTitle("Drone Metal: essenciais","drone"))
    }

    @Test fun observedEditorialTitlesAllowNarrowQualifiersWithoutParentSubstitution() {
        fun playlist(title: String,credit: String) = AppleMusicWebItem("pl.example123",
            AppleMusicWebKind.PLAYLIST,"br",title,artistName=credit,playlistType="editorial")
        assertTrue(amsVerifiedSearchedStylePlaylist(playlist("Rock progressivo: essenciais","Apple Music: rock clássico"),"progressive rock","br"))
        assertTrue(amsVerifiedSearchedStylePlaylist(playlist("O melhor do dark ambient","Apple Music"),"dark ambient","br"))
        assertTrue(amsVerifiedSearchedStylePlaylist(playlist("Trap Stars Brasil","Apple Music: hip-hop/rap"),"trap","br"))
        assertTrue(amsVerifiedSearchedStylePlaylist(playlist("Drill de Nova York: essenciais","Apple Music: hip-hop/rap"),"drill","br"))
        assertTrue(amsVerifiedSearchedStylePlaylist(playlist("Post-Rock Essentials","Apple Music Indie"),"post-rock","br"))
        assertFalse(amsVerifiedSearchedStylePlaylist(playlist("O melhor do rock","Apple Music: rock"),"progressive rock","br"))
        assertFalse(amsVerifiedSearchedStylePlaylist(playlist("Trap Stars Brasil","Apple Music: hip-hop/rap"),"drill","br"))
        assertFalse(amsVerifiedSearchedStylePlaylist(playlist("O melhor do dark ambient","Apple Music"),"drone","br"))
        assertTrue(amsStylePublicPlaylistCandidates("trap","us").isEmpty())
        assertTrue(amsStylePublicPlaylistCandidates("boom bap","br").isEmpty())
    }

    @Test fun searchedPlaylistRequiresEditorialTypeExactStyleAndAppleCredit() {
        val playlist = AppleMusicWebItem("pl.example123",AppleMusicWebKind.PLAYLIST,"br",
            "Post-Punk Essentials",artistName="Apple Music Alternative",playlistType="editorial")
        assertTrue(amsVerifiedSearchedStylePlaylist(playlist,"post-punk","br"))
        assertFalse(amsVerifiedSearchedStylePlaylist(playlist.copy(playlistType="user-shared"),"post-punk","br"))
        assertFalse(amsVerifiedSearchedStylePlaylist(playlist.copy(artistName="Apple Music fan"),"post-punk","br"))
        assertFalse(amsVerifiedSearchedStylePlaylist(playlist.copy(title="Rock Essentials"),"post-punk","br"))
        assertFalse(amsVerifiedSearchedStylePlaylist(playlist.copy(storefront="us"),"post-punk","br"))
        assertFalse(amsVerifiedSearchedStylePlaylist(playlist,"hardcore punk","br"))
    }

    @Test fun exactAlbumMotionPrefersMatchingAspectAndRejectsForeignUrls() {
        val square = "https://mvod.itunes.apple.com/example/square.m3u8"
        val tall = "https://mvod.itunes.apple.com/example/tall.m3u8"
        val data = kotlinx.serialization.json.buildJsonObject {
            put("motionDetailSquare",kotlinx.serialization.json.buildJsonObject {
                put("video",kotlinx.serialization.json.JsonPrimitive(square))
            })
            put("motionDetailTall",kotlinx.serialization.json.buildJsonObject {
                put("video",kotlinx.serialization.json.JsonPrimitive(tall))
            })
            put("motionDetailRaw",kotlinx.serialization.json.buildJsonObject {
                put("url",kotlinx.serialization.json.JsonPrimitive("https://example.org/wrong.m3u8"))
            })
        }
        assertEquals(square to tall,appleMusicWebAlbumMotion(data,false))
        assertEquals(tall to square,appleMusicWebAlbumMotion(data,true))
        assertNull(appleMusicWebAlbumMotion(null,false))
    }

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
