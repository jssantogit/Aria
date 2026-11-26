package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.Song
import org.junit.Assert.*
import org.junit.Test

class CanonicalContinueListeningShelfTest {
    private val track = Song(
        videoId = "active-media-1",
        title = "Faixa real",
        artist = "Artista real",
        thumbnailUrl = "https://example.com/cover.jpg",
    )

    @Test fun pausedLivePositionProducesResumeOnlyForExistingMediaItem() {
        val shelf = canonicalContinueListeningShelf(track, false, 70_000L, 240_000L)!!
        assertEquals(CONTINUE_LISTENING_TITLE, shelf.title)
        val item = shelf.items.single()
        assertEquals("Faixa real", item.title)
        assertEquals("Artista real · Retomar em 1:10", item.subtitle)
        assertEquals(CONTINUE_LISTENING_BROWSE_ID, item.browseId)
        assertNull(item.videoId)
    }

    @Test fun titlesCanBeLocalizedWithoutChangingNativeResumeIdentity() {
        val item = canonicalContinueListeningShelf(
            track, false, 70_000L, 240_000L,
            heading = "Continue listening", resumeLabel = "Resume from",
        )!!.items.single()
        assertEquals("Artista real · Resume from 1:10", item.subtitle)
        assertEquals(CONTINUE_LISTENING_BROWSE_ID, item.browseId)
    }

    @Test fun noResumeCardForPlayingUnstartedCompletedOrInvalidPlayback() {
        assertNull(canonicalContinueListeningShelf(track, true, 70_000L, 240_000L))
        assertNull(canonicalContinueListeningShelf(track, false, 0L, 240_000L))
        assertNull(canonicalContinueListeningShelf(track, false, 29_999L, 240_000L))
        assertNull(canonicalContinueListeningShelf(track, false, 235_000L, 240_000L))
        assertNull(canonicalContinueListeningShelf(track, false, 70_000L, 0L))
        assertNull(canonicalContinueListeningShelf(track, false, 70_000L, 50_000L))
        assertNull(canonicalContinueListeningShelf(null, false, 70_000L, 240_000L))
        assertNull(canonicalContinueListeningShelf(track.copy(videoId = ""), false, 70_000L, 240_000L))
    }
}
