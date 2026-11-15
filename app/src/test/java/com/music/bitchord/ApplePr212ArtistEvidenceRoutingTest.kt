package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.applePr212ArtistEvidenceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApplePr212ArtistEvidenceRoutingTest {
    private val mbid = "11111111-1111-4111-8111-111111111111"
    private fun page(id: String, type: BrowseType = BrowseType.ARTIST): DetailPage =
        DetailPage(
            browseId = id, title = "NewJeans", subtitle = "Artista",
            thumbnailUrl = null, songs = UiState.Success(emptyList()), type = type,
        )

    @Test fun itunesArtistKeepsCommercialBrowseIdentityWhileOpeningVerifiedMbSections() {
        assertEquals(mbid, applePr212ArtistEvidenceId(
            page("itunes:br:artist:99"),
            mapOf("itunes:br:artist:99" to mbid),
        ))
        assertNull(applePr212ArtistEvidenceId(
            page("itunes:br:artist:99"),
            mapOf("itunes:us:artist:99" to mbid),
        ))
        assertNull(applePr212ArtistEvidenceId(
            page("itunes:br:album:99", BrowseType.ALBUM),
            mapOf("itunes:br:album:99" to mbid),
        ))
        assertNull(applePr212ArtistEvidenceId(
            page("itunes:br:artist:99"),
            mapOf("itunes:br:artist:99" to "artist-by-name-not-proven"),
        ))
    }

    @Test fun existingPR212MusicBrainzArtistRouteRemainsValidUnchanged() {
        assertEquals(mbid, applePr212ArtistEvidenceId(
            page("catalog:ARTIST:$mbid"), emptyMap(),
        ))
    }
}
