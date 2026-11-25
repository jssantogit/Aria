package com.music.aria.ui.screens

import com.music.aria.data.listening.SavedCanonicalStaticPlaylist
import com.music.aria.data.catalog.CanonicalRecordingCoverGroupRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: a mixed local / MusicBrainz-only static playlist is not broken. */
class CanonicalPlaylistMixedMusicBrainzTest {
    private val metadataId = "123e4567-e89b-12d3-a456-426614174000"
    private val playableId = "223e4567-e89b-12d3-a456-426614174000"

    private val metadata = metadataOnlyUnavailablePlaylistRow(
        metadataId,
        CatalogMetadataRecordingLabel(metadataId, "two mistakes", "Kaylen Naicker"),
    )
    private val playable = V2DisplayRow(
        playableId, "Pureflow", "LE SSERAFIM",
        localArtworkUri = "content://library/pureflow",
    )

    @Test fun mixedPlaylistKeepsPlaybackWhileMetadataOnlyPlaylistRemainsInert() {
        assertFalse(metadata.isAvailablePlaylistMember())
        assertTrue(playable.isAvailablePlaylistMember())
        assertTrue(canonicalPlaylistHasPlayableRows(listOf(metadata, playable)))
        assertTrue(canonicalPlaylistHasPlayableRows(listOf(playable, metadata)))
        assertFalse(canonicalPlaylistHasPlayableRows(listOf(metadata)))
        assertFalse(canonicalPlaylistHasPlayableRows(emptyList()))
        assertFalse(canonicalPlaylistHasPlayableRows(null))
        assertEquals("Kaylen Naicker · Sem áudio", metadata.artist)
    }

    @Test fun genericMusicNotesNeverClaimAPlaylistCoverSlot() {
        val anotherCover = playable.copy(
            id = "323e4567-e89b-12d3-a456-426614174000",
            title = "Bad Idea",
            localArtworkUri = "content://library/bad-idea",
        )
        assertEquals(
            listOf(playable, anotherCover),
            distinctCanonicalPlaylistCoverRows(
                listOf(metadata, playable, metadata, anotherCover),
            ),
        )
        assertTrue(distinctCanonicalPlaylistCoverRows(listOf(metadata)).isEmpty())
        assertEquals(1, distinctCanonicalPlaylistCoverRows(
            listOf(playable, playable.copy(id = "another-recording")),
        ).size)
        // A remote asset by itself is not proof that an artwork bitmap exists.
        assertTrue(distinctCanonicalPlaylistCoverRows(
            listOf(playable.copy(
                id = "remote-without-art",
                localArtworkUri = null,
                remoteMediaAssetId = "remote-asset",
            )),
        ).isEmpty())
        assertEquals(
            "content://library/pureflow",
            canonicalPlaylistPaletteArtworkUri(null, listOf(metadata, playable)),
        )
    }

    @Test fun verifiedOfficialMusicBrainzArtIsVisualOnlyAndMayJoinTheCollage() {
        val trusted = "https://coverartarchive.org/release-group/official/250.jpg"
        val covers = mapVerifiedPlaylistRecordingCovers(
            listOf(metadataId),
            listOf(
                CanonicalRecordingCoverGroupRow(metadataId, "group-without-art"),
                CanonicalRecordingCoverGroupRow(metadataId, "group-official"),
                CanonicalRecordingCoverGroupRow("unknown", "group-other"),
            ),
            mapOf(
                "group-without-art" to "http://example.com/cover.jpg",
                "group-official" to trusted,
                "group-other" to "https://coverartarchive.org/other.jpg",
            ),
        )
        assertEquals(mapOf(metadataId to trusted), covers)
        val visual = distinctCanonicalPlaylistVisualCovers(
            listOf(playable, metadata), covers,
        )
        assertEquals(2, visual.size)
        assertEquals(playable, visual[0].playable)
        assertEquals(trusted, visual[1].verifiedCatalogUrl)
        assertFalse(metadata.isAvailablePlaylistMember())
        assertEquals(trusted, canonicalPlaylistPaletteArtworkUri(
            null, listOf(metadata), covers,
        ))
        assertEquals(emptyList<CanonicalPlaylistVisualCover>(),
            distinctCanonicalPlaylistVisualCovers(
                listOf(metadata), mapOf(metadataId to "http://evil.example/cover.jpg"),
            ))
    }

    @Test fun removalOnlyChangesTheTargetRecordingAndKeepsPlaylistIdentity() {
        val playlist = SavedCanonicalStaticPlaylist(
            id = "playlist-kk", name = "kk",
            recordingIds = listOf(playableId, metadataId),
            sourceProviderKey = "local:mediastore",
            description = "Playlist de teste",
            coverUri = "content://library/custom",
        )
        val updated = removeCanonicalPlaylistRecording(playlist, metadataId)
        assertEquals(listOf(playableId), updated.recordingIds)
        assertEquals(playlist.id, updated.id)
        assertEquals(playlist.name, updated.name)
        assertEquals(playlist.sourceProviderKey, updated.sourceProviderKey)
        assertEquals(playlist.description, updated.description)
        assertEquals(playlist.coverUri, updated.coverUri)
        assertEquals(playlist, removeCanonicalPlaylistRecording(playlist, "missing"))
    }
}
