package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalRecordingReleaseTitleRow
import com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CanonicalPlaylistAlbumRemovalTest {
    private fun edge(recording: String, release: String, name: String = "Album") =
        CanonicalRecordingReleaseTitleRow(recording, release, name)
    private val ids = listOf("r1", "other", "r2", "r3")
    private val playlist = SavedCanonicalStaticPlaylist(
        id = "playlist", name = "Favoritas", recordingIds = ids,
        description = "Keep", sourceProviderKey = "local-mediastore",
        coverUri = "content://cover/custom",
    )

    @Test fun removingAlbumKeepsOtherIdsCoverProviderAndOrdering() {
        val albums = canonicalPlaylistExactAlbums(ids, listOf(
            edge("r1", "album-a", "SAY MY NAME"),
            edge("r2", "album-a", "SAY MY NAME"),
            edge("r3", "album-a", "SAY MY NAME"),
            edge("other", "album-b", "SAY MY NAME"),
        ), emptyList())
        val request = canonicalPlaylistAlbumRemovalRequest("r2", ids, albums)!!
        assertEquals(listOf("r1", "r2", "r3"), request.recordingIds)
        val result = removeCanonicalPlaylistVerifiedAlbum(playlist, request, albums)
        assertEquals(listOf("other"), result.recordingIds)
        assertEquals(playlist.coverUri, result.coverUri)
        assertEquals(playlist.description, result.description)
        assertEquals(playlist.sourceProviderKey, result.sourceProviderKey)
    }

    @Test fun sourceOwnedReleaseWinsOverOtherGlobalEditions() {
        val owned = listOf(edge("r1", "local"), edge("r2", "local"))
        val global = listOf(
            edge("r1", "single"), edge("r1", "local"),
            edge("r2", "ep"), edge("r2", "local"),
            edge("r3", "ep"),
        )
        val mapping = canonicalPlaylistExactAlbums(ids, global, owned)
        assertEquals("local", mapping["r1"]?.releaseId)
        assertEquals(listOf("r1", "r2"), canonicalPlaylistAlbumMemberIds(ids, "r1", mapping))
    }

    @Test fun ambiguousReleaseAndSingleMemberDoNotExposeAlbumRemoval() {
        val mapping = canonicalPlaylistExactAlbums(ids, listOf(
            edge("r1", "album-a", "Same title"),
            edge("r1", "album-b", "Same title"),
            edge("r2", "album-a", "Same title"),
            edge("r3", "album-b", "Same title"),
        ), emptyList())
        assertNull(mapping["r1"])
        assertNull(canonicalPlaylistAlbumRemovalRequest("r1", ids, mapping))
        assertNull(canonicalPlaylistAlbumRemovalRequest("other", ids, mapping))
        assertNull(canonicalPlaylistAlbumRemovalRequest("r2", ids, mapping))
    }

    @Test fun confirmationRechecksLinksAndNeverRemovesNewUnapprovedMembers() {
        val albums = canonicalPlaylistExactAlbums(ids, listOf(
            edge("r1", "a"), edge("r2", "a"), edge("r3", "a"),
            edge("other", "b"),
        ), emptyList())
        val request = canonicalPlaylistAlbumRemovalRequest("r1", ids, albums)!!
        val latest = playlist.copy(recordingIds = ids + "new")
        val current = albums + ("new" to edge("new", "a"))
        assertEquals(listOf("other", "new"),
            removeCanonicalPlaylistVerifiedAlbum(latest, request, current).recordingIds)
        assertSame(latest, removeCanonicalPlaylistVerifiedAlbum(
            latest, request, current + ("r1" to edge("r1", "b")),
        ))
        val withoutSelected = playlist.copy(recordingIds = ids.filterNot { it == "r1" })
        assertSame(withoutSelected, removeCanonicalPlaylistVerifiedAlbum(
            withoutSelected, request, albums,
        ))
    }
}
