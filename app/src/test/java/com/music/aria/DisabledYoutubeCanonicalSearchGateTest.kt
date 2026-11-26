package com.music.aria

import com.music.aria.data.sources.SourceConfig
import com.music.aria.data.sources.SourceKind
import com.music.aria.ui.SearchSource
import com.music.aria.ui.effectiveSearchSource
import com.music.aria.ui.isYouTubeSourceEnabled
import com.music.aria.ui.shouldHandleExplicitLegacyYoutubeLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DisabledYoutubeCanonicalSearchGateTest {
    @Test fun disabledYoutubeRedirectsLegacySearchButKeepsOtherSourcesIntact() {
        val disabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = false))
        assertEquals(SearchSource.CANONICAL, effectiveSearchSource(SearchSource.YOUTUBE, disabled))
        assertEquals(SearchSource.CANONICAL, effectiveSearchSource(SearchSource.CANONICAL, disabled))
        assertEquals(SearchSource.LIBRARY, effectiveSearchSource(SearchSource.LIBRARY, disabled))
        assertEquals(SearchSource.ADDONS, effectiveSearchSource(SearchSource.ADDONS, disabled))
    }

    @Test fun enabledOrUninitializedYoutubeStillRoutesToCanonicalCatalog() {
        assertEquals(SearchSource.CANONICAL, effectiveSearchSource(SearchSource.YOUTUBE, emptyList()))
        assertEquals(SearchSource.CANONICAL, effectiveSearchSource(
            SearchSource.YOUTUBE, listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true)),
        ))
    }
    @Test fun historicalEnabledAndMissingYoutubeSourceNeverAuthorizeNetworkRequests() {
        val enabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true))
        val disabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = false))
        for (configs in listOf(emptyList(), enabled, disabled)) {
            assertFalse(isYouTubeSourceEnabled(configs))
            assertFalse(shouldHandleExplicitLegacyYoutubeLink(configs))
        }
    }

}
