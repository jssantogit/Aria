package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.ui.components.BrowseActionsSheet
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.SongActionsPresentation

/**
 * A catalogue artist/release is an identity and metadata, not an audio asset.
 * Its menu uses the SAME native entity action rows as the Library, but no
 * Play/Play next/Queue/Download callbacks are ever constructed here.
 */
internal data class CanonicalCatalogEntityMenuSpec(
    val browseType: BrowseType,
    val ratingTarget: CanonicalEntityRatingTarget,
    val musicBrainzUrl: String,
    val ratingLabel: String,
    val playlistKind: CanonicalSearchKind?,
)

internal fun canonicalCatalogEntityMenuSpec(browseId: String?): CanonicalCatalogEntityMenuSpec? {
    val target = browseId?.let(::canonicalCatalogEntityRatingTarget) ?: return null
    val (type, endpoint, label) = when (target.entityType) {
        "ARTIST" -> Triple(BrowseType.ARTIST, "artist", "Avaliar artista")
        "ALBUM" -> Triple(BrowseType.ALBUM, "release", "Avaliar álbum")
        "RELEASE_GROUP" -> Triple(BrowseType.ALBUM, "release-group", "Avaliar álbum")
        else -> return null
    }
    return CanonicalCatalogEntityMenuSpec(
        browseType = type,
        ratingTarget = target,
        musicBrainzUrl = "https://musicbrainz.org/$endpoint/${target.entityId}",
        ratingLabel = label,
        // Edition and group import requires a verified RecordingId listing.
        // Artists have no unique, bounded track collection to import.
        playlistKind = when (target.entityType) {
            "ALBUM" -> CanonicalSearchKind.RELEASE
            "RELEASE_GROUP" -> CanonicalSearchKind.RELEASE_GROUP
            else -> null
        },
    )
}

/**
 * Renders *only* verified metadata operations for online artists/releases.
 *
 * The host owns the frosted card, anchor and dismissal. This keeps the
 * catalog, Library and Navidrome action geometry and label order identical,
 * while optional callbacks model their real, source-scoped capabilities.
 * A rating dialog is owned by the caller, which can outlive this menu.
 */
@Composable
internal fun CanonicalCatalogEntityMenuBody(
    browseId: String,
    title: String,
    subtitle: String,
    artworkUrl: String?,
    presentation: SongActionsPresentation = SongActionsPresentation.Menu,
    onDismiss: () -> Unit,
    onRate: (CanonicalCatalogEntityMenuSpec) -> Unit,
    onAddVerifiedRecordings: ((CanonicalCatalogEntityMenuSpec) -> Unit)? = null,
) {
    val spec = remember(browseId) { canonicalCatalogEntityMenuSpec(browseId) } ?: return
    val context = LocalContext.current
    val repository = remember(context) {
        CatalogMetadataPinRepository.get(context.applicationContext)
    }
    val pinnedItems by repository.pins.collectAsStateWithLifecycle()
    val pin = remember(browseId, title, subtitle, artworkUrl) {
        CatalogMetadataPin(
            browseId = browseId,
            title = title.trim().take(240),
            subtitle = subtitle.take(240),
            artworkUrl = trustedCatalogMetadataPinArtwork(artworkUrl),
        )
    }
    val canPin = validCatalogMetadataPin(pin)
    val pinned = pinnedItems.any { it.browseId == browseId }

    BrowseActionsSheet(
        target = BrowseTarget(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = artworkUrl,
            type = spec.browseType,
            fromCard = false,
        ),
        presentation = presentation,
        // This identity is a MusicBrainz entity only; never supply onPlay,
        // onShuffle, onPlayNext or onAddToQueue.
        onAddToPlaylist = if (spec.playlistKind != null && onAddVerifiedRecordings != null) {
            {
                onDismiss()
                onAddVerifiedRecordings(spec)
            }
        } else null,
        onEditRating = {
            onDismiss()
            onRate(spec)
        },
        isPinned = pinned,
        onTogglePin = if (canPin) {
            {
                repository.toggle(pin)
                onDismiss()
            }
        } else null,
        onShare = {
            onDismiss()
            val text = buildString {
                append(title)
                if (subtitle.isNotBlank()) append(" — ").append(subtitle)
                append("\n").append(spec.musicBrainzUrl)
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(share, "Compartilhar catálogo"))
        },
        onViewSource = {
            onDismiss()
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(spec.musicBrainzUrl)),
            )
        },
        viewSourceLabel = "Ver no MusicBrainz",
    )
}
