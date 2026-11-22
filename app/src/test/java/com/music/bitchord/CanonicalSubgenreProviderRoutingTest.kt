package com.music.bitchord.ui

import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSubgenreProviderRoutingTest {
    @Test fun unsupportedAppleSubgenreDoesNotRemainStuckWaitingForApple() {
        val projected = projectCanonicalGenreState(
            tag = "death metal",
            genrePlaylists = emptyList(),
            playlistHeading = "Playlists",
            appleByTag = emptyMap(),
            musicBrainzRows = UiState.Success(emptyList()),
            ordered = listOf(BuiltInProviderKeys.ITUNES, BuiltInProviderKeys.MUSICBRAINZ),
            releasesHeading = "Lançamentos",
            musicBrainzResultTag = "death metal",
        )
        assertFalse(projected is UiState.Loading)
    }

    @Test fun unsupportedAppleSubgenreDoesNotUseDisabledMusicBrainz() {
        val projected = projectCanonicalGenreState(
            tag = "death metal",
            genrePlaylists = emptyList(),
            playlistHeading = "Playlists",
            appleByTag = emptyMap(),
            musicBrainzRows = UiState.Success(emptyList()),
            ordered = listOf(BuiltInProviderKeys.ITUNES),
            releasesHeading = "Lançamentos",
            musicBrainzResultTag = "death metal",
        )
        assertFalse(projected is UiState.Loading)
        assertFalse(projected is UiState.Success && projected.data.any { it.items.isNotEmpty() })
    }

    @Test fun rootGenreRemainsSupportedByApplePolicyAndSubgenreIsNotAssumed() {
        assertTrue(com.music.bitchord.data.catalog.metadata.AppleExploreGenrePolicy.supports("metal"))
        assertFalse(com.music.bitchord.data.catalog.metadata.AppleExploreGenrePolicy.supports("death metal"))
    }
}
