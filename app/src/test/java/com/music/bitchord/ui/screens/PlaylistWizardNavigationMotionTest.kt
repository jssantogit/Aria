package com.music.bitchord.ui.screens

import com.music.bitchord.ui.components.playlistWizardFadeMillis
import com.music.bitchord.ui.components.playlistWizardStationaryArrivalFrame
import com.music.bitchord.ui.components.shouldAnimateRouteArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Creator steps and subtabs share a stationary, content-only fade.
 * Route identity still owns the transition, not search text or draft edits.
 */
class PlaylistWizardNavigationMotionTest {
    @Test fun everyWizardStepGetsANavigationIdentityWithoutDirectionalMovement() {
        assertEquals(
            WizardStep.entries.size,
            WizardStep.entries.map { playlistWizardArrivalKey(it) }.distinct().size,
        )
        val initial = playlistWizardArrivalKey(WizardStep.IDENTITY)
        val songs = playlistWizardArrivalKey(WizardStep.SONGS)
        val review = playlistWizardArrivalKey(WizardStep.REVIEW)
        assertTrue(shouldAnimateRouteArrival(initial, songs, reduced = false))
        assertTrue(shouldAnimateRouteArrival(songs, review, reduced = false))
        assertTrue(shouldAnimateRouteArrival(review, songs, reduced = false))
        assertFalse(shouldAnimateRouteArrival(initial, initial, reduced = false))
        assertEquals(0f, playlistWizardStationaryArrivalFrame(0f).translationDp, 0.0001f)
        assertEquals(0f, playlistWizardStationaryArrivalFrame(0.5f).translationDp, 0.0001f)
        assertEquals(0f, playlistWizardStationaryArrivalFrame(1f).translationDp, 0.0001f)
    }

    @Test fun normalPlaylistSongsAlbumsAndArtistsTabsFadeWithoutSliding() {
        val songs = playlistWizardArrivalKey(WizardStep.SONGS, browseTabIndex = 0)
        val albums = playlistWizardArrivalKey(WizardStep.SONGS, browseTabIndex = 1)
        val artists = playlistWizardArrivalKey(WizardStep.SONGS, browseTabIndex = 2)
        assertNotEquals(songs, albums)
        assertNotEquals(albums, artists)
        assertTrue(shouldAnimateRouteArrival(songs, albums, reduced = false))
        assertTrue(shouldAnimateRouteArrival(albums, artists, reduced = false))
        assertTrue(shouldAnimateRouteArrival(artists, songs, reduced = false))
        assertFalse(shouldAnimateRouteArrival(songs, songs, reduced = false))
        assertEquals(165, playlistWizardFadeMillis(false))
        assertEquals(0, playlistWizardFadeMillis(true))
    }

    @Test fun categoryDrillDownAndFieldSubtabsFadeWithoutResettingSearch() {
        val categories = playlistWizardArrivalKey(WizardStep.CATEGORIES)
        val musicAll = playlistWizardArrivalKey(
            WizardStep.FIELDS, categoryId = "music", fieldTabIndex = 0,
        )
        val musicCredits = playlistWizardArrivalKey(
            WizardStep.FIELDS, categoryId = "music", fieldTabIndex = 1,
        )
        val libraryAll = playlistWizardArrivalKey(
            WizardStep.FIELDS, categoryId = "library", fieldTabIndex = 0,
        )
        assertTrue(shouldAnimateRouteArrival(categories, musicAll, reduced = false))
        assertTrue(shouldAnimateRouteArrival(musicAll, categories, reduced = false))
        assertNotEquals(musicAll, musicCredits)
        assertTrue(shouldAnimateRouteArrival(musicAll, musicCredits, reduced = false))
        assertTrue(shouldAnimateRouteArrival(musicCredits, musicAll, reduced = false))
        assertNotEquals(musicAll, libraryAll)
        // Typing in either Search field is not part of the route identity.
        assertEquals(musicAll, playlistWizardArrivalKey(
            WizardStep.FIELDS, categoryId = "music", fieldTabIndex = 0,
        ))
        assertFalse(shouldAnimateRouteArrival(musicAll, musicCredits, reduced = true))
    }

    @Test fun nestedRuleGroupsNavigateWithoutChangingRootTabOrDraftIdentity() {
        val root = playlistWizardArrivalKey(WizardStep.GROUP, groupPath = listOf(0))
        val nested = playlistWizardArrivalKey(WizardStep.GROUP, groupPath = listOf(0, 2))
        assertNotEquals(root, nested)
        assertTrue(shouldAnimateRouteArrival(root, nested, reduced = false))
        assertTrue(shouldAnimateRouteArrival(nested, root, reduced = false))
        assertEquals(
            playlistWizardArrivalKey(WizardStep.EDITOR),
            playlistWizardArrivalKey(WizardStep.EDITOR, browseTabIndex = 2,
                categoryId = "music", fieldTabIndex = 4, groupPath = listOf(1, 3)),
        )
        assertFalse(shouldAnimateRouteArrival(
            playlistWizardArrivalKey(WizardStep.EDITOR),
            playlistWizardArrivalKey(WizardStep.EDITOR),
            reduced = false,
        ))
    }

    @Test fun emptyPlaylistBrowseQueryDoesNotWaitForTypingDebounce() {
        assertEquals(0L, normalPlaylistSearchDebounceMillis(""))
        assertEquals(0L, normalPlaylistSearchDebounceMillis("  "))
        assertEquals(160L, normalPlaylistSearchDebounceMillis("newjeans"))
        assertEquals(160L, normalPlaylistSearchDebounceMillis("a"))
    }

    @Test fun stationaryFadeIsSubtleFiniteAndRespectsStableFinalState() {
        val start = playlistWizardStationaryArrivalFrame(0f)
        val middle = playlistWizardStationaryArrivalFrame(0.5f)
        val end = playlistWizardStationaryArrivalFrame(1f)
        assertEquals(0.88f, start.alpha, 0.0001f)
        assertEquals(0.94f, middle.alpha, 0.0001f)
        assertEquals(1f, end.alpha, 0.0001f)
        assertTrue(start.alpha < middle.alpha && middle.alpha < end.alpha)
        for (progress in listOf(-1f, 0f, 0.5f, 1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val frame = playlistWizardStationaryArrivalFrame(progress)
            assertEquals(0f, frame.translationDp, 0.0001f)
            assertTrue(frame.alpha in 0.88f..1f)
        }
        assertEquals(end, playlistWizardStationaryArrivalFrame(Float.NaN))
        assertEquals(end, playlistWizardStationaryArrivalFrame(Float.POSITIVE_INFINITY))
    }
}
