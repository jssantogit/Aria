package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.ui.appleFirstHomeShelves
import com.music.bitchord.ui.appleHomeContinuationShelf
import com.music.bitchord.ui.appleHomeUnshownVerified
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Measured capacity of the actual Apple native Home multi-genre compositor.
 * 36 original editorial products remain fixed; remaining verified products
 * are revealed in-memory, never via an imaginary iTunes Search API offset.
 */
class AppleHomeVerifiedPoolCapacityTest {
    private val today = LocalDate.parse("2026-11-23")

    private fun genreFor(tag: String): String = when (tag) {
        "pop" -> "Pop"
        "hip-hop" -> "Hip-Hop/Rap"
        "rock" -> "Rock"
        "electronic" -> "Electronic"
        "k-pop" -> "K-Pop"
        "rnb" -> "R&B/Soul"
        "jazz" -> "Jazz"
        "classical" -> "Classical"
        else -> error("Unknown native genre")
    }
    private fun album(id: Long, tag: String): ItunesEditorialRelease =
        ItunesEditorialRelease(
            appleScopedId = "itunes:br:album:" + id,
            appleCollectionId = id, storefront = "br",
            title = "Apple product " + id, artistName = "Artist", artistId = 101,
            releaseDate = "2026-11-01", genre = genreFor(tag),
            format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
            artworkUrl = "https://is1-ssl.mzstatic.com/music.jpg",
            trackCount = 2, tracks = emptyList(),
            musicBrainzReleaseGroupId = null, musicBrainzArtistId = null,
            musicBrainzRecordingIds = emptyMap(),
        )

    @Test fun homePoolRetainsThirtyHundredAndThreeHundredSourceProductsBeyondFixedLanding() =
        runBlocking {
            for (target in listOf(30, 100, 300)) {
                val tags = AppleNativeHomeDiscovery.sampleTags(today)
                var requests = 0
                val client = AppleNativeHomeDiscovery(
                    source = AppleNativeHomeGenreSource { tag, storefront ->
                        assertEquals("br", storefront)
                        val index = tags.indexOf(tag)
                        assertTrue(index >= 0)
                        requests++
                        val quantity = target / 3 + if (index < target % 3) 1 else 0
                        val offset = target / 3 * index + minOf(index, target % 3)
                        AppleEditorialLoad.Success(
                            (1..quantity).map { n -> album((offset + n).toLong(), tag) },
                            AppleEditorialReadState.COMPLETE,
                        )
                    },
                )
                var pool: List<ItunesEditorialRelease> = emptyList()
                val result = client.discover(
                    "br", today.toString(), onVerifiedPool = { pool = it },
                ) as AppleEditorialLoad.Success
                assertEquals(3, requests)
                assertEquals(target, pool.size)
                assertEquals(target, pool.map { it.appleScopedId }.distinct().size)
                assertEquals(minOf(target, 36), result.releases.size)
                val original = appleFirstHomeShelves(
                    appleAlbums = result.releases,
                    existingLocalShelves = emptyList(),
                    releasesHeading = "Lançamentos",
                    discoveryHeading = "Descobrir",
                    todayIsoDate = today.toString(),
                )
                val shown = original.flatMap { it.items }
                    .mapNotNull { it.browseId }.toSet()
                val notShown = appleHomeUnshownVerified(pool, original)
                assertEquals(target - shown.size, notShown.size)
                val extra = appleHomeContinuationShelf(
                    true, original, pool, revealedCount = target,
                )
                val combinedIds = shown + extra?.items.orEmpty()
                    .mapNotNull { it.browseId }
                assertEquals(target, combinedIds.size)
                assertEquals(target, combinedIds.distinct().size)
                assertTrue(combinedIds.all { it.startsWith("itunes:br:album:") })
                assertNull(appleHomeContinuationShelf(false, original, pool, 24))
                assertNull(appleHomeContinuationShelf(true, original, pool, 0))
            }
        }

    @Test fun mismatchedStorefrontAndContestedSourceIdsCannotEnterPool() = runBlocking {
        val tags = AppleNativeHomeDiscovery.sampleTags(today)
        var pool = emptyList<ItunesEditorialRelease>()
        val client = AppleNativeHomeDiscovery(
            source = AppleNativeHomeGenreSource { tag, _ ->
                val rows = if (tag != tags.first()) emptyList() else listOf(
                    album(1, tag), album(2, tag).copy(storefront = "us"),
                    album(3, tag), album(3, tag).copy(title = "Conflicted"),
                    album(4, tag).copy(appleScopedId = "itunes:br:album:404"),
                )
                AppleEditorialLoad.Success(rows, AppleEditorialReadState.COMPLETE)
            },
        )
        val result = client.discover(
            "br", today.toString(), onVerifiedPool = { pool = it },
        ) as AppleEditorialLoad.Success
        assertEquals(listOf("itunes:br:album:1"), pool.map { it.appleScopedId })
        assertEquals(pool.map { it.appleScopedId },
            result.releases.map { it.appleScopedId })
    }
}
