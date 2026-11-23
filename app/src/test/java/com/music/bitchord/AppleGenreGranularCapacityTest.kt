package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The iTunes Search API has no native offset. Test retaining its bounded
 * verified response and revealing real storefront IDs in stable native rails.
 */
class AppleGenreGranularCapacityTest {
    private fun edition(id: Long, genre: String = "Rock") = ItunesEditorialRelease(
        appleScopedId = "itunes:br:album:$id",
        appleCollectionId = id, storefront = "br",
        title = "Album $id", artistName = "Band", artistId = 42,
        releaseDate = "2026-11-01", genre = genre,
        format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
        artworkUrl = "https://is1-ssl.mzstatic.com/music.jpg",
        trackCount = 2, tracks = emptyList(),
        musicBrainzReleaseGroupId = null, musicBrainzArtistId = null,
        musicBrainzRecordingIds = emptyMap(),
    )

    @Test fun threeBoundedSourceSizesKeepEveryVerifiedAlbumBeyondLandingTwentyFour() =
        runBlocking {
            for (count in listOf(30, 100, 200)) {
                var requests = 0
                val explorer = AppleExploreGenreDiscovery(
                    nativeSource = AppleNativeGenreSearchSource { term, storefront ->
                        requests++
                        assertEquals("rock", term)
                        assertEquals("br", storefront)
                        AppleEditorialLoad.Success(
                            (1L..count.toLong()).map(::edition),
                            AppleEditorialReadState.POSSIBLY_TRUNCATED,
                        )
                    },
                )
                var progress = 0
                val found = explorer.discoverNative("rock", "br") {
                    progress = it.size
                } as AppleEditorialLoad.Success
                assertEquals(1, requests)
                assertEquals(count, progress)
                assertEquals(count, found.releases.size)
                assertTrue(found.releases.all { it.appleScopedId.startsWith("itunes:br:album:") })
                assertEquals(AppleEditorialReadState.POSSIBLY_TRUNCATED, found.state)

                val first = appleFirstGenreShelves(
                    "rock", found.releases, null, "Lançamentos", "2026-11-23",
                )
                val all = first.flatMap { it.items }.mapNotNull { it.browseId }.toMutableSet()
                assertEquals(24, all.size)
                var revealed = 24
                while (revealed < count) {
                    revealed = minOf(revealed + 24, count)
                    val additional = appleGenreContinuationShelf(
                        "rock", true, first, found.releases, revealed,
                    )?.items.orEmpty()
                    additional.mapNotNull { it.browseId }.forEach(all::add)
                }
                assertEquals(count, all.size)
                assertTrue(all.all { it.startsWith("itunes:br:album:") })
            }
        }

    @Test fun contradictoryStorefrontIdentityDoesNotBecomeAnAdditionalCard() = runBlocking {
        val client = AppleExploreGenreDiscovery(
            nativeSource = AppleNativeGenreSearchSource { _, _ ->
                AppleEditorialLoad.Success(
                    (1L..35L).map(::edition) + listOf(
                        edition(36L, "Pop"),
                        edition(37L).copy(storefront = "us"),
                        edition(38L).copy(appleScopedId = "itunes:br:album:999"),
                        edition(39L), edition(39L).copy(title = "Changed by API"),
                    ), AppleEditorialReadState.COMPLETE,
                )
            },
        )
        val releases = (client.discoverNative("rock") as AppleEditorialLoad.Success).releases
        assertEquals(35, releases.size)
        val first = appleFirstGenreShelves("rock", releases, null, "Lançamentos")
        val extra = appleGenreContinuationShelf("rock", true, first, releases, 100)!!
        assertEquals(11, extra.items.size)
        assertNull(appleGenreContinuationShelf("pop", true, first, releases, 100))
        assertNull(appleGenreContinuationShelf("rock", false, first, releases, 100))
    }

    @Test fun providerPolicyBlocksAppleOverflowWhenDiscogsOwnsGenre() {
        val source = (1L..60L).map(::edition)
        val apple = mapOf("rock" to UiState.Success(source))
        val discogs = mapOf("rock" to UiState.Success(listOf(
            HomeShelf("Álbuns", listOf(
                ShelfItem("Discogs", "Artist", null, null, "discogs:release:111"),
            )),
        )))
        fun project(ordered: List<com.music.bitchord.data.providers.ProviderKey>) =
            projectCanonicalGenreState(
                tag = "rock", genrePlaylists = emptyList(),
                playlistHeading = "Playlists", appleByTag = apple,
                musicBrainzRows = UiState.Success(emptyList()),
                ordered = ordered, releasesHeading = "Lançamentos",
                discogsByTag = discogs, appleVisibleCount = 60,
            )
        val appleOnly = (project(listOf(BuiltInProviderKeys.ITUNES))
            as UiState.Success).data
        assertEquals("Mais do gênero", appleOnly.last().title)
        assertEquals(36, appleOnly.last().items.size)
        assertTrue(appleOnly.last().items.all {
            it.browseId.orEmpty().startsWith("itunes:br:album:") &&
                it.videoId == null
        })

        val discogsFirst = (project(listOf(
            BuiltInProviderKeys.DISCOGS, BuiltInProviderKeys.ITUNES,
        )) as UiState.Success).data
        assertFalse(discogsFirst.any { it.title == "Mais do gênero" })
        assertTrue(discogsFirst.any { shelf ->
            shelf.items.any { it.browseId == "discogs:release:111" }
        })
    }
}
