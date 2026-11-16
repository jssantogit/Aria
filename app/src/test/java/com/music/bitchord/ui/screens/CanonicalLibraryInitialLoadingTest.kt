package com.music.bitchord.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An initial empty placeholder is not proof that the library is empty. */
class CanonicalLibraryInitialLoadingTest {
    @Test fun unifiedLibraryWaitsForFirstDatabaseEmission() {
        assertTrue(canonicalLibraryInitialLoading(
            unified = true, firstEmissionReceived = false, localIndexReady = true,
        ))
    }

    @Test fun unifiedLibraryWaitsForLocalIndexBeforeShowingEmptyState() {
        assertTrue(canonicalLibraryInitialLoading(
            unified = true, firstEmissionReceived = true, localIndexReady = false,
        ))
    }

    @Test fun realEmptyDatabaseIsNotAnInfiniteLoader() {
        assertFalse(canonicalLibraryInitialLoading(
            unified = true, firstEmissionReceived = true, localIndexReady = true,
        ))
    }

    @Test fun remoteLibraryDoesNotWaitForLocalMediaIndex() {
        assertFalse(canonicalLibraryInitialLoading(
            unified = false, firstEmissionReceived = true, localIndexReady = false,
        ))
    }
}
