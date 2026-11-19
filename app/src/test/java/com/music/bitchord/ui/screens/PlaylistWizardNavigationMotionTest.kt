package com.music.bitchord.ui.screens

import com.music.bitchord.ui.components.shouldAnimateRouteArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Creator transitions use the shared pushed-page arrival on the live list,
 * not the barely visible 150ms root-tab policy.
 */
class PlaylistWizardNavigationMotionTest {
    @Test fun everyWizardStepGetsANavigationIdentity() {
        assertEquals(
            WizardStep.entries.size,
            WizardStep.entries.map { playlistWizardArrivalKey(it) }.distinct().size,
        )
        val initial = playlistWizardArrivalKey(WizardStep.IDENTITY)
        val songs = playlistWizardArrivalKey(WizardStep.SONGS)
        val review = playlistWizardArrivalKey(WizardStep.REVIEW)
        assertTrue(shouldAnimateRouteArrival(initial, songs, reduced = false))
        assertTrue(playlistWizardArrivalForward(initial, songs))
        assertTrue(playlistWizardArrivalForward(songs, review))
        assertFalse(playlistWizardArrivalForward(review, songs))
        assertFalse(playlistWizardArrivalForward(songs, initial))
    }

    @Test fun normalPlaylistSongsAlbumsAndArtistsTabsEachAnimateInTheCorrectDirection() {
        val songs = playlistWizardArrivalKey(WizardStep.SONGS, browseTabIndex = 0)
        val albums = playlistWizardArrivalKey(WizardStep.SONGS, browseTabIndex = 1)
        val artists = playlistWizardArrivalKey(WizardStep.SONGS, browseTabIndex = 2)
        assertNotEquals(songs, albums)
        assertNotEquals(albums, artists)
        assertTrue(shouldAnimateRouteArrival(songs, albums, reduced = false))
        assertTrue(playlistWizardArrivalForward(songs, albums))
        assertTrue(playlistWizardArrivalForward(albums, artists))
        assertFalse(playlistWizardArrivalForward(artists, songs))
        assertFalse(shouldAnimateRouteArrival(songs, songs, reduced = false))
    }

    @Test fun categoryDrillDownAndFieldSubtabsAnimateWithoutResettingSearch() {
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
        assertTrue(playlistWizardArrivalForward(categories, musicAll))
        assertFalse(playlistWizardArrivalForward(musicAll, categories))
        assertNotEquals(musicAll, musicCredits)
        assertTrue(shouldAnimateRouteArrival(musicAll, musicCredits, reduced = false))
        assertTrue(playlistWizardArrivalForward(musicAll, musicCredits))
        assertFalse(playlistWizardArrivalForward(musicCredits, musicAll))
        assertNotEquals(musicAll, libraryAll)
        // Typing in either Search field is deliberately NOT part of the route.
        assertEquals(musicAll, playlistWizardArrivalKey(
            WizardStep.FIELDS, categoryId = "music", fieldTabIndex = 0,
        ))
        assertFalse(shouldAnimateRouteArrival(musicAll, musicCredits, reduced = true))
    }

    @Test fun nestedRuleGroupsNavigateWithoutChangingRootTabOrDraftIdentity() {
        val root = playlistWizardArrivalKey(WizardStep.GROUP, groupPath = listOf(0))
        val nested = playlistWizardArrivalKey(WizardStep.GROUP, groupPath = listOf(0, 2))
        assertNotEquals(root, nested)
        assertEquals(
            playlistWizardArrivalKey(WizardStep.EDITOR),
            playlistWizardArrivalKey(WizardStep.EDITOR, browseTabIndex = 2,
                categoryId = "music", fieldTabIndex = 4, groupPath = listOf(1, 3)),
        )
        // An editor field may be updated many times without an unwanted
        // content fade. The selection/data state is not in this route key.
        assertFalse(shouldAnimateRouteArrival(
            playlistWizardArrivalKey(WizardStep.EDITOR),
            playlistWizardArrivalKey(WizardStep.EDITOR),
            reduced = false,
        ))
    }
}
