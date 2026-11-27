package com.music.aria

import com.music.aria.data.model.UiState
import com.music.aria.data.model.HomeShelf
import com.music.aria.data.model.ShelfItem
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.ui.shouldStartAppleHomeEditorial
import com.music.aria.ui.shouldStartDiscogsHomeEditorial
import com.music.aria.ui.shouldStartMusicBrainzHomeEditorial
import com.music.aria.ui.editorialPrimaryMiss
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure fallback state: dispatch may not bypass a pending preferred provider. */
class CanonicalHomeEditorialDispatchTest {
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
