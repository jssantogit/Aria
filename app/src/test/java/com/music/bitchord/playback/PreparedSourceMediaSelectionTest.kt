package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.io.IOException

/**
 * A virtual bitchord://source URI has no extension. Media3 selects its parser
 * BEFORE the DataSource resolver is opened. Selecting after the resolver
 * would make HLS/MPD bytes appear to ProgressiveMediaSource as audio.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class PreparedSourceMediaSelectionTest {
    private val logical = MediaItem.Builder()
        .setMediaId("source:track-42")
        .setUri("bitchord://source?s=source42&t=track42")
        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
            .setTitle("Audio selection").build())
        .build()

    @Test fun manifestTypeIsChosenFromWinningPhysicalUrlBeforeMediaSourceConstruction() {
        val physical = DataSpec.Builder()
            .setUri("https://cdn.example/master.m3u8?sig=a")
            .setHttpRequestHeaders(mapOf("X-Token" to "private"))
            .build()
        val prepared = prepareSourceMediaSelection(logical, physical)
        assertEquals(MimeTypes.APPLICATION_M3U8,
            prepared.item.localConfiguration?.mimeType)
        assertEquals(Uri.parse("https://cdn.example/master.m3u8?sig=a"),
            prepared.item.localConfiguration?.uri)
        assertEquals("source:track-42", prepared.item.mediaId)
        assertEquals("Audio selection", prepared.item.mediaMetadata.title.toString())
        assertEquals("private", prepared.resolved.httpRequestHeaders["X-Token"])
    }

    @Test fun dashAndExtensionlessDeclaredHlsAreSelectedWithoutMainThreadNetwork() {
        val dash = prepareSourceMediaSelection(logical,
            DataSpec.Builder().setUri("https://cdn.example/manifest.mpd").build())
        assertEquals(MimeTypes.APPLICATION_MPD,
            dash.item.localConfiguration?.mimeType)
        StreamContainer.declare("https://cdn.example/stream/42", "hls")
        val hls = prepareSourceMediaSelection(logical,
            DataSpec.Builder().setUri("https://cdn.example/stream/42").build())
        assertEquals(MimeTypes.APPLICATION_M3U8,
            hls.item.localConfiguration?.mimeType)
        val audio = prepareSourceMediaSelection(logical,
            DataSpec.Builder().setUri("https://cdn.example/song.flac").build())
        assertNull(audio.item.localConfiguration?.mimeType)
    }

    @Test fun typedPhysicalManifestIsNeverExposedThroughPlayerTimeline() {
        val physical = MediaItem.Builder()
            .setMediaId(logical.mediaId)
            .setUri("https://cdn.example/master.mpd?secret=hidden-in-url")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build()
        val underlying = androidx.media3.exoplayer.source.SinglePeriodTimeline(
            1_000_000L, true, false, false, null, physical,
        )
        val timeline = sourceMediaTimeline(underlying, logical)
        val window = timeline.getWindow(0, androidx.media3.common.Timeline.Window())
        assertEquals(logical.mediaId, window.mediaItem.mediaId)
        assertEquals(logical.localConfiguration?.uri,
            window.mediaItem.localConfiguration?.uri)
        assertTrue(window.mediaItem.localConfiguration?.uri.toString()
            .indexOf("secret=hidden") == -1)
        assertEquals(1_000_000L, window.durationUs)
    }

    @Test fun invalidLogicalSourceCannotBecomeASourceSelection() {
        try {
            prepareSourceMediaSelection(MediaItem.fromUri("file:///music/a.flac"),
                DataSpec.Builder().setUri("https://cdn.example/a.mpd").build())
            org.junit.Assert.fail("Non-source item cannot be preselected")
        } catch (_: IOException) { }
    }

    @Test fun noNetworkOrSourceFactoryCallHappensWhileMediaSourceIsCreated() {
        var calls = 0
        val unused = androidx.media3.datasource.DataSource.Factory {
            error("I/O only after preparation")
        }
        val factory = SourceScopedMediaSourceFactory(
            defaultFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(unused),
            createSourceFactory = {
                calls++
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(unused)
            },
            preflightSource = { item ->
                calls++
                prepareSourceMediaSelection(item,
                    DataSpec.Builder().setUri("https://cdn.example/stream.mpd").build())
            },
        )
        val lazyMediaSource = factory.createMediaSource(logical)
        assertTrue(lazyMediaSource is DeferredResolvedSourceMediaSource)
        assertEquals(0, calls)
    }
}
