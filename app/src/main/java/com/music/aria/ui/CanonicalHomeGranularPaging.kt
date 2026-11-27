package com.music.aria.ui

import com.music.aria.data.model.HomeShelf
import com.music.aria.data.model.ShelfItem
import com.music.aria.ui.screens.CanonicalCatalogSearchRow
import com.music.aria.ui.screens.CanonicalSearchKind
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderKey
import java.time.LocalDate
import java.util.Locale

/**
 * Three fixed, non-overlapping-or-explicitly-deduplicated source windows.
 * Cursor values represent RAW MusicBrainz positions, not the number of tiles.
 * null = this window is exhausted; zero = it has not yet been queried.
 */
internal data class CanonicalHomePageSnapshot(
    val todayIsoDate: String? = null,
    val offsets: List<Int?> = listOf(null, null, null),
    val rows: List<CanonicalCatalogSearchRow> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    val nextWindow: Int? get() = offsets.indexOfFirst { it != null }
        .takeIf { it >= 0 }
    val hasMore: Boolean get() = todayIsoDate != null && nextWindow != null
}

internal fun canonicalHomeWindowDates(
    todayIsoDate: String, window: Int,
): Pair<String, String>? {
    val date = runCatching { LocalDate.parse(todayIsoDate) }.getOrNull() ?: return null
    return when (window) {
        0 -> date.minusDays(30).toString() to date.toString()
        1 -> date.minusDays(90).toString() to date.toString()
        2 -> date.minusDays(365).toString() to date.minusDays(91).toString()
        else -> null
    }
}

/** The landing sampler has already queried only SOME windows; leave the others open. */
internal fun initialCanonicalHomePageSnapshot(
    date: String,
    narrow: MusicBrainzRecentEditorialScan,
    wide: MusicBrainzRecentEditorialScan?,
    historic: MusicBrainzRecentEditorialScan?,
): CanonicalHomePageSnapshot {
    require(canonicalHomeWindowDates(date, 0) != null)
    return CanonicalHomePageSnapshot(
        todayIsoDate = date,
        offsets = listOf(narrow.nextOffset, wide?.nextOffset ?: if (wide == null) 0 else null,
            historic?.nextOffset ?: if (historic == null) 0 else null),
    )
}

/** A failed page preserves the same offset; it does not claim the catalog ends. */
internal fun advanceCanonicalHomePage(
    previous: CanonicalHomePageSnapshot,
    requestedWindow: Int,
    requestedOffset: Int,
    scanned: MusicBrainzRecentEditorialScan,
): CanonicalHomePageSnapshot? {
    if (!previous.hasMore || previous.nextWindow != requestedWindow ||
        previous.offsets.getOrNull(requestedWindow) != requestedOffset ||
        scanned.nextOffset?.let { it < requestedOffset } == true
    ) return null
    if (scanned.sourceFailure) return previous.copy(loading = false, failed = true)
    val nextOffsets = previous.offsets.toMutableList()
    nextOffsets[requestedWindow] = scanned.nextOffset
    val known = previous.rows.mapTo(hashSetOf()) {
        it.musicBrainzId.lowercase(Locale.ROOT)
    }
    val additions = scanned.rows.filter { row ->
        row.kind == CanonicalSearchKind.RELEASE_GROUP &&
            canonicalEditorialReleaseType(row) != null &&
            canonicalCatalogReleaseDateNotFuture(
                row.releaseDate, requireNotNull(previous.todayIsoDate),
            ) &&
            known.add(row.musicBrainzId.lowercase(Locale.ROOT))
    }
    return previous.copy(
        offsets = nextOffsets, rows = previous.rows + additions,
        loading = false, failed = false,
    )
}

/**
 * Preserve all original source-owned Home shelves and their LazyColumn keys.
 * Never treat more catalogue results as recency, popularity, playback or art.
 */
internal fun canonicalHomeContinuationShelf(
    selectionOwner: ProviderKey?,
    originalEditorialShelves: List<HomeShelf>,
    paging: CanonicalHomePageSnapshot,
): HomeShelf? {
    if (selectionOwner != BuiltInProviderKeys.MUSICBRAINZ ||
        paging.todayIsoDate == null
    ) return null
    val visible = originalEditorialShelves.flatMap { it.items }
        .mapNotNull { it.browseId }.toMutableSet()
    val extra = paging.rows.asSequence()
        .map { row ->
            ShelfItem(
                title = row.title, subtitle = row.subtitle.orEmpty(),
                thumbnailUrl = row.artworkUri, videoId = null,
                browseId = canonicalNativeBrowseId(row),
            )
        }
        .filter { item -> item.browseId?.let(visible::add) == true }
        .toList()
    return extra.takeIf { it.isNotEmpty() }?.let {
        HomeShelf("Mais do catálogo", it)
    }
}
