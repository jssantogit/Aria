package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.DiscogsNativePage
import com.music.bitchord.ui.components.MessageState

/**
 * Uses the same native DetailScreen and release-shelf primitives as Apple and
 * MusicBrainz, with metadata-only tracks, no playback and no fabricated art.
 * The entire Discogs-specific composition stays outside BitChordApp bytecode.
 */
@Composable
internal fun DiscogsNativeDetailPane(
    state: UiState<DiscogsNativePage>?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    activeShelf: HomeShelf?,
    onActiveShelfChange: (HomeShelf?) -> Unit,
    onReleaseOpen: (String) -> Unit,
    onLoadMore: () -> Unit,
    searching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
) {
    val displayed = (state as? UiState.Success)?.data
    if (displayed == null) {
        MessageState(
            if (state is UiState.Error)
                "Não foi possível carregar este catálogo Discogs. Verifique a conexão e as permissões."
            else "Carregando Discogs…",
        )
        return
    }
    val uriHandler = LocalUriHandler.current
    DetailScreen(
        page = displayed.page,
        currentSong = null,
        isPlaying = false,
        listState = listState,
        activeShelf = activeShelf,
        onActiveShelfChange = onActiveShelfChange,
        onSongClick = { _, _ -> },
        onSongLongPress = {},
        onSongSwipe = {},
        onShuffle = {},
        onArtistClick = { _, _ -> },
        onAddSuggested = {},
        onSectionItemClick = { card -> card.browseId?.let(onReleaseOpen) },
        metadataTracks = displayed.tracks,
        searching = searching,
        onSearchingChange = onSearchingChange,
        footerContent = { _ ->
            if (displayed.hasMore) item(key = "discogs:artist:more") {
                OutlinedButton(
                    onClick = onLoadMore,
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = 20.dp, vertical = 12.dp,
                    ),
                ) { Text("Carregar mais lançamentos") }
            }
            if (displayed.incomplete) item(key = "discogs:artist:partial") {
                Text(
                    "Este é um recorte do catálogo Discogs; pode haver outros lançamentos.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            displayed.attributionUrl?.let { url ->
                item(key = "discogs:source:disclaimer") {
                    Text(
                        "This application uses Discogs’ API but is not affiliated with, sponsored or endorsed by Discogs. ‘Discogs’ is a trademark of Zink Media, LLC.",
                        modifier = Modifier.fillMaxWidth().padding(
                            horizontal = 20.dp, vertical = 12.dp,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item(key = "discogs:source:attribution") {
                    Text(
                        "Data provided by Discogs. · Abrir fonte",
                        modifier = Modifier.fillMaxWidth()
                            .clickable { uriHandler.openUri(url) }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        contentPadding = contentPadding,
    )
}
