package com.music.bitchord.ui.screens

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
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.CoverArtArchive
import com.music.bitchord.data.catalog.metadata.CoverArtSize
import com.music.bitchord.data.catalog.metadata.RoomMusicBrainzCoverArtStore
import com.music.bitchord.ui.loadCanonicalCoversProgressively
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.NativeDetailTrackRowLayout
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.components.NativeCompactOverflowMenu
import com.music.bitchord.ui.components.NativeCompactMenuItem
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.data.catalog.metadata.safeAppleArtwork
import com.music.bitchord.data.catalog.metadata.appleApprovedBadgeForStoreUrl

/**
 * Artist-top artwork must be certified by the exact Apple edition resolver,
 * not discovered through CAA, ListenBrainz image hints or fuzzy titles.
 */
internal fun nativePublicTopVerifiedAppleArtwork(
    row: CanonicalCatalogSearchRow,
): String? {
    val approved = appleApprovedBadgeForStoreUrl(row.appleStoreUrl)
    return row.artworkUri?.takeIf {
        safeAppleArtwork(it) != null &&
            approved != null && approved == row.appleStoreBadgeUrl
    }
}

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
        NativeArtistInformationalTrackRow(
            title = recording.title,
            subtitle = canonicalArtistPublicTrackReleaseCaption(recording, releaseCaptions),
            palette = palette,
            onClick = if (onRecordingOpen != null &&
                (recording.hasCatalogedAsset || recording.hasVerifiedLocalAsset))
                ({ onRecordingOpen(recording) }) else null,
            onLongPress = onRecordingHold?.let { hold -> { hold(recording) } },
            modifier = Modifier.testTag("artist-top-row-${recording.musicBrainzId}"),
            leading = {
                val shape = RoundedCornerShape(7.dp)
                Box(Modifier.size(NATIVE_ARTIST_TRACK_COVER_SIZE).clip(shape)
                    .background(palette.onBackground.copy(alpha = 0.08f))) {
                    CoverPlaceholder(Icons.Rounded.MusicNote, Modifier.fillMaxSize())
                    nativePublicTopVerifiedAppleArtwork(recording)?.let { art ->
                        AsyncImage(
                            model = art.artworkAt(ROW_ART_PX),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            },
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
