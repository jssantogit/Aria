package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
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

    @Test fun kpopUsesAppleGenreOnlyNotAnUnverifiedMusicBrainzTag() {
        assertFalse(AppleExploreGenrePolicy.matches("k-pop", edition(101, "Pop").genre))
        assertTrue(AppleExploreGenrePolicy.matches("k-pop", edition(102, "K-Pop").genre))
        assertFalse(AppleExploreGenrePolicy.matches("hip-hop", edition(103, "Rock").genre))
        assertTrue(AppleExploreGenrePolicy.matches("hip-hop", edition(104, "Hip-Hop/Rap").genre))
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
