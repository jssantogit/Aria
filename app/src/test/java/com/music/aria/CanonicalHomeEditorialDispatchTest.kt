package com.music.aria

import com.music.aria.data.catalog.metadata.ItunesEditorialRelease
import com.music.aria.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.aria.data.model.HomeShelf
import com.music.aria.data.model.HomeShelfRow
import com.music.aria.data.model.ModuleShelfRow
import com.music.aria.data.model.ShelfItem
import com.music.aria.data.model.UiState
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderKey
import com.music.aria.ui.canonicalCuratedHomeRows
import com.music.aria.ui.editorialPrimaryMiss
import com.music.aria.ui.projectCanonicalHomeDisplay
import com.music.aria.ui.projectCanonicalHomeEditorialRows
import com.music.aria.ui.shouldStartAppleHomeEditorial
import com.music.aria.ui.shouldStartAppleHomeEditorialRows
import com.music.aria.ui.shouldStartDiscogsHomeEditorial
import com.music.aria.ui.shouldStartMusicBrainzHomeEditorial
import com.music.aria.ui.screens.CanonicalCatalogSearchRow
import com.music.aria.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure fallback state: dispatch may not bypass a pending preferred provider. */
class CanonicalHomeEditorialDispatchTest {
    @Test fun launchesKeepSlotAcrossAuthorizedFallback() {
        val mb = BuiltInProviderKeys.MUSICBRAINZ
        val apple = BuiltInProviderKeys.ITUNES
        val discogs = BuiltInProviderKeys.DISCOGS
        val today = "2026-11-27"
        val release = appleRelease(55, today)
        val oldAlbum = appleRelease(56, "2026-08-28")
        val cachedApple = UiState.Success(listOf(release, oldAlbum))
        val mbRows = canonicalCuratedHomeRows(
            savedReleases = emptyList(),
            metadata = listOf(CanonicalCatalogSearchRow(
                musicBrainzId = "11111111-1111-4111-8111-111111111111",
                kind = CanonicalSearchKind.RELEASE_GROUP,
                title = "Lançamento MB",
                subtitle = "Artista",
                releaseDate = today,
                releaseKind = "Album",
            )),
            localPicks = emptyList(),
            trendingRecordingMbids = emptyList(),
            releasesHeading = "Lançamentos",
            picksHeading = "Escolhas",
            trendingHeading = "Em alta",
            todayIsoDate = today,
        )
        val personal = HomeShelf("Novidades", listOf(
            ShelfItem("Download", "Artista", null, null, "downloads:album:42"),
        ))
        fun select(
            ordered: List<ProviderKey>,
            mbLoading: Boolean = false,
            home: List<HomeShelfRow> = listOf(personal),
            discogsState: UiState<List<HomeShelf>> = UiState.Success(emptyList()),
            heading: String = "Novidades",
        ) = projectCanonicalHomeEditorialRows(
            canonicalHomeShelves = home,
            appleHomeReleases = cachedApple,
            catalogEditorialOrder = ordered,
            canonicalWorldwideLoading = mbLoading,
            releasesHeading = heading,
            discoveryHeading = heading,
            todayIsoDate = today,
            discogsEditorial = discogsState,
        )

        val pendingMb = select(listOf(mb, apple), mbLoading = true)
        assertEquals(mb, pendingMb.provider)
        assertEquals(UiState.Loading, pendingMb.state)
        for (order in listOf(listOf(discogs, mb, apple), listOf(mb, discogs, apple))) {
            val pendingDiscogs = select(order, discogsState = UiState.Loading)
            assertEquals(discogs, pendingDiscogs.provider)
            assertEquals(UiState.Loading, pendingDiscogs.state)
        }
        val discogsShelf = HomeShelf("Novidades", listOf(
            ShelfItem("Álbum Discogs", "Artista", null, null, "discogs:release:123"),
        ))
        val legacyWinner = select(listOf(mb, discogs, apple),
            discogsState = UiState.Success(listOf(discogsShelf)))
        assertEquals(discogs, legacyWinner.provider)
        assertEquals(listOf(discogsShelf), (legacyWinner.state as UiState.Success).data)
        // The same populated Apple response is still present after admission is removed.
        val revoked = select(listOf(mb))
        assertEquals(null, revoked.provider)
        assertEquals(UiState.Success(emptyList<HomeShelfRow>()), revoked.state)

        val mbWinner = select(listOf(mb, apple), home = mbRows, heading = "Lançamentos")
        val fallback = select(listOf(mb, discogs, apple))
        assertEquals(mb, mbWinner.provider)
        assertEquals(apple, fallback.provider)
        val mbModules = (mbWinner.state as UiState.Success).data.filterIsInstance<ModuleShelfRow>()
        val appleRows = (fallback.state as UiState.Success).data
        val appleModules = appleRows.filterIsInstance<ModuleShelfRow>()
        assertEquals(listOf("home:editorial:launches"), mbModules.map { it.moduleId })
        assertEquals(mbModules.map { it.moduleId }, appleModules.map { it.moduleId })
        assertEquals(listOf(release.appleScopedId), appleModules.single().shelf.items.map { it.browseId })
        assertEquals(listOf(oldAlbum.appleScopedId),
            appleRows.filterIsInstance<HomeShelf>().flatMap { row -> row.items.map { it.browseId } })
        assertFalse(shouldStartAppleHomeEditorialRows(
            ordered = listOf(mb, apple),
            mbLoading = false,
            visibleHomeShelves = mbRows,
            releasesHeading = "Novidades",
        ))
    }

    @Test fun appleFallbackDispatchIgnoresUnmarkedMusicBrainzHomonym() {
        val order = listOf(BuiltInProviderKeys.MUSICBRAINZ, BuiltInProviderKeys.ITUNES)
        val discovery = HomeShelf("Novidades", listOf(
            ShelfItem("Álbum de catálogo", "Artista", null, null,
                "catalog:RELEASE_GROUP:11111111-1111-4111-8111-111111111111"),
        ))

        assertTrue(shouldStartAppleHomeEditorialRows(
            ordered = order,
            mbLoading = false,
            visibleHomeShelves = listOf(discovery),
            releasesHeading = discovery.title,
        ))
    }

    @Test fun enrichingAppleLaunchArtworkKeepsTheMarkedPreviewInSourceOrder() {
        val apple = BuiltInProviderKeys.ITUNES
        val today = "2026-11-27"
        val releases = listOf(
            appleRelease(59, "2026-08-29"),
            appleRelease(53, "2026-08-28"), // Reserved for its format, outside the preview.
            appleRelease(57, today),
            appleRelease(55, "2026-11-01"),
        )
        val artwork = "https://is1-ssl.mzstatic.com/image/thumb/Music/cover/600x600bb.jpg"
        val poolEntry = appleRelease(99, "2026-04-01")
        fun project(products: List<ItunesEditorialRelease>) = projectCanonicalHomeEditorialRows(
            canonicalHomeShelves = emptyList(),
            appleHomeReleases = UiState.Success(products),
            catalogEditorialOrder = listOf(apple),
            canonicalWorldwideLoading = false,
            releasesHeading = "New releases",
            discoveryHeading = "Discover",
            todayIsoDate = today,
        )
        val before = project(releases)
        val after = project(releases.map {
            if (it.appleCollectionId == 57L) it.copy(artworkUrl = artwork) else it
        })
        val originalModules = (before.state as UiState.Success).data.filterIsInstance<ModuleShelfRow>()
        val enrichedModules = (after.state as UiState.Success).data.filterIsInstance<ModuleShelfRow>()
        assertEquals(listOf("home:editorial:launches"), originalModules.map { it.moduleId })
        val originalModule = originalModules.single()
        assertEquals(listOf("itunes:br:album:59", "itunes:br:album:57", "itunes:br:album:55"),
            originalModule.shelf.items.map { it.browseId })
        val enrichedShelf = originalModule.shelf.copy(
            items = originalModule.shelf.items.map {
                if (it.browseId == "itunes:br:album:57") it.copy(thumbnailUrl = artwork) else it
            },
        )
        assertEquals(listOf(originalModule.copy(shelf = enrichedShelf)), enrichedModules)

        val displayed = projectCanonicalHomeDisplay(
            canonicalHomeShelves = emptyList(),
            selection = after,
            publicPlaylistShelf = null,
            appleHomePool = releases + poolEntry,
            appleRevealedCount = 1,
        )
        assertEquals("Mais do catálogo", displayed.last().title)
        assertEquals(listOf("itunes:br:album:99"), displayed.last().items.map { it.browseId })
    }

    private fun appleRelease(id: Long, date: String) = ItunesEditorialRelease(
        appleScopedId = "itunes:br:album:$id",
        appleCollectionId = id,
        storefront = "br",
        title = "Álbum $id",
        artistName = "Artista",
        artistId = 100,
        releaseDate = date,
        genre = null,
        format = ItunesEditorialReleaseFormat.ALBUM,
        artworkUrl = null,
        trackCount = null,
        tracks = emptyList(),
        musicBrainzReleaseGroupId = null,
        musicBrainzArtistId = null,
        musicBrainzRecordingIds = emptyMap(),
    )

    @Test fun noSecondaryWhilePrimaryHasNotAnswered() {
        assertFalse(editorialPrimaryMiss<String>(null))
        assertFalse(editorialPrimaryMiss<String>(UiState.Loading))
        assertFalse(editorialPrimaryMiss(UiState.Success(listOf("itunes:br:album:99"))))
    }

    @Test fun nativeAppleFallbackUsesVisibleMbProductsNotUnrenderableRawRows() {
        val mb = BuiltInProviderKeys.MUSICBRAINZ
        val apple = BuiltInProviderKeys.ITUNES
        val nonCatalog = HomeShelf("Releases", listOf(
            ShelfItem("Personal", "Owner", null, null, "downloads:album:42"),
        ))
        assertTrue(shouldStartAppleHomeEditorial(
            listOf(mb, apple), false, listOf(nonCatalog), "Releases",
        ))
        assertFalse(shouldStartAppleHomeEditorial(
            listOf(mb, apple), true, listOf(nonCatalog), "Releases",
        ))
        val nativeMb = HomeShelf("Releases", listOf(
            ShelfItem("Exact MB release", "Artist", null, null,
                "catalog:RELEASE_GROUP:11111111-1111-4111-8111-111111111111"),
        ))
        assertFalse(shouldStartAppleHomeEditorial(
            listOf(mb, apple), false, listOf(nativeMb), "Releases",
        ))
        assertTrue(shouldStartAppleHomeEditorial(
            listOf(apple, mb), false, listOf(nativeMb), "Releases",
        ))
        assertFalse(shouldStartAppleHomeEditorial(
            listOf(mb), false, emptyList(), "Releases",
        ))
    }

    @Test fun loadingPrimaryHoldsFallbackEvenWhenPriorCompositionSawIdleState() {
        val mb = BuiltInProviderKeys.MUSICBRAINZ
        val apple = BuiltInProviderKeys.ITUNES
        // The dispatcher reevaluates the current loading flow after
        // initiating the MB request; a stale Idle=false snapshot cannot
        // substitute for this still-loading state.
        assertFalse(shouldStartAppleHomeEditorial(
            listOf(mb, apple), mbLoading = true,
            visibleHomeShelves = emptyList(), releasesHeading = "Releases",
        ))
        assertTrue(shouldStartAppleHomeEditorial(
            listOf(mb, apple), mbLoading = false,
            visibleHomeShelves = emptyList(), releasesHeading = "Releases",
        ))
    }

    @Test fun arbitraryThreeProviderHomeOrderNeverSkipsPendingMiddle() {
        val mb = BuiltInProviderKeys.MUSICBRAINZ
        val apple = BuiltInProviderKeys.ITUNES
        val discogs = BuiltInProviderKeys.DISCOGS
        val emptyApple = UiState.Success(emptyList<com.music.aria.data.catalog.metadata.ItunesEditorialRelease>())
        val pendingDiscogs: UiState<List<HomeShelf>> = UiState.Loading
        val missedDiscogs: UiState<List<HomeShelf>> = UiState.Error("unavailable")
        // An Apple miss is NOT sufficient to start third-choice MB.
        assertFalse(shouldStartMusicBrainzHomeEditorial(
            listOf(apple, discogs, mb), emptyApple, pendingDiscogs,
        ))
        assertTrue(shouldStartMusicBrainzHomeEditorial(
            listOf(apple, discogs, mb), emptyApple, missedDiscogs,
        ))
        // MB failed, but Apple must not bypass second-choice Discogs.
        assertFalse(shouldStartAppleHomeEditorial(
            listOf(mb, discogs, apple), false, emptyList(), "Releases",
            pendingDiscogs,
        ))
        assertTrue(shouldStartAppleHomeEditorial(
            listOf(mb, discogs, apple), false, emptyList(), "Releases",
            missedDiscogs,
        ))
        // A Discogs-first install can still fall through MB to Apple.
        assertFalse(shouldStartAppleHomeEditorial(
            listOf(discogs, mb, apple), false, emptyList(), "Releases",
            pendingDiscogs,
        ))
        assertTrue(shouldStartAppleHomeEditorial(
            listOf(discogs, mb, apple), false, emptyList(), "Releases",
            missedDiscogs,
        ))
        assertFalse(shouldStartDiscogsHomeEditorial(
            listOf(apple, mb, discogs), false, emptyList(), "Releases",
            UiState.Loading,
        ))
        assertTrue(shouldStartDiscogsHomeEditorial(
            listOf(apple, mb, discogs), false, emptyList(), "Releases",
            emptyApple,
        ))
    }

    @Test fun secondaryAllowedAfterRealEmptyOrUnavailablePrimary() {
        assertTrue(editorialPrimaryMiss(UiState.Success(emptyList<String>())))
        assertTrue(editorialPrimaryMiss<String>(UiState.Error("unavailable")))
    }
}
