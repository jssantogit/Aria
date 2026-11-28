package com.music.bitchord

import com.music.bitchord.data.model.*
import com.music.bitchord.ui.screens.*
import org.junit.Assert.*
import org.junit.Test

class AmsDetailPreviewProjectionTest {
    @Test fun selectedDuplicateOccurrenceDoesNotJumpToFirst() {
        val sample = Song("apple_music_web:br:song:123","Sample","Artist",null,isAudioPreview=true)
        val other = sample.copy(videoId="apple_music_web:br:song:456")
        val queue = listOf(sample,other,sample)
        val selected = requireNotNull(selectPlayableQueueOccurrence(queue,2))
        assertEquals(queue,selected.first)
        assertEquals(2,selected.second)
        assertNull(selectPlayableQueueOccurrence(queue,3))
    }

    @Test fun searchPreviewRequiresExactAmsIdentityAndEvidence() {
        val row = AppleEditorialTrackSearchRow("apple_music_web:br:song:123","Track","Artist",null,null,180000L,hasAudioPreview=true)
        assertTrue(requireNotNull(amsSearchPreviewSong(row)).isAudioPreview)
        assertNull(amsSearchPreviewSong(row.copy(hasAudioPreview=false)))
        assertNull(amsSearchPreviewSong(row.copy(key="itunes:br:song:123")))
    }

    @Test fun shelfPreviewRequiresSourceEvidenceAndExactSongIdentity() {
        val card = ShelfItem("Track","Artist",null,null,"apple_music_web:br:song:123",
            appleStoreUrl="https://music.apple.com/br/song/track/123",hasAudioPreview=true)
        val preview = requireNotNull(amsShelfPreviewSong(card))
        assertTrue(preview.isAudioPreview)
        assertEquals(card.browseId,preview.videoId)
        assertNull(amsShelfPreviewSong(card.copy(hasAudioPreview=false)))
        assertNull(amsShelfPreviewSong(card.copy(videoId="legacy")))
        assertNull(amsShelfPreviewSong(card.copy(browseId="apple_music_web:us:song:123")))
        assertNull(amsShelfPreviewSong(card.copy(browseId="apple_music_web:br:album:123")))
        assertNull(amsShelfPreviewSong(card.copy(appleStoreUrl=null)))
    }

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
