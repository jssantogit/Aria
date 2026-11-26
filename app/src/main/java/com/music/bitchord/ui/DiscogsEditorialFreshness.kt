package com.music.bitchord.ui

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState

/**
 * Time-scoped provider Content is not a permanent app catalog. A successful
 * Discogs sample can be reused for two hours; negative results and denied
 * access are not cached for two hours after credentials/permissions change.
 *
 * Calls in flight are handled by the owning Job, not by this snapshot guard.
 */
internal fun discogsEditorialSnapshotReusable(
    startedAtMillis: Long,
    nowMillis: Long,
    state: UiState<List<HomeShelf>>?,
): Boolean {
    if (startedAtMillis <= 0L || nowMillis < startedAtMillis) return false
    val age = nowMillis - startedAtMillis
    val ttl = when (state) {
        is UiState.Success -> if (state.data.isNotEmpty()) 2L * 60L * 60L * 1_000L
            else 30_000L
        is UiState.Error -> 30_000L
        UiState.Loading, null -> return false
    }
    return age < ttl
}
