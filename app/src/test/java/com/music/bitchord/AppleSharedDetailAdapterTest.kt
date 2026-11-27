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
        val adapted = requireNotNull(appleArtistAsSharedDetail(page))
        assertEquals(BrowseType.ARTIST, adapted.page.type)
        assertEquals("itunes:br:artist:1656329288", adapted.page.browseId)
        assertEquals(listOf("Álbuns", "Singles e EPs", "Outros lançamentos"),
            adapted.page.sections.map { it.title })
        assertEquals(listOf("itunes:br:album:10", "itunes:br:album:20",
            "itunes:br:album:30", "itunes:br:album:40"),
            adapted.page.sections.flatMap { it.items }.map { it.browseId })
        assertEquals(artist.artworkUri, adapted.artistHeroUrl)
        assertTrue((adapted.page.songs as UiState.Success).data.isEmpty())
        assertTrue(adapted.metadataTracks.isEmpty())
    }

    @Test fun appleArtistSinglesAndEpsAreCombinedAndSortedByVerifiedDates() {
        val older = album(11, AppleEditorialReleaseCategory.SINGLE)
            .copy(releaseDate = "2023-01-01")
        val newer = album(12, AppleEditorialReleaseCategory.EP)
            .copy(releaseDate = "2026-08-01")
        val page = requireNotNull(appleArtistAsSharedDetail(
            AppleEditorialArtistDetailPage(
                artist = artist,
                albums = listOf(older, newer),
                possiblyTruncated = false,
            ),
        )).page
        assertEquals(listOf("Singles e EPs"), page.sections.map { it.title })
        assertEquals(listOf(newer.browseId, older.browseId),
            page.sections.single().items.map { it.browseId })
        assertTrue(page.sections.single().items.all { it.videoId == null })
        assertTrue(page.sections.none {
            it.title == "Videoclipes" || it.title == "Álbuns ao vivo" ||
                it.title == "Coletâneas"
        })
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
        val adapted = requireNotNull(appleAlbumAsSharedDetail(page))
        assertEquals(BrowseType.ALBUM, adapted.page.type)
        assertEquals(selected.browseId, adapted.page.browseId)
        assertEquals(listOf("How Sweet", "Bubble Gum"), adapted.metadataTracks.map { it.title })
        assertEquals(listOf(1, 2), adapted.metadataTracks.map { it.trackNumber })
        assertEquals(listOf("itunes:br:track:101", "itunes:br:track:102"),
            adapted.metadataTracks.map { it.key })
        assertTrue((adapted.page.songs as UiState.Success).data.isEmpty())
        assertFalse(adapted.page.browseId.startsWith("catalog:"))
    }

    @Test fun albumTrackDurationIsPreservedInMetadataButNotShownInNativeRows() {
        val selected = album(20, AppleEditorialReleaseCategory.EP)
        val page = AppleEditorialAlbumDetailPage(
            album = selected,
            tracks = listOf(
                AppleEditorialTrackRow("itunes:br:track:101", "Track", "NewJeans", 1, 1,
                    durationMs = 183000L),
                AppleEditorialTrackRow("itunes:br:track:102", "Other", "NewJeans", 1, 2),
            ), partialTracklist = false,
        )
        val adapted = requireNotNull(appleAlbumAsSharedDetail(page))
        assertEquals(listOf(183000L, null), adapted.metadataTracks.map { it.durationMs })
        // The Local Library contract hides individual durations, not the
        // original metadata (including unknown values) used by other consumers.
        assertTrue(adapted.metadataTracks.all {
            com.music.bitchord.ui.screens.nativeMetadataAlbumTrackSubtitle(it, "NewJeans") == null
        })
    }

    @Test fun albumArtistCreditUsesOnlyExactMatchingStorefrontArtistId() {
        val selected = album(20, AppleEditorialReleaseCategory.ALBUM)
            .copy(artistBrowseId = "itunes:br:artist:1656329288")
        val source = AppleEditorialAlbumDetailPage(
            album = selected, tracks = emptyList(), partialTracklist = false,
        )
        val adapted = requireNotNull(appleAlbumAsSharedDetail(source))
        assertEquals("itunes:br:artist:1656329288", adapted.albumArtistBrowseId)
        assertEquals("NewJeans", adapted.albumArtistDisplayName)
        assertNull(requireNotNull(appleAlbumAsSharedDetail(source.copy(
            album = selected.copy(artistBrowseId = "itunes:us:artist:1656329288"),
        ))).albumArtistBrowseId)
        assertNull(requireNotNull(appleAlbumAsSharedDetail(source.copy(
            album = selected.copy(artistBrowseId = "catalog:ARTIST:1656329288"),
        ))).albumArtistBrowseId)
    }

    @Test fun artistRelationCanOnlyBePublishedAsValidatedCanonicalId() {
        val linked = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
        val verified = requireNotNull(appleArtistAsSharedDetail(
            AppleEditorialArtistDetailPage(
                artist = artist, albums = emptyList(), possiblyTruncated = false,
                verifiedMusicBrainzArtistMbid = linked,
            ),
        ))
        assertEquals(linked, verified.verifiedArtistMbid)
        val notAnId = requireNotNull(appleArtistAsSharedDetail(
            AppleEditorialArtistDetailPage(
                artist = artist, albums = emptyList(), possiblyTruncated = false,
                verifiedMusicBrainzArtistMbid = "namesake",
            ),
        ))
        assertNull(notAnId.verifiedArtistMbid)
        assertNull(requireNotNull(appleArtistAsSharedDetail(
            AppleEditorialArtistDetailPage(
                artist = artist, albums = emptyList(), possiblyTruncated = false,
            ),
        )).verifiedArtistMbid)
    }

    @Test fun artistDisplaysOnlyValidatedAppleTrackIdentitiesWithoutPlayableAssets() {
        val track = com.music.bitchord.ui.screens.AppleEditorialTrackSearchRow(
            key = "itunes:br:track:123", title = "Magnetic",
            artistName = "ILLIT", artworkUri = null, albumBrowseId = null,
            durationMs = 180000,
        )
        val wrongStore = track.copy(key = "itunes:us:track:456")
        val invalid = track.copy(key = "catalog:RECORDING:not-an-apple-track")
        val adapted = requireNotNull(appleArtistAsSharedDetail(
            AppleEditorialArtistDetailPage(
                artist = artist, albums = emptyList(), possiblyTruncated = false,
                tracks = listOf(track, track, wrongStore, invalid),
            ),
        ))
        assertEquals(listOf(track), adapted.artistTracks)
        assertTrue((adapted.page.songs as UiState.Success).data.isEmpty())
    }

    @Test fun albumMoreFromArtistUsesOnlyExactAppleStorefrontSiblingIDs() {
        val selected = album(20, AppleEditorialReleaseCategory.EP)
            .copy(artistBrowseId = artist.browseId)
        val sibling = album(21, AppleEditorialReleaseCategory.ALBUM)
            .copy(artistBrowseId = artist.browseId)
        val crossStore = sibling.copy(browseId = "itunes:us:album:21")
        // Same display name, but a different source-owned artist.
        val wrongArtist = sibling.copy(browseId = "itunes:br:album:22",
            artistBrowseId = "itunes:br:artist:999")
        val missingCredit = sibling.copy(browseId = "itunes:br:album:23",
            artistBrowseId = null)
        val sameAlbum = sibling.copy(browseId = selected.browseId)
        val page = requireNotNull(appleAlbumAsSharedDetail(
            AppleEditorialAlbumDetailPage(
                album = selected, tracks = emptyList(), partialTracklist = false,
                relatedAlbums = listOf(sibling, sibling, crossStore, wrongArtist, missingCredit, sameAlbum),
            ),
        ))
        assertEquals(listOf("Mais de NewJeans"), page.page.sections.map { it.title })
        assertEquals(listOf(sibling.browseId),
            page.page.sections.single().items.map { it.browseId })
        assertTrue(page.page.sections.single().items.all { it.videoId == null })
        assertEquals(emptyList<String>(), requireNotNull(appleAlbumAsSharedDetail(
            AppleEditorialAlbumDetailPage(
                album = selected, tracks = emptyList(), partialTracklist = false,
            ),
        )).page.sections.map { it.title })
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
        val result = requireNotNull(appleArtistAsSharedDetail(AppleEditorialArtistDetailPage(
            artist = artist, albums = listOf(wrong), possiblyTruncated = false)))
        assertTrue(result.page.sections.isEmpty())
    }

    @Test fun relatedAlbumRailAndNavigationRejectConflictsAndShareVisibleLimit() {
        val credit = artist.browseId
        val selected = album(20, AppleEditorialReleaseCategory.ALBUM)
            .copy(artistBrowseId = credit)
        val good = album(21, AppleEditorialReleaseCategory.ALBUM)
            .copy(artistBrowseId = credit)
        val conflict = album(22, AppleEditorialReleaseCategory.ALBUM)
            .copy(artistBrowseId = credit)
        val wrongCredit = album(23, AppleEditorialReleaseCategory.ALBUM)
            .copy(artistBrowseId = credit)
        val remaining = (24L..36L).map {
            album(it, AppleEditorialReleaseCategory.ALBUM).copy(artistBrowseId = credit)
        }
        val source = AppleEditorialAlbumDetailPage(
            album = selected, tracks = emptyList(), partialTracklist = false,
            relatedAlbums = listOf(good, good, conflict, conflict.copy(title = "Contradiction"),
                wrongCredit, wrongCredit.copy(artistBrowseId = "itunes:br:artist:999")) + remaining,
        )
        val visible = requireNotNull(appleAlbumAsSharedDetail(source))
            .page.sections.single().items.map { it.browseId }
        val expected = (listOf(good) + remaining).take(12).map { it.browseId }
        assertEquals(expected, visible)
        fun navigate(id: String?) = com.music.bitchord.ui.screens.appleNativeShelfEntryForRoute(
            selected.browseId, id, true, null, source,
        )
        visible.forEach { assertEquals(it, navigate(it)?.browseId) }
        assertNull(navigate(conflict.browseId))
        assertNull(navigate(wrongCredit.browseId))
        assertNull(navigate(remaining.last().browseId))
    }

    @Test fun artistSectionsFeaturedReleaseAndTapUseSameAdmittedAlbums() {
        val valid = album(10, AppleEditorialReleaseCategory.ALBUM)
            .copy(artistBrowseId = artist.browseId)
        val withArtwork = valid.copy(artworkUri = artist.artworkUri)
        val disputed = album(20, AppleEditorialReleaseCategory.ALBUM)
        val foreign = album(30, AppleEditorialReleaseCategory.ALBUM)
            .copy(browseId = "itunes:us:album:30")
        val wrongCredit = album(40, AppleEditorialReleaseCategory.ALBUM)
            .copy(artistBrowseId = "itunes:br:artist:999")
        val source = AppleEditorialArtistDetailPage(
            artist = artist, albums = listOf(valid, withArtwork, disputed,
                disputed.copy(category = AppleEditorialReleaseCategory.EP), foreign, wrongCredit),
            possiblyTruncated = false,
        )
        val visual = requireNotNull(appleArtistAsSharedDetail(source))
        assertEquals(listOf(valid.browseId), visual.page.sections.flatMap { it.items }.map { it.browseId })
        assertEquals(valid.browseId, visual.featuredRelease?.browseId)
        fun navigate(id: String) = com.music.bitchord.ui.screens.appleNativeShelfEntryForRoute(
            artist.browseId, id, true, source, null,
        )
        assertEquals(withArtwork, navigate(valid.browseId))
        assertNull(navigate(disputed.browseId))
        assertNull(navigate(foreign.browseId))
        assertNull(navigate(wrongCredit.browseId))
    }
}
