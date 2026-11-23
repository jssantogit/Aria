package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleUnifiedSearchResultsTest {
    private fun artist(id: Int, title: String) =
        AppleEditorialSearchRow("itunes:br:artist:$id", title, title,
            null, null, kind = AppleEditorialSearchKind.ARTIST)
    private fun album(id: Int) =
        AppleEditorialSearchRow("itunes:br:album:$id", "Release $id",
            "ILLIT", null, null)
    private fun track(id: Int) =
        AppleEditorialTrackSearchRow("itunes:br:track:$id", "Song $id",
            "ILLIT", null, "itunes:br:album:10", null)

    @Test fun exactArtistLeadsOneMixedListWithoutFuzzyNamesakes() {
        val entries = appleUnifiedSearchEntries(
            "illit",
            listOf(artist(1, "ILLIT"), artist(2, "MOKA ILLIT"),
                artist(3, "Ill-it")),
            listOf(track(1), track(2)),
            listOf(album(10), album(11)),
        )
        assertEquals(listOf(
            AppleUnifiedSearchEntry.Artist(artist(1, "ILLIT")),
            AppleUnifiedSearchEntry.Track(track(1)),
            AppleUnifiedSearchEntry.Album(album(10)),
            AppleUnifiedSearchEntry.Track(track(2)),
            AppleUnifiedSearchEntry.Album(album(11)),
        ), entries)
        assertTrue(entries.none { it is AppleUnifiedSearchEntry.Artist &&
            it.row.title != "ILLIT" })
    }

    @Test fun exactArtistScopesGeneralToSourceArtistIdNotNamesakesOrTributes() {
        val official = artist(18, "ILLIT")
        val otherId = "itunes:br:artist:903"
        val actualId = official.browseId
        val officialTrack = track(1).copy(artistBrowseId = actualId)
        val namesake = track(2).copy(artistBrowseId = otherId)
        val tribute = track(3).copy(title = "ILLIT tribute",
            artistName = "Other", artistBrowseId = otherId)
        val noIdExactCredit = track(4)
        val officialAlbum = album(10).copy(artistBrowseId = actualId)
        val namesakeAlbum = album(11).copy(artistBrowseId = otherId)
        val unrelatedAlbum = album(12).copy(artistName = "Other",
            title = "ILLIT Greatest Hits", artistBrowseId = otherId)
        val mixed = appleUnifiedSearchEntries(
            "illit", listOf(official, artist(19, "MOKA ILLIT")),
            listOf(officialTrack, namesake, tribute, noIdExactCredit),
            listOf(officialAlbum, namesakeAlbum, unrelatedAlbum),
        )
        assertEquals(listOf(
            AppleUnifiedSearchEntry.Artist(official),
            AppleUnifiedSearchEntry.Track(officialTrack),
            AppleUnifiedSearchEntry.Album(officialAlbum),
            AppleUnifiedSearchEntry.Track(noIdExactCredit),
        ), mixed)
        // Search tabs still use their original untouched source rows.
        assertEquals(4, listOf(officialTrack, namesake, tribute, noIdExactCredit).size)
        assertEquals(3, listOf(officialAlbum, namesakeAlbum, unrelatedAlbum).size)
        // No verified artist hit: do not pretend a name match proves identity.
        val withoutExact = appleUnifiedSearchEntries("illit",
            listOf(artist(19, "MOKA ILLIT")), listOf(namesake),
            listOf(namesakeAlbum))
        assertTrue(withoutExact.contains(AppleUnifiedSearchEntry.Track(namesake)))
        assertTrue(withoutExact.contains(AppleUnifiedSearchEntry.Album(namesakeAlbum)))
    }

    @Test fun noExactArtistKeepsBoundedCandidatesAndOriginalTypes() {
        val artists = (1..9).map { artist(it, "Artist $it") }
        val entries = appleUnifiedSearchEntries("Artist", artists,
            listOf(track(1), track(1)), listOf(album(10), album(10)))
        assertEquals(3, entries.count { it is AppleUnifiedSearchEntry.Artist })
        assertEquals(1, entries.count { it is AppleUnifiedSearchEntry.Track })
        assertEquals(1, entries.count { it is AppleUnifiedSearchEntry.Album })
    }

    @Test fun unifiedPaginationTargetsOneRealSourceAndAvoidsNamesakeFlood() {
        val states = mapOf(
            AppleSearchSection.ARTISTS to AppleSearchPageStatus(hasMore = true),
            AppleSearchSection.TRACKS to AppleSearchPageStatus(hasMore = true),
            AppleSearchSection.ALBUMS to AppleSearchPageStatus(hasMore = true),
        )
        assertEquals(AppleSearchSection.ALBUMS, appleUnifiedNextPage(
            "illit", listOf(artist(1, "ILLIT")),
            (1..8).map(::track), listOf(album(10)), states,
        ))
        assertNull(appleUnifiedNextPage(
            "illit", listOf(artist(1, "ILLIT")), emptyList(), emptyList(),
            mapOf(AppleSearchSection.ARTISTS to AppleSearchPageStatus(hasMore = true)),
        ))
        assertEquals(AppleSearchSection.ARTISTS, appleUnifiedNextPage(
            "moka", listOf(artist(1, "Moka ILLIT")), emptyList(), emptyList(),
            mapOf(AppleSearchSection.ARTISTS to AppleSearchPageStatus(
                retryableError = true,
            )),
        ))
    }
}
