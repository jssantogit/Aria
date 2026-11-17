package com.music.bitchord

import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.editorialPrimaryMiss
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure fallback state: dispatch may not bypass a pending preferred provider. */
class CanonicalHomeEditorialDispatchTest {
    @Test fun noSecondaryWhilePrimaryHasNotAnswered() {
        assertFalse(editorialPrimaryMiss<String>(null))
        assertFalse(editorialPrimaryMiss<String>(UiState.Loading))
        assertFalse(editorialPrimaryMiss(UiState.Success(listOf("itunes:br:album:99"))))
    }

    @Test fun secondaryAllowedAfterRealEmptyOrUnavailablePrimary() {
        assertTrue(editorialPrimaryMiss(UiState.Success(emptyList<String>())))
        assertTrue(editorialPrimaryMiss<String>(UiState.Error("unavailable")))
    }
}
