package com.music.aria.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.music.aria.data.catalog.CanonicalCatalogDatabase
import com.music.aria.data.catalog.CanonicalRecordingCoverGroupRow
import com.music.aria.data.catalog.metadata.CoverArtArchive
import com.music.aria.data.catalog.metadata.CoverArtSize
import com.music.aria.data.catalog.metadata.RoomMusicBrainzCoverArtStore
import com.music.aria.data.catalog.metadata.isTrustedArchiveImageUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A verified Official Recording->ReleaseGroup link and a verified, fresh v5
 * front are both necessary. The first eligible edition is the DAO's order.
 * No recording-title matching, arbitrary URLs, or playback asset fabrication. */
internal fun mapVerifiedPlaylistRecordingCovers(
    recordingMbids: List<String>,
    links: List<CanonicalRecordingCoverGroupRow>,
    verifiedFrontUrls: Map<String, String>,
): Map<String, String> {
    val requested = recordingMbids.toSet()
    val found = LinkedHashMap<String, String>()
    links.forEach { link ->
        if (link.recordingMbid !in requested || link.recordingMbid in found) return@forEach
        val url = verifiedFrontUrls[link.groupMbid]
            ?.takeIf(::isTrustedArchiveImageUrl) ?: return@forEach
        found[link.recordingMbid] = url
    }
    return found
}

/** Batched cache-only visual enrichment for playlists. Never executes a CAA
 * HTTP lookup or modifies the Recording/MediaAsset identity used for playback. */
@Composable
internal fun rememberVerifiedPlaylistMetadataCovers(
    rows: List<V2DisplayRow>?,
): Map<String, String> {
    val context = LocalContext.current.applicationContext
    val ids = remember(rows) {
        rows.orEmpty().asSequence()
            .filterNot(V2DisplayRow::isAvailablePlaylistMember)
            .map(V2DisplayRow::id)
            .filter(::isVerifiedCatalogRecordingId)
            .distinct().take(500).toList()
    }
    val covers by produceState(
        initialValue = emptyMap<String, String>(),
        key1 = context, key2 = ids,
    ) {
        value = if (ids.isEmpty()) emptyMap() else try {
            withContext(Dispatchers.IO) {
                val dao = CanonicalCatalogDatabase.get(context).catalogDao()
                val links = ids.chunked(300).flatMap {
                    dao.verifiedRecordingCoverGroupMbids(it)
                }
                if (links.isEmpty()) emptyMap() else {
                    val resolver = CoverArtArchive(
                        store = RoomMusicBrainzCoverArtStore(dao),
                    )
                    val verified = resolver.cachedVerifiedGroupCovers(
                        links.map(CanonicalRecordingCoverGroupRow::groupMbid).distinct(),
                        CoverArtSize.THUMBNAIL,
                    )
                    mapVerifiedPlaylistRecordingCovers(ids, links, verified)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
    }
    return covers
}
