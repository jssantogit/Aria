package com.music.aria.ui.screens

import com.music.aria.data.settings.SongSort
import java.util.Locale
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.aria.ui.components.NativeDetailTrackNumber
import com.music.aria.ui.theme.ArtworkPalette

/** Online release sort/filter preserves official track numbers and duplicate titles. */
internal fun canonicalMetadataAlbumVisibleIndices(
    rows: List<Pair<String, String?>>,
    query: String,
    sort: SongSort,
): List<Int> {
    val term = query.trim()
    val kept = rows.indices.filter { i ->
        term.isEmpty() || rows[i].first.contains(term, ignoreCase = true) ||
            rows[i].second?.contains(term, ignoreCase = true) == true
    }
    return when (sort) {
        SongSort.TITLE_ASC -> kept.sortedWith(
            compareBy<Int> { rows[it].first.lowercase(Locale.ROOT) }.thenBy { it },
        )
        SongSort.TITLE_DESC -> kept.sortedWith(
            compareByDescending<Int> { rows[it].first.lowercase(Locale.ROOT) }.thenBy { it },
        )
        // Exact date-added information is not available for metadata-only recordings.
        else -> kept
    }
}

/** The source track position survives user search and sorting, including multi-disc albums. */
internal fun nativeMetadataAlbumTrackNumber(
    track: NativeMetadataTrack,
    originalIndex: Int,
): Int = track.trackNumber?.takeIf { it > 0 } ?: originalIndex + 1

/** A non-numeric provider position must not be replaced by an invented disc number. */
internal fun nativeMetadataAlbumTrackPositionLabel(
    track: NativeMetadataTrack, originalIndex: Int,
): String = track.sourcePosition?.takeIf(String::isNotBlank)
    ?: nativeMetadataAlbumTrackNumber(track, originalIndex).toString()

@Composable
internal fun NativeMetadataAlbumTrackPosition(
    track: NativeMetadataTrack, originalIndex: Int, palette: ArtworkPalette,
) {
    val label = nativeMetadataAlbumTrackPositionLabel(track, originalIndex)
    val number = label.toIntOrNull()
    if (number != null && label == number.toString()) {
        NativeDetailTrackNumber(number = number, color = palette.onBackgroundVariant)
    } else Text(
        label, modifier = Modifier.width(44.dp),
        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelMedium, color = palette.onBackgroundVariant,
    )
}

private fun nativeMetadataDurationLabel(durationMs: Long?): String? {
    val seconds = durationMs?.takeIf { it >= 0L }?.div(1000L) ?: return null
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** Display only verified source credit, movement, duration and disc information. */
internal fun nativeMetadataAlbumTrackSubtitle(
    track: NativeMetadataTrack,
    albumCredit: String,
): String? = listOfNotNull(
    nativeAlbumTrackSecondaryLine(track.credit.orEmpty(), albumCredit),
    track.discNumber?.takeIf { it > 1 }?.let { "Disco $it" },
    track.workTitle?.takeIf(String::isNotBlank),
    nativeMetadataDurationLabel(track.durationMs),
).joinToString(" · ").takeIf(String::isNotBlank)
