package com.music.bitchord.ui.screens

import org.junit.Assert.*
import org.junit.Test

class DiscogsSearchRelevanceTest {
    private fun artist(id: Long, name: String) =
        DiscogsNativeArtistSearchRow(id, name)
    private fun release(id: Long, title: String) =
        DiscogsNativeReleaseSearchRow(id, title)

    @Test fun illitDoesNotMatchIlliteracyIlliterateOrSubstrings() {
        assertNull(discogsSearchArtistRank("illit", "Illiterate"))
        assertNull(discogsSearchArtistRank("illit", "Illiterati Press"))
        assertNull(discogsSearchArtistRank("illit", "Illitheas"))
        assertNull(discogsSearchArtistRank("illit", "MOKA ILLIT"))
        assertEquals(0, discogsSearchArtistRank("illit", "Illit"))
        assertEquals(1, discogsSearchArtistRank("illit", "Illit (2)"))
        assertNull(discogsSearchReleaseRank("illit",
            "Fecal Matter - Illiteracy Will Prevail"))
        assertNull(discogsSearchReleaseRank("illit",
            "Various - Illiterate"))
        assertNull(discogsSearchReleaseRank("illit",
            "Adam Schmitt - Illiterature"))
        assertEquals(0, discogsSearchReleaseRank("illit",
            "ILLIT - Magnetic"))
        assertEquals(1, discogsSearchReleaseRank("illit",
            "Illit (2) - BREAK EVEN"))
        assertEquals(3, discogsSearchReleaseRank("illit",
            "Various - Illit"))
    }

    @Test fun generalOneMixedListPrefersExactArtistsAndTheirDiscography() {
        val a = artist(17, "Illit")
        val b = artist(18, "Illit (2)")
        val rows = discogsUnifiedSearchEntries("illit",
            listOf(artist(1, "Illiterate"), artist(2, "Illitheas"),
                b, a, artist(3, "Illit Grondal")),
            listOf(
                release(100, "Fecal Matter - Illiteracy Will Prevail"),
                release(101, "ILLIT - Magnetic"),
                release(102, "Various - Illit"),
                release(103, "Illit (2) - Super Real Me"),
            ),
        )
        assertEquals(listOf(
            DiscogsUnifiedSearchEntry.Artist(a),
            DiscogsUnifiedSearchEntry.Artist(b),
            DiscogsUnifiedSearchEntry.Release(release(101, "ILLIT - Magnetic")),
            DiscogsUnifiedSearchEntry.Release(release(103, "Illit (2) - Super Real Me")),
        ), rows)
        assertEquals("discogs:release:101",
            (rows[2] as DiscogsUnifiedSearchEntry.Release).row.browseId)
    }

    @Test fun noExactArtistAllowsRealTitleQueriesButNotSubstrings() {
        assertEquals(listOf(
            DiscogsUnifiedSearchEntry.Release(release(2, "Various - Magnetic")),
        ), discogsUnifiedSearchEntries("magnetic",
            listOf(artist(1, "Magnetics")),
            listOf(release(2, "Various - Magnetic"),
                release(3, "Someone - Magneticall")),
        ))
        assertEquals("Magnetic", discogsSearchReleaseDisplay(
            "ILLIT - Magnetic").title)
        assertEquals("ILLIT", discogsSearchReleaseDisplay(
            "ILLIT - Magnetic").artistCredit)
        assertEquals(DiscogsSearchReleaseDisplay("Album", null),
            discogsSearchReleaseDisplay("Album"))
    }

    @Test fun optionalPaginationDoesNotFloodExactArtistNamesakes() {
        assertEquals(DiscogsUnifiedSearchNext.RELEASES,
            discogsUnifiedNextPage("illit", listOf(artist(10, "Illit")),
                hasMoreArtists = true, hasMoreReleases = true))
        assertNull(discogsUnifiedNextPage("illit", listOf(artist(10, "Illit")),
            hasMoreArtists = true, hasMoreReleases = false))
        assertEquals(DiscogsUnifiedSearchNext.ARTISTS,
            discogsUnifiedNextPage("illit", emptyList(),
                hasMoreArtists = true, hasMoreReleases = false))
        assertEquals(0, discogsSearchArtistRank("Beyonce", "Beyoncé"))
        assertEquals(2, discogsSearchArtistRank("Beatles", "The Beatles"))
        assertNull(discogsSearchArtistRank("illit", "Ill-It"))
    }
}
