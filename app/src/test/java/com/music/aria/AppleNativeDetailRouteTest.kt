package com.music.aria

import com.music.aria.data.model.UiState
import com.music.aria.ui.screens.AppleEditorialAlbumDetailPage
import com.music.aria.ui.screens.AppleEditorialArtistDetailPage
import com.music.aria.ui.screens.AppleEditorialReleaseCategory
import com.music.aria.ui.screens.AppleEditorialSearchKind
import com.music.aria.ui.screens.AppleEditorialSearchRow
import com.music.aria.ui.screens.AppleNativeDetailKind
import com.music.aria.ui.screens.appleNativeDetailKind
import com.music.aria.ui.screens.appleNativeArtistSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleNativeDetailRouteTest {
    private fun row(id: Int, category: AppleEditorialReleaseCategory = AppleEditorialReleaseCategory.ALBUM) =
        AppleEditorialSearchRow(
            browseId = "itunes:br:album:$id", title = "Release $id",
            artistName = "Artist", artworkUri = null, releaseDate = null,
            category = category,
        )

    @Test fun nativeAlbumRouteTakesPriorityOverParentArtistOnNestedNavigation() {
        val album = UiState.Success(AppleEditorialAlbumDetailPage(row(10), emptyList(), false))
        val artist = UiState.Success(AppleEditorialArtistDetailPage(
            artist = row(9).copy(browseId = "itunes:br:artist:9", kind = AppleEditorialSearchKind.ARTIST),
            albums = listOf(row(10)), possiblyTruncated = false,
        ))
        assertEquals(AppleNativeDetailKind.ALBUM, appleNativeDetailKind(album, artist))
        assertEquals(AppleNativeDetailKind.ARTIST, appleNativeDetailKind(null, artist))
        assertEquals(AppleNativeDetailKind.NONE, appleNativeDetailKind(null, null))
    }

    @Test fun loadingAndErrorStatesKeepTheNativeBackRouteOpen() {
        assertEquals(AppleNativeDetailKind.ALBUM,
            appleNativeDetailKind(UiState.Loading, null))
        assertEquals(AppleNativeDetailKind.ARTIST,
            appleNativeDetailKind(null, UiState.Error("network")))
    }

    @Test fun artistSectionsReuseNativeGroupingAndRetainExactProductIDs() {
        val rows = listOf(
            row(3, AppleEditorialReleaseCategory.SINGLE),
            row(1), row(4, AppleEditorialReleaseCategory.UNCLASSIFIED),
            row(2, AppleEditorialReleaseCategory.EP),
        )
        val grouped = appleNativeArtistSections(rows)
        assertEquals(listOf("Álbuns", "Singles e EPs", "Outros lançamentos"),
            grouped.map { it.first })
        assertEquals(listOf("itunes:br:album:1", "itunes:br:album:2",
            "itunes:br:album:3", "itunes:br:album:4"),
            grouped.flatMap { it.second }.map { it.browseId })
        assertTrue(grouped.flatMap { it.second }.all { it.browseId.startsWith("itunes:") })
    }
}
