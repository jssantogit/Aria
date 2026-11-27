package com.music.aria

import com.music.aria.data.catalog.metadata.ItunesEditorialRelease
import com.music.aria.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.aria.data.model.HomeShelf
import com.music.aria.data.model.ShelfItem
import com.music.aria.ui.appleFirstHomeShelves
import org.junit.Assert.*
import org.junit.Test

class AppleHomeEditorialShelvesTest {
    private fun album(id: Long, title: String, date: String? = "2026-11-05") =
        ItunesEditorialRelease(
            appleScopedId = "itunes:br:album:$id", appleCollectionId = id,
            storefront = "br", title = title, artistName = "ILLIT",
            artistId = 42, releaseDate = date, genre = "K-pop",
            format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
            artworkUrl = "https://is1-ssl.mzstatic.com/cover.jpg",
            trackCount = 5, tracks = emptyList(),
            musicBrainzReleaseGroupId = null,
            musicBrainzArtistId = null, musicBrainzRecordingIds = emptyMap(),
        )

    @Test fun missingMusicBrainzHintsDoNotLeaveAppleHomeInInfiniteLoading() {
        // When the MB seed fetch finishes empty, there is no Apple request
        // pending and no claim that a storefront has zero products.
        assertFalse(com.music.aria.ui.appleFirstHomeEditorialIsPending(
            appleStillLoading = true,
            musicBrainzStillLoading = false,
            hasCandidateSeeds = false,
        ))
        assertTrue(com.music.aria.ui.appleFirstHomeEditorialIsPending(
            appleStillLoading = true,
            musicBrainzStillLoading = true,
            hasCandidateSeeds = false,
        ))
        assertTrue(com.music.aria.ui.appleFirstHomeEditorialIsPending(
            appleStillLoading = true,
            musicBrainzStillLoading = false,
            hasCandidateSeeds = true,
        ))
        assertFalse(com.music.aria.ui.appleFirstHomeEditorialIsPending(
            appleStillLoading = false,
            musicBrainzStillLoading = false,
            hasCandidateSeeds = true,
        ))
    }

    @Test fun homeUsesOnlyAppleProductsForPublicReleaseAndDiscoveryShelves() {
        val old = HomeShelf("Lançamentos", listOf(ShelfItem(
            "MusicBrainz Only", "Someone", "https://mb.org/cover.jpg",
            null, "catalog:RELEASE_GROUP:529f87a1-1081-48a4-8ee1-3c08de229858",
        )))
        val local = HomeShelf("Músicas para você", listOf(ShelfItem(
            "Local Song", "My library", null, null, "local:pick:123",
        )))
        val shelves = appleFirstHomeShelves(
            appleAlbums = listOf(album(10, "SUPER REAL ME"),
                album(11, "Older catalog item", date = "2021-01-01")),
            existingLocalShelves = listOf(old, local),
            releasesHeading = "Lançamentos",
            discoveryHeading = "Descoberta global",
            todayIsoDate = "2026-11-13",
        )
        assertEquals(listOf("itunes:br:album:10"),
            shelves.first { it.title == "Lançamentos" }.items.map { it.browseId })
        assertEquals(listOf("itunes:br:album:11"),
            shelves.first { it.title == "Outros lançamentos" }.items.map { it.browseId })
        assertTrue(shelves.flatMap { it.items }.none {
            it.browseId.orEmpty().startsWith("catalog:")
        })
        assertTrue(shelves.flatMap { it.items }.any { it.browseId == "local:pick:123" })
        assertTrue(shelves.flatMap { it.items }.filter {
            it.browseId.orEmpty().startsWith("itunes:")
        }.all { it.videoId == null })
    }


    @Test fun homeDistributesRealAppleFormatsAcrossNativeShelvesWithoutDuplicates() {
        val releases = (1..22).map { i ->
            album(i.toLong(), "Apple $i",
                if (i <= 12) "2026-11-${(i % 11 + 1).toString().padStart(2, '0')}"
                else "2024-05-01",
            ).copy(format = when (i % 4) {
                0 -> ItunesEditorialReleaseFormat.ALBUM
                1 -> ItunesEditorialReleaseFormat.SINGLE
                2 -> ItunesEditorialReleaseFormat.EP
                else -> ItunesEditorialReleaseFormat.UNCLASSIFIED
            })
        }
        val shelves = appleFirstHomeShelves(
            releases, emptyList(), "Lançamentos", "Para descobrir", "2026-11-22",
        )
        val labels = shelves.map { it.title }
        assertTrue("Lançamentos" in labels)
        assertTrue("Para descobrir" in labels)
        assertTrue("Álbuns" in labels)
        assertTrue("Singles e EPs" in labels)
        assertTrue("Outros lançamentos" in labels)
        val all = shelves.flatMap { it.items }
        assertEquals(all.size, all.map { it.browseId }.distinct().size)
        assertTrue(all.all { it.browseId.orEmpty().startsWith("itunes:br:album:") &&
            it.videoId == null })
        val singles = shelves.first { it.title == "Singles e EPs" }.items
        assertTrue(singles.all { item ->
            releases.first { it.appleScopedId == item.browseId }.format in setOf(
                ItunesEditorialReleaseFormat.SINGLE, ItunesEditorialReleaseFormat.EP,
            )
        })
        val albums = shelves.first { it.title == "Álbuns" }.items
        assertTrue(albums.all { item ->
            releases.first { it.appleScopedId == item.browseId }.format ==
                ItunesEditorialReleaseFormat.ALBUM
        })
    }

    @Test fun futureAndInvalidDatesCannotBeCalledNewReleases() {
        val shelves = appleFirstHomeShelves(
            appleAlbums = listOf(album(10, "Future", date = "2027-01-01"),
                album(11, "Undated", date = null),
                album(12, "Old", date = "2020-02-01")),
            existingLocalShelves = emptyList(),
            releasesHeading = "Lançamentos",
            discoveryHeading = "Descoberta",
            todayIsoDate = "2026-11-13",
        )
        assertTrue(shelves.none { it.title == "Lançamentos" })
        assertTrue(shelves.flatMap { it.items }.none { it.title == "Future" })
        assertEquals(setOf("Undated", "Old"),
            shelves.flatMap { it.items }.map { it.title }.toSet())
    }

    @Test fun duplicateOrContradictoryAppleIDsCannotGetTwoHomeCards() {
        val result = appleFirstHomeShelves(
            appleAlbums = listOf(
                album(10, "A"), album(10, "A"),
                album(20, "B"), album(20, "Contradiction"),
            ),
            existingLocalShelves = emptyList(),
            releasesHeading = "Recentes", discoveryHeading = "Descoberta",
            todayIsoDate = "2026-11-13",
        )
        assertEquals(listOf("itunes:br:album:10"),
            result.flatMap { it.items }.map { it.browseId })
    }
}
