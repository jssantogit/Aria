package com.music.aria.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.aria.playback.CanonicalCollectionIntent
import com.music.aria.data.listening.SavedCanonicalStaticPlaylist
import com.music.aria.data.model.Song
import com.music.aria.data.spotify.LocalPlaylistStore
import com.music.aria.download.Downloads
import com.music.aria.data.listening.CanonicalStaticPlaylistRepository
import com.music.aria.data.listening.SmartPlaylistDefinitionDatabase
import com.music.aria.data.catalog.CanonicalCatalogDatabase
import com.music.aria.data.catalog.metadata.isTrustedArchiveImageUrl
import com.music.aria.data.model.ROW_ART_PX
import com.music.aria.data.model.artworkAt
import com.music.aria.data.catalog.CanonicalRecordingReleaseGroupLinkRow
import com.music.aria.data.catalog.CatalogReleaseGroupEntity
import com.music.aria.playback.CanonicalCollectionIntentPlanner
import com.music.aria.ui.components.PAGE_GUTTER
import com.music.aria.ui.components.NativeCompactMenuItem
import com.music.aria.ui.components.HeldContextMenu
import com.music.aria.ui.components.HeldItem
import com.music.aria.ui.components.LongPressOrigin
import com.music.aria.ui.components.ActionRow
import com.music.aria.ui.components.NativeDetailTrackRowLayout
import com.music.aria.ui.components.SongRow
import com.music.aria.ui.components.NativeDetailTrackSwipeContainer
import com.music.aria.ui.components.nativeCapturedSwipeRowBackground
import com.music.aria.ui.components.NativeDetailTrackArtwork
import com.music.aria.ui.components.NativeDetailTrackRatingSlot
import com.music.aria.ui.components.NativeTrackRowText
import com.music.aria.ui.components.CanonicalRatingStars
import com.music.aria.ui.components.LocalCanonicalRatingDisplay
import com.music.aria.ui.components.CanonicalRatingDisplaySnapshot
import com.music.aria.ui.components.RatingDisplay
import com.music.aria.ui.theme.rememberArtworkPalette
import com.music.aria.ui.theme.appleMusicKeyColor
import com.music.aria.ui.icons.BitChordIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Only catalog-backed, currently available and unsaved RecordingIds can be suggested. */
internal fun suggestedCanonicalPlaylistTracks(
    available: List<V2DisplayRow>,
    selectedIds: Set<String>,
    page: Int,
    shuffleSeed: Long = 0L,
): List<V2DisplayRow> {
    val candidates = available.distinctBy { it.id }
        .filter { it.id !in selectedIds && it.isAvailablePlaylistMember() }
    if (candidates.isEmpty()) return emptyList()
    // A shuffled bag is exhausted before any song repeats. Adjacent cycles
    // must also avoid replaying the final (possibly partial) page immediately.
    val pageCount = (candidates.size + 4) / 5
    val cycle = Math.floorDiv(page.coerceAtLeast(0), pageCount)
    val finalPageSize = ((candidates.size - 1) % 5) + 1
    var previousTail = emptySet<String>()
    var ordered = candidates
    for (index in 0..cycle) {
        val roundSeed = shuffleSeed xor (index.toLong() * -7046029254386353131L)
        val randomized = candidates.shuffled(kotlin.random.Random(roundSeed))
        ordered = if (index == 0 || candidates.size <= 5) {
            randomized
        } else {
            // Enough other recordings always exist for five new suggestions
            // when there are at least six candidates.
            val eligible = randomized.filterNot { it.id in previousTail }
            val deferred = randomized.filter { it.id in previousTail }
            eligible.take(5) + (eligible.drop(5) + deferred).shuffled(
                kotlin.random.Random(roundSeed xor -4658895280553007687L),
            )
        }
        previousTail = ordered.takeLast(finalPageSize).mapTo(HashSet()) { it.id }
    }
    return ordered.drop(Math.floorMod(page, pageCount) * 5).take(5)
}

/** Preserve the playlist's original ID, source, description and user-picked cover. */
internal fun appendSuggestedRecording(
    playlist: SavedCanonicalStaticPlaylist,
    recordingId: String,
): SavedCanonicalStaticPlaylist =
    if (recordingId in playlist.recordingIds) playlist
    else playlist.copy(recordingIds = playlist.recordingIds + recordingId)

/** Explicit user cover always takes priority over generated recording collages. */
internal fun LibraryPlaylistCard.explicitCoverUri(): String? = when (this) {
    is LibraryPlaylistCard.CanonicalNormal -> definition.coverUri
    is LibraryPlaylistCard.Smart -> definition.coverUri
    is LibraryPlaylistCard.Normal -> null
}

/** Repeat each sleeve only once in a collage, as the native playlist page does. */
internal fun distinctCanonicalPlaylistCoverRows(rows: List<V2DisplayRow>): List<V2DisplayRow> =
    rows.asSequence()
        // MusicBrainz-only members have verified labels, not cover media.
        // Never spend a collage quadrant on their generic music-note placeholder.
        .filter { row ->
            row.isAvailablePlaylistMember() &&
                (!row.localArtworkUri.isNullOrBlank() ||
                    (!row.remoteArtworkRevision.isNullOrBlank() &&
                        (!row.remoteMediaAssetId.isNullOrBlank() ||
                            row.remoteArtworkRevision.startsWith("content://") ||
                            row.remoteArtworkRevision.startsWith("file://"))))
        }
        .distinctBy { row ->
            row.localArtworkUri?.let { "local:$it" }
                ?: row.remoteArtworkRevision?.takeIf { it.isNotBlank() }
                ?: row.remoteMediaAssetId?.let { "remote:$it" }
                ?: row.id
        }.take(4).toList()

/** A native playable sleeve or a trusted official MusicBrainz front.
 * Metadata artwork never changes the Recording's playback capability. */
internal data class CanonicalPlaylistVisualCover(
    val playable: V2DisplayRow? = null,
    val verifiedCatalogUrl: String? = null,
)

internal fun distinctCanonicalPlaylistVisualCovers(
    rows: List<V2DisplayRow>,
    verifiedMetadataCovers: Map<String, String>,
): List<CanonicalPlaylistVisualCover> {
    val audioCovers = distinctCanonicalPlaylistCoverRows(rows).associateBy(V2DisplayRow::id)
    return rows.mapNotNull { row ->
        val playable = audioCovers[row.id]
        if (playable != null) CanonicalPlaylistVisualCover(playable = playable)
        else verifiedMetadataCovers[row.id]?.takeIf(::isTrustedArchiveImageUrl)
            ?.let { CanonicalPlaylistVisualCover(verifiedCatalogUrl = it) }
    }.distinctBy { cover ->
        cover.playable?.let { row ->
            row.localArtworkUri?.let { "local:$it" }
                ?: row.remoteArtworkRevision?.let { "remote:$it" }
                ?: "asset:${row.remoteMediaAssetId}"
        } ?: "catalog:${cover.verifiedCatalogUrl}"
    }.take(4)
}

@Composable
internal fun CanonicalPlaylistVisualArtwork(
    cover: CanonicalPlaylistVisualCover,
    modifier: Modifier = Modifier,
) {
    val row = cover.playable
    if (row != null) SmartPlaylistPreviewArtwork(row, modifier)
    else Box(modifier.clip(publicPlaylistTrackCoverShape())) {
        NativePlaylistMissingArtwork(Modifier.fillMaxSize())
        cover.verifiedCatalogUrl?.let { url ->
            AsyncImage(
                model = url.artworkAt(ROW_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Artwork always comes from recordings actually in the saved collection.
 * No generated cover URLs, no legacy video IDs and no title-based album lookup.
 */
@Composable
internal fun CanonicalPlaylistCoverArt(
    rows: List<V2DisplayRow>,
    modifier: Modifier = Modifier,
    customCoverUri: String? = null,
    verifiedMetadataCovers: Map<String, String>? = null,
) {
    // Parent detail supplies its batch; standalone Library cards resolve only
    // their own saved members. Neither path queries Room per visible row.
    val ownCovers = if (verifiedMetadataCovers == null)
        rememberVerifiedPlaylistMetadataCovers(rows) else emptyMap()
    val byRecording = verifiedMetadataCovers ?: ownCovers
    val covers = remember(rows, byRecording) {
        distinctCanonicalPlaylistVisualCovers(rows, byRecording)
    }
    when {
        !customCoverUri.isNullOrBlank() -> Box(modifier.clip(RoundedCornerShape(12.dp))) {
            // As with native track artwork, preserve the automatic sleeve
            // beneath Coil. A revoked/broken document must not leave a blank cover.
            CanonicalPlaylistCoverArt(
                rows = rows, modifier = Modifier.fillMaxSize(),
                verifiedMetadataCovers = byRecording,
            )
            AsyncImage(
                model = customCoverUri,
                contentDescription = "Capa personalizada",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        covers.size >= 2 -> CanonicalPlaylistCoverTiles(
            covers = covers, modifier = modifier,
        )
        covers.isNotEmpty() -> CanonicalPlaylistVisualArtwork(
            cover = covers.first(), modifier = modifier,
        )
        else -> BoxWithConstraints(
            modifier.clip(RoundedCornerShape(12.dp))
                .testTag("canonical-playlist-empty-cover"),
            contentAlignment = Alignment.Center,
        ) {
            // The empty cover also appears inside 44dp Library tiles. A
            // hard-coded 180dp placeholder overflowed those compact frames.
            PlaylistWizardArtworkPlaceholder(minOf(maxWidth, maxHeight))
        }
    }
}

/** Cover every pixel of the collage with verified artwork.
 * With two or three releases, don't add generic empty quadrants or discard
 * the other real sleeve; use the same native artwork renderer at every size. */
@Composable
internal fun CanonicalPlaylistCoverTiles(
    covers: List<CanonicalPlaylistVisualCover>,
    modifier: Modifier = Modifier,
) {
    val verified = covers.take(4)
    when (verified.size) {
        2 -> Row(modifier.clip(RoundedCornerShape(12.dp))
            .testTag("canonical-playlist-cover-two")) {
            verified.forEach { row ->
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    CanonicalPlaylistVisualArtwork(row, Modifier.fillMaxSize())
                }
            }
        }
        3 -> Row(modifier.clip(RoundedCornerShape(12.dp))
            .testTag("canonical-playlist-cover-three")) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CanonicalPlaylistVisualArtwork(verified[0], Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                verified.drop(1).forEach { row ->
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        CanonicalPlaylistVisualArtwork(row, Modifier.fillMaxSize())
                    }
                }
            }
        }
        4 -> Column(modifier.clip(RoundedCornerShape(12.dp))
            .testTag("canonical-playlist-cover-four")) {
            for (offset in listOf(0, 2)) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (index in offset until offset + 2) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            CanonicalPlaylistVisualArtwork(verified[index], Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        else -> verified.firstOrNull()?.let { row ->
            CanonicalPlaylistVisualArtwork(row, modifier)
        }
    }
}

/** The same 52dp, 8dp-radius neutral music-note fallback as public playlists.
 * Missing artwork is not an excuse to gray out a real saved Recording identity. */
@Composable
internal fun NativePlaylistMissingArtwork(modifier: Modifier = Modifier) {
    Box(
        modifier.clip(publicPlaylistTrackCoverShape())
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("native-playlist-missing-artwork"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A user-picked playlist cover owns the page colors, not whichever track
 * happens to be first. Uncovered playlists retain their existing fallback.
 */
internal fun canonicalPlaylistPaletteArtworkUri(
    explicitCoverUri: String?,
    rows: List<V2DisplayRow>?,
    verifiedMetadataCovers: Map<String, String> = emptyMap(),
): String? = explicitCoverUri?.takeIf { it.isNotBlank() }
    ?: rows?.firstNotNullOfOrNull { row ->
        row.localArtworkUri?.takeIf(String::isNotBlank)
            ?: verifiedMetadataCovers[row.id]?.takeIf(::isTrustedArchiveImageUrl)
    }

/** A verified playlist count is not a popularity, ranking or editorial claim. */
internal fun verifiedPlaylistMemberCountSummary(count: Int): String =
    "${count} " + if (count == 1) "faixa" else "faixas"

internal fun canonicalPlaylistHeaderType(smart: Boolean): String =
    if (smart) "Playlist inteligente" else "Playlist"

/** Null means membership is loading; never show a misleading zero. */
internal fun canonicalPlaylistMembershipSummary(
    rows: List<V2DisplayRow>?,
    loadFailed: Boolean = false,
): String? {
    if (rows == null || loadFailed) return null
    val count = verifiedPlaylistMemberCountSummary(rows.size)
    val durations = rows.map { it.durationMs }
    val total = if (durations.isNotEmpty() && durations.all { (it ?: 0L) > 0L }) {
        durations.filterNotNull().sum()
    } else 0L
    val minutes = total / 60000L
    val time = when {
        total <= 0L -> ""
        minutes >= 60L -> " · ${minutes / 60L}h ${minutes % 60L}min"
        else -> " · ${minutes.coerceAtLeast(1L)}min"
    }
    return "${count}${time}"
}

/** Compatibility for callers still requiring the full one-line summary. */
internal fun canonicalPlaylistDetailMeta(
    smart: Boolean, rows: List<V2DisplayRow>?,
): String = listOfNotNull(canonicalPlaylistHeaderType(smart),
    canonicalPlaylistMembershipSummary(rows)).joinToString(" · ")

/** A saved RecordingId without eligible MediaAsset remains visible but is not clickable. */
internal fun V2DisplayRow.isAvailablePlaylistMember(): Boolean =
    (title != "Gravação indisponível" || artist != "Fonte não disponível") &&
        // Metadata-only labels are useful offline but NEVER a playback proof.
        !artist.endsWith(CATALOG_METADATA_NO_AUDIO_SUFFIX)

/** A metadata-only entry must not disable a mixed playlist's play/shuffle UI.
 * The playback layer independently validates each eligible asset at queue time. */
internal fun canonicalPlaylistHasPlayableRows(rows: List<V2DisplayRow>?): Boolean =
    rows != null &&
        rows.size <= CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE &&
        rows.any(V2DisplayRow::isAvailablePlaylistMember)

/** Remove by exact RecordingId, keeping source, artwork and every other member. */
internal fun removeCanonicalPlaylistRecording(
    playlist: SavedCanonicalStaticPlaylist,
    recordingId: String,
): SavedCanonicalStaticPlaylist =
    if (recordingId !in playlist.recordingIds) playlist
    else playlist.copy(recordingIds = playlist.recordingIds.filterNot { it == recordingId })

/** Only an exact, uniquely proven Recording → ReleaseGroup relationship
 * enables album navigation. No artist/title inference or arbitrary edition. */
internal fun canonicalPlaylistAlbumForRecording(
    recordingId: String,
    links: List<CanonicalRecordingReleaseGroupLinkRow>,
): String? = links.asSequence()
    .filter { it.recordingId == recordingId }
    .map { it.releaseGroupId }
    .distinct()
    .singleOrNull()
    ?.takeIf(::isVerifiedCatalogRecordingId)

/** The same native Library row used everywhere, wired to canonical playback. */
/** Native SongRow's proven dimensions and controls, but canonical RecordingIds. */
/** Match the chosen remote rendition first; only fall back to a proven
 * canonical recording rating. No title joins, no provider IO in list rows. */
internal fun V2DisplayRow.canonicalPlaylistRating(
    snapshot: CanonicalRatingDisplaySnapshot,
): RatingDisplay? = remoteMediaAssetId?.let(snapshot.byMediaAssetId::get)
    ?: snapshot.byRecordingId[id]

internal fun canonicalTrackDuration(durationMs: Long?): String? =
    durationMs?.takeIf { it > 0 }?.let { ms ->
        val seconds = ms / 1000L
        if (seconds >= 3600L) "%d:%02d:%02d".format(
            seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L,
        ) else "%d:%02d".format(seconds / 60L, seconds % 60L)
    }

@Composable
internal fun CanonicalPlaylistTrackRow(
    track: V2DisplayRow,
    onPlay: (String) -> Unit,
    onQueue: ((String, CanonicalCollectionIntent) -> Unit)? = null,
    onHeldActions: (() -> Unit)? = null,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground,
    subtitleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    rowBackground: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.background,
    activeTint: androidx.compose.ui.graphics.Color = appleMusicKeyColor(),
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    allowUnavailableHold: Boolean = false,
    verifiedMetadataArtworkUrl: String? = null,
) {
    val available = track.isAvailablePlaylistMember()
    // Ratings belong to the Recording identity, not to playback capability.
    val trackRating = track.canonicalPlaylistRating(LocalCanonicalRatingDisplay.current)
    val swipeSetting = com.music.aria.ui.AppUi.hostOrNull?.swipeToPlayNext
    val playNext = swipeSetting?.collectAsStateWithLifecycle()?.value ?: false
    val onSwipeToQueue = if (available && onQueue != null) {
        {
            onQueue(
                track.id,
                canonicalPlaylistSwipeIntent(playNext),
            )
        }
    } else null
    NativeDetailTrackSwipeContainer(
        onSwipeToQueue = onSwipeToQueue,
        // The row must mask the queue reveal on an artwork-tinted page.
        rowBackground = rowBackground,
    ) { rowModifier, swipeCover ->
        NativeDetailTrackRowLayout(
            modifier = rowModifier.heightIn(min = 68.dp).then(
                if (isCurrent) Modifier.testTag("canonical-playlist-current-track")
                else Modifier,
            ),
            background = nativeCapturedSwipeRowBackground(
                swipeCover,
                if (isCurrent) activeTint.copy(alpha = 0.14f)
                else androidx.compose.ui.graphics.Color.Transparent,
            ),
            onClick = if (available) ({ onPlay(track.id) }) else null,
            // Even non-playable saved entries must remain accessible for removal.
            onLongPress = if (available || allowUnavailableHold) onHeldActions else null,
            onMore = null,
            // "Sem áudio" is already explicit; do not make the label illegible.
            opacity = 1f,
            leading = {
                NativeDetailTrackArtwork(showPlayingBars = available && isCurrent && isPlaying) {
                    Box(Modifier.fillMaxSize().clip(publicPlaylistTrackCoverShape())) {
                        if (available) SmartPlaylistPreviewArtwork(
                            row = track, modifier = Modifier.fillMaxSize(),
                        ) else {
                            NativePlaylistMissingArtwork(Modifier.fillMaxSize())
                            verifiedMetadataArtworkUrl?.takeIf(::isTrustedArchiveImageUrl)
                                ?.let { url ->
                                    AsyncImage(
                                        model = url.artworkAt(ROW_ART_PX),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                        }
                    }
                }
            },
            title = {
                Text(
                    track.title, style = MaterialTheme.typography.titleMedium,
                    color = if (isCurrent) activeTint else titleColor, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            subtitle = {
                Text(
                    track.artist, style = MaterialTheme.typography.bodyMedium,
                    color = subtitleColor, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            rating = {
                CanonicalRatingStars(
                    rating = trackRating,
                    color = titleColor,
                )
            },
            showRatingSlot = available || trackRating != null,
            actions = {},

        )
    }
}

/** The caller supplies only a verified canonical RecordingId, never a title or video ID. */
internal fun isVerifiedCanonicalPlaylistCurrentTrack(
    recordingId: String,
    currentCanonicalRecordingId: String?,
): Boolean = recordingId.isNotBlank() && currentCanonicalRecordingId != null &&
    recordingId == currentCanonicalRecordingId

/** The exact same queue intent selected by the shared SongRow swipe setting. */
internal fun canonicalPlaylistSwipeIntent(playNext: Boolean): CanonicalCollectionIntent =
    if (playNext) CanonicalCollectionIntent.ADD_NEXT
    else CanonicalCollectionIntent.ADD_TO_QUEUE

/**
 * Provider-independent playlist track actions. They are composed inside the
 * same frosted HeldContextMenu used by local and Navidrome rows, not in a
 * solid Popup anchored to a now-retired row ellipsis.
 */
@Composable
internal fun CanonicalPlaylistHeldTrackActions(
    onDismiss: () -> Unit,
    onPlay: (() -> Unit)?,
    onPlayNext: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?,
    onOpenAlbum: (() -> Unit)?,
    onRemoveTrack: (() -> Unit)? = null,
    onRemoveAlbum: (() -> Unit)? = null,
) {
        onPlay?.let { play ->
            NativeCompactMenuItem(
                label = "Reproduzir daqui",
                icon = Icons.Rounded.PlayArrow,
                onClick = {
                    onDismiss()
                    play()
                },
            )
        }
        onPlayNext?.let { next ->
            NativeCompactMenuItem(
                label = "Reproduzir a seguir",
                icon = Icons.Rounded.SkipNext,
                onClick = {
                    onDismiss()
                    next()
                },
            )
        }
        onAddToQueue?.let { queue ->
            NativeCompactMenuItem(
                label = "Adicionar à fila",
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                onClick = {
                    onDismiss()
                    queue()
                },
            )
        }
        onOpenAlbum?.let { openAlbum ->
            NativeCompactMenuItem(
                label = "Abrir álbum",
                icon = Icons.Rounded.Album,
                onClick = {
                    onDismiss()
                    openAlbum()
                },
            )
        }
        onRemoveTrack?.let { remove ->
            NativeCompactMenuItem(
                label = "Remover faixa",
                icon = Icons.Rounded.DeleteOutline,
                onClick = {
                    onDismiss()
                    remove()
                },
            )
        }
        onRemoveAlbum?.let { remove ->
            NativeCompactMenuItem(
                label = "Remover álbum",
                icon = Icons.Rounded.Album,
                onClick = {
                    onDismiss()
                    remove()
                },
            )
        }
}

/**
 * A playlist is a native Library detail page, not a separate full-screen Dialog.
 * The owning Library host keeps the floating mini-player and bottom navigation.
 */
@Composable
internal fun CanonicalPlaylistDetailScreen(
    card: LibraryPlaylistCard,
    onClose: () -> Unit,
    onAction: (CanonicalCollectionIntent) -> Unit,
    onShuffle: () -> Unit,
    onTrackClick: (String) -> Unit,
    onTrackQueue: (String, CanonicalCollectionIntent) -> Unit,
    onOpenAlbum: (String, String, String) -> Unit = { _, _, _ -> },
    onLegacyPlay: (String, List<Song>, Int) -> Unit = { _, _, _ -> },
    onLegacyShuffle: (String, List<Song>) -> Unit = { _, _ -> },
    onLegacyQueue: (List<Song>) -> Unit = {},
    onLegacySongMenu: (Song) -> Unit = {},
    onLegacySongSwipe: (Song) -> Unit = {},
    onAddSongs: () -> Unit = {},
    onEditRules: () -> Unit = {},
    contentPadding: PaddingValues,
    searching: Boolean = false,
    onSearchingChange: (Boolean) -> Unit = {},
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onRowsReady: (List<V2DisplayRow>?) -> Unit = {},
    sort: PlaylistLibrarySort = PlaylistLibrarySort.DEFAULT,
    listState: LazyListState? = null,
    currentCanonicalRecordingId: String? = null,
    isPlaying: Boolean = false,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current.applicationContext
    val playlistPinRepo = remember(context) { LibraryPlaylistPinRepository.get(context) }
    val ratingTarget = remember(card.id) { canonicalOwnedPlaylistRatingTarget(card.id) }
    val rating by rememberCanonicalCatalogEntityRating(ratingTarget)
    var editingRating by remember(card.id) { mutableStateOf(false) }
    if (editingRating && ratingTarget != null) {
        CanonicalCatalogEntityRatingDialog(
            target = ratingTarget, title = "Avaliar playlist",
            onDismiss = { editingRating = false },
        )
    }
    val pinnedPlaylists by playlistPinRepo.pins.collectAsStateWithLifecycle()
    val historicalPlaylists by LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    val historicalSongs = (card as? LibraryPlaylistCard.Normal)?.let { normal ->
        historicalPlaylists.firstOrNull { it.browseId == normal.shelfItem.browseId }?.songs
    }.orEmpty()
    val historicalPlayable = historicalSongs.mapIndexedNotNull { index, song ->
        legacyPlaylistPlayableSong(song, Downloads.verifiedSavedUri(song.videoId))
            ?.let { index to it }
    }
    val playableHistoricalSongs = historicalPlayable.map { it.second }
    val normalRepository = remember(context) {
        CanonicalStaticPlaylistRepository(
            SmartPlaylistDefinitionDatabase.get(context).staticPlaylistDao(),
        )
    }
    val scope = rememberCoroutineScope()
    var suggestionPage by remember(card.id) { mutableStateOf(0) }
    val suggestionShuffleSeed = remember(card.id) { kotlin.random.Random.nextLong() }
    var pendingSuggestionId by remember(card.id) { mutableStateOf<String?>(null) }
    var suggestionError by remember(card.id) { mutableStateOf(false) }
    var trackLoadFailed by remember(card.id) { mutableStateOf(false) }
    val source = (card as? LibraryPlaylistCard.CanonicalNormal)?.definition?.sourceProviderKey
    val availableSuggestions by produceState(
        initialValue = emptyList<V2DisplayRow>(), key1 = card.id, key2 = source,
    ) {
        if (card is LibraryPlaylistCard.CanonicalNormal) {
            value = try {
                canonicalNormalPlaylistSearch(context, "", source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    var pendingAlbumRemoval by remember(card.id) {
        mutableStateOf<CanonicalPlaylistAlbumRemovalRequest?>(null)
    }
    val suggestions = suggestedCanonicalPlaylistTracks(
        availableSuggestions,
        (card as? LibraryPlaylistCard.CanonicalNormal)
            ?.definition?.recordingIds.orEmpty().toSet(),
        suggestionPage,
        shuffleSeed = suggestionShuffleSeed,
    )
    fun removeSavedRecording(id: String) {
        val playlist = card as? LibraryPlaylistCard.CanonicalNormal ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Read on click: another screen may have edited this playlist.
                    val latest = normalRepository.list().firstOrNull {
                        it.id == playlist.definition.id
                    } ?: error("Playlist indisponível")
                    if (id in latest.recordingIds) {
                        normalRepository.save(removeCanonicalPlaylistRecording(latest, id))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(
                    context, "Não foi possível remover a faixa.", Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    fun addSuggested(id: String) {
        val playlist = card as? LibraryPlaylistCard.CanonicalNormal ?: return
        if (pendingSuggestionId != null || id !in suggestions.map { it.id }) return
        pendingSuggestionId = id
        suggestionError = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Reload at tap time: never overwrite a concurrent edit of
                    // the same canonical playlist with a stale detail snapshot.
                    val current = normalRepository.list().firstOrNull {
                        it.id == playlist.definition.id
                    } ?: error("Playlist indisponível")
                    normalRepository.save(appendSuggestedRecording(current, id))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                suggestionError = true
            } finally {
                pendingSuggestionId = null
            }
        }
    }
    val rows by produceState<List<V2DisplayRow>?>(initialValue = null, key1 = card.id, key2 = card) {
        value = try {
            trackLoadFailed = false
            when (card) {
                is LibraryPlaylistCard.CanonicalNormal ->
                    canonicalNormalPlaylistSelectedRows(
                        context, card.definition.recordingIds, card.definition.sourceProviderKey,
                    )
                is LibraryPlaylistCard.Smart -> {
                    val ids = withContext(Dispatchers.IO) {
                        // Building the rule evaluator can read Room configuration.
                        // Keep it away from the main thread, especially in a lazy shelf.
                        persistentSmartPlaylistPreview(context)
                            .preview(card.definition.query, card.definition.id)
                            .map { it.recordingId }
                    }
                    canonicalNormalPlaylistSelectedRows(context, ids)
                }
                is LibraryPlaylistCard.Normal -> emptyList()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            trackLoadFailed = true
            emptyList()
        }
    }
    val metadataCovers = rememberVerifiedPlaylistMetadataCovers(rows)
    LaunchedEffect(card.id, rows) {
        onRowsReady(rows)
    }
    // Resolve in batches, once per playlist snapshot. Rendering lazy rows
    // never queries Room individually and never guesses album by track name.
    val albumByRecording by produceState(
        initialValue = emptyMap<String, CatalogReleaseGroupEntity>(),
        key1 = card.id, key2 = rows,
    ) {
        val ids = rows.orEmpty().map { it.id }.distinct().filter(::isVerifiedCatalogRecordingId)
        value = if (ids.isEmpty()) emptyMap() else try {
            withContext(Dispatchers.IO) {
                val dao = CanonicalCatalogDatabase.get(context).catalogDao()
                val links = ids.chunked(300).flatMap { chunk ->
                    dao.recordingReleaseGroupLinksByIds(chunk)
                }.groupBy { it.recordingId }
                val uniqueLinks = ids.mapNotNull { recordingId ->
                    canonicalPlaylistAlbumForRecording(
                        recordingId, links[recordingId].orEmpty(),
                    )?.let { recordingId to it }
                }.toMap()
                val groups = uniqueLinks.values.distinct().chunked(300)
                    .flatMap { groupIds -> dao.catalogReleaseGroupsByIds(groupIds) }
                    .associateBy(CatalogReleaseGroupEntity::id)
                uniqueLinks.mapNotNull { (recordingId, groupId) ->
                    groups[groupId]?.takeIf { it.title.isNotBlank() }
                        ?.let { recordingId to it }
                }.toMap()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
    }
    // The editor adds exact catalog ReleaseIds, not MusicBrainz ReleaseGroups.
    // Use those same membership edges to remove whole albums from this
    // particular playlist, never from the Library or another account.
    val exactAlbums by produceState(
        initialValue = emptyMap<String, com.music.aria.data.catalog.CanonicalRecordingReleaseTitleRow>(),
        key1 = card.id, key2 = rows, key3 = source,
    ) {
        value = if (card !is LibraryPlaylistCard.CanonicalNormal) emptyMap() else try {
            readCanonicalPlaylistExactAlbums(
                context, rows.orEmpty().map { it.id }, source,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
    }
    val visibleRows = remember(rows, query, sort) {
        val filtered = rows.orEmpty().filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
        }
        when (sort) {
            PlaylistLibrarySort.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
            PlaylistLibrarySort.TITLE_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            PlaylistLibrarySort.DEFAULT -> filtered
        }
    }
    val visibleHistoricalSongs = historicalSongs.withIndex().filter { (_, song) ->
        song.title.contains(query, ignoreCase = true) ||
            song.artist.contains(query, ignoreCase = true)
    }.let { indexed ->
        when (sort) {
            PlaylistLibrarySort.TITLE_ASC -> indexed.sortedBy { it.value.title.lowercase() }
            PlaylistLibrarySort.TITLE_DESC -> indexed.sortedByDescending { it.value.title.lowercase() }
            PlaylistLibrarySort.DEFAULT -> indexed
        }
    }
    val palette = rememberArtworkPalette(
        imageUrl = if (card is LibraryPlaylistCard.Normal) {
            card.shelfItem.thumbnailUrl ?: historicalSongs.firstOrNull()?.thumbnailUrl
        } else canonicalPlaylistPaletteArtworkUri(
            card.explicitCoverUri(), rows, metadataCovers,
        ),
    )
    val playlistDescription = when (card) {
        is LibraryPlaylistCard.CanonicalNormal -> card.definition.description
        is LibraryPlaylistCard.Smart -> card.definition.description
        is LibraryPlaylistCard.Normal -> ""
    }.takeIf { it.isNotBlank() }
    val canPlay = if (card is LibraryPlaylistCard.Normal) {
        playableHistoricalSongs.isNotEmpty()
    } else canonicalPlaylistHasPlayableRows(rows)

    var heldTrack by remember(card.id) { mutableStateOf<V2DisplayRow?>(null) }
    var heldTrackOrigin by remember(card.id) { mutableStateOf<HeldItem?>(null) }
    val dismissHeldTrack: () -> Unit = {
        heldTrack = null
        heldTrackOrigin = null
    }
    // Measure once against the actual detail viewport, as canonical albums do.
    // A LazyColumn item has unbounded height, so its own BoxWithConstraints
    // cannot reliably enforce the landscape artwork-height limit.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val releaseSleeve = nativeReleaseSleeveSize(maxWidth, maxHeight)
    NativePlaylistDetailFrame(
        palette = palette,
        listState = listState ?: rememberLazyListState(),
        bottomPadding = contentPadding.calculateBottomPadding(),
        heldActions = { hazeState ->
            HeldContextMenu(
                item = heldTrack,
                held = heldTrackOrigin,
                hazeState = hazeState,
                hostProvidesBlur = true,
                bottomChromeClearance = maxOf(154.dp, contentPadding.calculateBottomPadding()),
                plateTint = palette.elevated,
                onDismiss = dismissHeldTrack,
                onPreviewClick = { chosen ->
                    dismissHeldTrack()
                    if (chosen.isAvailablePlaylistMember()) onTrackClick(chosen.id)
                },
            ) { chosen ->
                CanonicalPlaylistHeldTrackActions(
                    onDismiss = dismissHeldTrack,
                    onPlay = if (chosen.isAvailablePlaylistMember()) {
                        { onTrackClick(chosen.id) }
                    } else null,
                    onPlayNext = if (chosen.isAvailablePlaylistMember()) {
                        { onTrackQueue(chosen.id, CanonicalCollectionIntent.ADD_NEXT) }
                    } else null,
                    onAddToQueue = if (chosen.isAvailablePlaylistMember()) {
                        { onTrackQueue(chosen.id, CanonicalCollectionIntent.ADD_TO_QUEUE) }
                    } else null,
                    onOpenAlbum = albumByRecording[chosen.id]?.let { group ->
                        {
                            onOpenAlbum(
                                "catalog:RELEASE_GROUP:${group.id}",
                                group.title,
                                group.artistDisplay.orEmpty(),
                            )
                        }
                    },
                    onRemoveTrack = if (card is LibraryPlaylistCard.CanonicalNormal) {
                        { removeSavedRecording(chosen.id) }
                    } else null,
                    onRemoveAlbum = if (card is LibraryPlaylistCard.CanonicalNormal &&
                        canonicalPlaylistAlbumRemovalRequest(
                            chosen.id, rows.orEmpty().map { it.id }, exactAlbums,
                        ) != null
                    ) {
                        {
                            pendingAlbumRemoval = canonicalPlaylistAlbumRemovalRequest(
                                chosen.id, rows.orEmpty().map { it.id }, exactAlbums,
                            )
                        }
                    } else null,
                )
            }
        },
    ) {
        item(key = "playlist-detail-cover") {
            // The same hero frame used by DetailScreen.ReleaseHeader; the
            // playlist still passes real RecordingIds to its playback handoff.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            NativePlaylistDetailHeader(
                title = card.title,
                credit = "",
                // The type belongs under the title; verified totals close
                // the list, not the hero. This mirrors the observed hierarchy
                // without manufacturing Apple's editorial update metadata.
                meta = canonicalPlaylistHeaderType(card is LibraryPlaylistCard.Smart),
                palette = palette,
                rating = rating,
                onRate = if (ratingTarget != null) ({ editingRating = true }) else null,
                // Exactly the same 66% artwork and landscape height cap as albums.
                sleeveSize = releaseSleeve,
                artwork = {
                    if (card is LibraryPlaylistCard.Normal &&
                        !card.shelfItem.thumbnailUrl.isNullOrBlank()
                    ) {
                        AsyncImage(
                            model = card.shelfItem.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CanonicalPlaylistCoverArt(
                            rows = rows.orEmpty(), modifier = Modifier.fillMaxSize(),
                            customCoverUri = card.explicitCoverUri(),
                            verifiedMetadataCovers = metadataCovers,
                        )
                    }
                },
                actions = {
                    NativePlaylistHeroActions(
                        canPlay = canPlay,
                        isPinned = pinnedPlaylists.any { it.id == card.id },
                        onTogglePin = {
                            playlistPinRepo.toggle(LibraryPlaylistPin(card.id, card.title))
                        },
                        onPlay = {
                            if (card is LibraryPlaylistCard.Normal) {
                                onLegacyPlay(card.id, playableHistoricalSongs, 0)
                            } else onAction(CanonicalCollectionIntent.PLAY_NOW)
                        },
                        onShuffle = {
                            if (card is LibraryPlaylistCard.Normal) {
                                onLegacyShuffle(card.id, playableHistoricalSongs)
                            } else onShuffle()
                        },
                    )
                }
            )
            }
        }
        if (playlistDescription != null) {
            item(key = "playlist-detail-description") {
                AboutSection(
                    title = "Sobre a playlist",
                    text = playlistDescription,
                    palette = palette,
                    horizontalPadding = PAGE_GUTTER,
                )
            }
        }
        if (searching || query.isNotBlank()) {
            item(key = "playlist-detail-search") {
                NativeDetailSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onClose = {
                        onQueryChange("")
                        onSearchingChange(false)
                    },
                    autoFocus = searching,
                    onFocused = {},
                    palette = palette,
                )
            }
        }
        if (rows == null && card !is LibraryPlaylistCard.Normal) item(key = "playlist-detail-loading") {
            NativeLoadingSpinner(modifier = Modifier.fillParentMaxHeight(0.62f))
        }
        if (rows?.isEmpty() == true && card !is LibraryPlaylistCard.Normal) {
            if (trackLoadFailed) {
                item(key = "playlist-detail-load-error") {
                    Text(
                        "Não foi possível consultar as músicas desta playlist.",
                        modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (card is LibraryPlaylistCard.CanonicalNormal) {
                item(key = "playlist-detail-empty-add-and-suggestions") {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = PAGE_GUTTER, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PlaylistWizardSecondaryAction(
                            label = "Adicionar músicas",
                            onClick = onAddSongs,
                        )
                        if (availableSuggestions.isNotEmpty()) {
                            SettingsGroupSurface {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(
                                        start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Músicas sugeridas",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            "Adicione faixas da biblioteca à playlist",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    PlaylistSuggestionAction(
                                        icon = Icons.Rounded.Refresh,
                                        label = "Atualizar sugestões",
                                        enabled = availableSuggestions.size > 5,
                                        onClick = { suggestionPage++ },
                                    )
                                }
                                suggestions.forEach { suggested ->
                                    LibraryRow(
                                        title = suggested.title,
                                        subtitle = suggested.artist,
                                        imageUrl = null,
                                        titleColor = MaterialTheme.colorScheme.onSurface,
                                        subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        artwork = {
                                            SmartPlaylistPreviewArtwork(
                                                suggested, Modifier.fillMaxSize(),
                                            )
                                        },
                                        horizontalPadding = 16.dp,
                                        trailing = {
                                            PlaylistSuggestionAction(
                                                icon = Icons.Rounded.AddCircleOutline,
                                                label = "Adicionar ${suggested.title}",
                                                enabled = pendingSuggestionId == null,
                                                onClick = { addSuggested(suggested.id) },
                                            )
                                        },
                                    )
                                }
                                if (suggestionError) {
                                    Text(
                                        "Não foi possível adicionar a música.",
                                        modifier = Modifier.padding(16.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (card is LibraryPlaylistCard.Smart) {
                // Smart membership is rule-driven, never mutate an invented
                // manual track list. The native rule editor is the only action.
                item(key = "playlist-detail-empty-smart-edit") {
                    Box(Modifier.padding(horizontal = PAGE_GUTTER, vertical = 16.dp)) {
                        PlaylistWizardSecondaryAction(
                            label = "Editar regras",
                            onClick = onEditRules,
                        )
                    }
                }
            }
        }
        if (card is LibraryPlaylistCard.Normal) {
            items(visibleHistoricalSongs, key = { "historical:${it.index}" }) { indexed ->
                val playableIndex = historicalPlayable.indexOfFirst { it.first == indexed.index }
                val playable = historicalPlayable.getOrNull(playableIndex)?.second
                SongRow(
                    song = indexed.value,
                    onClick = {
                        if (playable != null && playableIndex >= 0) {
                            onLegacyPlay(card.id, playableHistoricalSongs, playableIndex)
                        }
                    },
                    modifier = if (playable == null) Modifier.alpha(0.45f) else Modifier,
                    onLongPress = playable?.let { { onLegacySongMenu(it) } },
                    onMore = playable?.let { { onLegacySongMenu(it) } },
                    onSwipeToQueue = playable?.let { { onLegacySongSwipe(it) } },
                    rowBackground = palette.background,
                    subtitleColor = palette.onBackgroundVariant,
                    activeTint = palette.accent,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 80.dp),
                    thickness = 0.5.dp,
                    color = palette.divider,
                )
            }
        }
        items(visibleRows, key = { it.id }) { track ->
            CanonicalPlaylistTrackRow(
                track = track,
                onPlay = onTrackClick,
                onQueue = onTrackQueue,
                onHeldActions = {
                    // The row's long press records exact native coordinates.
                    // Without these, never replace a track with a floating popup.
                    LongPressOrigin.consume()?.let { origin ->
                        heldTrackOrigin = origin
                        heldTrack = track
                    }
                },
                titleColor = palette.onBackground,
                subtitleColor = palette.onBackgroundVariant,
                rowBackground = palette.background,
                activeTint = palette.accent,
                isCurrent = isVerifiedCanonicalPlaylistCurrentTrack(
                    track.id, currentCanonicalRecordingId,
                ),
                isPlaying = isPlaying,
                allowUnavailableHold = card is LibraryPlaylistCard.CanonicalNormal,
                verifiedMetadataArtworkUrl = metadataCovers[track.id],
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 80.dp),
                thickness = 0.5.dp, color = palette.divider,
            )
        }
        if (query.isBlank()) {
            val summary = if (card is LibraryPlaylistCard.Normal) {
                verifiedPlaylistMemberCountSummary(historicalSongs.size)
            } else canonicalPlaylistMembershipSummary(rows, loadFailed = trackLoadFailed)
            if (summary != null) {
                item(key = "playlist-detail-summary") {
                    NativePlaylistDetailFooter(summary, palette)
                }
            }
        }
    }
    } // End common viewport scope; confirmation dialog stays outside it.
    pendingAlbumRemoval?.let { request ->
        CanonicalPlaylistAlbumRemovalConfirmation(
            request = request,
            onDismiss = { pendingAlbumRemoval = null },
            onConfirm = {
                pendingAlbumRemoval = null
                if (card is LibraryPlaylistCard.CanonicalNormal) {
                    scope.launch {
                        try {
                            val removed = withContext(Dispatchers.IO) {
                                val latest = normalRepository.list().firstOrNull {
                                    it.id == card.definition.id
                                } ?: error("Playlist indisponível")
                                val exact = readCanonicalPlaylistExactAlbums(
                                    context, latest.recordingIds, latest.sourceProviderKey,
                                )
                                val updated = removeCanonicalPlaylistVerifiedAlbum(
                                    latest, request, exact,
                                )
                                if (updated === latest) false else {
                                    normalRepository.save(updated)
                                    true
                                }
                            }
                            if (!removed) Toast.makeText(
                                context, "O álbum mudou. Atualize a playlist.",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Toast.makeText(
                                context, "Não foi possível remover o álbum.",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
        )
    }
}