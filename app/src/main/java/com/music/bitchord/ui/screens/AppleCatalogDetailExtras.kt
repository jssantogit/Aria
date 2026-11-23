package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.music.bitchord.data.catalog.metadata.safeAppleArtwork
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.NativeDetailTrackRowLayout
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
) {
    Column {
        // The native artist screen owns its search field and scroll-based
        // continuation. This footer only communicates source limitations,
        // not another provider-specific toolbar.
        if (state.loadingMore) {
            CircularProgressIndicator(
                color = palette.onBackground,
                modifier = Modifier.padding(PAGE_GUTTER),
            )
        }
        if (state.possiblyTruncated && !state.hasMore) {
            Text(
                "A API pública pode não incluir toda a discografia.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.onBackgroundVariant,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
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

/** Apple returned these exact artist tracks, but no public popularity rank. */
@Composable
internal fun AppleArtistMetadataTracksSection(
    tracks: List<AppleEditorialTrackSearchRow>,
    palette: ArtworkPalette,
) {
    if (tracks.isEmpty()) return
    NativeArtistTracksShelf(
        title = "Músicas",
        tracks = tracks.take(16),
        palette = palette,
        horizontalPadding = PAGE_GUTTER,
        itemKey = { it.key },
    ) { track ->
        // Identical native row geometry as canonical/local artist songs, but
        // without fake play, rating or overflow actions on metadata-only rows.
        NativeArtistInformationalTrackRow(
            title = track.title,
            subtitle = track.artistName,
            palette = palette,
            leading = {
                Box(Modifier.size(NATIVE_ARTIST_TRACK_COVER_SIZE)
                    .clip(RoundedCornerShape(7.dp))
                    .background(palette.onBackground.copy(alpha = 0.08f))) {
                    CoverPlaceholder(Icons.Rounded.MusicNote, Modifier.fillMaxSize())
                    safeAppleArtwork(track.artworkUri)?.let { verified ->
                        AsyncImage(
                            model = verified, contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            },
        )
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
