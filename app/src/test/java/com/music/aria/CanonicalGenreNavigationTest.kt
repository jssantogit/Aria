package com.music.aria

import com.music.aria.ui.CanonicalExploreGenre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalGenreNavigationTest {
    private val metal = CanonicalExploreGenre("metal", "Metal")
    private val death = CanonicalExploreGenre("death metal", "Death Metal", "metal")
    private val doom = CanonicalExploreGenre("doom metal", "Doom Metal", "metal")

    @Test fun nestedGenreBackReturnsToImmediateParentAndRestoresSearchOrigin() {
        val events = mutableListOf<String>()
        val nav = CanonicalGenreNavigation(
            showExplore = { events += "explore" },
            returnToSearch = { events += "search" },
            requestMusicBrainz = { events += "mb:$it" },
        )
        nav.open(metal, fromSearch = true)
        nav.open(death, fromSearch = false)
        nav.open(doom, fromSearch = false)
        nav.back()
        assertEquals(death, nav.current)
        nav.back()
        assertEquals(metal, nav.current)
        nav.back()
        assertNull(nav.current)
        assertEquals(
            listOf("explore", "mb:metal", "mb:death metal", "mb:doom metal",
                "mb:death metal", "mb:metal", "search"), events,
        )
    }

    @Test fun exploreRootCanOpenAndCloseWithoutOpeningSearch() {
        val events = mutableListOf<String>()
        val nav = CanonicalGenreNavigation(
            showExplore = { events += "explore" },
            returnToSearch = { events += "search" },
            requestMusicBrainz = { events += it },
        )
        nav.open(metal, fromSearch = false)
        nav.open(death, fromSearch = false)
        nav.clear()
        assertNull(nav.current)
        nav.back()
        assertEquals(listOf("metal", "death metal"), events)
    }

    @Test fun selectingSameCategoryDoesNotCreateSelfReferentialBackLoop() {
        val nav = CanonicalGenreNavigation({}, {}, {})
        nav.open(death, fromSearch = false)
        nav.open(death, fromSearch = false)
        nav.back()
        assertNull(nav.current)
    }
}
