package com.music.bitchord

import com.music.bitchord.data.model.*
import com.music.bitchord.ui.screens.*
import org.junit.Assert.*
import org.junit.Test

class AmsDetailPreviewProjectionTest {
    @Test fun preservesUnavailableRowsAndRepeatedMembersInPlayableProjection() {
        val page = DetailPage("apple_music_web:br:playlist:pl.123456","Playlist","",
            null,UiState.Success(emptyList()),BrowseType.PLAYLIST)
        fun row(id: String, available: Boolean) =
            NativeMetadataTrack(id,"Track","Artist",null,null,durationMs=180000L,hasAudioPreview=available)
        val sample = row("apple_music_web:br:song:123",true)
        val tracks = listOf(sample,row("apple_music_web:br:song:456",false),sample,
            row("apple_music_web:us:song:789",true),row("itunes:br:song:123",true))
        val playable = amsDetailPreviewSongs(page,tracks)
        assertEquals(listOf(0,2),playable.keys.toList())
        assertTrue(playable.values.all { it.isAudioPreview && it.localUri == null })
        assertEquals(listOf(sample.key,sample.key),playable.values.map { it.videoId })
        assertEquals(180000L,tracks[0].durationMs)
        assertTrue(amsDetailPreviewSongs(page.copy(browseId="itunes:br:album:123"),tracks).isEmpty())
    }
}
