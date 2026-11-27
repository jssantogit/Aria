package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMetadataActionPolicyTest {
    private val validRecording = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    @Test fun exactMusicBrainzRecordingMayBeSavedWithoutMediaAssetButNeverPlayed() {
        assertTrue(isVerifiedCatalogRecordingId(validRecording))
        assertFalse(isVerifiedCatalogRecordingId("local:title:Artist - Song"))
        assertFalse(isVerifiedCatalogRecordingId("https://musicbrainz.org/recording/$validRecording"))
        assertEquals(
            listOf(validRecording),
            planMetadataOnlyPlaylistMembers(emptyList(), validRecording),
        )
        assertEquals(
            listOf(validRecording),
            planMetadataOnlyPlaylistMembers(listOf(validRecording), validRecording),
        )
    }

    @Test fun amsScopedSongIdentityIsOnlyAdmittedForLocalMetadataActions() {
        val br = "apple_music_web:br:song:123"
        val us = "apple_music_web:us:song:123"
        assertFalse(isVerifiedCatalogRecordingId(br))
        assertTrue(isLocalMetadataRecordingId(br))
        assertEquals(listOf(validRecording,br,us),planMetadataOnlyPlaylistMembers(listOf(validRecording,br),us))
        assertEquals(listOf(br),planMetadataOnlyPlaylistMembers(listOf(br),br))
        for (invalid in listOf("apple_music_web:br:album:123","apple_music_web:br:song:0","itunes:br:track:123"))
            assertEquals(null,planMetadataOnlyPlaylistMembers(emptyList(),invalid))
        val track = NativeMetadataTrack(br,"Song","Artist",1,1)
        val release = "apple_music_web:br:album:12"
        assertEquals(listOf(br),amsCollectionRecordingLabels(release,listOf(track,track),true)?.map { it.recordingId })
        assertEquals(null,amsCollectionRecordingLabels(release,listOf(track),false))
        assertEquals(null,amsCollectionRecordingLabels(release,listOf(track.copy(key=us)),true))
        assertEquals(null,amsCollectionRecordingLabels(release,listOf(track,track.copy(title="Conflict")),true))
    }

    @Test fun invalidCatalogMetadataCannotEnterUserPlaylists() {
        assertEquals(null, planMetadataOnlyPlaylistMembers(emptyList(), "not-mbid"))
        assertEquals(null, planMetadataOnlyPlaylistMembers(emptyList(), ""))
    }
}
