package com.music.aria

import com.music.aria.data.sources.SourceConfig
import com.music.aria.data.sources.SourceKind
import com.music.aria.ui.SearchSource
import com.music.aria.ui.SearchOrigin
import com.music.aria.ui.availableSearchProviderTabs
import com.music.aria.ui.searchOriginOf
import com.music.aria.ui.searchSourceForOrigin
import com.music.aria.ui.screens.canonicalSearchEditing
import com.music.aria.ui.screens.serverSearchEditing
import com.music.aria.data.catalog.isCatalogRemoteServerProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSearchOriginsTest {
    @Test fun catalogIsOnlyVisibleOriginWithoutVerifiedLocalOrConfiguredApis() {
        val tabs = availableSearchProviderTabs(
            configs = emptyList(),
            remoteProviderKeys = listOf("local-mediastore"),
            hasLocalTracks = false,
            catalogLabel = "Catálogo",
            localLabel = "Local",
        )
        assertEquals(listOf("Catálogo"), tabs.map { it.label })
        assertEquals(listOf(SearchSource.CANONICAL), tabs.map { it.source })
    }

    @Test fun localOnlyAppearsWithVerifiedDeviceTracksAndNoGenericServerOrigin() {
        val tabs = availableSearchProviderTabs(
            configs = emptyList(),
            remoteProviderKeys = listOf("local-mediastore"),
            hasLocalTracks = true,
            catalogLabel = "Catálogo",
            localLabel = "Local",
        )
        assertEquals(listOf("Catálogo", "Local"), tabs.map { it.label })
        assertEquals(listOf("catalog", "local"), tabs.map { it.key })
    }

    @Test fun configuredApisOwnSeparateTabsAndDisabledApisDoNotAppear() {
        val tabs = availableSearchProviderTabs(
            configs = listOf(
                SourceConfig(id = "ember", kind = SourceKind.ADDON, label = "Ember",
                    baseUrl = "https://ember.example", enabled = true),
                SourceConfig(id = "disabled", kind = SourceKind.ADDON, label = "Other",
                    baseUrl = "https://other.example", enabled = false),
            ),
            remoteProviderKeys = listOf("opensubsonic:accountA", "local-mediastore",
                "opensubsonic:accountB", "jellyfin:accountC"),
            hasLocalTracks = false,
            catalogLabel = "Catálogo",
            localLabel = "Local",
        )
        assertEquals(
            listOf("Catálogo", "Navidrome 1", "Navidrome 2", "Jellyfin", "Ember"),
            tabs.map { it.label },
        )
        assertEquals(
            listOf("catalog", "remote:opensubsonic:accountA", "remote:opensubsonic:accountB",
                "remote:jellyfin:accountC", "addon:ember"),
            tabs.map { it.key },
        )
        assertTrue(tabs.drop(1).all { it.source == SearchSource.ADDONS })
    }

    @Test fun appleSearchEditingOnlyShowsSuggestionsBeforeSubmission() {
        assertTrue(canonicalSearchEditing("newjeans", searchCommitted = false, detailOpen = false))
        assertFalse(canonicalSearchEditing("", searchCommitted = false, detailOpen = false))
        assertFalse(canonicalSearchEditing("  ", searchCommitted = false, detailOpen = false))
        assertFalse(canonicalSearchEditing("newjeans", searchCommitted = true, detailOpen = false))
        assertFalse(canonicalSearchEditing("newjeans", searchCommitted = false, detailOpen = true))
    }
    @Test fun serverSearchWaitsForSubmitInsteadOfShowingPrematureEmptyResult() {
        assertFalse(serverSearchEditing("", searchCommitted = false))
        assertFalse(serverSearchEditing("  ", searchCommitted = false))
        assertTrue(serverSearchEditing("newjeans", searchCommitted = false))
        assertFalse(serverSearchEditing("newjeans", searchCommitted = true))
    }
    @Test fun oneGeneralOriginHidesMetadataProviderSplit() {
        assertEquals(SearchOrigin.GENERAL, searchOriginOf(SearchSource.YOUTUBE))
        assertEquals(SearchOrigin.GENERAL, searchOriginOf(SearchSource.CANONICAL))
        assertEquals(SearchOrigin.SERVER, searchOriginOf(SearchSource.ADDONS))
        assertEquals(SearchOrigin.LOCAL, searchOriginOf(SearchSource.LIBRARY))
    }
    @Test fun generalAlwaysUsesCatalogIndependentlyOfYoutube() {
        val on = listOf(SourceConfig(kind=SourceKind.YOUTUBE, enabled=true))
        val off = listOf(SourceConfig(kind=SourceKind.YOUTUBE, enabled=false))
        assertEquals(SearchSource.CANONICAL,searchSourceForOrigin(SearchOrigin.GENERAL,false,on))
        assertEquals(SearchSource.CANONICAL,searchSourceForOrigin(SearchOrigin.GENERAL,true,on))
        assertEquals(SearchSource.CANONICAL,searchSourceForOrigin(SearchOrigin.GENERAL,false,off))
        assertEquals(SearchSource.ADDONS,searchSourceForOrigin(SearchOrigin.SERVER,true,off))
        assertEquals(SearchSource.LIBRARY,searchSourceForOrigin(SearchOrigin.LOCAL,true,off))
    }
    @Test fun serverSearchOnlyAcceptsTrustworthyProviderNamespaceNotLocalOrMusicBrainz() {
        assertTrue(isCatalogRemoteServerProvider("opensubsonic:configured-account"))
        assertTrue(isCatalogRemoteServerProvider("jellyfin:configured-account"))
        assertFalse(isCatalogRemoteServerProvider("local"))
        assertFalse(isCatalogRemoteServerProvider("musicbrainz"))
        assertFalse(isCatalogRemoteServerProvider("opensubsonic"))
    }
}
