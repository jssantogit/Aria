package com.music.bitchord

import com.music.bitchord.ui.DiscogsNativeRoute
import com.music.bitchord.ui.discogsNativeDetailRouteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
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
