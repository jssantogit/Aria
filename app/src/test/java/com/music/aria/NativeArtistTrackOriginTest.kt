package com.music.aria

import com.music.aria.ui.screens.NativeArtistTrackOrigin
import com.music.aria.ui.screens.NATIVE_ARTIST_RELEASE_ITEM_SPACING
import com.music.aria.ui.screens.NATIVE_ARTIST_RELEASE_TOP_SPACING
import com.music.aria.ui.screens.NATIVE_ARTIST_TRACK_COVER_SIZE
import com.music.aria.ui.screens.nativeArtistTrackOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeArtistTrackOriginTest {
    @Test fun appleAndMusicBrainzRelationProduceOnlyOneArtistSongRail() {
        assertEquals(NativeArtistTrackOrigin.APPLE_METADATA,
            nativeArtistTrackOrigin(true, true, true, false))
        assertEquals(NativeArtistTrackOrigin.VERIFIED_CATALOG,
            nativeArtistTrackOrigin(false, true, true, false))
        assertEquals(NativeArtistTrackOrigin.SCOPED_CATALOG,
            nativeArtistTrackOrigin(false, false, true, false))
        assertEquals(NativeArtistTrackOrigin.OWNED_AUDIO,
            nativeArtistTrackOrigin(false, false, false, true))
        assertEquals(NativeArtistTrackOrigin.NONE,
            nativeArtistTrackOrigin(false, false, false, false))
    }
    @Test fun verifiedSourceMetadataNeverCreatesASecondTrackRail() {
        assertEquals(NativeArtistTrackOrigin.SOURCE_METADATA,
            nativeArtistTrackOrigin(false, false, false, false, hasSourceMetadata = true))
        assertEquals(NativeArtistTrackOrigin.APPLE_METADATA,
            nativeArtistTrackOrigin(true, true, true, true, hasSourceMetadata = true))
        assertEquals(NativeArtistTrackOrigin.VERIFIED_CATALOG,
            nativeArtistTrackOrigin(false, true, true, true, hasSourceMetadata = true))
        assertEquals(NativeArtistTrackOrigin.SCOPED_CATALOG,
            nativeArtistTrackOrigin(false, false, true, true, hasSourceMetadata = true))
    }

    @Test fun sameArtistVisualMetricsAcrossSources() {
        assertEquals(48f, NATIVE_ARTIST_TRACK_COVER_SIZE.value)
        assertEquals(14f, NATIVE_ARTIST_RELEASE_ITEM_SPACING.value)
        assertEquals(22f, NATIVE_ARTIST_RELEASE_TOP_SPACING.value)
    }
}
