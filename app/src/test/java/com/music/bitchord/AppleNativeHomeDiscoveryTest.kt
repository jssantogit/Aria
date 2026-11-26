package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleNativeHomeDiscoveryTest {
    private fun release(
        id: Long, tag: String, storefront: String = "br",
        date: String? = "2026-11-12",
    ) = ItunesEditorialRelease(
        appleScopedId = "itunes:$storefront:album:$id",
        appleCollectionId = id,
        storefront = storefront,
        title = "Release $id",
        artistName = "Artist $id",
        artistId = id + 1,
        releaseDate = date,
        genre = when (tag) {
            "hip-hop" -> "Hip-Hop/Rap"
            "rnb" -> "R&B/Soul"
            "k-pop" -> "K-Pop"
            else -> tag.replaceFirstChar { it.uppercase() }
        },
        format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
        artworkUrl = "https://is1-ssl.mzstatic.com/a.jpg",
        trackCount = 1,
        tracks = emptyList(),
        musicBrainzReleaseGroupId = null,
        musicBrainzArtistId = null,
        musicBrainzRecordingIds = emptyMap(),
    )

    @Test fun nativeHomeDiscoversAppleProductsWithoutMbidsOrMandatoryMusicBrainz() =
        runBlocking {
            val tags = AppleNativeHomeDiscovery.sampleTags(LocalDate.parse("2026-11-17"))
            val queried = mutableListOf<String>()
            val updates = mutableListOf<List<String>>()
            val home = AppleNativeHomeDiscovery(
                source = AppleNativeHomeGenreSource { tag, storefront ->
                    queried += "$tag:$storefront"
                    AppleEditorialLoad.Success(listOf(
                        release(100 + queried.size.toLong(), tag).copy(
                            musicBrainzReleaseGroupId =
                                "529f87a1-1081-48a4-8ee1-3c08de229858",
                        ),
                        release(200, tag, storefront = "us"),
                        release(300, "rock").copy(releaseDate = "2026-11-18"),
                    ), AppleEditorialReadState.COMPLETE)
                },
            )
            val result = home.discover(
                "br", "2026-11-17", onProgress = { releases ->
                    updates += releases.map { it.appleScopedId }
                },
            ) as AppleEditorialLoad.Success
            assertEquals(tags.map { "$it:br" }, queried)
            assertEquals(5, result.releases.size)
            assertTrue(result.releases.all { it.appleScopedId.startsWith("itunes:br:album:") })
            assertTrue(result.releases.all { it.musicBrainzReleaseGroupId == null })
            assertEquals(listOf(1, 2, 3, 4, 5), updates.map { it.size })
            assertTrue(result.releases.all { it.releaseDate == "2026-11-12" })
            assertEquals(5, result.probeReport?.probesMade)
        }

    @Test fun saturatedFirstGenresCannotStarveFifthAppleHomeCategory() = runBlocking {
        val day = LocalDate.parse("2026-11-17")
        val tags = AppleNativeHomeDiscovery.sampleTags(day)
        val progress = mutableListOf<List<Long>>()
        var requests = 0
        val home = AppleNativeHomeDiscovery(
            source = AppleNativeHomeGenreSource { tag, _ ->
                requests++
                val start = (tags.indexOf(tag) + 1) * 1000L
                AppleEditorialLoad.Success((1L..24L).map { id ->
                    release(start + id, tag)
                }, AppleEditorialReadState.COMPLETE)
            },
        )
        val result = home.discover("br", day.toString(),
            onProgress = { progress += it.map { row -> row.appleCollectionId } },
        ) as AppleEditorialLoad.Success
        assertEquals(5, requests)
        assertEquals(36, result.releases.size)
        assertEquals(listOf(8, 15, 22, 29, 36), progress.map { it.size })
        assertEquals(120, result.probeReport?.matchingProducts)
        assertEquals(5, tags.size)
        assertEquals(36, result.releases.map { it.appleScopedId }.distinct().size)
        assertTrue(progress.zipWithNext().all { (earlier, later) ->
            later.take(earlier.size) == earlier
        })
        val shown = result.releases.map { it.appleCollectionId }
        tags.forEachIndexed { index, _ ->
            val start = (index + 1) * 1000L
            assertEquals(if (index == 0) 8 else 7,
                shown.count { it in (start + 1)..(start + 24) })
        }
    }

    @Test fun unfilledAppleHomeGenreSlotsReturnToVerifiedProducts() = runBlocking {
        val day = LocalDate.parse("2026-11-17")
        val tags = AppleNativeHomeDiscovery.sampleTags(day)
        val updates = mutableListOf<Int>()
        val home = AppleNativeHomeDiscovery(
            source = AppleNativeHomeGenreSource { tag, _ ->
                AppleEditorialLoad.Success(
                    if (tag == tags.first()) (1L..24L).map { release(it, tag) }
                    else emptyList(),
                    AppleEditorialReadState.COMPLETE,
                )
            },
        )
        val result = home.discover("br", day.toString(),
            onProgress = { updates += it.size },
        ) as AppleEditorialLoad.Success
        assertEquals(24, result.releases.size)
        assertEquals(listOf(8, 24), updates)
        assertEquals(24, result.probeReport?.matchingProducts)
        assertEquals(AppleEditorialReadState.COMPLETE, result.state)
        assertEquals((1L..24L).toList(), result.releases.map { it.appleCollectionId })
    }

    @Test fun conflictingAppleProductClaimsAreRemovedRatherThanArbitrarilyMerged() =
        runBlocking {
            val today = LocalDate.parse("2026-11-17")
            val tags = AppleNativeHomeDiscovery.sampleTags(today)
            val home = AppleNativeHomeDiscovery(
                source = AppleNativeHomeGenreSource { tag, _ ->
                    AppleEditorialLoad.Success(listOf(
                        release(99, tag).copy(
                            title = if (tag == tags.first()) "First" else "Contradiction",
                        ),
                    ), AppleEditorialReadState.COMPLETE)
                },
            )
            val result = home.discover("br", today.toString()) as AppleEditorialLoad.Success
            assertTrue(result.releases.isEmpty())
            assertEquals(AppleEditorialReadState.POSSIBLY_TRUNCATED, result.state)
        }

    @Test fun failureOfOneGenreDoesNotTurnOtherGenresIntoHiddenMbFallback() = runBlocking {
        val tags = AppleNativeHomeDiscovery.sampleTags(LocalDate.parse("2026-11-17"))
        val home = AppleNativeHomeDiscovery(
            source = AppleNativeHomeGenreSource { tag, _ ->
                if (tag == tags.first()) AppleEditorialLoad.Failure(
                    ItunesCatalogFailure.UNAVAILABLE,
                ) else AppleEditorialLoad.Success(
                    listOf(release(42 + tags.indexOf(tag).toLong(), tag)),
                    AppleEditorialReadState.COMPLETE,
                )
            },
        )
        val result = home.discover("br", "2026-11-17") as AppleEditorialLoad.Success
        assertEquals(4, result.releases.size)
        assertEquals(1, result.probeReport?.failedRequests)
        assertEquals(AppleEditorialReadState.POSSIBLY_TRUNCATED, result.state)
    }

    @Test fun malformedDateAndStorefrontNeverIssueAppleRequest() = runBlocking {
        var probed = false
        val home = AppleNativeHomeDiscovery(
            source = AppleNativeHomeGenreSource { _, _ ->
                probed = true
                AppleEditorialLoad.Success(emptyList(), AppleEditorialReadState.COMPLETE)
            },
        )
        assertTrue(home.discover("invalid", "2026-11-17") is AppleEditorialLoad.Failure)
        assertTrue(home.discover("br", "not-a-date") is AppleEditorialLoad.Failure)
        assertTrue(home.discover("br", "2026-11-17", maxGenres = 10) is AppleEditorialLoad.Failure)
        assertFalse(probed)
    }
}
