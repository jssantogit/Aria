package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song

/**
 * This is a paused *live player* recovery surface, not a ranking of recent
 * listens. PlaybackService already persists the queue and playhead through
 * LastPlayed / BoundedContextPersistence; the UI reads the restored Media3
 * position, and can only invoke Play on that same existing player.
 *
 * No listening-event duration, song metadata, arbitrary history row, or
 * provider-only catalog item can be substituted for this actual playhead.
 */
internal const val CONTINUE_LISTENING_BROWSE_ID = "session:continue-current"
internal const val CONTINUE_LISTENING_TITLE = "Continue ouvindo"

private const val MIN_RESUME_POSITION_MS = 30_000L
private const val MIN_RESUME_DURATION_MS = 60_000L
private const val MIN_TIME_REMAINING_MS = 15_000L

private fun resumeClock(positionMs: Long): String =
    "${positionMs / 60_000L}:${((positionMs / 1_000L) % 60L).toString().padStart(2, '0')}"

/** A single resume action, no fake media/stream URL and no inferred progress. */
internal fun canonicalContinueListeningShelf(
    song: Song?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
): HomeShelf? {
    if (song == null || isPlaying || song.videoId.isBlank() ||
        song.title.isBlank() || song.artist.isBlank() ||
        durationMs < MIN_RESUME_DURATION_MS ||
        positionMs < MIN_RESUME_POSITION_MS ||
        positionMs > durationMs - MIN_TIME_REMAINING_MS
    ) return null
    return HomeShelf(
        title = CONTINUE_LISTENING_TITLE,
        items = listOf(ShelfItem(
            title = song.title,
            subtitle = "${song.artist} · Retomar em ${resumeClock(positionMs)}",
            thumbnailUrl = song.thumbnailUrl,
            videoId = null,
            browseId = CONTINUE_LISTENING_BROWSE_ID,
        )),
    )
}
