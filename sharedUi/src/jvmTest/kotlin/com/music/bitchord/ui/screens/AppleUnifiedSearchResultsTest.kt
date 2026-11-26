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

    @Test fun ambiguousExactArtistDoesNotAttachTracksWithoutNativeCreditId() {
        val primary = artist(1, "ILLIT")
        val homonym = artist(2, "ILLIT")
        val verified = track(1).copy(artistBrowseId = primary.browseId)
        val unknown = track(2)
        val other = track(3).copy(artistBrowseId = homonym.browseId)
        assertEquals(listOf(AppleUnifiedSearchEntry.Artist(primary), AppleUnifiedSearchEntry.Track(verified)),
            appleUnifiedSearchEntries("ILLIT", listOf(primary, homonym),
                listOf(unknown, other, verified), emptyList()))
    }

    @Test fun accentAndLeadingArticleResolveExactArtistWithoutPunctuationConfusion() {
        val beyonce = artist(10, "Beyoncé")
        val credited = track(1).copy(artistName = "Beyoncé",
            artistBrowseId = beyonce.browseId)
        val other = track(2).copy(artistName = "Other",
            artistBrowseId = "itunes:br:artist:999")
        assertEquals(listOf(
            AppleUnifiedSearchEntry.Artist(beyonce),
            AppleUnifiedSearchEntry.Track(credited),
        ), appleUnifiedSearchEntries(
            "Beyonce", listOf(beyonce, artist(11, "Beyonce Tribute")),
            listOf(credited, other), emptyList(),
        ))
        val theBeatles = artist(30, "The Beatles")
        assertEquals(listOf(AppleUnifiedSearchEntry.Artist(theBeatles)),
            appleUnifiedSearchEntries("beatles", listOf(theBeatles),
                emptyList(), emptyList()))
        assertEquals(listOf(AppleUnifiedSearchEntry.Artist(artist(19, "Ill-It"))),
            appleUnifiedSearchEntries("Ill-It",
                listOf(artist(19, "Ill-It")), emptyList(), emptyList()))
        val states = mapOf(AppleSearchSection.ARTISTS to
            AppleSearchPageStatus(hasMore = true))
        assertNull(appleUnifiedNextPage("Beyonce", listOf(beyonce),
            emptyList(), emptyList(), states))
        assertNull(appleUnifiedNextPage("beatles", listOf(theBeatles),
            emptyList(), emptyList(), states))
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
