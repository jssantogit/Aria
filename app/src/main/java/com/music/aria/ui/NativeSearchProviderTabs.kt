package com.music.aria.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.aria.data.model.UiState
import com.music.aria.data.sources.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * The existing Search provider-origin tabs, lifted out of the oversized
 * AriaApp composition method. Presentation state only, no new screen,
 * search implementation or provider permissions.
 */
@Composable
internal fun rememberNativeSearchProviderTabs(
    viewModel: MainViewModel,
    searchTabSelected: Boolean,
    searchSource: SearchSource,
    selectedSearchProviderKey: String?,
    catalogLabel: String,
    localLabel: String,
): List<SearchProviderTab> {
    val context = LocalContext.current.applicationContext
    val configuredSearchApis by SourceRegistry.configs.collectAsStateWithLifecycle()
    val availableLocalSongs by viewModel.availableLocalSongs.collectAsStateWithLifecycle()
    val indexedSearchProviderKeys by produceState(initialValue = emptyList<String>()) {
        val accounts = com.music.aria.data.catalog.provider.opensubsonic
            .SharedPrefsOpenSubsonicAccountPersistence(context)
        accounts.observe().collectLatest {
            value = withContext(Dispatchers.IO) {
                com.music.aria.ui.screens.canonicalAvailablePlaylistSourceKeys(context)
            }
        }
    }
    LaunchedEffect(searchTabSelected) {
        if (searchTabSelected) viewModel.loadLibrarySongs()
    }
    val tabs = availableSearchProviderTabs(
        configs = configuredSearchApis,
        remoteProviderKeys = indexedSearchProviderKeys,
        hasLocalTracks = (availableLocalSongs as? UiState.Success)
            ?.data?.isNotEmpty() == true,
        catalogLabel = catalogLabel,
        localLabel = localLabel,
    )
    LaunchedEffect(tabs, searchSource, selectedSearchProviderKey, searchTabSelected) {
        if (!searchTabSelected) return@LaunchedEffect
        val valid = tabs.any { tab ->
            when (searchSource) {
                SearchSource.CANONICAL, SearchSource.YOUTUBE -> tab.key == "catalog"
                SearchSource.LIBRARY -> tab.key == "local"
                SearchSource.ADDONS -> tab.key == selectedSearchProviderKey
            }
        }
        if (!valid) viewModel.setSearchSource(SearchSource.CANONICAL)
    }
    return tabs
}
