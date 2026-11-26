package com.music.bitchord.ui

import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import org.junit.Assert.*
import org.junit.Test

class CanonicalGenreSupplementPolicyTest {
    private val apple = BuiltInProviderKeys.ITUNES
    private val mb = BuiltInProviderKeys.MUSICBRAINZ

    @Test fun supportedApplePrimaryGenreLoadsIndependentMusicBrainzTagFacts() {
        assertTrue(shouldSupplementAppleGenre("metal", listOf(apple, mb), UiState.Loading))
        assertTrue(shouldSupplementAppleGenre("metal", listOf(apple, mb), null))
    }

    @Test fun noSupplementForUnsupportedAppleTagsOrDisabledMusicBrainz() {
        assertFalse(shouldSupplementAppleGenre("death metal", listOf(apple, mb), UiState.Loading))
        assertFalse(shouldSupplementAppleGenre("metal", listOf(apple), UiState.Loading))
        assertFalse(shouldSupplementAppleGenre("metal", listOf(mb, apple), UiState.Loading))
        assertFalse(shouldSupplementAppleGenre("invented tag", listOf(apple, mb), UiState.Loading))
    }

    @Test fun completedAppleMissRoutesToFullFallbackInsteadOfDuplicateSupplement() {
        assertFalse(shouldSupplementAppleGenre(
            "metal", listOf(apple, mb), UiState.Success(emptyList()),
        ))
        assertFalse(shouldSupplementAppleGenre(
            "metal", listOf(apple, mb), UiState.Error("Unavailable"),
        ))
    }
}
