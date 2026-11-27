package com.music.aria.ui

import com.music.aria.data.model.UiState
import com.music.aria.data.providers.BuiltInProviderKeys
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
        assertTrue(com.music.aria.data.catalog.metadata.AppleExploreGenrePolicy.supports("metal"))
        assertFalse(com.music.aria.data.catalog.metadata.AppleExploreGenrePolicy.supports("death metal"))
    }
}
