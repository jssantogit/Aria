package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.metadata.AppleChartTerritory
import com.music.bitchord.data.catalog.metadata.AppleCountryChart
import com.music.bitchord.data.catalog.metadata.AppleCountryAlbumChart
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.components.ArtworkPageBackButton

/**
 * Provider-owned, ranked Apple storefront page. A tap opens the source's exact
 * song URL; no Apple chart entry becomes a playable BitChord MediaAsset.
 * The "consulted" date isn't called an official publication period.
 */
@Composable
internal fun AppleCountryChartScreen(
    territory: AppleChartTerritory,
    state: UiState<AppleCountryChart>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    previewEnabled: Boolean = false,
    onPreview: ((com.music.bitchord.data.catalog.metadata.AppleChartSong) -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkPageBackButton(onClick = onBack, hazeState = null, isCategory = true)
        }
        Text(
            text = "Top ${((state as? UiState.Success)?.data?.sourceRowCount ?: 100)} · ${territory.displayName}",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp),
        )
        Text(
            text = "Apple Music · Músicas mais tocadas",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 18.dp),
        )
        when (state) {
            UiState.Loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(top = 50.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            is UiState.Error -> Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(14.dp))
                Button(onClick = onRetry) { Text("Tentar novamente") }
            }
            is UiState.Success -> LazyColumn(
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(state.data.songs, key = { "chart:${it.country}:${it.songId}" }) { song ->
                    val playable = previewEnabled && onPreview != null &&
                        com.music.bitchord.ui.amsCountryChartPreview(song) != null
                    NativeSearchResultRow(
                        row = NativeSearchRowUi(
                            title = "${song.position}. ${song.title}",
                            subtitle = song.artistName,
                            artworkUri = song.artworkUrl,
                            artistPortrait = false,
                        ),
                        onClick = { if (playable) onPreview?.invoke(song)
                            else uriHandler.openUri(song.officialUrl) },
                    )
                }
            }
        }
    }
}


/** Verified Apple album positions, never a curated essentials claim. */
@Composable
internal fun AppleCountryAlbumChartScreen(
    territory: AppleChartTerritory,
    state: UiState<AppleCountryAlbumChart>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
) {
    BackHandler(onBack = onBack)
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkPageBackButton(onClick = onBack, hazeState = null, isCategory = true)
        }
        Text(
            text = "Top ${((state as? UiState.Success)?.data?.sourceRowCount ?: 100)} álbuns · ${territory.displayName}",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp),
        )
        Text(
            text = "Apple Music · Álbuns mais tocados",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 18.dp),
        )
        when (state) {
            UiState.Loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(top = 50.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            is UiState.Error -> Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(14.dp))
                Button(onClick = onRetry) { Text("Tentar novamente") }
            }
            is UiState.Success -> LazyColumn(
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(state.data.albums, key = { "album-chart:${it.country}:${it.albumId}" }) { album ->
                    NativeSearchResultRow(
                        row = NativeSearchRowUi(
                            title = "${album.position}. ${album.title}",
                            subtitle = "${album.artistName} · Abrir no Apple Music",
                            artworkUri = album.artworkUrl,
                            artistPortrait = false,
                        ),
                        onClick = { uriHandler.openUri(album.officialUrl) },
                    )
                }
            }
        }
    }
}



// This is a separate composable boundary so BitChordApp never inlines an
// additional chart navigation branch into its JVM-limited main Compose method.
@Composable
internal fun AppleCountryHomeRoute(
    chartAuthorized: Boolean,
    navigation: com.music.bitchord.ui.CanonicalCountryChartNavigation,
    charts: Map<String, UiState<AppleCountryChart>>,
    albumCharts: Map<String, UiState<AppleCountryAlbumChart>>,
    onAlbumRetry: (String) -> Unit,
    onRetry: (String) -> Unit,
    contentPadding: PaddingValues,
    previewEnabled: Boolean = false,
    onSongPreview: ((com.music.bitchord.data.catalog.metadata.AppleChartSong) -> Unit)? = null,
    home: @Composable () -> Unit,
) {
    val active = navigation.active.takeIf { chartAuthorized }
    when {
        active == null -> home()
        navigation.albumActive -> AppleCountryAlbumChartScreen(
            territory = active,
            state = albumCharts[active.code] ?: UiState.Loading,
            onBack = navigation::close,
            onRetry = { onAlbumRetry(active.code) },
            contentPadding = contentPadding,
        )
        else -> AppleCountryChartScreen(
            territory = active,
            state = charts[active.code] ?: UiState.Loading,
            onBack = navigation::close,
            onRetry = { onRetry(active.code) },
            contentPadding = contentPadding,
            previewEnabled = previewEnabled,
            onPreview = onSongPreview,
        )
    }
}

/** Only data adaptation: use the Library's existing grid and the root's native
 * floating toolbar. No nested toolbar, external-open-only row or fake playlist. */
@Composable
internal fun AmsAlbumChartCollectionPage(
    state: UiState<AppleCountryAlbumChart>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    contentPadding: PaddingValues,
    onRetry: () -> Unit,
    onItemClick: (com.music.bitchord.data.model.ShelfItem) -> Unit,
    onItemLongPress: (com.music.bitchord.data.model.ShelfItem) -> Unit,
) {
    val collection = (state as? UiState.Success)?.data?.let { com.music.bitchord.ui.projectAmsAlbumChartCollection(it) }
    if (collection != null) {
        LibraryGridPage(shelf=collection.shelf,gridState=gridState,
            onItemClick=onItemClick,onItemLongPress=onItemLongPress,contentPadding=contentPadding,
            rankByBrowseId=collection.ranks)
    } else {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
            horizontalAlignment=Alignment.CenterHorizontally) {
            if (state is UiState.Loading) NativeLoadingSpinner()
            else {
                Text((state as? UiState.Error)?.message ?: "Não foi possível verificar este ranking.")
                Spacer(Modifier.size(14.dp))
                Button(onClick=onRetry) { Text("Tentar novamente") }
            }
        }
    }
}
