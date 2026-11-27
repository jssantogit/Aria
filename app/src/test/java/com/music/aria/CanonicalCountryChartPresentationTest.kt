package com.music.aria.ui

import com.music.aria.data.catalog.metadata.appleChartTerritories
import com.music.aria.data.catalog.metadata.parseAppleAlbumChartBrowseId
import com.music.aria.data.catalog.metadata.parseAppleChartBrowseId
import org.junit.Assert.*
import org.junit.Test

class CanonicalCountryChartPresentationTest {
    @Test fun countryChartShelfIsHiddenWithoutAuthorization() {
        assertNull(canonicalCountryChartShelf(false))
    }

    @Test fun countryChartShelfShowsOneSongDestinationPerTerritoryInOrder() {
        val shelf = canonicalCountryChartShelf(true)!!
        assertEquals("Rankings pelo mundo", shelf.title)
        assertEquals(
            appleChartTerritories.map { "catalog:CHART:itunes:${it.code}" },
            shelf.items.map { it.browseId },
        )
        assertEquals(
            appleChartTerritories,
            shelf.items.map { parseAppleChartBrowseId(it.browseId) },
        )
        assertTrue(shelf.items.all { parseAppleAlbumChartBrowseId(it.browseId) == null })
        assertEquals("Top 100 músicas · Brasil", shelf.items.first().title)
        assertTrue(shelf.items.all { it.videoId == null })
        assertTrue(shelf.items.all { it.thumbnailUrl == null })
    }
}
