package com.music.aria.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.music.aria.data.catalog.CanonicalCatalogDatabase
import com.music.aria.data.catalog.metadata.CoverArtArchive
import com.music.aria.data.catalog.metadata.CoverArtSize
import com.music.aria.data.catalog.metadata.RoomMusicBrainzCoverArtStore
import com.music.aria.ui.loadCanonicalCoversProgressively
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.aria.R
import com.music.aria.data.model.ROW_ART_PX
import com.music.aria.data.model.artworkAt
import com.music.aria.ui.components.CoverPlaceholder
import com.music.aria.ui.components.NativeDetailTrackRowLayout
import com.music.aria.ui.components.PAGE_GUTTER
import com.music.aria.ui.components.RowMoreButton
import com.music.aria.ui.components.NativeCompactOverflowMenu
import com.music.aria.ui.components.NativeCompactMenuItem
import com.music.aria.ui.theme.ArtworkPalette

/** Only a confirmed Release MBID can supply the front for a recording.
 * A title/artist match would risk showing the wrong edition's cover. */
internal fun nativePublicTopCoverReleaseIds(
    rows: List<CanonicalCatalogSearchRow>,
): List<String> = rows.asSequence()
    .filter { it.artworkUri.isNullOrBlank() }
    .mapNotNull { it.coverReleaseMbid }
    .filter(String::isNotBlank)
    .distinct()
    .take(10)
    .toList()

/**
 * Online rank and Library tracks share the native track row geometry. The
 * online adapter never promotes a Recording MBID into a playable asset or
 * invents a three-dot/rating capability without a Library recording.
 */
@Composable
internal fun NativeArtistPublicTopSection(
    artistMbid: String,
    rows: List<CanonicalCatalogSearchRow>,
    palette: ArtworkPalette,
    sampled: Boolean = false,
    title: String? = null,
    onRecordingOpen: ((CanonicalCatalogSearchRow) -> Unit)?,
    onRecordingHold: ((CanonicalCatalogSearchRow) -> Unit)? = null,
) {
    val exact = rows.filter {
        it.kind == CanonicalSearchKind.RECORDING &&
            it.artistScopeMbid?.equals(artistMbid, ignoreCase = true) == true
    }
    val context = LocalContext.current.applicationContext
    val catalogDb = remember(context) { CanonicalCatalogDatabase.get(context) }
    val officialReleaseIds = exact.mapNotNull { row ->
        row.coverReleaseMbid?.trim()?.takeIf(String::isNotBlank)
    }.distinct()
    // The source Recording has already proved an exact Official edition MBID.
    // Render only its cached title/date; never use the artist as a substitute
    // or guess an album by matching the song name or artwork.
    val releaseCaptions by produceState<Map<String, String>>(
        initialValue = emptyMap(),
        key1 = officialReleaseIds,
    ) {
        if (officialReleaseIds.isEmpty()) return@produceState
        catalogDb.invalidationTracker.createFlow(
            "catalog_release", "catalog_external_identity", "musicbrainz_release_enrichment",
        ).collectLatest {
            value = try {
                withContext(Dispatchers.IO) {
                    catalogDb.catalogDao().cachedArtistOfficialReleaseCaptions(officialReleaseIds)
                        .mapNotNull { cached ->
                            canonicalArtistOfficialReleaseCaption(cached)?.let { caption ->
                                cached.releaseMbid.lowercase(Locale.ROOT) to caption
                            }
                        }.toMap()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
    val coverArt = remember(context) {
        CoverArtArchive(
            store = RoomMusicBrainzCoverArtStore(
                CanonicalCatalogDatabase.get(context).catalogDao(),
            ),
        )
    }
    val coverIds = nativePublicTopCoverReleaseIds(exact)
    val recoveredCovers by produceState(
        initialValue = emptyMap<String, String>(), key1 = coverIds,
    ) {
        if (coverIds.isEmpty()) return@produceState
        loadCanonicalCoversProgressively(
            keys = coverIds,
            maxConcurrent = 2,
            fetch = { edition ->
                withContext(Dispatchers.IO) {
                    try {
                        coverArt.findCover(edition, null, CoverArtSize.THUMBNAIL)
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { null }
                }
            },
            publish = { batch -> value = value + batch },
        )
    }
    NativeArtistTracksShelf(
        title = title ?: stringResource(R.string.top_songs),
        tracks = exact,
        palette = palette,
        horizontalPadding = PAGE_GUTTER,
        itemKey = { it.musicBrainzId },
    ) { recording ->
        NativeDetailTrackRowLayout(
            // A public Recording MBID is not a playable RecordingId. Unlike
            // the old metadata-only navigation there is no tap destination.
            onClick = if (onRecordingOpen != null &&
                (recording.hasCatalogedAsset || recording.hasVerifiedLocalAsset))
                ({ onRecordingOpen(recording) }) else null,
            onLongPress = onRecordingHold?.let { hold -> { hold(recording) } },
            onMore = null,
            modifier = Modifier.testTag("artist-top-row-${recording.musicBrainzId}"),
            horizontalInset = 0.dp,
            leading = {
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier.size(52.dp).clip(shape)
                        .background(palette.onBackground.copy(alpha = 0.08f)),
                ) {
                    CoverPlaceholder(Icons.Rounded.MusicNote, Modifier.fillMaxSize())
                    (recording.artworkUri?.takeIf(String::isNotBlank)
                        ?: recoveredCovers[recording.coverReleaseMbid])?.let { art ->
                        AsyncImage(
                            model = art.artworkAt(ROW_ART_PX),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            },
            title = {
                Text(
                    text = recording.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            subtitle = {
                val caption = canonicalArtistPublicTrackReleaseCaption(
                    recording, releaseCaptions,
                )
                if (!caption.isNullOrBlank()) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            rating = {},
            showRatingSlot = false,
            actions = {},

        )
    }
}


/** Use the verified Recording→Official edition in the current network result.
 * Previously its title was thrown away; the Room-only lookup then stayed
 * blank for a newly visited online artist. Cached captions are a fallback. */
internal fun canonicalArtistPublicTrackReleaseCaption(
    recording: CanonicalCatalogSearchRow,
    cached: Map<String, String>,
): String? {
    if (recording.kind != CanonicalSearchKind.RECORDING) return null
    val edition = recording.coverReleaseMbid?.trim()?.takeIf(String::isNotBlank)
        ?: return null
    return canonicalArtistVerifiedEditionCaption(
        recording.officialReleaseTitle, recording.releaseDate,
    ) ?: cached[edition.lowercase(Locale.ROOT)]
}
