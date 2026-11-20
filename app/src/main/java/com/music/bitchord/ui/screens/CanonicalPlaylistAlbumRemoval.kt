package com.music.bitchord.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalRecordingReleaseTitleRow
import com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist
import com.music.bitchord.ui.components.ConfirmationAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exact RecordingId → ReleaseId membership is the only album-removal proof.
 * Prefer the selected provider's owned association over global MusicBrainz
 * edition links; ambiguous assignments are excluded, not guessed by title,
 * artist or cover art.
 */
internal fun canonicalPlaylistExactAlbums(
    playlistRecordingIds: List<String>,
    globalLinks: List<CanonicalRecordingReleaseTitleRow>,
    sourceOwnedLinks: List<CanonicalRecordingReleaseTitleRow>,
): Map<String, CanonicalRecordingReleaseTitleRow> {
    val eligible = playlistRecordingIds.toHashSet()
    val global = globalLinks.filter { it.recordingId in eligible }.groupBy { it.recordingId }
    val owned = sourceOwnedLinks.filter { it.recordingId in eligible }.groupBy { it.recordingId }
    return playlistRecordingIds.distinct().mapNotNull { id ->
        val candidates = owned[id].orEmpty().ifEmpty { global[id].orEmpty() }
        val link = candidates.distinctBy { it.releaseId }.singleOrNull()
            ?.takeIf { it.releaseId.isNotBlank() && it.releaseTitle.isNotBlank() }
            ?: return@mapNotNull null
        id to link
    }.toMap()
}

internal fun canonicalPlaylistAlbumMemberIds(
    playlistRecordingIds: List<String>,
    selectedRecordingId: String,
    exactAlbums: Map<String, CanonicalRecordingReleaseTitleRow>,
): List<String> {
    val release = exactAlbums[selectedRecordingId]?.releaseId ?: return emptyList()
    return playlistRecordingIds.filter { exactAlbums[it]?.releaseId == release }
}

internal data class CanonicalPlaylistAlbumRemovalRequest(
    val selectedRecordingId: String,
    val releaseId: String,
    val releaseTitle: String,
    val recordingIds: List<String>,
)

internal fun canonicalPlaylistAlbumRemovalRequest(
    selectedRecordingId: String,
    playlistRecordingIds: List<String>,
    exactAlbums: Map<String, CanonicalRecordingReleaseTitleRow>,
): CanonicalPlaylistAlbumRemovalRequest? {
    val album = exactAlbums[selectedRecordingId] ?: return null
    val members = canonicalPlaylistAlbumMemberIds(
        playlistRecordingIds, selectedRecordingId, exactAlbums,
    )
    // With one member, Remove track is already the complete album action.
    if (members.size < 2) return null
    return CanonicalPlaylistAlbumRemovalRequest(
        selectedRecordingId, album.releaseId, album.releaseTitle, members,
    )
}

/** Revalidate after confirmation; never erase members newly added to the
 * playlist while a confirmation dialog was open, nor an ambiguous edition. */
internal fun removeCanonicalPlaylistVerifiedAlbum(
    latest: SavedCanonicalStaticPlaylist,
    request: CanonicalPlaylistAlbumRemovalRequest,
    currentAlbums: Map<String, CanonicalRecordingReleaseTitleRow>,
): SavedCanonicalStaticPlaylist {
    if (request.selectedRecordingId !in latest.recordingIds ||
        currentAlbums[request.selectedRecordingId]?.releaseId != request.releaseId) return latest
    val approved = request.recordingIds.toSet()
    val removable = latest.recordingIds.filter {
        it in approved && currentAlbums[it]?.releaseId == request.releaseId
    }.toSet()
    return if (removable.isEmpty()) latest
    else latest.copy(recordingIds = latest.recordingIds.filterNot(removable::contains))
}

/** The exact ReleaseIds used by Add Album are queried in batches, off-main. */
internal suspend fun readCanonicalPlaylistExactAlbums(
    context: Context,
    playlistRecordingIds: List<String>,
    sourceProviderKey: String?,
): Map<String, CanonicalRecordingReleaseTitleRow> = withContext(Dispatchers.IO) {
    val ids = playlistRecordingIds.distinct()
    if (ids.isEmpty()) return@withContext emptyMap()
    val dao = CanonicalCatalogDatabase.get(context.applicationContext).catalogDao()
    val providers = canonicalAvailablePlaylistSourceKeys(context.applicationContext)
        .filter { sourceProviderKey == null || it == sourceProviderKey }
    val global = ids.chunked(300).flatMap { dao.recordingReleaseTitlesByIds(it) }
    val owned = if (providers.isEmpty()) emptyList()
        else ids.chunked(300).flatMap { dao.providerOwnedRecordingReleaseTitles(it, providers) }
    canonicalPlaylistExactAlbums(ids, global, owned)
}

@Composable
internal fun CanonicalPlaylistAlbumRemovalConfirmation(
    request: CanonicalPlaylistAlbumRemovalRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val count = request.recordingIds.size
    ConfirmationAlert(
        hazeState = null,
        title = "Remover álbum da playlist?",
        description = "Remover $count faixas de \"" + request.releaseTitle +
            "\" desta playlist? O álbum continuará disponível na Biblioteca.",
        confirmLabel = "Remover $count faixas",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
