package com.music.bitchord.playback

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import org.robolectric.RobolectricTestRunner

/** A stored cache span is not grandfathered authorization for retired video IDs. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class RetiredWatchCacheGateTest {
    @Test fun historicalWatchUriCannotBeAnsweredByCacheBeforeResolverDeniesIt() {
        assertTrue(isRetiredWatchCacheRequest(Uri.parse("bitchord://watch?v=old-video-id")))
        assertTrue(isRetiredWatchCacheRequest(Uri.parse("bitchord://historical?v=old-video-id")))
    }

    @Test fun canonicalAndActiveProviderSourceMediaKeepTheirOwnRouting() {
        assertFalse(isRetiredWatchCacheRequest(
            Uri.parse("bitchord://source?s=addon-1&t=track-2")))
        assertFalse(isRetiredWatchCacheRequest(
            Uri.parse("bitchord://source?s=addon-1&t=track-2&v=metadata")))
        assertFalse(isRetiredWatchCacheRequest(
            Uri.parse("bitchord://canonical?recordingId=record-1")))
    }

    @Test fun opaqueBitChordUriCannotCrashPlaybackPolicyInspection() {
        assertFalse(isRetiredWatchCacheRequest(Uri.parse("bitchord:historical?v=old")))
    }

    @Test fun localFilesAndNonBitChordUrlsAreUnaffected() {
        assertFalse(isRetiredWatchCacheRequest(Uri.parse("file:///sdcard/Music/v.mp3")))
        assertFalse(isRetiredWatchCacheRequest(Uri.parse("content://media/external/audio/3")))
        assertFalse(isRetiredWatchCacheRequest(Uri.parse("https://example.invalid/audio?v=1")))
    }
}
