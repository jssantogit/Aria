package com.music.bitchord.ui.components

import com.music.bitchord.data.catalog.CanonicalArtistCreditRow
import com.music.bitchord.data.catalog.ArtistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerArtistEvidenceTest {
    private val credited = listOf(CanonicalArtistCreditRow("artist-1", "BTS", 0))
    private val artists = listOf(ArtistEntity(id = "artist-1", name = "BTS"))

    @Test fun sourceOwnedArtistUsesVerifiedIdAndSourceNotTitleGuess() {
        assertEquals(
            SourceOwnedArtistEvidence("artist-1", "BTS", "local-mediastore"),
            verifiedSourceOwnedPlayerArtist(
                "recording-1", "local-mediastore", credited, artists,
            ),
        )
        assertEquals(
            SourceOwnedArtistEvidence("artist-1", "BTS", "opensubsonic:account-1"),
            verifiedSourceOwnedPlayerArtist(
                "recording-1", "opensubsonic:account-1", credited, artists,
            ),
        )
    }

    @Test fun noArtistFromAnotherProviderOrAmbiguousLead() {
        assertNull(verifiedSourceOwnedPlayerArtist("recording-1", "local-mediastore", credited, emptyList()))
        assertNull(verifiedSourceOwnedPlayerArtist("recording-1", "local-mediastore",
            credited + CanonicalArtistCreditRow("artist-2", "Other", 0),
            artists + ArtistEntity(id = "artist-2", name = "Other")))
        assertNull(verifiedSourceOwnedPlayerArtist("recording-1", "", credited, artists))
    }

    @Test fun appleRouteIsNotInventedFromMetadataName() {
        assertEquals(
            "itunes:br:artist:123",
            verifiedAppleArtistBrowseRoute("itunes:br:artist:123"),
        )
        assertNull(verifiedAppleArtistBrowseRoute("itunes:br:album:123"))
        assertNull(verifiedAppleArtistBrowseRoute("123"))
        assertNull(verifiedAppleArtistBrowseRoute("itunes:br:artist:0"))
    }
}
