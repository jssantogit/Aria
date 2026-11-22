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
            text = "Top 100 · ${territory.displayName}",
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
                    NativeSearchResultRow(
                        row = NativeSearchRowUi(
                            title = "${song.position}. ${song.title}",
                            subtitle = "${song.artistName} · Abrir no Apple Music",
                            artworkUri = song.artworkUrl,
                            artistPortrait = false,
                        ),
                        onClick = { uriHandler.openUri(song.officialUrl) },
                    )
                }
            }
        }
    }
}
