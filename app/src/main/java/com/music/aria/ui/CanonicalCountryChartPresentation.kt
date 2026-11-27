package com.music.aria.ui

import com.music.aria.data.catalog.metadata.appleChartBrowseId
import com.music.aria.data.catalog.metadata.appleAlbumChartBrowseId
import com.music.aria.data.catalog.metadata.appleChartTerritories
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderRuntime
import com.music.aria.data.model.HomeShelf
import com.music.aria.data.model.ShelfItem
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Render no charts if their capability is not explicitly authorized.
 * Country tiles are navigation placeholders; they do NOT claim verified
 * weekly chart entries before the user opens and fetches that territory.
 */
internal fun canonicalCountryChartShelf(
    authorized: Boolean,
    heading: String = "Rankings pelo mundo",
): HomeShelf? = if (!authorized) null else HomeShelf(
    title = heading,
    items = appleChartTerritories.flatMap { territory ->
        listOf(
            ShelfItem(
                title = "Top 100 músicas · ${territory.displayName}",
                subtitle = "Apple Music · Músicas mais tocadas",
                thumbnailUrl = null,
                videoId = null,
                browseId = appleChartBrowseId(territory.code),
            ),
            ShelfItem(
                title = "Top 100 álbuns · ${territory.displayName}",
                subtitle = "Apple Music · Álbuns mais tocados",
                thumbnailUrl = null,
                videoId = null,
                browseId = appleAlbumChartBrowseId(territory.code),
            ),
        )
    },
)

/** A capability toggle must remove both the Home navigation and opened results. */
@Composable
internal fun rememberAppleCountryChartAccess(): Boolean {
    val policyFlow = ProviderRuntime.state
    val snapshot = policyFlow?.collectAsStateWithLifecycle()
    return snapshot != null && ProviderRuntime.gateOrNull()?.open(
        ProviderCapability.CHART_MOST_PLAYED,
        BuiltInProviderKeys.ITUNES,
    ) != null
}
