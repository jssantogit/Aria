package com.music.aria.ui

import com.music.aria.data.catalog.metadata.ListenBrainzSitewideRecording
import com.music.aria.data.model.HomeShelf
import com.music.aria.data.model.ShelfItem
import com.music.aria.ui.screens.CanonicalCatalogSearchRow
import com.music.aria.ui.screens.CanonicalSearchKind
import java.util.Locale

/**
 * People who have an exact Artist MBID credited on the ListenBrainz sitewide
 * WEEK chart. This is not an artist growth metric, or an official artist rank.
 * The only ordering is the first appearance in the measured recording chart.
 */
internal fun canonicalWeeklyChartArtistsShelf(
    rows: List<CanonicalCatalogSearchRow>,
    rankings: List<ListenBrainzSitewideRecording>,
    heading: String = "Artistas nas paradas",
    limit: Int = 16,
): HomeShelf? {
    if (limit !in 1..30 || heading.isBlank()) return null
    val firstAppearance = linkedMapOf<String, Int>()
    rankings.asSequence().filter { it.listenCount > 0 }
        .sortedWith(compareByDescending<ListenBrainzSitewideRecording> { it.listenCount }
            .thenBy { it.recordingMbid })
        .distinctBy { it.recordingMbid }
        .forEachIndexed { index, row ->
            row.artistMbids.forEach { artist ->
                firstAppearance.putIfAbsent(artist.lowercase(Locale.ROOT), index)
            }
        }
    if (firstAppearance.isEmpty()) return null
    val cards = rows.asSequence()
        .filter { it.kind == CanonicalSearchKind.ARTIST && it.title.isNotBlank() }
        .filter { it.musicBrainzId.lowercase(Locale.ROOT) in firstAppearance }
        .distinctBy { it.musicBrainzId.lowercase(Locale.ROOT) }
        .sortedWith(compareBy<CanonicalCatalogSearchRow> {
            firstAppearance[it.musicBrainzId.lowercase(Locale.ROOT)] ?: Int.MAX_VALUE
        }.thenBy { it.musicBrainzId })
        .take(limit)
        .map { row ->
            ShelfItem(
                row.title, "Na parada semanal · ListenBrainz", row.artworkUri, null,
                canonicalNativeBrowseId(row),
            )
        }.toList()
    return cards.takeIf { it.isNotEmpty() }?.let { HomeShelf(heading, it) }
}
