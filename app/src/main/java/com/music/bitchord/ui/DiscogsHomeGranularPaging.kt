package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialCandidate
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialCursor
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialQuery
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsGranularRead
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderKey

/** Rotate real Discogs Genre and Style predicates instead of exhausting Pop. */
internal fun discogsHomeGranularQueries(): List<DiscogsEditorialQuery> =
    discogsHomeDiscoveryTags.flatMap(::discogsEditorialQueriesForTag).distinct()

internal data class DiscogsHomePageSnapshot(
    val cursors: List<DiscogsEditorialCursor?> = emptyList(),
    val nextQueryIndex: Int? = null,
    val knownMasterIds: Set<Long> = emptySet(),
    val releases: List<DiscogsEditorialCandidate> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** A provider page ceiling is NOT evidence of complete global coverage. */
    val sourceCeilingHit: Boolean = false,
) {
    val hasMore: Boolean get() = nextQueryIndex != null &&
        cursors.getOrNull(nextQueryIndex) != null
}

internal fun initialDiscogsHomePageSnapshot(
    alreadyVerified: List<DiscogsEditorialCandidate>,
    queryCount: Int,
    landingCursors: Map<Int, DiscogsEditorialCursor> = emptyMap(),
): DiscogsHomePageSnapshot {
    require(queryCount in 0..40)
    return DiscogsHomePageSnapshot(
        cursors = List(queryCount) { landingCursors[it] ?: DiscogsEditorialCursor() },
        nextQueryIndex = if (queryCount > 0) 0 else null,
        knownMasterIds = alreadyVerified.map { it.masterId }.toSet(),
    )
}

/**
 * Every scroll chunk moves to the NEXT native Genre/Style predicate, even
 * when the current source query has many later pages. Each query keeps its own
 * real cursor, so diversity does not trade away eventual source traversal.
 */
internal fun advanceDiscogsHomePage(
    previous: DiscogsHomePageSnapshot,
    queryIndex: Int,
    read: DiscogsGranularRead,
): DiscogsHomePageSnapshot? {
    if (!previous.hasMore || previous.nextQueryIndex != queryIndex ||
        queryIndex !in previous.cursors.indices
    ) return null
    val known = previous.knownMasterIds.toMutableSet()
    val added = mutableListOf<DiscogsEditorialCandidate>()
    val source = when (read) {
        is DiscogsGranularRead.Page -> read.releases
        is DiscogsGranularRead.Interrupted -> read.releases
    }
    source.forEach { row ->
        if (row.masterId > 0L && row.releaseId > 0L &&
            row.title.isNotBlank() && row.artist.isNotBlank() &&
            known.add(row.masterId)
        ) added += row
    }
    return when (read) {
        is DiscogsGranularRead.Interrupted -> {
            val cursors = previous.cursors.toMutableList()
            cursors[queryIndex] = read.retryCursor
            previous.copy(
                cursors = cursors, nextQueryIndex = queryIndex,
                knownMasterIds = known, releases = previous.releases + added,
                loading = false, failed = true,
            )
        }
        is DiscogsGranularRead.Page -> {
            val cursors = previous.cursors.toMutableList()
            cursors[queryIndex] = read.nextCursor
            val next = (1..cursors.size).firstNotNullOfOrNull { step ->
                ((queryIndex + step) % cursors.size)
                    .takeIf { cursors[it] != null }
            }
            previous.copy(
                cursors = cursors, nextQueryIndex = next,
                knownMasterIds = known, releases = previous.releases + added,
                loading = false, failed = false,
                sourceCeilingHit = previous.sourceCeilingHit || read.stoppedByPageLimit,
            )
        }
    }
}

/** Original Home's source-selected and community shelves never move. */
internal fun discogsHomeContinuationShelf(
    selectedProvider: ProviderKey?,
    original: List<HomeShelf>,
    paging: DiscogsHomePageSnapshot,
): HomeShelf? {
    if (selectedProvider != BuiltInProviderKeys.DISCOGS) return null
    val visible = original.flatMap { it.items }
        .mapNotNull { it.browseId }.toMutableSet()
    val cards = paging.releases.asSequence()
        .map { item ->
            ShelfItem(
                title = item.title,
                subtitle = listOfNotNull(
                    com.music.bitchord.ui.screens.discogsArtistDisplayName(item.artist),
                    item.year?.toString(),
                ).joinToString(" · "),
                thumbnailUrl = null, videoId = null, browseId = item.browseId,
            )
        }
        .filter { it.browseId?.let(visible::add) == true }
        .toList()
    return cards.takeIf { it.isNotEmpty() }?.let {
        HomeShelf("Mais do catálogo", it)
    }
}
