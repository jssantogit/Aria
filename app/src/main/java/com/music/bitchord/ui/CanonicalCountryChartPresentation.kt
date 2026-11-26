package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.appleChartBrowseId
import com.music.bitchord.data.catalog.metadata.appleAlbumChartBrowseId
import com.music.bitchord.data.catalog.metadata.appleChartTerritories
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.catalog.metadata.knownAppleChartTerritory
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
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
    provider: ProviderKey = BuiltInProviderKeys.ITUNES,
): HomeShelf? = if (!authorized) null else HomeShelf(
    title = heading,
    items = appleChartTerritories.flatMap { territory ->
        listOf(
            ShelfItem(
                title = "Top 100 músicas · ${territory.displayName}",
                subtitle = (if (provider == BuiltInProviderKeys.APPLE_MUSIC_WEB) "Apple Music Web" else "Apple Music") + " · Músicas mais tocadas",
                thumbnailUrl = null,
                videoId = null,
                browseId = canonicalAppleChartRoute(provider,territory.code,false),
            ),
            ShelfItem(
                title = "Top 100 álbuns · ${territory.displayName}",
                subtitle = (if (provider == BuiltInProviderKeys.APPLE_MUSIC_WEB) "Apple Music Web" else "Apple Music") + " · Álbuns mais tocados",
                thumbnailUrl = null,
                videoId = null,
                browseId = canonicalAppleChartRoute(provider,territory.code,true),
            ),
        )
    },
)

internal fun canonicalAppleChartRoute(provider: ProviderKey, country: String, albums: Boolean): String =
    if (provider == BuiltInProviderKeys.APPLE_MUSIC_WEB)
        "catalog:" + (if (albums) "ALBUM_CHART" else "CHART") + ":apple_music_web:$country"
    else if (albums) appleAlbumChartBrowseId(country) else appleChartBrowseId(country)

internal fun parseCanonicalAppleChartRoute(id: String?, provider: ProviderKey?, albums: Boolean) =
    if (provider == null) null else appleChartTerritories.firstOrNull {
        id == canonicalAppleChartRoute(provider,it.code,albums)
    }

internal fun preferredAppleChartProvider(gate: ProviderPolicyGate?): ProviderKey? =
    gate?.ordered(ProviderCapability.CHART_MOST_PLAYED)?.firstOrNull {
        it.key in setOf(BuiltInProviderKeys.ITUNES,BuiltInProviderKeys.APPLE_MUSIC_WEB)
    }?.key

@Composable
internal fun rememberAppleCountryChartProvider(): ProviderKey? {
    val flow = ProviderRuntime.state
    val snapshot = flow?.collectAsStateWithLifecycle()
    // Read the policy snapshot so ordering changes invalidate the composition.
    if (snapshot?.value == null) return null
    return preferredAppleChartProvider(ProviderRuntime.gateOrNull())
}

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
