package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Old Song-based album pages must not diverge from canonical Recording rows. */
class AlbumTrackSecondaryCreditTest {
    @Test fun hideRepeatedAlbumArtistButKeepDifferentCredits() {
        assertEquals(null, albumRowSecondaryArtist("Caligula's Horse", "caligula's horse"))
        assertEquals(null, albumRowSecondaryArtist("", "Artist"))
        assertEquals("Guest Singer", albumRowSecondaryArtist("  Guest Singer  ", "Artist"))
    }

    @Test fun generalLibraryAndPlaylistRowsDoNotOptIntoAlbumCompaction() {
        assertEquals("Caligula's Horse", albumRowSecondaryArtist("Caligula's Horse", null))
    }
}
