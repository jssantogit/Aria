package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.AppleEditorialCatalogRepository
import com.music.bitchord.data.catalog.metadata.AppleEditorialLoad
import com.music.bitchord.data.catalog.metadata.AppleEditorialReadState
import com.music.bitchord.data.catalog.metadata.ItunesCatalogClient
import com.music.bitchord.data.catalog.metadata.ItunesCatalogEntity
import com.music.bitchord.data.catalog.metadata.ItunesCatalogResult
import com.music.bitchord.data.catalog.metadata.ItunesCatalogTransport
import com.music.bitchord.data.catalog.metadata.ItunesCatalogSearchAttribute
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.catalog.metadata.ItunesPublicSearchSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleEditorialCatalogRepositoryTest {
    private class Source(val json: String) : ItunesCatalogTransport {
        var parameters = emptyMap<String, String>()
        override suspend fun get(path: String, parameters: Map<String, String>): String {
            this.parameters = parameters
            return json
        }
    }

    private fun album(id: Int, count: Int = 1) =
        """{"wrapperType":"collection","collectionType":"Album","collectionId":$id,
            "artistId":77,"artistName":"Artist","collectionName":"Release $id",
            "trackCount":$count,"releaseDate":"2024-03-25T00:00:00Z"}"""

    private fun track(id: Int, collection: Int) =
        """{"wrapperType":"track","kind":"song","trackId":$id,"collectionId":$collection,
            "artistName":"Artist","artistId":77,"trackName":"Song $id",
            "discNumber":1,"trackNumber":1}"""

    @Test fun artistTracksAreStrictlyScopedToExactAppleIdAndNeverIncludeVideos() =
        runBlocking {
            val source = Source("""{"resultCount":4,"results":[
                {"wrapperType":"artist","artistId":77,"artistName":"Artist"},
                ${track(101, 20)},
                {"wrapperType":"track","kind":"song","trackId":102,
                 "collectionId":21,"artistId":88,"artistName":"Namesake",
                 "trackName":"Unrelated"},
                {"wrapperType":"track","kind":"music-video","trackId":103,
                 "collectionId":22,"artistId":77,"artistName":"Artist",
                 "trackName":"Not a song"}
            ]}""")
            val result = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
                .artistTracks(77, limit = 40)
            assertEquals("song", source.parameters["entity"])
            assertEquals("77", source.parameters["id"])
            assertTrue(result is com.music.bitchord.data.catalog.metadata.AppleEditorialTrackLoad.Success)
            val songs = (result as
                com.music.bitchord.data.catalog.metadata.AppleEditorialTrackLoad.Success).tracks
            assertEquals(listOf(101L), songs.map { it.id })
            assertTrue(songs.all { !it.isPlayable && it.musicBrainzId == null })
        }

    @Test fun searchApiSupportsOfficialTwoHundredResultLimitAndReportsPossibleTruncation() =
        runBlocking {
            val rows = (1..200).joinToString(",") { album(it) }
            val source = Source("""{"resultCount":200,"results":[$rows]}""")
            val result = ItunesCatalogClient(source).search(
                "Artist", ItunesCatalogEntity.ALBUM, limit = 200,
            )
            assertTrue(result is ItunesCatalogResult.Success)
            assertTrue((result as ItunesCatalogResult.Success).possiblyTruncated)
            assertEquals(200, result.items.size)
            assertEquals("200", source.parameters["limit"])
        }

    @Test fun artistCatalogReportsLookupCapAsPossibleIncompleteDiscography() = runBlocking {
        val source = Source("""{"resultCount":201,"results":[
            {"wrapperType":"artist","artistId":77,"artistName":"Artist"},
            ${(1..200).joinToString(",") { album(it) }}
        ]}""")
        val result = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            .artistDiscography(artistId = 77)
        assertTrue(result is AppleEditorialLoad.Success)
        result as AppleEditorialLoad.Success
        assertEquals(200, result.releases.size)
        assertEquals(AppleEditorialReadState.POSSIBLY_TRUNCATED, result.state)
        assertEquals("200", source.parameters["limit"])
        assertTrue(result.releases.all { it.musicBrainzReleaseId == null })
    }

    @Test fun albumWithIncompleteTracklistIsVisibleButNeverDeclaredComplete() = runBlocking {
        val source = Source("""{"resultCount":2,"results":[
            ${album(123, count = 2)}, ${track(101, 123)}
        ]}""")
        val result = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            .albumDetail(123)
        assertTrue(result is AppleEditorialLoad.Success)
        result as AppleEditorialLoad.Success
        assertEquals(AppleEditorialReadState.PARTIAL_TRACKLIST, result.state)
        assertEquals(1, result.releases.single().tracks.size)
    }

    @Test fun partialAlbumDiagnosticsSeparateSongsVideosAndConflictingRows() = runBlocking {
        val source = Source("""{"resultCount":8,"results":[
            ${album(123, count = 5)},
            ${track(101, 123)},
            ${track(101, 123)},
            ${track(102, 123)},
            ${track(103, 999)},
            {"wrapperType":"track","kind":"music-video","trackId":104,"collectionId":123,"trackName":"Video"},
            {"wrapperType":"track","kind":"song","collectionId":123,"trackName":"No identity"},
            {"wrapperType":"track","kind":"song","trackId":105,"collectionId":123,"trackName":"No position"}
        ]}""")
        val result = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            .albumDetail(123) as AppleEditorialLoad.Success
        assertEquals(AppleEditorialReadState.PARTIAL_TRACKLIST, result.state)
        val diagnostic = requireNotNull(result.albumDiagnostics)
        assertEquals(5, diagnostic.declaredTrackCount)
        assertEquals(3, diagnostic.decodedTracks)
        assertEquals(1, diagnostic.positionlessTracks)
        assertEquals(1, diagnostic.duplicatePositions)
        val lookup = requireNotNull(diagnostic.lookup)
        assertEquals(8, lookup.declaredResults)
        assertEquals(8, lookup.rawRows)
        assertEquals(5, lookup.matchingSongRows)
        assertEquals(1, lookup.matchingNonSongRows)
        assertEquals(1, lookup.foreignSongRows)
        assertEquals(1, lookup.rejectedSongRows)
        assertEquals(1, lookup.removedDuplicateOrDisputedSongs)
    }

    @Test fun videoLookupCountsOnlyUniqueExactCollectionMembersAndNeverReturnsMedia() = runBlocking {
        val source = Source("""{"resultCount":7,"results":[
            ${album(123, count = 3)},
            ${track(101, 123)},
            {"wrapperType":"track","kind":"music-video","trackId":102,"collectionId":123,"trackName":"Video"},
            {"wrapperType":"track","kind":"music-video","trackId":103,"collectionId":123,"trackName":"Duplicate"},
            {"wrapperType":"track","kind":"music-video","trackId":103,"collectionId":123,"trackName":"Duplicate"},
            {"wrapperType":"track","kind":"music-video","trackId":104,"collectionId":999,"trackName":"Foreign"},
            {"wrapperType":"track","kind":"music-video","trackId":101,"collectionId":123,"trackName":"Song ID collision"}
        ]}""")
        val result = ItunesCatalogClient(source).observeAlbumMusicVideoRows(123) as ItunesCatalogResult.Success
        assertEquals("123", source.parameters["id"])
        assertEquals("musicVideo", source.parameters["entity"])
        val observation = requireNotNull(result.albumLookupObservation)
        assertEquals(4, observation.matchingNonSongRows)
        assertEquals(1, observation.verifiedMusicVideoRows)
        assertEquals(2, result.items.size)
        assertTrue(result.items.none { it.isPlayable })
        // Observation is not permission to mark an incomplete audio read complete.
        val detail = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            .albumDetail(123) as AppleEditorialLoad.Success
        assertEquals(AppleEditorialReadState.PARTIAL_TRACKLIST, detail.state)
        assertEquals(1, detail.releases.single().tracks.size)
    }

    @Test fun mixedLookupCanProveAudioCompletenessWithoutChangingDeclaredCollectionCount() = runBlocking {
        val calls = mutableListOf<String>()
        var mixedVariant = "valid"
        val first = """{"resultCount":2,"results":[${album(123, 2)},${track(101, 123)}]}"""
        val transport = object : ItunesCatalogTransport {
            override suspend fun get(path: String, parameters: Map<String, String>): String {
                val entity = requireNotNull(parameters["entity"])
                calls += entity
                assertEquals("123", parameters["id"])
                assertEquals("br", parameters["country"])
                if (entity == "song") return first
                assertEquals("musicTrack", entity)
                val video = """{"wrapperType":"track","kind":"music-video","trackId":102,"collectionId":123,"trackName":"Video"}"""
                val member = when (mixedVariant) {
                    "foreign" -> video.replace("\"collectionId\":123", "\"collectionId\":999")
                    "collision" -> video.replace("\"trackId\":102", "\"trackId\":101")
                    "unknown" -> video.replace("music-video", "other")
                    "song" -> track(102, 123).replace("\"trackNumber\":1", "\"trackNumber\":2")
                    else -> video
                }
                val original = if (mixedVariant == "conflict")
                    track(101, 123).replace("Song 101", "Other title") else track(101, 123)
                return """{"resultCount":3,"results":[${album(123, 2)},$original,$member]}"""
            }
        }
        val repository = AppleEditorialCatalogRepository(ItunesCatalogClient(transport))
        val verified = repository.albumDetail(123) as AppleEditorialLoad.Success
        assertEquals(listOf("song", "musicTrack"), calls)
        assertEquals(AppleEditorialReadState.COMPLETE, verified.state)
        assertEquals(2, verified.releases.single().trackCount)
        assertEquals(listOf(101L), verified.releases.single().tracks.map { it.id })
        assertEquals(1, verified.albumDiagnostics?.verifiedExcludedNonAudioRows)
        assertEquals(true, verified.albumDiagnostics?.mixedLookupVerified)
        val page = requireNotNull(com.music.bitchord.ui.appleNativeAlbumDetail(verified, "itunes:br:album:123"))
        assertFalse(page.partialTracklist)
        assertEquals(1, page.tracks.size)
        for (variant in listOf("foreign", "collision", "unknown", "conflict")) {
            mixedVariant = variant
            val partial = repository.albumDetail(123) as AppleEditorialLoad.Success
            assertEquals(variant, AppleEditorialReadState.PARTIAL_TRACKLIST, partial.state)
            assertEquals(false, partial.albumDiagnostics?.mixedLookupVerified)
            assertTrue(requireNotNull(com.music.bitchord.ui.appleNativeAlbumDetail(
                partial, "itunes:br:album:123")).partialTracklist)
        }
        mixedVariant = "song"
        val recovered = repository.albumDetail(123) as AppleEditorialLoad.Success
        assertEquals(AppleEditorialReadState.COMPLETE, recovered.state)
        assertEquals(listOf(101L, 102L), recovered.releases.single().tracks.map { it.id })
        assertEquals(0, recovered.albumDiagnostics?.verifiedExcludedNonAudioRows)
    }

    @Test fun albumFieldSongSearchRecoversOnlyExactCollectionMembers() = runBlocking {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        var variant = "valid"
        fun song(id: Int, position: Int, collection: Int = 123, title: String = "Release 123"): String =
            track(id, collection).replace("\"trackNumber\":1", "\"trackNumber\":$position")
                .dropLast(1) + ",\"collectionName\":\"$title\",\"trackCount\":2}"
        val transport = object : ItunesCatalogTransport {
            override suspend fun get(path: String, parameters: Map<String, String>): String {
                calls += path to parameters
                val original = song(101, 1)
                if (path == "lookup") return """{"resultCount":2,"results":[${album(123, 2)},$original]}"""
                assertEquals("search", path)
                assertEquals("albumTerm", parameters["attribute"])
                assertEquals("Release 123", parameters["term"])
                assertEquals("br", parameters["country"])
                assertEquals("song", parameters["entity"])
                assertEquals("200", parameters["limit"])
                val additional = when (variant) {
                    "foreign" -> song(102, 2, 999)
                    "wrongTitle" -> song(102, 2, title = "Another edition")
                    "position" -> song(102, 1)
                    "wrongTotal" -> song(102, 2).replace("\"trackCount\":2", "\"trackCount\":3")
                    else -> song(102, 2)
                }
                val observed = if (variant == "conflict")
                    original.replace("Song 101", "Contradictory title") else original
                return """{"resultCount":3,"results":[$observed,$additional,${song(201, 1, 999)}]}"""
            }
        }
        val repository = AppleEditorialCatalogRepository(ItunesCatalogClient(transport))
        val recovered = repository.albumDetail(123) as AppleEditorialLoad.Success
        assertEquals(listOf("song", "musicTrack", "song"), calls.map { it.second["entity"] })
        assertEquals(AppleEditorialReadState.COMPLETE, recovered.state)
        assertEquals(listOf(101L, 102L), recovered.releases.single().tracks.map { it.id })
        assertEquals(true, recovered.albumDiagnostics?.albumSongSearchAttempted)
        assertEquals(true, recovered.albumDiagnostics?.albumSongSearchVerified)
        assertEquals(0, recovered.albumDiagnostics?.verifiedExcludedNonAudioRows)
        assertFalse(requireNotNull(com.music.bitchord.ui.appleNativeAlbumDetail(
            recovered, "itunes:br:album:123")).partialTracklist)
        for (bad in listOf("foreign", "wrongTitle", "position", "wrongTotal", "conflict")) {
            variant = bad
            val partial = repository.albumDetail(123) as AppleEditorialLoad.Success
            assertEquals(bad, AppleEditorialReadState.PARTIAL_TRACKLIST, partial.state)
            assertEquals(listOf(101L), partial.releases.single().tracks.map { it.id })
            assertEquals(false, partial.albumDiagnostics?.albumSongSearchVerified)
        }
    }

    @Test fun mixedCollectionMayExcludeOnlyUniquelyVerifiedBookletMembership() = runBlocking {
        var kind = "interactive-booklet"
        var collision = false
        val transport = object : ItunesCatalogTransport {
            override suspend fun get(path: String, parameters: Map<String, String>): String {
                val original = track(101, 123)
                if (parameters["entity"] == "song")
                    return """{"resultCount":2,"results":[${album(123, 2)},$original]}"""
                val booklet = """{"wrapperType":"track","kind":"$kind","trackId":${if (collision) 101 else 102},"collectionId":123,"trackName":"Booklet"}"""
                return """{"resultCount":3,"results":[${album(123, 2)},$original,$booklet]}"""
            }
        }
        val repository = AppleEditorialCatalogRepository(ItunesCatalogClient(transport))
        val verified = repository.albumDetail(123) as AppleEditorialLoad.Success
        assertEquals(AppleEditorialReadState.COMPLETE, verified.state)
        assertEquals(1, verified.albumDiagnostics?.verifiedExcludedNonAudioRows)
        assertEquals(listOf(101L), verified.releases.single().tracks.map { it.id })
        collision = true
        assertEquals(AppleEditorialReadState.PARTIAL_TRACKLIST,
            (repository.albumDetail(123) as AppleEditorialLoad.Success).state)
        collision = false
        kind = "pdf"
        assertEquals(AppleEditorialReadState.PARTIAL_TRACKLIST,
            (repository.albumDetail(123) as AppleEditorialLoad.Success).state)
    }

    @Test fun albumWithAllOrderedTracksReportsVerifiedSourceCompleteness() = runBlocking {
        val source = Source("""{"resultCount":2,"results":[
            ${album(123, count = 1)}, ${track(101, 123)}
        ]}""")
        val result = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            .albumDetail(123)
        assertEquals(AppleEditorialReadState.COMPLETE,
            (result as AppleEditorialLoad.Success).state)
    }

    @Test fun trackCountCannotClassifyHistoricalEpOrUnlabelledAlbum() = runBlocking {
        fun rows(count: Int, title: String = "Release 123", declared: Int = count): String {
            val songs = (1..count).joinToString(",") { n ->
                track(100 + n, 123).replace("\"trackNumber\":1", "\"trackNumber\":$n")
            }
            val header = album(123, declared).replace("Release 123", title)
            return """{"resultCount":${count + 1},"results":[$header,$songs]}"""
        }
        suspend fun read(raw: String) = AppleEditorialCatalogRepository(ItunesCatalogClient(Source(raw)))
            .albumDetail(123) as AppleEditorialLoad.Success
        val complete = read(rows(7))
        assertEquals(AppleEditorialReadState.COMPLETE, complete.state)
        assertEquals(com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.UNCLASSIFIED,
            complete.releases.single().format)
        val partial = read(rows(7, declared = 8))
        assertEquals(AppleEditorialReadState.PARTIAL_TRACKLIST, partial.state)
        assertEquals(com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.UNCLASSIFIED,
            partial.releases.single().format)
        val short = read(rows(6))
        assertEquals(com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.UNCLASSIFIED,
            short.releases.single().format)
        val explicit = read(rows(7, title = "Release 123 (EP)"))
        assertEquals(com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.EP,
            explicit.releases.single().format)
    }

    @Test fun missingStorefrontAlbumIsDifferentFromTransportFailure() = runBlocking {
        val source = Source("""{"resultCount":0,"results":[]}""")
        val result = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            .albumDetail(987)
        assertEquals(AppleEditorialReadState.NOT_IN_STOREFRONT,
            (result as AppleEditorialLoad.Success).state)
        assertTrue(result.releases.isEmpty())
    }

    @Test fun supplementalDiscoveryUsesDocumentedSearchAttributesAndExactArtistId() =
        runBlocking {
            val source = Source("""{"resultCount":3,"results":[
                ${album(501)},
                {"wrapperType":"collection","collectionType":"Album",
                 "collectionId":999,"artistId":88,"artistName":"Artist",
                 "collectionName":"Wrong artist"},
                {"wrapperType":"track","kind":"song","trackId":12,
                 "trackName":"Not album","artistId":77}
            ]}""")
            val catalog = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
            val result = catalog.searchArtistAlbumCandidates(
                query = "Artist", artistId = 77, storefront = "br",
                attribute = ItunesCatalogSearchAttribute.ARTIST_TERM,
            ) as AppleEditorialLoad.Success
            assertEquals(listOf(501L), result.releases.map { it.appleCollectionId })
            assertEquals("artistTerm", source.parameters["attribute"])
            assertEquals("album", source.parameters["entity"])
            assertEquals("200", source.parameters["limit"])
            assertFalse(source.parameters.containsKey("offset"))
            catalog.searchArtistAlbumCandidates(
                query = "Release 501", artistId = 77, storefront = "br",
                attribute = ItunesCatalogSearchAttribute.ALBUM_TERM,
            )
            assertEquals("albumTerm", source.parameters["attribute"])
        }

    @Test fun artistSupplementalQueriesUseEditorialCapabilityEvenWithSearchDisabled() =
        runBlocking {
            val capabilities = mutableListOf<ProviderCapability>()
            val transport = object : ItunesCatalogTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>,
                ): String = error("Must use capability-aware dispatch")
                override suspend fun get(
                    path: String, parameters: Map<String, String>,
                    capability: ProviderCapability,
                ): String {
                    assertEquals("search", path)
                    capabilities += capability
                    return """{"resultCount":0,"results":[]}"""
                }
            }
            val catalog = AppleEditorialCatalogRepository(ItunesCatalogClient(transport))
            for (attribute in listOf(
                ItunesCatalogSearchAttribute.ARTIST_TERM,
                ItunesCatalogSearchAttribute.ALBUM_TERM,
            )) {
                val result = catalog.searchArtistAlbumCandidates(
                    query = "Artist", artistId = 77, storefront = "br",
                    attribute = attribute,
                )
                assertTrue(result is AppleEditorialLoad.Success)
            }
            assertEquals(listOf(
                ProviderCapability.EDITORIAL_DISCOGRAPHY,
                ProviderCapability.EDITORIAL_DISCOGRAPHY,
            ), capabilities)
        }

    @Test fun searchPaginationUsesDocumentedSongTermNotUndocumentedOffset() = runBlocking {
        val source = Source("""{"resultCount":0,"results":[]}""")
        val response = ItunesPublicSearchSource(ItunesCatalogClient(source)).search(
            query = "Ditto",
            entity = ItunesCatalogEntity.SONG,
            country = "br",
            attribute = ItunesCatalogSearchAttribute.SONG_TERM,
        )
        assertTrue(response is ItunesCatalogResult.Success)
        assertEquals("song", source.parameters["entity"])
        assertEquals("songTerm", source.parameters["attribute"])
        assertEquals("200", source.parameters["limit"])
        assertFalse(source.parameters.containsKey("offset"))
    }

    @Test fun sameArtistHomonymsAndForeignAlbumIdsNeverEnterSelectedArtistShelf() =
        runBlocking {
            val source = Source("""{"resultCount":3,"results":[
                {"wrapperType":"artist","artistId":77,"artistName":"Artist"},
                ${album(123)},
                {"wrapperType":"collection","collectionType":"Album","collectionId":999,
                 "artistId":88,"artistName":"Artist","collectionName":"Wrong artist"}
            ]}""")
            val result = AppleEditorialCatalogRepository(ItunesCatalogClient(source))
                .artistDiscography(77)
            result as AppleEditorialLoad.Success
            assertEquals(listOf(123L), result.releases.map { it.appleCollectionId })
            assertEquals(AppleEditorialReadState.COMPLETE, result.state)
        }
}
