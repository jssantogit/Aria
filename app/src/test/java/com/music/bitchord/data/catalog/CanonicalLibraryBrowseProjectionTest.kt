package com.music.bitchord.data.catalog

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The worker-dispatched Room projection must preserve Recording identity,
 * stable artist/title ordering and every eligible source MediaAsset. */
class CanonicalLibraryBrowseProjectionTest {
    @Test fun backgroundProjectionRetainsGroupingAndStableSort() = runBlocking {
        val rows = listOf(
            CanonicalBrowseAssetRow(
                recordingId = "rec-b", mediaAssetId = "asset-b-2",
                providerKey = "opensubsonic:one", title = "Second",
                artistDisplay = "Zeta", durationMs = 150_000L, artworkUri = null,
            ),
            CanonicalBrowseAssetRow(
                recordingId = "rec-a", mediaAssetId = "asset-a",
                providerKey = "local-mediastore", title = "First",
                artistDisplay = "Alpha", durationMs = 120_000L, artworkUri = "content://art/1",
            ),
            CanonicalBrowseAssetRow(
                recordingId = "rec-b", mediaAssetId = "asset-b-1",
                providerKey = "local-mediastore", title = "Second",
                artistDisplay = "Zeta", durationMs = 150_000L,
                artworkUri = "content://art/2",
            ),
        )
        val store = object : CanonicalLibraryBrowseStore {
            override fun observeTrackAssets(providerKey: String): Flow<List<CanonicalBrowseAssetRow>> =
                flowOf(rows)
        }
        val result = CanonicalLibraryBrowseRepository(store).observe("multi-source").first()
        assertEquals(listOf("rec-a", "rec-b"), result.tracks.map { it.recordingId })
        assertEquals("content://art/1", result.tracks[0].artworkUri)
        assertEquals("content://art/2", result.tracks[1].artworkUri)
        assertEquals(listOf("asset-b-1", "asset-b-2"),
            result.tracks[1].availableAssets.map { it.mediaAssetId })
    }
}
