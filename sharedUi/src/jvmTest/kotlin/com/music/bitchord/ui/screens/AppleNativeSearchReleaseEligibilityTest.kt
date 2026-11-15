package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.SearchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR212 keeps the same rows; eligibility must not invent Apple release formats. */
class AppleNativeSearchReleaseEligibilityTest {
    private fun row(id: String, type: String? = null) = CanonicalCatalogSearchRow(
        musicBrainzId = id,
        kind = CanonicalSearchKind.RELEASE,
        title = "Get Up",
        subtitle = "NewJeans",
        releaseKind = type,
    )

    @Test fun unclassifiedAppleCollectionRemainsVisibleInBothGeneralAndAlbumsFilters() {
        val apple = row("itunes:br:album:123456")
        assertEquals(null, apple.releaseKind)
        assertEquals(listOf(apple), canonicalRowsForFilter(
            listOf(apple), SearchFilter.ALBUMS))
        val grouped = canonicalSearchDisplaySections(listOf(apple), SearchFilter.ALL)
        assertTrue(grouped.any {
            it.first == SearchFilter.ALBUMS && it.second == listOf(apple)
        })
    }

    @Test fun unclassifiedMusicBrainzEditionsAndInvalidAppleRoutesRemainExcluded() {
        val rows = listOf(
            row("1dbb029f-d5cc-4acc-9a1a-fd3b31d27777"),
            row("itunes:br:album:0"),
            row("itunes:br:track:12"),
            row("itunes:BR:album:12"),
            row("itunes:br:album:99999999999999999999999999"),
        )
        assertTrue(canonicalRowsForFilter(rows, SearchFilter.ALBUMS).isEmpty())
    }

    @Test fun verifiedMusicBrainzTypesStillWorkWithoutAppleSpecialCases() {
        val verified = row("1dbb029f-d5cc-4acc-9a1a-fd3b31d27777", "EP")
        assertEquals(listOf(verified),
            canonicalRowsForFilter(listOf(verified), SearchFilter.ALBUMS))
        assertFalse(verified.musicBrainzId.startsWith("itunes:"))
    }
}
