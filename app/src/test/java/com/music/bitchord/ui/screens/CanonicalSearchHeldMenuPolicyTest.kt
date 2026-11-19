package com.music.bitchord.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSearchHeldMenuPolicyTest {
    private fun result(kind: CanonicalSearchKind, audio: Boolean) = CanonicalCatalogSearchRow(
        musicBrainzId = "test-mbid",
        kind = kind,
        title = "Result",
        subtitle = null,
        hasCatalogedAsset = audio,
    )

    @Test fun metadataOnlyRecordingsHaveNoQueueAction() {
        assertFalse(canonicalSearchHeldQueueAllowed(result(CanonicalSearchKind.RECORDING, false)))
    }

    @Test fun nonRecordingsNeverGetQueueAction() {
        assertFalse(canonicalSearchHeldQueueAllowed(result(CanonicalSearchKind.ARTIST, true)))
        assertFalse(canonicalSearchHeldQueueAllowed(result(CanonicalSearchKind.RELEASE, true)))
        assertFalse(canonicalSearchHeldQueueAllowed(result(CanonicalSearchKind.RELEASE_GROUP, true)))
    }

    @Test fun searchNeverOffersQueueEvenWhenAnAssetIsCataloged() {
        assertFalse(canonicalSearchHeldQueueAllowed(result(CanonicalSearchKind.RECORDING, true)))
    }

    @Test fun searchOffersMetadataSaveForRecordingsAndReleasesButNotArtists() {
        assertTrue(canonicalSearchHeldCanAddToPlaylist(CanonicalSearchKind.RECORDING))
        assertTrue(canonicalSearchHeldCanAddToPlaylist(CanonicalSearchKind.RELEASE))
        assertTrue(canonicalSearchHeldCanAddToPlaylist(CanonicalSearchKind.RELEASE_GROUP))
        assertFalse(canonicalSearchHeldCanAddToPlaylist(CanonicalSearchKind.ARTIST))
    }

    @Test fun metadataOnlySearchNeverOffersNoOpDownloads() {
        // A toast stating that no source exists is not a real download action.
        CanonicalSearchKind.entries.forEach { kind ->
            assertFalse(canonicalSearchHeldShowsDownload(kind))
        }
    }

    @Test fun catalogMetadataPlaylistPreservesOrderedVerifiedRecordingIdsWithoutAudio() {
        val labels = listOf(
            CatalogMetadataRecordingLabel("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Track A", "Artist"),
            CatalogMetadataRecordingLabel("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "Track B", "Artist"),
        )
        val proposed = planCatalogMetadataCollectionMembers(
            listOf("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), labels,
        )
        org.junit.Assert.assertEquals(
            listOf("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            proposed,
        )
        org.junit.Assert.assertNull(planCatalogMetadataCollectionMembers(
            emptyList(), labels + CatalogMetadataRecordingLabel("invalid", "Bad", "Artist"),
        ))
    }
}
