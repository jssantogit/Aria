package com.music.bitchord

import com.music.bitchord.ui.DiscogsNativeRoute
import com.music.bitchord.ui.discogsNativeDetailRouteKey
import com.music.bitchord.ui.discogsArtistPage
import com.music.bitchord.ui.discogsNativeLoadingPage
import com.music.bitchord.ui.discogsNativePageDisplayAllowed
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.DiscogsNativeArtistSearchRow
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsNativeArtistRelease
import com.music.bitchord.data.model.BrowseType
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression: Search -> Artist and Artist -> Release render through the same
 * native DetailScreen and its source-owned browseId route. Never fall back
 * to a generic discogs_native_artist pane or fabricate an MBID.
 */
class DiscogsNativeNavigationTest {
    @Test fun searchArtistToNativeDetailKeepsExactDiscogsIdentity() {
        val selected = discogsNativeDetailRouteKey(
            DiscogsNativeRoute.ARTIST, "discogs:artist:17",
        )
        assertEquals("discogs:artist:17", selected)
        assertNotEquals("discogs_native_artist", selected)
        assertNotEquals("catalog:ARTIST:17", selected)
        assertEquals("discogs:artist:18", discogsNativeDetailRouteKey(
            DiscogsNativeRoute.ARTIST, "discogs:artist:18",
        ))
        assertNotEquals(selected, discogsNativeDetailRouteKey(
            DiscogsNativeRoute.ARTIST, "discogs:artist:18",
        ))
    }

    @Test fun searchHitToArtistScreenToAlbumCardKeepsOneNativeDetailPageContract() {
        // Search row and DetailPage are two projections of the SAME native
        // provider ID. No extra screen key or MusicBrainz identity required.
        val searchHit = DiscogsNativeArtistSearchRow(17L, "Original Artist")
        val release = DiscogsNativeArtistRelease(
            artistId = 17L, releaseId = 500L, masterId = null,
            title = "A Real Edition", year = 2024,
            formats = listOf("Album"), tracklist = emptyList(),
        )
        val page = discogsArtistPage(
            searchHit, listOf(release), hasMore = false, incomplete = false,
        ).page
        assertEquals(BrowseType.ARTIST, page.type)
        assertEquals(searchHit.browseId, page.browseId)
        assertEquals(page.browseId, discogsNativeDetailRouteKey(
            DiscogsNativeRoute.ARTIST, searchHit.browseId,
        ))
        val card = page.sections.single().items.single()
        assertEquals("discogs:release:500", card.browseId)
        assertEquals(card.browseId, discogsNativeDetailRouteKey(
            DiscogsNativeRoute.RELEASE, card.browseId,
        ))
        assertTrue(card.videoId == null && page.thumbnailUrl == null)
    }

    @Test fun tappedArtistPaintsCanonicalDetailLoadingSkeletonBeforeNetworkReturns() {
        val row = DiscogsNativeArtistSearchRow(17L, "Original Artist")
        val pending = discogsNativeLoadingPage(
            row.browseId, row.name, "Artista · Discogs", BrowseType.ARTIST,
        )
        assertEquals(row.browseId, pending.page.browseId)
        assertEquals(BrowseType.ARTIST, pending.page.type)
        assertEquals(row.name, pending.page.title)
        assertTrue(pending.page.songs is UiState.Loading)
        assertTrue(pending.page.sections.isEmpty())
        val loaded = discogsArtistPage(
            row, emptyList(), hasMore = false, incomplete = false,
        )
        assertEquals(pending.page.browseId, loaded.page.browseId)

        val edition = discogsNativeLoadingPage(
            "discogs:release:500", "A Real Edition",
            row.name, BrowseType.ALBUM,
        )
        assertEquals(BrowseType.ALBUM, edition.page.type)
        assertTrue(edition.page.songs is UiState.Loading)
        assertEquals("discogs:release:500", edition.page.browseId)
    }

    @Test fun deniedDiscographyCanOnlyRenderAContentFreeNativePermissionPage() {
        val pending = discogsNativeLoadingPage(
            "discogs:artist:17", "From an authorized search",
            "Artista · Discogs", BrowseType.ARTIST,
        )
        val denied = pending.copy(page = pending.page.copy(
            songs = UiState.Error("Ative a Discografia do Discogs em Provedores."),
        ))
        assertTrue(discogsNativePageDisplayAllowed(
            denied, discographyAuthorized = false, catalogSearchAuthorized = true,
        ))
        assertTrue(discogsNativePageDisplayAllowed(
            pending, discographyAuthorized = true, catalogSearchAuthorized = false,
        ))
        assertFalse(discogsNativePageDisplayAllowed(
            pending, discographyAuthorized = false, catalogSearchAuthorized = true,
        ))
        assertFalse(discogsNativePageDisplayAllowed(
            denied, discographyAuthorized = false, catalogSearchAuthorized = false,
        ))
        val loaded = discogsArtistPage(
            DiscogsNativeArtistSearchRow(17L, "From an authorized search"),
            emptyList(), hasMore = false, incomplete = false,
        )
        assertFalse(discogsNativePageDisplayAllowed(
            loaded, discographyAuthorized = false, catalogSearchAuthorized = true,
        ))
    }

    @Test fun albumNavigationAndBackRestoreArtistRouteExactly() {
        val artist = discogsNativeDetailRouteKey(
            DiscogsNativeRoute.ARTIST, "discogs:artist:17",
        )
        val release = discogsNativeDetailRouteKey(
            DiscogsNativeRoute.RELEASE, "discogs:release:500",
        )
        assertEquals("discogs:release:500", release)
        assertNotEquals(artist, release)
        assertEquals("discogs:artist:17", artist)
    }

    @Test fun genericParallelRoutesAndForeignIdsCannotEnterNativeDetail() {
        val artist = DiscogsNativeRoute.ARTIST
        val edition = DiscogsNativeRoute.RELEASE
        assertNull(discogsNativeDetailRouteKey(artist, null))
        assertNull(discogsNativeDetailRouteKey(null, "discogs:artist:17"))
        assertNull(discogsNativeDetailRouteKey(artist, "discogs_native_artist"))
        assertNull(discogsNativeDetailRouteKey(edition, "discogs_native_release"))
        assertNull(discogsNativeDetailRouteKey(artist, "discogs:release:17"))
        assertNull(discogsNativeDetailRouteKey(edition, "discogs:artist:17"))
        assertNull(discogsNativeDetailRouteKey(artist, "itunes:br:artist:17"))
        assertNull(discogsNativeDetailRouteKey(artist, "catalog:ARTIST:17"))
        assertNull(discogsNativeDetailRouteKey(artist, "discogs:artist:0"))
        assertNull(discogsNativeDetailRouteKey(artist, "discogs:artist:-1"))
        assertNull(discogsNativeDetailRouteKey(artist, "discogs:artist:0017"))
        assertNull(discogsNativeDetailRouteKey(artist, "discogs:artist:1000000000000"))
    }
}
