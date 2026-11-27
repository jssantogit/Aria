package com.music.aria

import com.music.aria.data.catalog.metadata.*
import com.music.aria.data.model.HomeShelf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleExploreGenreEditorialTest {
    private fun edition(id: Long, genre: String?) = ItunesEditorialRelease(
        appleScopedId = "itunes:br:album:$id",
        appleCollectionId = id, storefront = "br",
        title = "Music $id", artistName = "Band", artistId = 42,
        releaseDate = "2026-11-01", genre = genre,
        format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
        artworkUrl = "https://is1-ssl.mzstatic.com/music.jpg",
        trackCount = 2, tracks = emptyList(),
        musicBrainzReleaseGroupId = null, musicBrainzArtistId = null,
        musicBrainzRecordingIds = emptyMap(),
    )


    @Test fun nativeAppleGenreSearchNeedsNoMusicBrainzSeedAndKeepsStorefrontIds() = runBlocking {
        val requested = mutableListOf<String>()
        val explorer = AppleExploreGenreDiscovery(
            nativeSource = AppleNativeGenreSearchSource { term, storefront ->
                requested += "$term:$storefront"
                AppleEditorialLoad.Success(listOf(
                    edition(10, "K-Pop").copy(
                        musicBrainzReleaseGroupId =
                            "529f87a1-1081-48a4-8ee1-3c08de229858"),
                    edition(11, "Pop"), edition(12, "K-Pop").copy(storefront = "us"),
                ), AppleEditorialReadState.COMPLETE)
            },
        )
        val found = explorer.discoverNative("k-pop", "br") as AppleEditorialLoad.Success
        assertEquals(listOf("K-Pop:br"), requested)
        assertEquals(listOf("itunes:br:album:10"),
            found.releases.map { it.appleScopedId })
        assertNull(found.releases.single().musicBrainzReleaseGroupId)
    }

    @Test fun paritySubgenresRequireExactAppleStorefrontEvidence() = runBlocking {
        val cases = mapOf(
            "hard rock" to ("Hard Rock" to "Rock"),
            "house" to ("House" to "Electronic"),
            "techno" to ("Techno" to "Electronic"),
            "trance" to ("Trance" to "Dance"),
            "smooth jazz" to ("Smooth Jazz" to "Jazz"),
            "bluegrass" to ("Bluegrass" to "Country"),
        )
        for ((tag, names) in cases) {
            var requests = 0
            val source = AppleExploreGenreDiscovery(
                nativeSource = AppleNativeGenreSearchSource { term, storefront ->
                    assertEquals(tag, term)
                    assertEquals("br", storefront)
                    requests++
                    AppleEditorialLoad.Success(
                        listOf(edition(1, names.first), edition(2, names.second),
                            edition(3, null)),
                        AppleEditorialReadState.COMPLETE,
                    )
                },
            )
            val result = source.discoverNative(tag) as AppleEditorialLoad.Success
            assertEquals(listOf(1L), result.releases.map { it.appleCollectionId })
            assertEquals(1, requests)
            assertTrue(AppleExploreGenrePolicy.supports(tag))
            assertFalse(AppleExploreGenrePolicy.matches(tag, names.second))
        }
    }

    @Test fun cappedRockPoolExpandsOnlyFromExactAppleArtistAndGenre() = runBlocking {
        val artists = mutableListOf<Long>()
        val progress = mutableListOf<Int>()
        val source = object : AppleNativeGenreSearchSource {
            override suspend fun search(term: String, storefront: String) =
                AppleEditorialLoad.Success((1L..20L).map { edition(it, "Rock") },
                    AppleEditorialReadState.POSSIBLY_TRUNCATED)

            override suspend fun artistAlbums(artistId: Long, storefront: String): AppleEditorialLoad {
                artists += artistId
                assertEquals("br", storefront)
                return AppleEditorialLoad.Success(
                    listOf(edition(1, "Rock"), edition(91, "Pop"),
                        edition(92, "Rock").copy(artistId = 99),
                        edition(93, "Rock").copy(storefront = "us")) +
                        (21L..35L).map { edition(it, "Rock") },
                    AppleEditorialReadState.COMPLETE,
                )
            }
        }
        val discovery = AppleExploreGenreDiscovery(nativeSource = source)
        val home = discovery.discoverNative("rock") as AppleEditorialLoad.Success
        assertEquals(20, home.releases.size)
        assertTrue(artists.isEmpty())
        val result = discovery.discoverNative("rock", expandSparseCapacity = true,
            onProgress = { progress += it.size }) as AppleEditorialLoad.Success
        assertEquals(listOf(42L), artists)
        assertEquals(listOf(20, 30), progress)
        assertEquals((1L..30L).toList(), result.releases.map { it.appleCollectionId })
        assertEquals(2, result.probeReport?.probesMade)
        assertEquals(AppleEditorialReadState.POSSIBLY_TRUNCATED, result.state)
    }

    @Test fun failedSupplementRetainsOriginalPoolAndStopsProbes() = runBlocking {
        val artists = mutableListOf<Long>()
        val source = object : AppleNativeGenreSearchSource {
            override suspend fun search(term: String, storefront: String) =
                AppleEditorialLoad.Success((1L..20L).map {
                    edition(it, "Rock").copy(artistId = 40L + it)
                }, AppleEditorialReadState.POSSIBLY_TRUNCATED)
            override suspend fun artistAlbums(artistId: Long, storefront: String): AppleEditorialLoad {
                artists += artistId
                return AppleEditorialLoad.Failure(ItunesCatalogFailure.UNAVAILABLE)
            }
        }
        val result = AppleExploreGenreDiscovery(nativeSource = source)
            .discoverNative("rock", expandSparseCapacity = true) as AppleEditorialLoad.Success
        assertEquals(20, result.releases.size)
        assertEquals(listOf(41L), artists)
        assertEquals(1, result.probeReport?.failedRequests)
        assertEquals(2, result.probeReport?.probesMade)
    }

    @Test fun unsupportedNativeGenreDoesNotContactAnySource() = runBlocking {
        var called = false
        val explorer = AppleExploreGenreDiscovery(
            nativeSource = AppleNativeGenreSearchSource { _, _ ->
                called = true
                AppleEditorialLoad.Success(emptyList(), AppleEditorialReadState.NOT_IN_STOREFRONT)
            },
        )
        assertTrue(explorer.discoverNative("fabricated-tag") is AppleEditorialLoad.Failure)
        assertFalse(called)
    }

    @Test fun kpopMusicBrainzTagsAloneDoNotMakeAnApplePopAlbumIntoKpop() {
        val shelves = com.music.aria.ui.appleFirstGenreShelves(
            tag = "k-pop",
            appleAlbums = listOf(edition(101, "Pop"), edition(102, "K-Pop")),
            publicPlaylists = null,
            releaseHeading = "Lançamentos",
        )
        assertEquals(listOf("itunes:br:album:102"),
            shelves.flatMap { it.items }.map { it.browseId })
    }

    @Test fun genrePresentationPreservesStorefrontAndNativeAppleAlbumRouting() {
        val shelves = com.music.aria.ui.appleFirstGenreShelves(
            tag = "hip-hop",
            appleAlbums = listOf(edition(5, "Hip-Hop/Rap"),
                edition(6, "Rock"), edition(7, null)),
            publicPlaylists = HomeShelf("Playlists", emptyList()),
            releaseHeading = "Lançamentos",
        )
        assertEquals(listOf("itunes:br:album:5"),
            shelves.flatMap { it.items }.map { it.browseId })
        assertTrue(shelves.flatMap { it.items }.all { it.videoId == null })
        assertTrue(shelves.none { it.title == "Playlists" })
    }


    @Test fun genreSeparatesEvidenceBackedFormatsAndRetainsPlaylistsAsIndependentSource() {
        val items = (1..18).map { id ->
            edition(id.toLong(), "K-Pop").copy(
                title = "K-pop $id", releaseDate = if (id <= 6) "2026-11-11"
                    else "2024-10-03",
                format = when (id % 4) {
                    0 -> ItunesEditorialReleaseFormat.ALBUM
                    1 -> ItunesEditorialReleaseFormat.EP
                    2 -> ItunesEditorialReleaseFormat.SINGLE
                    else -> ItunesEditorialReleaseFormat.UNCLASSIFIED
                },
            )
        }
        val public = HomeShelf("Playlists", listOf(
            com.music.aria.data.model.ShelfItem(
                "Verified playlist", "Creator", null, null,
                "listenbrainz:playlist:123",
            ),
        ))
        val shelves = com.music.aria.ui.appleFirstGenreShelves(
            tag = "k-pop", appleAlbums = items,
            publicPlaylists = public, releaseHeading = "Lançamentos",
            todayIsoDate = "2026-11-22",
        )
        val names = shelves.map { it.title }
        assertTrue("Lançamentos" in names)
        assertTrue("Para descobrir" in names)
        assertTrue("Álbuns" in names)
        assertTrue("Singles e EPs" in names)
        assertTrue("Outros lançamentos" in names)
        assertTrue("Playlists" in names)
        assertEquals(1, shelves.count { it.title == "Playlists" })
        val appleIds = shelves.flatMap { it.items }
            .mapNotNull { it.browseId?.takeIf { id -> id.startsWith("itunes:") } }
        assertEquals(appleIds.size, appleIds.distinct().size)
        assertTrue(shelves.flatMap { it.items }.all { it.videoId == null })
        for (shelf in shelves) {
            if (shelf.title == "Álbuns") assertTrue(shelf.items.all {
                items.first { release -> release.appleScopedId == it.browseId }.format ==
                    ItunesEditorialReleaseFormat.ALBUM
            })
            if (shelf.title == "Singles e EPs") assertTrue(shelf.items.all {
                items.first { release -> release.appleScopedId == it.browseId }.format in setOf(
                    ItunesEditorialReleaseFormat.SINGLE, ItunesEditorialReleaseFormat.EP,
                )
            })
        }
    }

    @Test fun boundedAppleGenreDiscoveryOnlyUsesAppleCategoryAndNeverCopiesMbids() =
        runBlocking {
            val calls = mutableListOf<String>()
            val collector = AppleExploreGenreDiscovery(
                catalog = AppleHomeEditorialDiscovery(
                    source = AppleHomeAlbumSearchSource { title, _ ->
                        calls += title
                        val id = if (title == "Music 5") 5 else 6
                        AppleEditorialLoad.Success(
                            listOf(edition(id.toLong(),
                                if (id == 5) "K-Pop" else "Pop").copy(
                                musicBrainzReleaseGroupId =
                                    "529f87a1-1081-48a4-8ee1-3c08de229858",
                            )),
                            AppleEditorialReadState.COMPLETE,
                        )
                    },
                ),
            )
            val result = collector.discover(
                tag = "k-pop",
                seeds = listOf(
                    AppleHomeEditorialSeed("Music 5", "Band"),
                    AppleHomeEditorialSeed("Music 6", "Band"),
                ),
                storefront = "br",
            ) as AppleEditorialLoad.Success
            assertEquals(listOf("Music 5", "Music 6"), calls)
            assertEquals(listOf("itunes:br:album:5"),
                result.releases.map { it.appleScopedId })
            assertNull(result.releases.single().musicBrainzReleaseGroupId)
        }

    @Test fun exploreCategoryCoversComeFromAppleGenreProductsNotMbSuggestions() {
        val result = com.music.aria.ui.appleExploreGenreCoverMap(
            appleAlbums = listOf(edition(1, "K-Pop"), edition(2, "Pop")),
            tags = listOf("k-pop", "pop", "metal"),
        )
        assertEquals("https://is1-ssl.mzstatic.com/music.jpg", result["k-pop"])
        assertEquals("https://is1-ssl.mzstatic.com/music.jpg", result["pop"])
        assertNull(result["metal"])
        assertEquals(2, result.size)
    }

    @Test fun genreKeepsUnderlyingAppleProbeCostEvenWhenOnlyOneGenreMatches() =
        runBlocking {
            var calls = 0
            val collector = AppleExploreGenreDiscovery(
                catalog = AppleHomeEditorialDiscovery(
                    source = AppleHomeAlbumSearchSource { title, _ ->
                        calls++
                        AppleEditorialLoad.Success(
                            listOf(edition(
                                if (title == "Music 1") 1 else 2,
                                if (title == "Music 1") "K-Pop" else "Pop",
                            )),
                            AppleEditorialReadState.COMPLETE,
                        )
                    },
                ),
            )
            val result = collector.discover(
                "k-pop", listOf(
                    AppleHomeEditorialSeed("Music 1", "Band"),
                    AppleHomeEditorialSeed("Music 2", "Band"),
                ), "br",
            ) as AppleEditorialLoad.Success
            assertEquals(2, calls)
            assertEquals(1, result.releases.size)
            assertEquals(2, result.probeReport?.probesMade)
            assertEquals(2, result.probeReport?.matchingProducts)
            assertEquals(0, result.probeReport?.failedRequests)
        }

    @Test fun unsupportedTagFailsClosedWithoutAnyAppleQuery() = runBlocking {
        var called = false
        val collector = AppleExploreGenreDiscovery(
            catalog = AppleHomeEditorialDiscovery(
                source = AppleHomeAlbumSearchSource { _, _ ->
                    called = true
                    AppleEditorialLoad.Success(emptyList(),
                        AppleEditorialReadState.NOT_IN_STOREFRONT)
                },
            ),
        )
        assertTrue(collector.discover(
            "unverified-tag", listOf(AppleHomeEditorialSeed("Music 5", "Band")), "br",
        ) is AppleEditorialLoad.Failure)
        assertFalse(called)
    }
}
