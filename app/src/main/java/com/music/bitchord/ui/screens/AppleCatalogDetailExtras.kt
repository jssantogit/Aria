package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.ArtworkPalette

/**
 * Source capability controls inside the ORIGINAL DetailScreen's lazy-list
 * footer. This is not a second artist or album screen/compositor. Apple owns
 * paging and product IDs, but all visual sections and chrome remain native.
 */
@Composable
internal fun AppleArtistCatalogExtras(
    state: AppleEditorialArtistDetailPage,
    palette: ArtworkPalette,
    onLoadMore: () -> Unit,
    onSearchMore: (String) -> Unit,
) {
    var query by remember(state.artist.browseId) { mutableStateOf("") }
    Column {
        if (state.hasMore) {
            Box(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 12.dp)) {
                ActionPill(
                    icon = BitChordIcons.Plus,
                    label = "Carregar mais",
                    onClick = onLoadMore,
                    filled = false,
                    enabled = !state.loadingMore,
                )
            }
        }
        if (state.possiblyTruncated) {
            Text(
                "A API pública pode não incluir toda a discografia.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.onBackgroundVariant,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        SectionHeading("Buscar lançamentos", palette)
        NativeDetailSearchField(
            query = query,
            onQueryChange = { query = it },
            onClose = { query = "" },
            autoFocus = false,
            onFocused = {},
            palette = palette,
            placeholder = "Título do lançamento",
        )
        Box(Modifier.padding(horizontal = PAGE_GUTTER)) {
            ActionPill(
                icon = BitChordIcons.Search,
                label = "Buscar",
                onClick = { onSearchMore(query.trim()) },
                filled = false,
                enabled = query.trim().length >= 2 && !state.loadingMore,
            )
        }
        if (state.loadingMore) {
            CircularProgressIndicator(
                color = palette.onBackground,
                modifier = Modifier.padding(PAGE_GUTTER),
            )
        }
        if (state.pageError || state.noAdditionalResults) {
            Text(
                text = if (state.pageError) "Não foi possível consultar mais lançamentos."
                    else "Nenhum lançamento adicional encontrado.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.onBackgroundVariant,
                modifier = Modifier.padding(PAGE_GUTTER),
            )
        }
    }
}

@Composable
internal fun NativeCatalogDetailNotes(
    notice: String?,
    attribution: NativeDetailAttribution?,
    palette: ArtworkPalette,
) {
    if (notice.isNullOrBlank() && attribution == null) return
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 12.dp)) {
        notice?.let {
            Text(it, color = palette.onBackgroundVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        attribution?.let { source ->
            val verifiedDiscogsLink = source.url?.takeIf { link ->
                val uri = runCatching { java.net.URI(link) }.getOrNull()
                uri?.scheme == "https" && uri.userInfo == null &&
                    uri.port == -1 && uri.host?.lowercase(java.util.Locale.ROOT)
                    ?.let { it == "discogs.com" || it == "www.discogs.com" } == true
            }
            Text(
                text = source.text,
                color = palette.onBackgroundVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = verifiedDiscogsLink?.let { link ->
                    Modifier.clickable { runCatching { uriHandler.openUri(link) } }
                } ?: Modifier,
            )
            source.disclaimer?.let { disclaimer ->
                Text(
                    disclaimer,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onBackgroundVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
