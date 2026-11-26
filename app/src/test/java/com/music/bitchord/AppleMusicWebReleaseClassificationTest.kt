package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.data.catalog.provider.applemusicweb.*
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebReleaseClassificationTest {
    private val album = ItunesEditorialReleaseFormat.ALBUM
    private val ep = ItunesEditorialReleaseFormat.EP
    private val single = ItunesEditorialReleaseFormat.SINGLE
    private val unknown = ItunesEditorialReleaseFormat.UNCLASSIFIED

    private fun edition(
        title: String = "Work", durations: List<Long?>, flag: Boolean? = null,
        declared: Int = durations.size, partial: Boolean = false,
        video: Boolean = false,
    ): AppleMusicWebAlbum {
        val identity = AppleMusicWebItem("100", AppleMusicWebKind.ALBUM, "br", title,
            trackCount = declared, isSingle = flag)
        val tracks = durations.mapIndexed { index, duration ->
            AppleMusicWebItem((index + 1).toString(), AppleMusicWebKind.SONG, "br", "Track",
                durationMillis = duration, discNumber = 1, trackNumber = index + 1)
        } + if (video) listOf(AppleMusicWebItem("99", AppleMusicWebKind.MUSIC_VIDEO, "br", "Video")) else emptyList()
        return AppleMusicWebAlbum(identity, tracks, emptyList(), partial)
    }

    @Test fun explicitTitleWinsOverCommercialAudioAndFalseFlagIsNotAlbum() {
        val explicit = classifyAppleMusicWebRelease(edition("Work - Single", List(4) { 180_000L }, false), true)
        assertEquals(single, explicit.display)
        assertEquals(ep, explicit.commercialAudio)
        assertTrue(explicit.commercialDisagreement)
        assertEquals(ep, classifyAppleMusicWebRelease(edition(durations = List(4) { 180_000L }, flag = false), true).display)
    }

    @Test fun positiveSingleFlagAndConflictingExplicitEpRemainDistinct() {
        val flag = classifyAppleMusicWebRelease(edition(durations = List(4) { 180_000L }, flag = true), true)
        assertEquals(single, flag.display)
        assertEquals(AppleMusicWebFormatEvidence.SOURCE_SINGLE_FLAG, flag.evidence)
        val conflict = classifyAppleMusicWebRelease(edition("Work - EP", listOf(180_000L), true), true)
        assertEquals(unknown, conflict.display)
        assertTrue(conflict.explicitConflict)
    }

    @Test fun commercialBoundariesAndVerifiedVideoExclusion() {
        assertEquals(single, classifyAppleMusicWebRelease(edition(durations = listOf(599_999L)), true).display)
        assertEquals(ep, classifyAppleMusicWebRelease(edition(durations = listOf(600_000L)), true).display)
        assertEquals(ep, classifyAppleMusicWebRelease(edition(durations = List(6) { 300_000L }), true).display)
        assertEquals(album, classifyAppleMusicWebRelease(edition(durations = List(6) { 300_001L }), true).display)
        assertEquals(album, classifyAppleMusicWebRelease(edition(durations = List(7) { 60_000L }), true).display)
        assertEquals(single, classifyAppleMusicWebRelease(edition(durations = listOf(120_000L), declared = 2, video = true), true).display)
    }

    @Test fun partialOrMissingDurationCannotInventShortRelease() {
        assertEquals(unknown, classifyAppleMusicWebRelease(edition(durations = listOf(100_000L), declared = 2, partial = true), false).display)
        assertEquals(unknown, classifyAppleMusicWebRelease(edition(durations = listOf(null)), true).display)
        assertEquals(album, classifyAppleMusicWebRelease(edition(durations = List(7) { null }, declared = 8, partial = true), false).display)
    }

    @Test fun duplicatePositionsAndCrossStorefrontEvidenceFailClosed() {
        val original = edition(durations = listOf(100_000L, 200_000L))
        val duplicate = original.copy(tracks = listOf(original.tracks[0], original.tracks[1].copy(trackNumber = 1)))
        val foreign = original.copy(tracks = listOf(original.tracks[0], original.tracks[1].copy(storefront = "us")))
        assertEquals(unknown, classifyAppleMusicWebRelease(duplicate, true).display)
        assertEquals(unknown, classifyAppleMusicWebRelease(foreign, true).display)
    }
}
