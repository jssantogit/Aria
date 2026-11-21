package com.music.bitchord.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** First-party artist ratings do not require playable songs. */
class NativeArtistRatingActionVisibilityTest {
    @Test fun artistHeroActionsStayAvailableWhenSearchHasNoMatches() {
        val original = listOf("track-A", "track-B")
        val filtered = emptyList<String>()
        assertTrue(shouldShowNativeCanonicalArtistActions(
            hasTracks = nativeArtistHeroTrackSet(original).isNotEmpty(),
            canRate = false,
        ))
        assertEquals(original, nativeArtistHeroTrackSet(original))
        assertTrue(filtered.isEmpty())
        assertTrue(nativeArtistHeroTrackSet(emptyList<String>()).isEmpty())
    }

    @Test fun metadataOnlyVerifiedArtistKeepsItsStarButNotPlayback() {
        assertTrue(shouldShowNativeCanonicalArtistActions(hasTracks = false, canRate = true))
        assertFalse(shouldShowNativeCanonicalArtistActions(hasTracks = false, canRate = false))
        assertTrue(shouldShowNativeCanonicalArtistActions(hasTracks = true, canRate = false))
    }
}
