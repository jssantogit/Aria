package com.music.aria

import com.music.aria.data.sources.SourceConfig
import com.music.aria.data.sources.SourceKind
import com.music.aria.ui.SearchOrigin
import com.music.aria.ui.SearchSource
import com.music.aria.ui.defaultDiscoverySearchSource
import com.music.aria.ui.effectiveSearchSource
import com.music.aria.ui.searchSourceForOrigin
import com.music.aria.ui.shouldPrefetchLegacyYouTubeAccountData
import com.music.aria.ui.shouldHandleExplicitLegacyYoutubeLink
import com.music.aria.ui.shouldRunLegacyYoutubeVersionLookup
import com.music.aria.ui.shouldUseCanonicalDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #208 first cutover: discovery/search must not silently reactivate legacy
 * YouTube endpoints on upgraded installs whose old opt-in preference is false.
 * YouTube media playback and persisted aliases are deliberately out of scope.
 */
class CanonicalCatalogCutoverTest {
    @Test fun homeAndExploreAlwaysUseCanonicalCatalogEvenOnOldInstall() {
        val sourceConfigurations = listOf(
            emptyList(),
            listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true)),
            listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = false)),
            listOf(SourceConfig(kind = SourceKind.ADDON, enabled = false)),
        )
        sourceConfigurations.forEach { configs ->
            assertTrue(shouldUseCanonicalDiscovery(explicitlyEnabled = false, configs = configs))
            assertTrue(shouldUseCanonicalDiscovery(explicitlyEnabled = true, configs = configs))
            assertEquals(
                SearchSource.CANONICAL,
                defaultDiscoverySearchSource(explicitlyEnabled = false, configs = configs),
            )
        }
    }

    @Test fun generalSearchNeverResolvesToYoutubeEvenIfRequestedExplicitly() {
        val youtubeEnabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true))
        assertEquals(
            SearchSource.CANONICAL,
            searchSourceForOrigin(SearchOrigin.GENERAL, canonicalMode = false, configs = youtubeEnabled),
        )
        assertEquals(SearchSource.CANONICAL, effectiveSearchSource(SearchSource.YOUTUBE, youtubeEnabled))
        assertEquals(SearchSource.CANONICAL, effectiveSearchSource(SearchSource.YOUTUBE, emptyList()))
        assertEquals(SearchSource.ADDONS, effectiveSearchSource(SearchSource.ADDONS, youtubeEnabled))
        assertEquals(SearchSource.LIBRARY, effectiveSearchSource(SearchSource.LIBRARY, youtubeEnabled))
    }

    @Test fun explicitYoutubeLinksAreNotConflatedWithAutomaticCatalogProbes() {
        val enabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true))
        val disabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = false))
        // Historical links remain parseable but must not activate the retired backend.
        assertFalse(shouldHandleExplicitLegacyYoutubeLink(enabled))
        assertFalse(shouldHandleExplicitLegacyYoutubeLink(disabled))
        assertFalse(shouldRunLegacyYoutubeVersionLookup(canonicalMode = false, configs = enabled))
    }

    @Test fun startupPrefetchAndHiddenYoutubeCatalogProbesStayOff() {
        val youtubeEnabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true))
        assertFalse(shouldPrefetchLegacyYouTubeAccountData(
            signedIn = true, canonicalDiscoveryEnabled = false, configs = youtubeEnabled,
        ))
        assertFalse(shouldRunLegacyYoutubeVersionLookup(
            canonicalMode = false, configs = youtubeEnabled,
        ))
        assertFalse(shouldRunLegacyYoutubeVersionLookup(
            canonicalMode = false, configs = emptyList(),
        ))
    }
}
