package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePr212HomeCoverageTest {
    private fun edition(id: Long, title: String, artist: String) =
        ItunesEditorialRelease(
            appleScopedId = "itunes:br:album:$id",
            appleCollectionId = id, storefront = "br",
            title = title, artistName = artist, artistId = id + 100,
            releaseDate = "2026-11-01", genre = "Pop",
            format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
            artworkUrl = "https://is1-ssl.mzstatic.com/$id.jpg",
            trackCount = 3, tracks = emptyList(),
            musicBrainzReleaseGroupId = null,
            musicBrainzArtistId = null,
            musicBrainzRecordingIds = emptyMap(),
        )

    @Test fun homeContinuesBoundedAppleDiscoveryWhenFirstEightSeedsYieldTooFewShelves() = runBlocking {
        val asked = mutableListOf<String>()
        val finder = AppleHomeEditorialDiscovery(
            source = AppleHomeAlbumSearchSource { title, _ ->
                asked += title
                val n = title.removePrefix("Edition ").toLong()
                AppleEditorialLoad.Success(
                    if (n in setOf(2L, 7L, 14L, 19L, 23L)) listOf(
                        edition(n, title, "Artist $n")
                    ) else emptyList(),
                    AppleEditorialReadState.COMPLETE,
                )
            },
        )
        val snapshots = mutableListOf<List<Long>>()
        val outcome = ApplePr212HomeCoverage(finder).discover(
            seeds = (1L..30L).map { AppleHomeEditorialSeed("Edition $it", "Artist $it") },
            storefront = "br",
            onProgress = { snapshots += it.map { release -> release.appleCollectionId } },
        ) as AppleEditorialLoad.Success
        assertEquals(24, asked.size)
        assertEquals(listOf(2L, 7L, 14L, 19L, 23L),
            outcome.releases.map { it.appleCollectionId })
        assertTrue(snapshots.any { it.size == 2 })
        assertEquals(5, snapshots.last().size)
        assertTrue(outcome.releases.all { it.appleScopedId.startsWith("itunes:br:album:") })
    }

    @Test fun enoughRealAppleProductsStopsAfterFirstBoundedBatch() = runBlocking {
        var calls = 0
        val finder = AppleHomeEditorialDiscovery(
            source = AppleHomeAlbumSearchSource { title, _ ->
                calls++
                val n = title.removePrefix("Edition ").toLong()
                AppleEditorialLoad.Success(
                    listOf(edition(n, title, "Artist $n")),
                    AppleEditorialReadState.COMPLETE,
                )
            },
        )
        val outcome = ApplePr212HomeCoverage(finder).discover(
            (1L..30L).map { AppleHomeEditorialSeed("Edition $it", "Artist $it") },
            "br",
        ) as AppleEditorialLoad.Success
        assertEquals(12, calls)
        assertEquals(12, outcome.releases.size)
        assertFalse(outcome.releases.any { it.appleScopedId.startsWith("catalog:") })
    }
    @Test fun revokedAppleProductIsRemovedFromProgressiveHomeSnapshot() = runBlocking {
        var calls = 0
        val discovery = AppleHomeEditorialDiscovery(
            source = AppleHomeAlbumSearchSource { _, _ ->
                calls++
                AppleEditorialLoad.Success(
                    listOf(edition(44, "Shared", "Artist $calls")),
                    AppleEditorialReadState.COMPLETE,
                )
            },
        )
        val snapshots = mutableListOf<List<Long>>()
        val result = ApplePr212HomeCoverage(discovery).discover(
            seeds = listOf(
                AppleHomeEditorialSeed("Shared", "Artist 1"),
                AppleHomeEditorialSeed("Shared", "Artist 2"),
            ),
            storefront = "br",
            onProgress = { snapshots += it.map { album -> album.appleCollectionId } },
        ) as AppleEditorialLoad.Success
        assertEquals(2, calls)
        assertTrue(result.releases.isEmpty())
        assertEquals(listOf(44L), snapshots.first())
        assertTrue(snapshots.last().isEmpty())
    }

}
