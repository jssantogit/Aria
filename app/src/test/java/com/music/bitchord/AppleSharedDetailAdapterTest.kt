package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.AppleEditorialAlbumDetailPage
import com.music.bitchord.ui.screens.AppleEditorialArtistDetailPage
import com.music.bitchord.ui.screens.AppleEditorialReleaseCategory
import com.music.bitchord.ui.screens.AppleEditorialSearchKind
import com.music.bitchord.ui.screens.AppleEditorialSearchRow
import com.music.bitchord.ui.screens.AppleEditorialTrackRow
import com.music.bitchord.ui.screens.appleAlbumAsSharedDetail
import com.music.bitchord.ui.screens.appleArtistAsSharedDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSharedDetailAdapterTest {
    private val artist = AppleEditorialSearchRow(
        browseId = "itunes:br:artist:1656329288",
        title = "NewJeans", artistName = "NewJeans",
        artworkUri = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/1024x1024bb.jpg",
        releaseDate = null, kind = AppleEditorialSearchKind.ARTIST,
    )
    private fun album(id: Long, category: AppleEditorialReleaseCategory) =
        AppleEditorialSearchRow(
            browseId = "itunes:br:album:$id", title = "Release $id",
            artistName = "NewJeans", artworkUri = null,
            releaseDate = "2024-05-24", category = category,
        )

    @Test fun artistUsesOneOriginalDetailPageWithOriginalReleaseShelves() {
        val page = AppleEditorialArtistDetailPage(
            artist = artist, albums = listOf(
                album(10, AppleEditorialReleaseCategory.ALBUM),
                album(20, AppleEditorialReleaseCategory.EP),
                album(30, AppleEditorialReleaseCategory.SINGLE),
                album(40, AppleEditorialReleaseCategory.UNCLASSIFIED),
            ), possiblyTruncated = false,
        )
        val adapted = assertNotNull(appleArtistAsSharedDetail(page))
        assertEquals(BrowseType.ARTIST, adapted.page.type)
        assertEquals("itunes:br:artist:1656329288", adapted.page.browseId)
        assertEquals(listOf("Álbuns", "EPs", "Singles", "Outros lançamentos"),
            adapted.page.sections.map { it.title })
        assertEquals(listOf("itunes:br:album:10", "itunes:br:album:20",
            "itunes:br:album:30", "itunes:br:album:40"),
            adapted.page.sections.flatMap { it.items }.map { it.browseId })
        assertEquals(artist.artworkUri, adapted.artistHeroUrl)
        assertTrue((adapted.page.songs as UiState.Success).data.isEmpty())
        assertTrue(adapted.metadataTracks.isEmpty())
    }

    @Test fun albumUsesNativeDetailPageWithNonPlayableNumberedMetadata() {
        val selected = album(20, AppleEditorialReleaseCategory.EP)
        val page = AppleEditorialAlbumDetailPage(
            album = selected,
            tracks = listOf(
                AppleEditorialTrackRow("itunes:br:track:101", "How Sweet", "NewJeans", 1, 1),
                AppleEditorialTrackRow("itunes:br:track:102", "Bubble Gum", "NewJeans", 1, 2),
            ), partialTracklist = false,
        )
        val adapted = assertNotNull(appleAlbumAsSharedDetail(page))
        assertEquals(BrowseType.ALBUM, adapted.page.type)
        assertEquals(selected.browseId, adapted.page.browseId)
        assertEquals(listOf("How Sweet", "Bubble Gum"), adapted.metadataTracks.map { it.title })
        assertEquals(listOf(1, 2), adapted.metadataTracks.map { it.trackNumber })
        assertEquals(listOf("itunes:br:track:101", "itunes:br:track:102"),
            adapted.metadataTracks.map { it.key })
        assertTrue((adapted.page.songs as UiState.Success).data.isEmpty())
        assertFalse(adapted.page.browseId.startsWith("catalog:"))
    }

    @Test fun differentAppleIDsNeverCollapseBecauseTheirNamesMatch() {
        val a = appleArtistAsSharedDetail(AppleEditorialArtistDetailPage(
            artist = artist, albums = emptyList(), possiblyTruncated = false))
        val b = appleArtistAsSharedDetail(AppleEditorialArtistDetailPage(
            artist = artist.copy(browseId = "itunes:br:artist:999"),
            albums = emptyList(), possiblyTruncated = false))
        assertTrue(a?.page?.browseId != b?.page?.browseId)
    }

    @Test fun rejectsCrossStorefrontAlbumAndNonArtistPages() {
        val wrong = album(20, AppleEditorialReleaseCategory.EP).copy(
            browseId = "itunes:us:album:20",
        )
        assertNull(appleArtistAsSharedDetail(AppleEditorialArtistDetailPage(
            artist = artist.copy(browseId = "itunes:br:album:1"),
            albums = emptyList(), possiblyTruncated = false)))
        val result = assertNotNull(appleArtistAsSharedDetail(AppleEditorialArtistDetailPage(
            artist = artist, albums = listOf(wrong), possiblyTruncated = false)))
        assertTrue(result.page.sections.isEmpty())
    }
}
