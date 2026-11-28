package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.ui.*
import org.junit.Assert.*
import org.junit.Test

class AmsTrackStyleProjectionTest {
    @Test fun independentMetadataRetainsPriorityOverEditorialDuplicateWithoutInventingIdentity() {
        fun card(id: String,title: String,credit: String = "Artist") =
            com.music.bitchord.data.model.ShelfItem(title=title,subtitle=credit,
                thumbnailUrl=null,videoId=null,browseId="apple_music_web:br:album:$id")
        val verified = card("20","Album")
        val curated = card("21","Other album")
        val playlist = card("22","Playlist album")
        val conflict = card("23","Album A")
        val rows = listOf(
            com.music.bitchord.data.model.HomeShelf("Lançamentos",listOf(verified,curated,conflict.copy(title="Album B"))),
            com.music.bitchord.data.model.HomeShelf("Álbuns das playlists",listOf(verified,playlist)),
        )
        val result = amsMergeStyleMetadataShelf(
            com.music.bitchord.data.model.HomeShelf("Álbuns do estilo",listOf(verified,conflict)),
            rows,"br")
        assertEquals(listOf("Álbuns do estilo","Lançamentos","Álbuns das playlists"),result.shelves.map { it.title })
        assertEquals(listOf(verified,curated,playlist),result.shelves.flatMap { it.items })
        assertEquals(setOf(conflict.browseId),result.rejectedIds)
        assertEquals(setOf(verified.browseId,curated.browseId,playlist.browseId,conflict.browseId),result.excludedIds)
        assertEquals(rows,amsMergeStyleMetadataShelf(null,rows,"br").shelves)
        val foreign = verified.copy(browseId="apple_music_web:us:album:20")
        assertEquals(rows,amsMergeStyleMetadataShelf(
            com.music.bitchord.data.model.HomeShelf("Álbuns do estilo",listOf(foreign)),rows,"br").shelves)
        val allRejected = amsMergeStyleMetadataShelf(
            com.music.bitchord.data.model.HomeShelf("Álbuns do estilo",listOf(conflict)),
            listOf(com.music.bitchord.data.model.HomeShelf("Lançamentos",listOf(conflict.copy(title="Album B")))),"br")
        assertTrue(allRejected.shelves.isEmpty())
        assertEquals(setOf(conflict.browseId),allRejected.excludedIds)
        assertEquals(allRejected.rejectedIds,allRejected.excludedIds)
    }

    @Test fun rawTrackCandidatesDoNotExhaustDiscoveryBeforeAlbumAdmission() {
        val songs = (1..14).map { index ->
            AppleMusicWebItem(index.toString(),AppleMusicWebKind.SONG,"br","Track",
                albumId=(100+index).toString(),genreNames=listOf("Jazz Fusion"),
                storeUrl="https://music.apple.com/br/album/album/${100+index}?i=$index")
        }
        val albums = (101..114).map { AppleMusicWebItem(it.toString(),AppleMusicWebKind.ALBUM,"br","Album") }
        assertEquals(14,amsStyleTrackAlbumIds(songs,"br","jazz fusion").size)
        assertTrue(amsStyleNeedsTrackDiscovery(songs,"br","jazz fusion",emptyList()))
        assertTrue(amsStyleNeedsTrackDiscovery(songs,"br","jazz fusion",albums.take(6)))
        assertTrue(amsStyleNeedsTrackDiscovery(songs,"br","jazz fusion",albums.take(11)))
        assertFalse(amsStyleNeedsTrackDiscovery(songs,"br","jazz fusion",albums.take(12)))
        assertTrue(amsStyleNeedsTrackDiscovery(songs,"br","jazz fusion",
            albums.take(12) + albums.first().copy(title="Conflicting identity")))
        val later = songs + songs.first().copy(albumId="999",
            storeUrl="https://music.apple.com/br/album/album/999?i=1")
        assertTrue(amsStyleNeedsTrackDiscovery(later,"br","jazz fusion",albums.take(12)))
    }

    @Test fun editorialStyleGrammarAndCandidateRankingAreStrictAndBounded() {
        assertTrue(amsExactStylePlaylistTitle("Tendências: hard rock","hard rock"))
        assertTrue(amsExactStylePlaylistTitle("Novidades: Hard Rock","hard rock"))
        assertTrue(amsExactStylePlaylistTitle("Música barroca: essenciais","baroque"))
        assertFalse(amsExactStylePlaylistTitle("Grandes riffs","hard rock"))
        assertFalse(amsExactStylePlaylistTitle("Natal roqueiro","hard rock"))
        assertFalse(amsExactStylePlaylistTitle("Novidades: rock","hard rock"))
        assertFalse(amsExactStylePlaylistTitle("Novidades: hard rock e metal","hard rock"))
        assertFalse(amsExactStylePlaylistTitle("J.S. Bach: essenciais","baroque"))
        fun card(id: String,title: String) = com.music.bitchord.data.model.ShelfItem(
            title=title,subtitle="Apple Music",thumbnailUrl=null,videoId=null,browseId="apple_music_web:br:playlist:$id")
        val generic = card("pl.generic123","Grandes riffs")
        val exact = card("pl.exact123","Tendências: hard rock")
        val second = card("pl.second123","Novidades: Hard Rock")
        val unrelated = card("pl.other123","Natal roqueiro")
        assertEquals(listOf(exact,second),amsStyleCuratorPlaylistCandidates(
            listOf(generic,exact,unrelated,second),"hard rock"))
        assertEquals(listOf(second,generic),amsStyleCuratorPlaylistCandidates(
            listOf(generic,exact,exact.copy(title="Conflicting title"),second),"hard rock"))
    }

    @Test fun curatorPlaylistDetailRequiresUnchangedIdentityAndStyleEvidence() {
        val identity = AppleMusicWebItem("pl.example123",AppleMusicWebKind.PLAYLIST,"br",
            "Jazz Fusion",artistName="Apple Music Jazz",playlistType="editorial",
            storeUrl="https://music.apple.com/br/playlist/jazz-fusion/pl.example123")
        val card = com.music.bitchord.data.model.ShelfItem(title=identity.title,subtitle="Apple Music Jazz",
            thumbnailUrl=null,videoId=null,browseId=identity.scopedId)
        fun valid(item: AppleMusicWebItem) =
            amsStyleCuratorPlaylistDetailMatches(item,identity.id,"br","jazz fusion",card)
        assertTrue(valid(identity))
        assertFalse(valid(identity.copy(title="Jazz")))
        assertFalse(valid(identity.copy(artistName="Other curator")))
        assertFalse(valid(identity.copy(playlistType="user-shared")))
        assertFalse(valid(identity.copy(storefront="us")))
        assertFalse(valid(identity.copy(id="pl.other123")))
        assertFalse(valid(identity.copy(storeUrl=null)))
        val broad = identity.copy(title="Modern Jazz",genreNames=listOf("Jazz"))
        val matchingCard = card.copy(title="Modern Jazz")
        assertFalse(amsStyleCuratorPlaylistDetailMatches(broad,identity.id,"br","jazz fusion",matchingCard))
        assertTrue(amsStyleCuratorPlaylistDetailMatches(broad.copy(genreNames=listOf("Jazz Fusion")),
            identity.id,"br","jazz fusion",matchingCard))
    }

    @Test fun conflictingSongLinksCannotAuthorizeEitherAlbumAcrossCompositionSources() {
        val song = AppleMusicWebItem("1",AppleMusicWebKind.SONG,"br","Track",albumId="20",
            storeUrl="https://music.apple.com/br/album/album/20?i=1",genreNames=listOf("Jazz Fusion"))
        val other = song.copy(albumId="21",storeUrl="https://music.apple.com/br/album/album/21?i=1",
            genreNames=listOf("Jazz"))
        val sparse = song.copy(albumId=null,genreNames=emptyList())
        val playlist = AppleMusicWebItem("pl.example123",AppleMusicWebKind.PLAYLIST,"br","Editorial")
        assertTrue(amsStyleTrackAlbumIds(listOf(song,other),"br","jazz fusion").isEmpty())
        val inconsistentUrl = other.copy(albumId="20")
        assertNull(amsStyleSongAlbumId(inconsistentUrl,"br"))
        assertTrue(amsStyleTrackAlbumIds(listOf(song,inconsistentUrl),"br","jazz fusion").isEmpty())
        assertTrue(amsStyleSupplementalAlbumIds(listOf(song,inconsistentUrl),emptyList(),"br",emptySet()).isEmpty())
        assertTrue(amsStylePlaylistAlbumIds(AppleMusicWebPlaylist(playlist,listOf(song,inconsistentUrl)),"br").isEmpty())
        assertTrue(amsStyleTrackAlbumIds(listOf(song,song.copy(title="Different song")),"br","jazz fusion").isEmpty())
        assertTrue(amsStyleTrackAlbumIds(listOf(song.copy(artistName="Artist A"),
            song.copy(artistName="Artist B")),"br","jazz fusion").isEmpty())
        assertTrue(amsStyleSupplementalAlbumIds(listOf(song,other),emptyList(),"br",emptySet()).isEmpty())
        assertTrue(amsStylePlaylistAlbumIds(AppleMusicWebPlaylist(playlist,listOf(song,other)),"br").isEmpty())
        assertEquals(listOf("20"),amsStyleTrackAlbumIds(listOf(song,sparse),"br","jazz fusion"))
        assertTrue(amsStyleTrackAlbumIds(listOf(song.copy(isrc="A"),song.copy(isrc="B")),"br","jazz fusion").isEmpty())
    }

    @Test fun supplementalCandidatesDoNotTurnSearchRankOrParentLabelsIntoStyleEvidence() {
        val song = AppleMusicWebItem("1",AppleMusicWebKind.SONG,"br","Track",
            albumId="21",storeUrl="https://music.apple.com/br/album/album/21?i=1",
            genreNames=listOf("Jazz"))
        val broad = AppleMusicWebItem("20",AppleMusicWebKind.ALBUM,"br","Album",genreNames=listOf("Jazz"))
        val foreign = broad.copy(id="22",storefront="us",genreNames=listOf("Jazz Fusion"))
        assertTrue(amsStyleTrackAlbumIds(listOf(song),"br","jazz fusion").isEmpty())
        assertEquals(listOf("20","21"),amsStyleSupplementalAlbumIds(listOf(song),listOf(broad,foreign),"br",emptySet()))
        assertEquals(listOf("21"),amsStyleSupplementalAlbumIds(listOf(song),listOf(broad),"br",setOf("20")))
        assertTrue(amsStyleAlbumLabelIds(listOf(broad,foreign),"br","jazz fusion").isEmpty())
        val hydrated = broad.copy(genreNames=listOf("Jazz Fusion"),artistName="Artist",
            storeUrl="https://music.apple.com/br/album/album/20")
        val rows = listOf(broad,foreign,hydrated)
        val ids = amsStyleAlbumLabelIds(rows,"br","jazz fusion")
        assertEquals(listOf("20"),ids)
        val item = requireNotNull(amsStyleTrackAlbumShelf(ids,"br",rows)).items.single()
        assertEquals("Artist",item.subtitle)
        assertEquals(hydrated.storeUrl,item.appleStoreUrl)
        assertNull(amsStyleTrackAlbumShelf(ids,"br",rows + hydrated.copy(title="Conflicting title")))
    }

    @Test fun supplementalDiscoveryIsFairAndBounded() {
        val albums = (1..60).map { AppleMusicWebItem(it.toString(),AppleMusicWebKind.ALBUM,"br","Album") }
        val songs = (1..60).map { index ->
            AppleMusicWebItem(index.toString(),AppleMusicWebKind.SONG,"br","Track",
                albumId=(100+index).toString(),storeUrl="https://music.apple.com/br/album/album/${100+index}?i=$index")
        }
        val ids = amsStyleSupplementalAlbumIds(songs,albums,"br",emptySet())
        assertEquals(50,ids.size)
        assertEquals(listOf("1","101","2","102"),ids.take(4))
        assertEquals(2,ids.chunked(25).size)
    }

    @Test fun playlistPreviewUsesAdmissionBudgetAndPreservesEditorialProvenance() {
        val identity = AppleMusicWebItem("pl.example123",AppleMusicWebKind.PLAYLIST,"br","Editorial")
        val tracks = (1..110).map { index ->
            AppleMusicWebItem(index.toString(),AppleMusicWebKind.SONG,"br","Track $index",
                albumId=(100+index).toString(),
                storeUrl="https://music.apple.com/br/album/album/${100+index}?i=$index")
        }
        val playlist = AppleMusicWebPlaylist(identity,tracks)
        val ids = amsStylePlaylistAlbumIds(playlist,"br")
        assertEquals((101..200).map { it.toString() },ids)
        assertEquals(4,ids.chunked(25).size)
        assertEquals("102",amsStylePlaylistAlbumIds(playlist,"br",setOf("apple_music_web:br:album:101")).first())
        assertTrue(amsStylePlaylistAlbumIds(playlist,"us").isEmpty())
        val albums = (126..160).map { id ->
            AppleMusicWebItem(id.toString(),AppleMusicWebKind.ALBUM,"br","Album $id",artistName="Artist",
                storeUrl="https://music.apple.com/br/album/album/$id")
        }
        val conflicting = albums.first().copy(artistName="Different artist")
        val sparse = albums[1].copy(genreNames=listOf("Jazz"))
        val shelf = requireNotNull(amsStylePlaylistAlbumShelf(playlist,"br",albums + conflicting + sparse))
        assertEquals("Álbuns das playlists",shelf.title)
        assertEquals(24,shelf.items.size)
        assertEquals("apple_music_web:br:album:127",shelf.items.first().browseId)
        assertEquals("apple_music_web:br:album:150",shelf.items.last().browseId)
    }

    @Test fun trackDiscoveryPreservesCandidatesBeyondOneMetadataBatch() {
        val tracks = (1..35).map { index ->
            AppleMusicWebItem(index.toString(),AppleMusicWebKind.SONG,"br","Track $index",
                albumId=(100+index).toString(),
                storeUrl="https://music.apple.com/br/album/album/${100+index}?i=$index",
                genreNames=listOf("Jazz Fusion"))
        }
        val ids = amsStyleTrackAlbumIds(tracks,"br","jazz fusion")
        assertEquals((101..135).map { it.toString() },ids)
        assertEquals(listOf(25,10),ids.chunked(25).map { it.size })
    }

    @Test fun missingEarlyCandidatesCannotConsumeTheVisibleAlbumBudget() {
        val candidates = (1..35).map { it.toString() }
        val resolved = (12..35).map { index ->
            AppleMusicWebItem(index.toString(),AppleMusicWebKind.ALBUM,"br","Album $index",
                artistName="Artist",storeUrl="https://music.apple.com/br/album/album/$index")
        }
        val shelf = requireNotNull(amsStyleTrackAlbumShelf(candidates,"br",resolved))
        assertEquals(24,shelf.items.size)
        assertEquals("apple_music_web:br:album:35",shelf.items.last().browseId)
    }

    @Test fun conflictingAlbumIdentityIsRejectedButOptionalMetadataDifferencesAreAllowed() {
        val album = AppleMusicWebItem("20",AppleMusicWebKind.ALBUM,"br","Album",artistName="Artist",
            artistId="55",upc="123456789012",storeUrl="https://music.apple.com/br/album/album/20")
        val sparse = album.copy(artistId=null,upc=null,genreNames=listOf("Jazz"))
        assertEquals(1,requireNotNull(amsStyleTrackAlbumShelf(listOf("20"),"br",listOf(album,sparse))).items.size)
        assertNull(amsStyleTrackAlbumShelf(listOf("20"),"br",listOf(album,album.copy(title="Other release"))))
        assertNull(amsStyleTrackAlbumShelf(listOf("20"),"br",listOf(album,album.copy(artistId="56"))))
        assertNull(amsStyleTrackAlbumShelf(listOf("20"),"br",listOf(album,album.copy(upc="987654321098"))))
    }

    @Test fun droneEditorialCandidateNeverAdmitsDroneMetalOrForeignMarket() {
        val candidate = AppleMusicWebItem("pl.0ce234a90e744532b0561dbbb7b21d4f",
            AppleMusicWebKind.PLAYLIST,"br","O melhor do drone",artistName="Apple Music",playlistType="editorial")
        assertTrue(amsVerifiedSearchedStylePlaylist(candidate,"drone","br"))
        assertTrue(amsVerifiedSearchedStylePlaylist(candidate.copy(title="Best of Drone"),"drone","br"))
        assertFalse(amsVerifiedSearchedStylePlaylist(candidate.copy(title="Drone Metal: essenciais"),"drone","br"))
        assertFalse(amsVerifiedSearchedStylePlaylist(candidate.copy(storefront="ch"),"drone","br"))
        assertFalse(amsVerifiedSearchedStylePlaylist(candidate.copy(playlistType="user-shared"),"drone","br"))
        assertTrue(candidate.id in amsStylePublicPlaylistCandidates("drone","br"))
        assertTrue(amsStylePublicPlaylistCandidates("drone","ch").isEmpty())
    }

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
        assertEquals(listOf("20"),amsStyleTrackAlbumIds(listOf(track,parent,foreign,track),"br","jazz fusion"))
        assertTrue(amsStyleTrackAlbumIds(listOf(track,wrongLink),"br","jazz fusion").isEmpty())
        assertTrue(amsStyleTrackAlbumIds(listOf(track),"br","metal").isEmpty())
        val album = AppleMusicWebItem("20",AppleMusicWebKind.ALBUM,"br","Album",artistName="Artist",
            storeUrl="https://music.apple.com/br/album/title/20")
        val shelf = requireNotNull(amsStyleTrackAlbumShelf(listOf("20"),"br",listOf(album,parent,album.copy(storefront="us"))))
        assertEquals("Álbuns do estilo",shelf.title)
        assertEquals(listOf(album.scopedId),shelf.items.map { it.browseId })
        assertNull(amsStyleTrackAlbumShelf(listOf("21"),"br",listOf(album)))
    }
}
