package com.music.aria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTabNavigationPolicyTest {
    private fun simulateRootTap(
        from: String,
        target: Int,
    ): Pair<List<String>, String> {
        val events = mutableListOf<String>()
        var route = from
        dispatchRootTabSelection(
            index = target,
            dismissPushedPages = {
                events += "dismiss:$route"
                route = "no_pushed_page"
            },
            activateRoot = {
                // The prior pushed-page owner must already be gone.
                assertEquals("no_pushed_page", route)
                events += "activate:$it"
                route = "tab:$it"
            },
            focusFreshSearch = { events += "focus_search" },
        )
        return events to route
    }

    @Test fun everyRealTabCanBeReachedDirectlyFromDeepLibraryAndOtherDestinations() {
        val pages = listOf(
            "unified_library:artists:album",
            "unified_library:albums:edition",
            "unified_library:songs",
            "canonical_playlist_library:playlist_detail",
            "smart_playlist_builder",
            "local_recent_additions",
            "library_show_all",
            "public_playlist:listenbrainz",
            "catalog:ARTIST:another",
            "catalog:RELEASE_GROUP:another",
            "rediscovery:recording",
            "my_ratings",
            "settings:appearance",
            "replay:story",
            "history",
            "spotify",
            "discord",
            "explore:genre",
            "tab:0",
            "tab:1",
            "tab:2",
            "tab:3",
        )
        for (page in pages) {
            for (tab in 0..3) {
                val (events, route) = simulateRootTap(page, tab)
                assertEquals("Route from $page to tab $tab", "tab:$tab", route)
                assertEquals(3.takeIf { tab == 3 } ?: 2, events.size)
                assertEquals("dismiss:$page", events.first())
                assertEquals("activate:$tab", events[1])
                assertEquals(tab == 3, events.last() == "focus_search")
            }
        }
    }

    @Test fun tappingCurrentTabAlsoClearsAnOpenSubsection() {
        val (events, route) = simulateRootTap("unified_library:artists", 2)
        assertEquals("tab:2", route)
        assertEquals(listOf("dismiss:unified_library:artists", "activate:2"), events)
    }

    @Test fun searchAlwaysResetsAfterActivatingSearchEvenFromSearchDetail() {
        val (events, route) = simulateRootTap("catalog:RELEASE:detail", 3)
        assertEquals("tab:3", route)
        assertEquals(listOf(
            "dismiss:catalog:RELEASE:detail",
            "activate:3",
            "focus_search",
        ), events)
    }

    @Test fun disabledOrInvalidVisualTabsDoNotTouchTheCurrentPage() {
        for (index in listOf(-1, 4, 5, Int.MAX_VALUE)) {
            var dismissCount = 0
            var rootCount = 0
            var searchCount = 0
            assertFalse(dispatchRootTabSelection(
                index,
                dismissPushedPages = { dismissCount++ },
                activateRoot = { rootCount++ },
                focusFreshSearch = { searchCount++ },
            ))
            assertEquals(0, dismissCount)
            assertEquals(0, rootCount)
            assertEquals(0, searchCount)
        }
        for (index in 0..3) assertTrue(rootTabAcceptsNavigation(index))
    }
}
