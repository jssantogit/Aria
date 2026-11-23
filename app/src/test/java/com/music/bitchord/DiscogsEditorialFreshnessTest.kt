package com.music.bitchord

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.discogsEditorialSnapshotReusable
import org.junit.Assert.*
import org.junit.Test

class DiscogsEditorialFreshnessTest {
    private val startedAt = 1_000_000L
    private val one = HomeShelf("Álbuns", listOf(
        ShelfItem("Source Album", "Artist", null, null, "discogs:release:50"),
    ))

    @Test fun nonEmptyVerifiedSnapshotKeepsItsTwoHourReuseLimit() {
        val source = UiState.Success(listOf(one))
        assertTrue(discogsEditorialSnapshotReusable(
            startedAt, startedAt + 2L * 60L * 60L * 1_000L - 1L, source,
        ))
        assertFalse(discogsEditorialSnapshotReusable(
            startedAt, startedAt + 2L * 60L * 60L * 1_000L, source,
        ))
        assertFalse(discogsEditorialSnapshotReusable(
            startedAt, startedAt - 1L, source,
        ))
    }

    @Test fun deniedTokenOrRateLimitCanBeRetriedSoonWithoutStaleTwoHourError() {
        val denied: UiState<List<HomeShelf>> = UiState.Error("token required")
        assertTrue(discogsEditorialSnapshotReusable(
            startedAt, startedAt + 29_999L, denied,
        ))
        assertFalse(discogsEditorialSnapshotReusable(
            startedAt, startedAt + 30_000L, denied,
        ))
        assertFalse(discogsEditorialSnapshotReusable(
            startedAt, startedAt + 30_000L, UiState.Success(emptyList()),
        ))
        assertFalse(discogsEditorialSnapshotReusable(
            startedAt, startedAt + 1L, UiState.Loading,
        ))
        assertFalse(discogsEditorialSnapshotReusable(0L, startedAt, denied))
        assertFalse(discogsEditorialSnapshotReusable(startedAt, startedAt, null))
    }
}
