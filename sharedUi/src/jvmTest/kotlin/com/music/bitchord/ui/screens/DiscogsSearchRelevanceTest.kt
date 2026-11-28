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
            DiscogsUnifiedSearchEntry.Artist(b),
            DiscogsUnifiedSearchEntry.Release(release(101, "ILLIT - Magnetic")),
            DiscogsUnifiedSearchEntry.Release(release(103, "Illit (2) - Super Real Me")),
        ), rows)
        assertEquals("discogs:release:101",
            (rows[1] as DiscogsUnifiedSearchEntry.Release).row.browseId)
    }

    @Test fun numberedHomonymsCollapseVisuallyWithoutCombiningSourceIds() {
        val numbered = artist(18, "Illit (2)")
        val plain = artist(17, "Illit")
        assertEquals("Illit", discogsArtistDisplayName(numbered.name))
        assertEquals(listOf(numbered), discogsSearchDistinctArtists(
            "illit", listOf(numbered, plain)))
        assertEquals(listOf(plain), discogsSearchDistinctArtists(
            "illit", listOf(plain, numbered)))
        assertEquals(listOf(numbered), discogsSearchDistinctArtists(
            "illit (2)", listOf(plain, numbered)))
        assertEquals(listOf(
            DiscogsUnifiedSearchEntry.Artist(numbered),
            DiscogsUnifiedSearchEntry.Release(release(101, "ILLIT - Magnetic")),
        ), discogsUnifiedSearchEntries("illit", listOf(numbered, plain),
            listOf(release(101, "ILLIT - Magnetic"))))
        assertEquals("discogs:artist:18", numbered.browseId)
        assertEquals("discogs:artist:17", plain.browseId)
        assertEquals(listOf(artist(1, "Beyoncé")),
            discogsSearchDistinctArtists("beyonce", listOf(
                artist(1, "Beyoncé"), artist(2, "Beyonce (2)"))))
        assertEquals(listOf(artist(3, "Illit Grondal")),
            discogsSearchDistinctArtists("illit",
                listOf(artist(3, "Illit Grondal"))))
    }

    @Test fun compoundTitleQueriesKeepSourceCreditWithoutAdmittingNamesakes() {
        assertEquals(3, discogsSearchReleaseRank("David Bowie Hunky Dory", "David Bowie - Hunky Dory"))
        assertEquals(3, discogsSearchReleaseRank("Hunky Dory - David Bowie", "David Bowie - Hunky Dory"))
        assertEquals(4, discogsSearchReleaseRank("David Bowie Hunky Dory", "David Bowie - Hunky Dory (Remastered)"))
        assertEquals(3, discogsSearchReleaseRank("illit magnetic", "Illit (2) - Magnetic"))
        assertEquals(3, discogsSearchReleaseRank("illit (2) magnetic", "Illit (2) - Magnetic"))
        assertNull(discogsSearchReleaseRank("illit (2) magnetic", "Illit - Magnetic"))
        assertNull(discogsSearchReleaseRank("David Bowie Hunky Dory", "David Bowies - Hunky Dory"))
        assertNull(discogsSearchReleaseRank("David Bowie Hunky Dory", "Other - Hunky Dory"))
        assertNull(discogsSearchReleaseRank("illit magnetic", "Illiterate - Magnetic"))
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

    @Test fun releaseProjectionGroupsOnlyExplicitMasterIdentityAcrossPages() {
        val first = DiscogsNativeReleaseSearchRow(20, "Artist - Album", 900)
        val edition = DiscogsNativeReleaseSearchRow(10, "Artist - Album", 900)
        val independent = DiscogsNativeReleaseSearchRow(30, "Artist - Album", 901)
        val unknown = DiscogsNativeReleaseSearchRow(40, "Artist - Album")
        val zero = DiscogsNativeReleaseSearchRow(50, "Artist - Album", 0)
        val previous = discogsSearchDistinctReleases("album", listOf(first, edition))
        assertEquals(listOf(first), previous)
        val merged = discogsSearchDistinctReleases("album",
            previous + listOf(edition, independent, unknown, zero, unknown))
        assertEquals(listOf(first, independent, unknown, zero), merged)
        assertEquals("discogs:release:20", merged.first().browseId)
        assertEquals(merged.map { DiscogsUnifiedSearchEntry.Release(it) },
            discogsUnifiedSearchEntries("album", emptyList(),
                listOf(first, edition, independent, unknown, zero)))
        val duplicatePage = discogsSearchDistinctReleases("album", previous + edition)
        assertTrue(discogsSearchNeedsNextRelevantPage(duplicatePage.size - previous.size, 3))
        val unrelated = DiscogsNativeReleaseSearchRow(60, "Other - Other", 900)
        assertEquals(listOf(first), discogsSearchDistinctReleases("album", listOf(unrelated, first)))
    }


    @Test fun representativeReleaseWinsOverExplicitPackagingWithinMaster() {
        val packageEdition = DiscogsNativeReleaseSearchRow(10, "NewJeans - Get Up (Weverse Version)", 900)
        val representative = DiscogsNativeReleaseSearchRow(20, "NewJeans - Get Up", 900)
        val independent = DiscogsNativeReleaseSearchRow(30, "NewJeans - Other Album", 901)
        val unrelatedMaster = packageEdition.copy(releaseId = 40, masterId = 902)
        assertEquals(listOf(representative, independent, unrelatedMaster),
            discogsSearchDistinctReleases("NewJeans",
                listOf(packageEdition, representative, independent, unrelatedMaster)))
        assertEquals(listOf(packageEdition),
            discogsSearchDistinctReleases("NewJeans", listOf(packageEdition)))
        assertEquals(listOf(packageEdition),
            discogsSearchDistinctReleases("NewJeans Get Up (Weverse Version)",
                listOf(representative, packageEdition)))
    }

    @Test fun ordinaryParentheticalTitlesKeepSourceOrderAndIdentity() {
        val first = DiscogsNativeReleaseSearchRow(10, "Artist - Album (Live)", 900)
        val second = DiscogsNativeReleaseSearchRow(20, "Artist - Album", 900)
        assertEquals(listOf(first), discogsSearchDistinctReleases("Artist", listOf(first, second)))
        val unknown = DiscogsNativeReleaseSearchRow(30, "Artist - Album (Photobook)")
        assertEquals(listOf(second, unknown),
            discogsSearchDistinctReleases("Artist", listOf(unknown, second)))
    }

    @Test fun initialServerPagesFullOfJunkAreSkippedWithinExistingHardCap() {
        assertTrue(discogsSearchNeedsNextRelevantPage(0, 2))
        assertTrue(discogsSearchNeedsNextRelevantPage(0, 3))
        assertFalse(discogsSearchNeedsNextRelevantPage(0, 4))
        assertFalse(discogsSearchNeedsNextRelevantPage(0, null))
        assertFalse(discogsSearchNeedsNextRelevantPage(1, 2))
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
    @Test fun repeatedReleaseWithContradictoryMasterCannotHideAnIndependentEdition() {
        val first = DiscogsNativeReleaseSearchRow(20, "Artist - Album", 900)
        val hidden = DiscogsNativeReleaseSearchRow(21, "Artist - Album", 900)
        val conflicting = first.copy(masterId = 901)
        assertEquals(listOf(first), discogsSearchDistinctReleases("album", listOf(first, hidden)))
        assertEquals(listOf(first.copy(masterId = null), hidden),
            discogsSearchDistinctReleases("album", listOf(first, hidden, conflicting)))
        assertEquals(listOf(hidden), discogsSearchDistinctReleases("album",
            listOf(first, hidden, first.copy(title = "Different - Work"))))
        assertEquals(listOf(first), discogsSearchDistinctReleases("album",
            listOf(first.copy(masterId = null), first)))
    }


    @Test fun disputedArtistIdOnLaterPageCannotHideIndependentHomonym() {
        val first = artist(17, "Illit")
        val independent = artist(18, "Illit (2)")
        val conflicting = artist(17, "Different Artist")
        assertEquals(listOf(first), discogsSearchDistinctArtists("illit", listOf(first, independent)))
        assertEquals(listOf(independent), discogsSearchDistinctArtists("illit",
            listOf(first, independent, conflicting)))
        assertEquals(listOf(first, independent), discogsSearchVerifiedArtistObservations(
            listOf(first, independent, first)))
    }

    @Test fun mixedSearchLoadingFollowsOnlyTheSelectedRemoteContinuation() {
        fun loading(local: Boolean, next: DiscogsUnifiedSearchNext?, artists: Boolean, releases: Boolean) =
            discogsUnifiedContinuationLoading(local, next, artists, releases)
        assertFalse(loading(true, DiscogsUnifiedSearchNext.RELEASES, true, true))
        assertTrue(loading(false, DiscogsUnifiedSearchNext.RELEASES, false, true))
        assertFalse(loading(false, DiscogsUnifiedSearchNext.RELEASES, true, false))
        assertTrue(loading(false, DiscogsUnifiedSearchNext.ARTISTS, true, false))
        assertFalse(loading(false, DiscogsUnifiedSearchNext.ARTISTS, false, true))
        assertFalse(loading(false, null, true, true))
    }
}
