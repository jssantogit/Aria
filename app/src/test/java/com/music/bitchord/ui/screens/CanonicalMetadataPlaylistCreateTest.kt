package com.music.bitchord.ui.screens

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalMetadataPlaylistCreateTest {
    private val first = CatalogMetadataRecordingLabel(
        "123e4567-e89b-12d3-a456-426614174000", "Faixa 1", "Artista",
    )
    private val second = CatalogMetadataRecordingLabel(
        "223e4567-e89b-12d3-a456-426614174000", "Faixa 2", "Artista",
    )

    @Test fun newPlaylistStoresOnlyVerifiedRecordingIdsInOriginalOrder() {
        val planned = planCanonicalMetadataPlaylistCreation(
            "  Minha playlist  ", listOf(first, second, first), "playlist-id",
        )
        assertEquals("Minha playlist", planned?.name)
        assertEquals("playlist-id", planned?.id)
        assertEquals(listOf(first.recordingId, second.recordingId), planned?.recordingIds)
        assertEquals(null, planned?.sourceProviderKey)
    }

    @Test fun invalidIdentityOrNameCannotProduceAPlaylist() {
        assertNull(planCanonicalMetadataPlaylistCreation("", listOf(first), "id"))
        assertNull(planCanonicalMetadataPlaylistCreation("nome", emptyList(), "id"))
        assertNull(planCanonicalMetadataPlaylistCreation(
            "nome", listOf(CatalogMetadataRecordingLabel("spotify-id", "Faixa", "Artista")), "id",
        ))
        assertNull(planCanonicalMetadataPlaylistCreation("nome", listOf(first), ""))
    }

    @Test fun metadataMustBeStoredBeforeNewPlaylistAndFailureDoesNotWrite() = runBlocking {
        val calls = mutableListOf<String>()
        val failed = persistCanonicalMetadataPlaylist(
            "Nome", listOf(first), "playlist-id",
            rememberLabels = { calls += "labels-failed"; false },
            savePlaylist = { calls += "playlist" },
        )
        assertNull(failed)
        assertEquals(listOf("labels-failed"), calls)

        calls.clear()
        val result = persistCanonicalMetadataPlaylist(
            "Nome", listOf(first, second), "playlist-id",
            rememberLabels = { calls += "labels"; true },
            savePlaylist = {
                calls += "playlist"
                assertEquals(listOf(first.recordingId, second.recordingId), it.recordingIds)
            },
        )
        assertEquals("playlist-id", result?.id)
        assertEquals(listOf("labels", "playlist"), calls)
    }
}
