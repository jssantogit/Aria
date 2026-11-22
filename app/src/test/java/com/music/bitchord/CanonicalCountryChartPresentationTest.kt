package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.parseAppleChartBrowseId
import org.junit.Assert.*
import org.junit.Test

class CanonicalCountryChartPresentationTest {
    @Test fun chartCountryNavigationIsShownOnlyForEligibleProvider() {
        assertNull(canonicalCountryChartShelf(false))
        val shelf = canonicalCountryChartShelf(true)!!
        assertEquals("Rankings pelo mundo", shelf.title)
        assertEquals(10, shelf.items.size)
        assertEquals("Top 100 · Brasil", shelf.items.first().title)
        assertTrue(shelf.items.all { it.videoId == null })
        assertTrue(shelf.items.all { it.thumbnailUrl == null })
        assertTrue(shelf.items.all {
            parseAppleChartBrowseId(it.browseId) != null
        })
    }
}
