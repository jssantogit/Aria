package com.music.aria.ui.screens

import com.music.aria.data.catalog.CanonicalBrowseTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Context changes available data/actions, not which native Library page is used. */
class CanonicalLibraryCrossContextParityTest {
    @Test fun dedicatedServersKeepTheirOwnPlaybackScopeWhileUnifiedLibraryCanUseAllSources() {
        assertEquals(
            "opensubsonic:navidrome-a",
            canonicalLibraryPlaybackSourceScope(
                unified = false,
                selectedSource = null,
                providerKey = "opensubsonic:navidrome-a",
            ),
        )
        assertEquals(
            "local-mediastore",
            canonicalLibraryPlaybackSourceScope(
                unified = false,
                selectedSource = "opensubsonic:other",
                providerKey = "local-mediastore",
            ),
        )
        assertEquals(
            "opensubsonic:navidrome-a",
            canonicalLibraryPlaybackSourceScope(
                unified = true,
                selectedSource = "opensubsonic:navidrome-a",
                providerKey = "local-mediastore",
            ),
        )
        assertEquals(
            null,
            canonicalLibraryPlaybackSourceScope(
                unified = true,
                selectedSource = null,
                providerKey = "local-mediastore",
            ),
        )
    }

    @Test fun sourceOnlyNavigationKeepsContextMenusWithoutStrandedBatchSelection() {
        for (category in com.music.aria.data.listening.CanonicalSmartFilterContext.entries) {
            assertEquals(false, canonicalLibraryCanStartBatchSelection(
                unified = false, category = category, detailOpen = false,
            ))
            assertEquals(false, canonicalLibraryCanStartBatchSelection(
                unified = true, category = category, detailOpen = true,
            ))
            assertEquals(true, canonicalLibraryCanStartBatchSelection(
                unified = true, category = category, detailOpen = false,
            ))
        }
    }

    @Test fun uncreditedArtistsKeepTheSameBrowseOnlyGroupAsLocalAndRemoteLibrary() {
        val recording = CanonicalBrowseTrack(
            recordingId = "recording:uncredited",
            title = "Uncredited song",
            artistDisplay = "Uncredited artist",
            durationMs = 190_000,
            artworkUri = null,
            availableAssets = emptyList(),
        )
        val groups = canonicalLibraryDisplayArtistGroups(listOf(recording), emptyList())
        assertEquals(listOf("Uncredited artist"), groups.map { it.name })
        assertEquals(
            playlistDisplayArtistGroupId("Uncredited artist"),
            groups.single().id,
        )
        assertEquals(
            emptyList<CanonicalSelectedEntity>(),
            canonicalLibraryDisplayArtistGroups(
                listOf(recording),
                listOf(CanonicalSelectedEntity("artist:verified", "Uncredited artist")),
            ),
        )
        assertTrue(groups.single().id != "artist:verified")
    }
}
