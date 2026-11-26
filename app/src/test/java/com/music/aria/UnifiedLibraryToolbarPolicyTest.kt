package com.music.aria

import com.music.aria.data.listening.CanonicalSmartFilterContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedLibraryToolbarPolicyTest {
    @Test fun onlyAlbumDetailsUseAlbumToolbarInsteadOfProfileAndRootFilters() {
        assertTrue(shouldUseUnifiedAlbumDetailToolbar(
            CanonicalSmartFilterContext.ALBUMS, detailOpen = true,
        ))
        assertFalse(shouldUseUnifiedAlbumDetailToolbar(
            CanonicalSmartFilterContext.ALBUMS, detailOpen = false,
        ))
        assertFalse(shouldUseUnifiedAlbumDetailToolbar(
            CanonicalSmartFilterContext.ARTISTS, detailOpen = true,
        ))
        assertFalse(shouldUseUnifiedAlbumDetailToolbar(
            CanonicalSmartFilterContext.SONGS, detailOpen = true,
        ))
        assertFalse(shouldUseUnifiedAlbumDetailToolbar(null, detailOpen = true))
    }

    @Test fun nestedArtistAlbumUsesNativeAlbumToolbarWithoutChangingArtistRoot() {
        assertTrue(shouldUseUnifiedAlbumDetailToolbar(
            CanonicalSmartFilterContext.ARTISTS,
            detailOpen = true,
            nestedAlbumOpen = true,
        ))
        assertFalse(shouldUseUnifiedAlbumDetailToolbar(
            CanonicalSmartFilterContext.ARTISTS,
            detailOpen = true,
            nestedAlbumOpen = false,
        ))
        assertFalse(shouldUseUnifiedAlbumDetailToolbar(
            CanonicalSmartFilterContext.ARTISTS,
            detailOpen = false,
            nestedAlbumOpen = true,
        ))
    }

    @Test fun artistAndAlbumDetailOverflowsUseAnchoredCardWithoutAFullScreenSheet() {
        val artist = com.music.aria.ui.screens.CanonicalLibraryEntityType.ARTIST
        val album = com.music.aria.ui.screens.CanonicalLibraryEntityType.ALBUM
        val track = com.music.aria.ui.screens.CanonicalLibraryEntityType.TRACK
        assertTrue(com.music.aria.ui.screens.canonicalLibraryDetailUsesAnchoredOverflow(true, artist))
        assertTrue(com.music.aria.ui.screens.canonicalLibraryDetailUsesAnchoredOverflow(true, album))
        assertFalse(com.music.aria.ui.screens.canonicalLibraryDetailUsesAnchoredOverflow(true, track))
        assertFalse(com.music.aria.ui.screens.canonicalLibraryDetailUsesAnchoredOverflow(false, artist))
    }

    @Test fun artistDetailGetsNativeOverflowForPinWithoutTakingAlbumOrNestedToolbar() {
        assertTrue(shouldUseUnifiedArtistDetailToolbar(
            CanonicalSmartFilterContext.ARTISTS, detailOpen = true, nestedAlbumOpen = false,
        ))
        assertFalse(shouldUseUnifiedArtistDetailToolbar(
            CanonicalSmartFilterContext.ARTISTS, detailOpen = true, nestedAlbumOpen = true,
        ))
        assertFalse(shouldUseUnifiedArtistDetailToolbar(
            CanonicalSmartFilterContext.ALBUMS, detailOpen = true, nestedAlbumOpen = false,
        ))
        assertFalse(shouldUseUnifiedArtistDetailToolbar(
            CanonicalSmartFilterContext.ARTISTS, detailOpen = false, nestedAlbumOpen = false,
        ))
    }

    @Test fun browseFiltersBelongToCategoryViewButNotArtistDetail() {
        assertTrue(shouldShowUnifiedLibraryBrowseFilters(detailOpen = false))
        assertFalse(shouldShowUnifiedLibraryBrowseFilters(detailOpen = true))
    }
}
