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
    val sourceUrl: String? = musicBrainzUrl.takeIf(String::isNotBlank),
    val sourceLabel: String = "Ver no MusicBrainz",
)

internal fun canonicalCatalogEntityMenuSpec(
    browseId: String?, sourceStoreUrl: String? = null,
): CanonicalCatalogEntityMenuSpec? {
    val target = browseId?.let(::canonicalCatalogEntityRatingTarget) ?: return null
    if (target.entityId.startsWith("apple_music_web:")) {
        val (type,label) = when(target.entityType) {
            "ARTIST" -> BrowseType.ARTIST to "Avaliar artista"
            "ALBUM" -> BrowseType.ALBUM to "Avaliar álbum"
            "PLAYLIST" -> BrowseType.PLAYLIST to "Avaliar playlist"
            else -> return null
        }
        return CanonicalCatalogEntityMenuSpec(type,target,"",label,null,
            sourceUrl=appleMusicWebVerifiedRouteUrl(sourceStoreUrl,browseId),
            sourceLabel="Abrir no Apple Music")
    }
    if (target.entityId.startsWith("itunes:") || target.entityId.startsWith("discogs:")) {
        val isDiscogs = target.entityId.startsWith("discogs:")
        val artist = target.entityType == "ARTIST"
        val source = if (isDiscogs)
            com.music.bitchord.ui.discogsNativeAttributionUrl(browseId)
        else verifiedAppleDetailStoreUrl(sourceStoreUrl, browseId)
        return CanonicalCatalogEntityMenuSpec(
            browseType = if (artist) BrowseType.ARTIST else BrowseType.ALBUM,
            ratingTarget = target,
            musicBrainzUrl = "",
            ratingLabel = if (artist) "Avaliar artista" else "Avaliar álbum",
            playlistKind = null,
            sourceUrl = source,
            sourceLabel = if (isDiscogs) "Abrir no Discogs" else "Abrir no Apple Music",
        )
    }
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
    sourceStoreUrl: String? = null,
    onDismiss: () -> Unit,
    onRate: (CanonicalCatalogEntityMenuSpec) -> Unit,
    onAddVerifiedRecordings: ((CanonicalCatalogEntityMenuSpec) -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onOpenLabel: String? = null,
    isCurrent: () -> Boolean = { true },
    onAddAmsCollection: (() -> Unit)? = null,
    onAmsPlay: (() -> Unit)? = null,
    onAmsShuffle: (() -> Unit)? = null,
    onAmsPlayNext: (() -> Unit)? = null,
    onAmsAddToQueue: (() -> Unit)? = null,
) {
    val spec = remember(browseId,sourceStoreUrl) {
        canonicalCatalogEntityMenuSpec(browseId,sourceStoreUrl)
    } ?: return
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
    // Only expose pinning for sources with a verified Library reopen route.
    val canPin = (browseId.startsWith("catalog:") || browseId.startsWith("apple_music_web:")) &&
        validCatalogMetadataPin(pin)
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
        onOpen = onOpen?.let { open -> { if (isCurrent()) open() } },
        onOpenLabel = onOpenLabel,
        // Only AMS collections can receive explicitly admitted sample actions.
        onPlay = amsCollectionMenuAction(spec,onAmsPlay,isCurrent,onDismiss),
        onShuffle = amsCollectionMenuAction(spec,onAmsShuffle,isCurrent,onDismiss),
        onPlayNext = amsCollectionMenuAction(spec,onAmsPlayNext,isCurrent,onDismiss),
        onAddToQueue = amsCollectionMenuAction(spec,onAmsAddToQueue,isCurrent,onDismiss),
        onAddToPlaylist = if (onAddAmsCollection != null && spec.ratingTarget.entityId.startsWith("apple_music_web:") &&
            spec.browseType in setOf(BrowseType.ALBUM,BrowseType.PLAYLIST)) {
            { if (isCurrent()) { onDismiss(); onAddAmsCollection() } }
        } else if (spec.playlistKind != null && onAddVerifiedRecordings != null) {
            {
                if (isCurrent()) {
                    onDismiss()
                    onAddVerifiedRecordings(spec)
                }
            }
        } else null,
        onEditRating = {
            if (isCurrent()) {
                onDismiss()
                onRate(spec)
            }
        },
        isPinned = pinned,
        onTogglePin = if (canPin) {
            {
                if (isCurrent()) {
                    repository.toggle(pin)
                    onDismiss()
                }
            }
        } else null,
        onShare = share@{
            if (!isCurrent()) return@share
            onDismiss()
            val text = buildString {
                append(title)
                if (subtitle.isNotBlank()) append(" — ").append(subtitle)
                spec.sourceUrl?.let { append("\n").append(it) }
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(share, "Compartilhar catálogo"))
        },
        onViewSource = spec.sourceUrl?.let { source -> {
            if (isCurrent()) {
                onDismiss()
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source)))
            }
        } },
        viewSourceLabel = spec.sourceLabel,
    )
}

internal fun amsCollectionMenuAction(spec: CanonicalCatalogEntityMenuSpec, action: (() -> Unit)?,
    isCurrent: () -> Boolean, dismiss: () -> Unit): (() -> Unit)? {
    if (action == null || !spec.ratingTarget.entityId.startsWith("apple_music_web:") ||
        spec.browseType !in setOf(BrowseType.ALBUM,BrowseType.PLAYLIST)) return null
    return { if (isCurrent()) { dismiss(); action() } }
}
