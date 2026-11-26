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
    private class Source(var json: String) : ItunesCatalogTransport {
        var parameters = emptyMap<String, String>()
        var requests = 0
        override suspend fun get(path: String, parameters: Map<String, String>): String {
            this.parameters = parameters
            requests++
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
                if (path == "lookup") {
                    if (parameters["entity"] == "musicTrack" && variant == "mixedUnavailable")
                        throw java.io.IOException("fixture unavailable")
                    val observed = if (parameters["entity"] == "musicTrack" && variant == "mixedConflict")
                        original.replace("Song 101", "Contradictory lookup") else original
                    return """{"resultCount":2,"results":[${album(123, 2)},$observed]}"""
                }
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
        for (bad in listOf("foreign", "wrongTitle", "position", "wrongTotal", "conflict",
            "mixedUnavailable", "mixedConflict")) {
            variant = bad
            val before = calls.size
            val partial = repository.albumDetail(123) as AppleEditorialLoad.Success
            if (bad.startsWith("mixed")) {
                assertEquals(2, calls.size - before)
                assertEquals(false, partial.albumDiagnostics?.albumSongSearchAttempted)
            }
            assertEquals(bad, AppleEditorialReadState.PARTIAL_TRACKLIST, partial.state)
            assertEquals(listOf(101L), partial.releases.single().tracks.map { it.id })
            assertEquals(false, partial.albumDiagnostics?.albumSongSearchVerified)
        }
    }

    @Test fun complementaryAppleObservationsRecoverOnlyProvenMissingSongs() = runBlocking {
        var variant = "valid"
        fun song(id: Int, position: Int): String =
            track(id, 123).replace("\"trackNumber\":1", "\"trackNumber\":$position")
                .dropLast(1) + ",\"collectionName\":\"Release 123\",\"trackCount\":3}"
        val transport = object : ItunesCatalogTransport {
            override suspend fun get(path: String, parameters: Map<String, String>): String {
                val original = song(101, 1)
                if (path == "lookup" && parameters["entity"] == "song")
                    return """{"resultCount":2,"results":[${album(123, 3)},$original]}"""
                val mixed = song(102, 2)
                if (path == "lookup")
                    return """{"resultCount":3,"results":[${album(123, 3)},$original,$mixed]}"""
                val additional = song(103, if (variant == "position") 2 else 3)
                val corroboration = if (variant == "conflict")
                    mixed.replace("Song 102", "Contradictory song") else original
                return """{"resultCount":2,"results":[$corroboration,$additional]}"""
            }
        }
        val repository = AppleEditorialCatalogRepository(ItunesCatalogClient(transport))
        val complete = repository.albumDetail(123) as AppleEditorialLoad.Success
        assertEquals(AppleEditorialReadState.COMPLETE, complete.state)
        assertEquals(listOf(101L, 102L, 103L), complete.releases.single().tracks.map { it.id })
        assertEquals(true, complete.albumDiagnostics?.albumSongSearchVerified)
        for (bad in listOf("position", "conflict")) {
            variant = bad
            val partial = repository.albumDetail(123) as AppleEditorialLoad.Success
            assertEquals(AppleEditorialReadState.PARTIAL_TRACKLIST, partial.state)
            assertEquals(listOf(101L), partial.releases.single().tracks.map { it.id })
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

    @Test fun completeExactTrackLabelsCanProveAnOmittedExplicitAppleSuffix() = runBlocking {
        fun rows(labels: List<String>, title: String = "Release 123", declared: Int = labels.size): String {
            val songs = labels.mapIndexed { index, label ->
                track(101 + index, 123).replace("\"trackNumber\":1", "\"trackNumber\":${index + 1}")
                    .dropLast(1) + ",\"collectionName\":\"$label\",\"trackTimeMillis\":180000}"
            }
            return """{"resultCount":${songs.size + 1},"results":[${album(123, declared).replace("Release 123", title)},${songs.joinToString(",")}]}"""
        }
        suspend fun read(raw: String) = AppleEditorialCatalogRepository(ItunesCatalogClient(Source(raw)))
            .albumDetail(123) as AppleEditorialLoad.Success
        val ep = read(rows(listOf("Release 123 - EP", "Release 123 - EP")))
        assertEquals(AppleEditorialReadState.COMPLETE, ep.state)
        assertEquals("Release 123", ep.releases.single().title)
        assertEquals(com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.EP,
            ep.releases.single().format)
        val single = read(rows(listOf("Release 123 (Single)")))
        assertEquals(com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.SINGLE,
            single.releases.single().format)
        for (raw in listOf(
            rows(listOf("Release 123 - EP", "Release 123 - Single")),
            rows(listOf("Different Release - EP")),
            rows(listOf("Release 123 - EP"), declared = 2),
            rows(listOf("Release 123")),
        )) assertEquals(com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.UNCLASSIFIED,
            read(raw).releases.single().format)
        assertEquals(com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.SINGLE,
            read(rows(listOf("Release 123 - EP"), title = "Release 123 - Single")).releases.single().format)
    }

    @Test fun appleAudioRuleObservationsRequireExactCompleteAudioAndNeverOverrideFormat() = runBlocking {
        fun rows(durations: List<Long>, title: String = "Release 123"): String {
            val songs = durations.mapIndexed { index, duration ->
                track(101 + index, 123).replace("\"trackNumber\":1", "\"trackNumber\":${index + 1}")
                    .dropLast(1) + ",\"trackTimeMillis\":$duration}"
            }
            return """{"resultCount":${songs.size + 1},"results":[${album(123, songs.size).replace("Release 123", title)},${songs.joinToString(",")}]}"""
        }
        suspend fun read(durations: List<Long>, title: String = "Release 123") =
            (AppleEditorialCatalogRepository(ItunesCatalogClient(Source(rows(durations, title))))
                .albumDetail(123) as AppleEditorialLoad.Success).releases.single()
        val catalog = com.music.bitchord.data.catalog.metadata.ItunesEditorialCatalog
        val unknown = com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.UNCLASSIFIED
        val album = com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.ALBUM
        val ep = com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.EP
        val single = com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.SINGLE
        val long = read(List(7) { 300_000L })
        assertEquals(unknown, long.format)
        assertEquals(album, catalog.observeAppleAudioRuleCandidate(long, true))
        assertEquals(unknown, catalog.observeAppleAudioRuleCandidate(long, false))
        assertEquals(unknown, catalog.observeAppleAudioRuleCandidate(
            long.copy(tracks = long.tracks.dropLast(1)), true))
        assertEquals(unknown, catalog.observeAppleAudioRuleCandidate(
            long.copy(tracks = long.tracks.map { it.copy(durationMs = null) }), true))
        assertEquals(unknown, catalog.observeAppleAudioRuleCandidate(
            long.copy(tracks = long.tracks.map { it.copy(trackNumber = 1) }), true))
        assertEquals(single, catalog.observeAppleAudioRuleCandidate(read(listOf(599_999L)), true))
        assertEquals(ep, catalog.observeAppleAudioRuleCandidate(read(listOf(600_000L)), true))
        assertEquals(ep, catalog.observeAppleAudioRuleCandidate(read(List(6) { 300_000L }), true))
        assertEquals(album, catalog.observeAppleAudioRuleCandidate(read(List(6) { 300_001L }), true))
        val historical = read(List(7) { 300_000L }, "Release 123 - EP")
        assertEquals(ep, historical.format)
        assertEquals(album, catalog.observeAppleAudioRuleCandidate(historical, true))
    }

    @Test fun commercialFormatUsesActualSongsNotDeclaredCountAndKeepsExplicitPriority() = runBlocking {
        fun rows(observed: Int, declared: Int, title: String = "Release 123"): String {
            val songs = (1..observed).joinToString(",") { n ->
                track(100 + n, 123).replace("\"trackNumber\":1", "\"trackNumber\":$n")
                    .dropLast(1) + ",\"trackTimeMillis\":180000}"
            }
            return """{"resultCount":${observed + 1},"results":[${album(123, declared).replace("Release 123", title)},$songs]}"""
        }
        suspend fun read(observed: Int, declared: Int, title: String = "Release 123") =
            AppleEditorialCatalogRepository(ItunesCatalogClient(Source(rows(observed, declared, title))))
                .albumDetail(123) as AppleEditorialLoad.Success
        val unknown = com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.UNCLASSIFIED
        val album = com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.ALBUM
        val ep = com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.EP
        val complete = read(7, 7)
        assertEquals(AppleEditorialReadState.COMPLETE, complete.state)
        assertEquals(unknown, complete.releases.single().format)
        assertEquals(album, complete.releases.single().displayFormat)
        val partial = read(7, 8)
        assertEquals(AppleEditorialReadState.PARTIAL_TRACKLIST, partial.state)
        assertEquals(album, partial.releases.single().displayFormat)
        assertEquals(unknown, read(6, 8).releases.single().displayFormat)
        assertEquals(ep, read(6, 6).releases.single().displayFormat)
        val historical = read(7, 7, "Release 123 - EP").releases.single()
        assertEquals(album, historical.commercialAudioFormat)
        assertEquals(ep, historical.displayFormat)
        val detail = com.music.bitchord.ui.appleNativeAlbumDetail(
            complete, complete.releases.single().appleScopedId,
        )
        assertNotNull(detail)
        assertEquals("ALBUM", detail!!.album.category.name)
        val partialDetail = com.music.bitchord.ui.appleNativeAlbumDetail(
            partial, partial.releases.single().appleScopedId,
        )
        assertNotNull(partialDetail)
        assertTrue(partialDetail!!.partialTracklist)
        assertEquals("ALBUM", partialDetail.album.category.name)
        val catalog = com.music.bitchord.data.catalog.metadata.ItunesEditorialCatalog
        val release = complete.releases.single()
        assertEquals(unknown, catalog.commercialAudioFormat(release, false, true))
        assertEquals(unknown, catalog.commercialAudioFormat(
            release.copy(tracks = release.tracks.map { it.copy(collectionId = 999L) }), true, true))
        assertEquals(unknown, catalog.commercialAudioFormat(
            release.copy(tracks = release.tracks.map { it.copy(trackNumber = 1) }), true, true))
        assertEquals(unknown, catalog.commercialAudioFormat(release.copy(tracks = emptyList()), true, true))
    }

    @Test fun exactCommercialEvidenceReusedWithoutExtraNetworkOrChangingExplicitType() = runBlocking {
        val header = album(123, 7)
        val songs = (1..7).joinToString(",") { n ->
            track(100 + n, 123).replace("\"trackNumber\":1", "\"trackNumber\":$n")
                .dropLast(1) + ",\"trackTimeMillis\":180000}"
        }
        val source = Source("""{"resultCount":8,"results":[$header,$songs]}""")
        var now = 10L
        val repository = AppleEditorialCatalogRepository(ItunesCatalogClient(source), nowNanos = { now })
        val loaded = repository.albumDetail(123) as AppleEditorialLoad.Success
        val release = loaded.releases.single()
        val kind = com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.ALBUM
        val unknown = com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.UNCLASSIFIED
        val ep = com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat.EP
        assertEquals(kind, release.displayFormat)
        assertEquals(unknown, release.format)
        source.json = """{"resultCount":1,"results":[$header]}"""
        val readsBeforeListing = source.requests
        val listing = repository.searchAlbums("Artist") as AppleEditorialLoad.Success
        assertEquals(readsBeforeListing + 1, source.requests)
        val card = listing.releases.single()
        assertEquals(kind, card.displayFormat)
        assertEquals(unknown, card.format)
        assertTrue(card.tracks.isEmpty())
        assertEquals("ALBUM", com.music.bitchord.ui.appleNativeSearchRows(listing).single().category.name)
        val plain = card.copy(commercialAudioFormat = unknown)
        assertEquals(kind, repository.reuseCommercialFormat(plain).displayFormat)
        assertEquals(ep, repository.reuseCommercialFormat(plain.copy(format = ep)).displayFormat)
        assertEquals(unknown, repository.reuseCommercialFormat(plain.copy(
            storefront = "us", appleScopedId = "itunes:us:album:123")).displayFormat)
        assertEquals(unknown, repository.reuseCommercialFormat(plain.copy(
            appleCollectionId = 456L, appleScopedId = "itunes:br:album:456")).displayFormat)
        assertEquals(readsBeforeListing + 1, source.requests)
        now += 15L * 60 * 1_000_000_000
        // Already enriched header-only rows in a pager cannot retain stale type.
        assertEquals(unknown, repository.reuseCommercialFormat(card).displayFormat)
        assertEquals(unknown, repository.reuseCommercialFormat(plain).displayFormat)
        assertEquals(kind, repository.reuseCommercialFormat(release).displayFormat)
        assertEquals(readsBeforeListing + 1, source.requests)
        source.json = """{"resultCount":8,"results":[$header,$songs]}"""
        repository.albumDetail(123)
        assertEquals(kind, repository.reuseCommercialFormat(plain).displayFormat)
        assertEquals(unknown, repository.reuseCommercialFormat(plain.copy(trackCount = 8)).displayFormat)
        assertEquals(unknown, repository.reuseCommercialFormat(plain).displayFormat)
        repository.albumDetail(123)
        source.json = """{"resultCount":0,"results":[]}"""
        repository.albumDetail(123)
        assertEquals(unknown, repository.reuseCommercialFormat(plain).displayFormat)
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
