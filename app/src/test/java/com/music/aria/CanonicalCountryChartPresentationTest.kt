package com.music.aria.ui

import com.music.aria.data.catalog.metadata.appleChartTerritories
import com.music.aria.data.catalog.metadata.parseAppleChartBrowseId
import com.music.aria.data.catalog.metadata.parseAppleAlbumChartBrowseId
import org.junit.Assert.*
import org.junit.Test

class CanonicalCountryChartPresentationTest {
    @Test fun chartCountryNavigationIsShownOnlyForEligibleProvider() {
        assertNull(canonicalCountryChartShelf(false))
        val shelf = canonicalCountryChartShelf(true)!!
        assertEquals("Rankings pelo mundo", shelf.title)
        assertEquals(appleChartTerritories.size * 2, shelf.items.size)
        assertEquals("Top 100 músicas · Brasil", shelf.items[0].title)
        assertEquals("Top 100 álbuns · Brasil", shelf.items[1].title)
        assertTrue(shelf.items.all { it.videoId == null })
        assertTrue(shelf.items.all { it.thumbnailUrl == null })
        val songs = shelf.items.filterIndexed { index, _ -> index % 2 == 0 }
        val albums = shelf.items.filterIndexed { index, _ -> index % 2 == 1 }
        assertEquals(appleChartTerritories.map { it.code },
            songs.map { parseAppleChartBrowseId(it.browseId)?.code })
        assertEquals(appleChartTerritories.map { it.code },
            albums.map { parseAppleAlbumChartBrowseId(it.browseId)?.code })
        assertTrue(albums.all { parseAppleChartBrowseId(it.browseId) == null })
        assertTrue(songs.all { parseAppleAlbumChartBrowseId(it.browseId) == null })
    }
}
