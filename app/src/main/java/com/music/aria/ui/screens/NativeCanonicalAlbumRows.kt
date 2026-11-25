package com.music.aria.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.aria.data.catalog.CanonicalBrowseTrack
import com.music.aria.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.aria.ui.components.NUMBERED_ROW_DIVIDER_INSET
import com.music.aria.ui.theme.ArtworkPalette

/**
 * A release track's number belongs to its verified running order, not its
 * position after user search, sorting or source filtering.
 */
internal fun nativeCanonicalAlbumRowNumber(
    recordingId: String,
    visibleIndex: Int,
    originalTrackNumbers: Map<String, Int>,
): Int = originalTrackNumbers[recordingId] ?: (visibleIndex + 1)

/**
 * Search only the already-verified album membership and preserve its sorted
 * visible order. Stable source numbering lives in the original release list.
 */
internal fun nativeCanonicalAlbumVisibleTracks(
    tracks: List<CanonicalBrowseTrack>,
    query: String,
): List<CanonicalBrowseTrack> = if (query.isBlank()) tracks else tracks.filter {
    it.title.contains(query, ignoreCase = true) ||
        it.artistDisplay.contains(query, ignoreCase = true)
}

/**
 * Shared native album track rows between root Library albums and albums opened
 * inside an artist. The visual and actions stay in CanonicalTrackRow; this
 * compositor owns only the numbering, divider, stable RecordingId key and
 * callback slots. It does not use Song, legacy browseId or YouTube videoId.
 */
/**
 * Header credits must come from a release artist or a unanimous set of its
 * verified RecordingId members. An empty release credit must not erase the
 * artist while repeating it beneath every numbered song. Mixed/unknown artist
 * credits are left to each track row rather than guessing a lead artist.
 *
 * This resolves display text only. It never creates an ArtistId or a route.
 */
internal fun nativeAlbumDisplayCredit(
    releaseArtist: String?,
    albumTracks: List<CanonicalBrowseTrack>,
): String {
    releaseArtist?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
    val credits = albumTracks.map { it.artistDisplay.trim() }
    val first = credits.firstOrNull()?.takeIf(String::isNotEmpty) ?: return ""
    return first.takeIf { lead ->
        credits.all { it.isNotEmpty() && it.equals(lead, ignoreCase = true) }
    }.orEmpty()
}

/** Hide a repeated release credit but preserve real guest artists. */
internal fun nativeAlbumTrackSecondaryLine(trackArtist: String, albumArtist: String): String? =
    trackArtist.trim().takeIf { it.isNotEmpty() &&
        !it.equals(albumArtist.trim(), ignoreCase = true) }

internal fun LazyListScope.nativeCanonicalAlbumTrackRows(
    tracks: List<CanonicalBrowseTrack>,
    artworkLoader: OpenSubsonicArtworkLoader,
    palette: ArtworkPalette,
    trackNumbers: Map<String, Int>,
    albumCredit: String,
    onTrackPlay: (CanonicalBrowseTrack) -> Unit,
    onTrackEditRating: (CanonicalBrowseTrack) -> Unit,
    onTrackOptions: (CanonicalBrowseTrack) -> Unit,
) {
    itemsIndexed(
        items = tracks,
        key = { _, track -> track.recordingId },
    ) { index, track ->
        CanonicalTrackRow(
            track = track,
            artworkLoader = artworkLoader,
            onClick = { onTrackPlay(track) },
            onEditRating = { onTrackEditRating(track) },
            // Detail pages use the lifted long-press menu; ⋯ rows are retired.
            onOptions = { onTrackOptions(track) },
            onLongPress = { onTrackOptions(track) },
            showInlineOptions = false,
            albumTrackNumber = nativeCanonicalAlbumRowNumber(
                track.recordingId, index, trackNumbers,
            ),
            albumCredit = albumCredit,
            palette = palette,
        )
        if (index < tracks.lastIndex) {
            HorizontalDivider(
                modifier = Modifier.padding(start = NUMBERED_ROW_DIVIDER_INSET),
                thickness = 0.5.dp,
                color = palette.divider,
            )
        }
    }
}
