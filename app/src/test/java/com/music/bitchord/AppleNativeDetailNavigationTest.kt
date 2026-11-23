package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.AppleEditorialAlbumDetailPage
import com.music.bitchord.ui.screens.AppleEditorialArtistDetailPage
import com.music.bitchord.ui.screens.AppleEditorialSearchKind
import com.music.bitchord.ui.screens.AppleEditorialSearchRow
import com.music.bitchord.ui.screens.AppleNativeDetailKind
import com.music.bitchord.ui.screens.appleAlbumAsSharedDetail
import com.music.bitchord.ui.screens.appleArtistAsSharedDetail
import com.music.bitchord.ui.screens.appleSourceRecentReleaseNeedsGenericLabel
import com.music.bitchord.ui.screens.appleNativeDetailDisplayAllowed
import com.music.bitchord.ui.screens.appleNativeDetailKind
import com.music.bitchord.ui.screens.appleNativeDetailRouteKey
import com.music.bitchord.ui.screens.appleNativePendingDetail
import com.music.bitchord.ui.screens.appleNativeShelfEntry
import com.music.bitchord.ui.screens.appleDetailVisibleOnTab
import com.music.bitchord.ui.screens.verifiedAppleDetailStoreUrl
import org.junit.Assert.*
import org.junit.Test

/** Regression for iTunes Search -> native Artist -> native Album -> Back. */
class AppleNativeDetailNavigationTest {
    private fun artist(id: Long = 17L) = AppleEditorialSearchRow(
        browseId = "itunes:br:artist:$id", title = "Artist",
        artistName = "Artist", artworkUri = null, releaseDate = null,
        kind = AppleEditorialSearchKind.ARTIST,
    )
    private fun album(id: Long = 500L) = AppleEditorialSearchRow(
        browseId = "itunes:br:album:$id", title = "A Real Album",
        artistName = "Artist", artworkUri = null, releaseDate = "2024-01-01",
    )

    @Test fun tappedArtistOpensExactStorefrontRouteNotParallelScreen() {
        val first = artist()
        val second = artist(18)
        val route = appleNativeDetailRouteKey(
            AppleNativeDetailKind.ARTIST, first, null,
        )
        assertEquals("itunes:br:artist:17", route)
        assertNotEquals("apple_native_artist", route)
        assertNotEquals("catalog:ARTIST:17", route)
        assertNotEquals(route, appleNativeDetailRouteKey(
            AppleNativeDetailKind.ARTIST, second, null,
        ))
        assertNull(appleNativeDetailRouteKey(
            AppleNativeDetailKind.ARTIST, null, null,
        ))
    }

    @Test fun typedNavigationRejectsWrongAppleEntitiesAndOtherProviderIds() {
        assertNull(appleNativeDetailRouteKey(
            AppleNativeDetailKind.NONE, artist(), album(),
        ))
        assertNull(appleNativeDetailRouteKey(
            AppleNativeDetailKind.ALBUM, artist(), null,
        ))
        assertNull(appleNativeDetailRouteKey(
            AppleNativeDetailKind.ARTIST, album(), null,
        ))
        assertNull(appleNativeDetailRouteKey(
            AppleNativeDetailKind.ALBUM, null, artist(),
        ))
        assertNull(appleNativeDetailRouteKey(
            AppleNativeDetailKind.ARTIST,
            artist().copy(browseId = "discogs:artist:17"), null,
        ))
        assertNull(appleNativeDetailRouteKey(
            AppleNativeDetailKind.ARTIST,
            artist().copy(browseId = "catalog:ARTIST:17"), null,
        ))
        assertNull(appleNativeDetailRouteKey(
            AppleNativeDetailKind.ARTIST,
            artist().copy(browseId = "itunes:br:track:17"), null,
        ))
        assertNull(appleNativeDetailRouteKey(
            AppleNativeDetailKind.ARTIST,
            artist().copy(browseId = "apple_native_artist"), null,
        ))
    }

    @Test fun artistAndAlbumRenderThroughSameDetailPageWithoutInventingSong() {
        val hit = artist()
        val pending = appleNativePendingDetail(
            hit, AppleNativeDetailKind.ARTIST,
        )!!
        assertEquals(hit.browseId, pending.page.browseId)
        assertEquals(BrowseType.ARTIST, pending.page.type)
        assertTrue(pending.page.songs is UiState.Loading)
        assertTrue(pending.page.sections.isEmpty())

        val loadedArtist = appleArtistAsSharedDetail(
            AppleEditorialArtistDetailPage(
                artist = hit, albums = listOf(album()), possiblyTruncated = false,
            ),
        )!!
        assertEquals(pending.page.browseId, loadedArtist.page.browseId)
        assertEquals("itunes:br:album:500",
            loadedArtist.page.sections.single().items.single().browseId)
        assertNull(loadedArtist.page.sections.single().items.single().videoId)

        val edition = appleNativePendingDetail(
            album(), AppleNativeDetailKind.ALBUM,
        )!!
        assertEquals(BrowseType.ALBUM, edition.page.type)
        assertTrue(edition.page.songs is UiState.Loading)
        val loadedAlbum = appleAlbumAsSharedDetail(
            AppleEditorialAlbumDetailPage(
                album = album(), tracks = emptyList(), partialTracklist = true,
            ),
        )!!
        assertEquals(edition.page.browseId, loadedAlbum.page.browseId)

        // Native album Back removes only the top album state. The unchanged
        // Apple artist identity regains its own exact route.
        assertEquals(AppleNativeDetailKind.ALBUM,
            appleNativeDetailKind(UiState.Loading, UiState.Success(
                AppleEditorialArtistDetailPage(
                    hit, listOf(album()), possiblyTruncated = false,
                ),
            )))
        assertEquals(hit.browseId, appleNativeDetailRouteKey(
            AppleNativeDetailKind.ARTIST, hit, null,
        ))
    }

    @Test fun nestedArtistAndRelatedAlbumShelvesOpenSameExactAppleProduct() {
        val artistShelfRow = album()
        val relatedRow = artistShelfRow.copy(
            artworkUri = "https://is1-ssl.mzstatic.com/image/thumb/Music/abc/600x600bb.jpg",
        )
        assertEquals(relatedRow, appleNativeShelfEntry(
            artistShelfRow.browseId, listOf(artistShelfRow, relatedRow),
        ))
        assertNull(appleNativeShelfEntry(
            artistShelfRow.browseId, listOf(artistShelfRow,
                relatedRow.copy(title = "Conflicting Release")),
        ))
        assertNull(appleNativeShelfEntry(
            "itunes:br:artist:17", listOf(artistShelfRow, relatedRow),
        ))
        assertNull(appleNativeShelfEntry(
            "discogs:release:500", listOf(artistShelfRow, relatedRow),
        ))
    }

    @Test fun appleHomeAlbumDoesNotTurnExploreIntoTheSearchTab() {
        // 0=Home, 1=Explore, 4=Search in BitChord's root navigation.
        assertTrue(appleDetailVisibleOnTab(0, 4, 0))
        assertTrue(appleDetailVisibleOnTab(1, 4, 1))
        assertTrue(appleDetailVisibleOnTab(4, 4, null))
        assertFalse(appleDetailVisibleOnTab(2, 4, 0))
        assertFalse(appleDetailVisibleOnTab(0, 4, null))
        assertFalse(appleDetailVisibleOnTab(1, 4, 0))
    }

    @Test fun providerOverflowOnlyOpensExactVerifiedAppleStorefrontProduct() {
        val appleArtist = "itunes:br:artist:17"
        val appleAlbum = "itunes:br:album:500"
        val link = "https://music.apple.com/br/album/real-album/500"
        assertEquals(link, verifiedAppleDetailStoreUrl(link, appleAlbum))
        assertEquals("https://music.apple.com/br/artist/name/17",
            verifiedAppleDetailStoreUrl("https://music.apple.com/br/artist/name/17",
                appleArtist))
        assertNull(verifiedAppleDetailStoreUrl(link, appleArtist))
        assertNull(verifiedAppleDetailStoreUrl(
            "https://music.apple.com/br/album/real-album/501", appleAlbum,
        ))
        assertNull(verifiedAppleDetailStoreUrl(
            "https://music.apple.com/us/album/real-album/500", appleAlbum,
        ))
        assertNull(verifiedAppleDetailStoreUrl(
            "https://music.apple.com.evil.test/br/album/real-album/500", appleAlbum,
        ))
        assertNull(verifiedAppleDetailStoreUrl(
            "http://music.apple.com/br/album/real-album/500", appleAlbum,
        ))
        assertNull(verifiedAppleDetailStoreUrl(
            "https://person@music.apple.com/br/album/real-album/500", appleAlbum,
        ))
        assertNull(verifiedAppleDetailStoreUrl(
            "https://music.apple.com/br/album/real-album/500",
            "catalog:RELEASE_GROUP:500",
        ))
    }

    @Test fun datedAppleArtistFeaturedCardHasExactProductIdentity() {
        val newest = album(601).copy(
            title = "New EP", releaseDate = "2026-11-01",
            category = com.music.bitchord.ui.screens.AppleEditorialReleaseCategory.EP,
        )
        val older = album(600).copy(
            title = "Old album", releaseDate = "2025-04-01",
        )
        val visual = appleArtistAsSharedDetail(
            AppleEditorialArtistDetailPage(
                artist = artist(), albums = listOf(older, newest),
                possiblyTruncated = false,
            ),
        )!!
        assertEquals(newest.browseId, visual.featuredRelease?.browseId)
        assertEquals("EP · 2026", visual.featuredRelease?.subtitle)
        assertNull(visual.featuredRelease?.videoId)
        assertNull(appleArtistAsSharedDetail(
            AppleEditorialArtistDetailPage(
                artist = artist(), albums = listOf(
                    album().copy(releaseDate = null),
                ), possiblyTruncated = false,
            ),
        )?.featuredRelease)
    }

    @Test fun unclassifiedAppleRecentReleaseNeverClaimsAnAlbumFormat() {
        val unknown = appleArtistAsSharedDetail(
            AppleEditorialArtistDetailPage(
                artist(), listOf(album().copy(
                    category = com.music.bitchord.ui.screens.AppleEditorialReleaseCategory.UNCLASSIFIED,
                )), possiblyTruncated = false,
            ),
        )?.featuredRelease!!
        assertEquals("2024", unknown.subtitle)
        assertTrue(appleSourceRecentReleaseNeedsGenericLabel(unknown))
        val typed = appleArtistAsSharedDetail(
            AppleEditorialArtistDetailPage(
                artist(), listOf(album().copy(
                    category = com.music.bitchord.ui.screens.AppleEditorialReleaseCategory.EP,
                )), possiblyTruncated = false,
            ),
        )?.featuredRelease!!
        assertEquals("EP · 2024", typed.subtitle)
        assertFalse(appleSourceRecentReleaseNeedsGenericLabel(typed))
    }

    @Test fun loadingAndPermissionErrorsUseOriginalNativeDetailPage() {
        val initial = appleNativePendingDetail(
            artist(), AppleNativeDetailKind.ARTIST,
        )!!
        assertTrue(initial.page.songs is UiState.Loading)
        val denied = appleNativePendingDetail(
            artist(), AppleNativeDetailKind.ARTIST,
            errorCode = "itunes_discography_disabled",
        )!!
        val failed = denied.page.songs as UiState.Error
        assertTrue(failed.message.contains("Discografia"))
        assertEquals(initial.page.browseId, denied.page.browseId)
        assertTrue(denied.page.sections.isEmpty())
        assertTrue(denied.metadataTracks.isEmpty())
        assertTrue(appleNativeDetailDisplayAllowed(
            denied, editorialAuthorized = false, searchAuthorized = true,
        ))
        assertFalse(appleNativeDetailDisplayAllowed(
            initial, editorialAuthorized = false, searchAuthorized = true,
        ))
        assertFalse(appleNativeDetailDisplayAllowed(
            denied, editorialAuthorized = false, searchAuthorized = false,
        ))
        assertFalse(appleNativeDetailDisplayAllowed(
            appleArtistAsSharedDetail(AppleEditorialArtistDetailPage(
                artist(), listOf(album()), possiblyTruncated = false,
            ))!!,
            editorialAuthorized = false, searchAuthorized = true,
        ))
    }
}
