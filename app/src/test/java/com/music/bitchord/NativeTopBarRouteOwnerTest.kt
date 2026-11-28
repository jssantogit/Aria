package com.music.bitchord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression: an artist page opened from a playlist can leave the playlist
 * state alive in the back stack. Only the ACTIVE AnimatedContent route may
 * contribute actions to the global FrostedTopBar.
 */
class NativeTopBarRouteOwnerTest {
    @Test fun artistFromPlaylistOwnsOneSearchAndOneOverflowGroup() {
        val owner = nativeTopBarRouteOwner("unified_library", activeDetailBrowseId = null)
        assertEquals(NativeTopBarRouteOwner.UNIFIED_LIBRARY, owner)
        assertFalse(owner == NativeTopBarRouteOwner.PLAYLIST_LIBRARY)
        assertFalse(owner == NativeTopBarRouteOwner.CATALOG_DETAIL)
    }

    @Test fun playlistOpenedWhileLibraryCategoryIsRememberedStillHasOneToolbar() {
        assertEquals(
            NativeTopBarRouteOwner.PLAYLIST_LIBRARY,
            nativeTopBarRouteOwner("canonical_playlist_library", activeDetailBrowseId = null),
        )
    }

    @Test fun publicPlaylistBeatsAnyRememberedLibraryContext() {
        assertEquals(
            NativeTopBarRouteOwner.PUBLIC_PLAYLIST,
            nativeTopBarRouteOwner(
                "public_playlist:listenbrainz-mbid",
                activeDetailBrowseId = "catalog:ARTIST:previous",
            ),
        )
    }

    @Test fun nestedAlbumOrArtistRouteOutranksRememberedCategoryAndPlaylist() {
        for (route in listOf(
            "catalog:ARTIST:astro",
            "catalog:RELEASE_GROUP:circles",
            "itunes:br:artist:17",
            "itunes:br:album:500",
            "discogs:artist:17",
            "discogs:release:500",
            "local:album:circles",
        )) {
            assertEquals(
                NativeTopBarRouteOwner.CATALOG_DETAIL,
                nativeTopBarRouteOwner(route, activeDetailBrowseId = route),
            )
        }
    }

    @Test fun noHiddenPageCanLeakToolbarIntoHomeSearchSettingsOrBuilder() {
        for (route in listOf(
            "tab:0", "tab:4", "settings", "smart_playlist_builder",
            "history", "my_ratings", "rediscovery:recording",
        )) {
            assertEquals(
                NativeTopBarRouteOwner.OTHER,
                nativeTopBarRouteOwner(route, activeDetailBrowseId = "catalog:ARTIST:stale"),
            )
        }
    }

    @Test fun sharedProviderOverflowUsesOnlyVisibleAdmittedRoute() {
        fun page(id: String) = com.music.bitchord.data.model.DetailPage(
            browseId=id,title="Test",subtitle="",thumbnailUrl=null,
            type=com.music.bitchord.data.model.BrowseType.ALBUM,
            songs=com.music.bitchord.data.model.UiState.Loading,
        )
        val ams = page("apple_music_web:br:album:123")
        val itunes = page("itunes:br:album:123")
        val discogs = page("discogs:release:123")
        for (apple in listOf(ams,itunes)) {
            val isAms = apple == ams
            fun pick(route: String, allowed: Boolean = true, local: String? = null) =
                com.music.bitchord.ui.providerOverflowPage(route,local,apple.browseId,discogs.browseId,
                    apple,discogs,isAms && allowed,!isAms && allowed,allowed)
            assertEquals(apple,pick(apple.browseId))
            assertEquals(null,pick(apple.browseId,false))
            assertEquals(null,pick(apple.browseId,local="local:album:test"))
            assertEquals(null,pick("tab:0"))
            assertEquals(discogs,pick(discogs.browseId))
            assertEquals(null,pick(discogs.browseId,false))
            assertEquals(null,pick(if (isAms) itunes.browseId else ams.browseId))
        }
    }

    @Test fun legacyHeldOverflowCannotResurrectARejectedRootOrRevokedProvider() {
        fun held(route: String?, apple: Boolean = true, discogs: Boolean = true) =
            com.music.bitchord.ui.providerHeldOverflowRoute(route, apple, discogs)
        assertEquals(null, held(null))
        assertEquals(null, held("apple_music_web:br:album:123"))
        assertEquals(null, held("catalog:RELEASE:123"))
        assertEquals("itunes:br:album:123", held("itunes:br:album:123", discogs = false))
        assertEquals(null, held("itunes:br:album:123", apple = false))
        assertEquals("discogs:release:123", held("discogs:release:123", apple = false))
        assertEquals(null, held("discogs:release:123", discogs = false))
        assertEquals(null, held("itunes:br:album:invalid"))
        assertEquals(null, held("discogs:release:0"))
        assertEquals(null, held("discogs:artist:123:extra"))
    }

    @Test fun providerHeldCardRequiresExactVisibleSnapshotAndPermission() {
        val card = com.music.bitchord.data.model.ShelfItem(
            title = "Album", subtitle = "Artist", thumbnailUrl = null, videoId = null,
            browseId = "itunes:br:album:123",
        )
        val shelf = com.music.bitchord.data.model.HomeShelf(title = "Albums", items = listOf(card))
        fun allowed(item: com.music.bitchord.data.model.ShelfItem,
                    shelves: List<com.music.bitchord.data.model.HomeShelf> = listOf(shelf),
                    apple: Boolean = true) =
            com.music.bitchord.ui.providerShelfHeldCardAllowed(item, shelves, apple, true)
        org.junit.Assert.assertTrue(allowed(card))
        assertFalse(allowed(card, apple = false))
        assertFalse(allowed(card, shelves = emptyList()))
        assertFalse(allowed(card.copy(title = "Other edition")))
        assertFalse(allowed(card.copy(videoId = "audio")))
    }

    @Test fun recentAdditionsDoesNotShareToolbarWithRememberedPlaylist() {
        assertEquals(
            NativeTopBarRouteOwner.RECENT_ADDITIONS,
            nativeTopBarRouteOwner("local_recent_additions", activeDetailBrowseId = null),
        )
    }
}
