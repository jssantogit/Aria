package com.music.aria.ui.screens

import com.music.aria.data.catalog.CanonicalBrowseLibrary
import com.music.aria.data.catalog.CanonicalBrowseMediaAsset
import com.music.aria.data.catalog.CanonicalBrowseTrack
import com.music.aria.playback.CanonicalCollectionIntent
import com.music.aria.playback.CanonicalCollectionIntentPlan
import com.music.aria.playback.CanonicalCollectionIntentPlanner
import com.music.aria.playback.CanonicalPlayableAsset
import com.music.aria.playback.CanonicalQueueIdentity
import com.music.aria.playback.CanonicalQueueResolver
import com.music.aria.playback.CanonicalRemotePlaybackRequest
import com.music.aria.playback.CanonicalRemotePlaybackUri
import com.music.aria.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #174 RED: a canonical recording rated 5/5 in the current Navidrome
 * account can also have a reconciled local copy. The old planner rejected
 * both even if a unique local copy could be proven playable.
 */
class SmartCollectionMixedSourcePlaybackRegressionTest {
    @Test fun savedFiveStarRecordingWithUniqueLocalAndRemoteAssetsPlaysUsingLocal() = runBlocking {
        val recordedPlans = mutableListOf<CanonicalCollectionIntentPlan>()
        val resolver = object : CanonicalQueueResolver {
            override suspend fun identifyAll(songs: List<Song>): List<CanonicalQueueIdentity?> =
                error("No Song identity inference allowed")
            override suspend fun resolveAll(identities: List<CanonicalQueueIdentity?>): List<CanonicalPlayableAsset?> =
                identities.map { id ->
                    id ?: return@map null
                    when (id.providerKey) {
                        "local-mediastore" -> CanonicalPlayableAsset(id, "content://media/external/audio/media/42")
                        else -> CanonicalPlayableAsset(
                            id, CanonicalRemotePlaybackUri.encode(
                                CanonicalRemotePlaybackRequest(id.recordingId, id.mediaAssetId!!, id.providerKey!!),
                            ),
                        )
                    }
                }
        }
        val handoff = SmartCollectionBatchHandoff(
            refreshIds = { listOf("canonical-five-star") },
            browse = {
                CanonicalBrowseLibrary(listOf(CanonicalBrowseTrack(
                    recordingId = "canonical-five-star", title = "Fixture", artistDisplay = "Artist",
                    durationMs = 200_000, artworkUri = null,
                    availableAssets = listOf(
                        CanonicalBrowseMediaAsset("remote-42", "opensubsonic:fixture"),
                        CanonicalBrowseMediaAsset("local-42", "local-mediastore"),
                    ),
                )))
            },
            planner = CanonicalCollectionIntentPlanner(resolver),
            submit = { recordedPlans += it; true },
        )
        assertEquals(
            SmartCollectionBatchResult.Submitted,
            handoff.execute(SmartCollectionActionSource.SavedDefinition("rated-five"), CanonicalCollectionIntent.PLAY_NOW),
        )
        assertEquals("local-mediastore", recordedPlans.single().selections.single().identity.providerKey)
    }
}
