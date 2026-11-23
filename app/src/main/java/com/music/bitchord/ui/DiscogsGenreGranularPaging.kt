package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialCandidate
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialCursor
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsGranularRead
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState

/** Discogs genre reads never change existing native shelves or a competing source. */
internal data class DiscogsGenrePageSnapshot(
    val tag: String? = null,
    val queryIndex: Int = 0,
    val cursor: DiscogsEditorialCursor? = null,
    val knownMasterIds: Set<Long> = emptySet(),
    val releases: List<DiscogsEditorialCandidate> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val stoppedByPageLimit: Boolean = false,
) {
    val hasMore: Boolean get() = tag != null && cursor != null
}

/**
 * A retryable failed Master/Release keeps the exact candidate position, even
 * when valid earlier releases from that same scroll gesture are published.
 * Each typed Discogs Style is separately queried and corroborated.
 */
internal fun advanceDiscogsGenrePage(
    prior: DiscogsGenrePageSnapshot,
    tag: String,
    queryCount: Int,
    read: DiscogsGranularRead,
): DiscogsGenrePageSnapshot? {
    if (prior.tag != tag || prior.cursor == null ||
        prior.queryIndex !in 0 until queryCount
    ) return null
    val added = mutableListOf<DiscogsEditorialCandidate>()
    val known = prior.knownMasterIds.toMutableSet()
    val newReleases = when (read) {
        is DiscogsGranularRead.Page -> read.releases
        is DiscogsGranularRead.Interrupted -> read.releases
    }
    newReleases.forEach { item ->
        if (item.masterId > 0L && item.releaseId > 0L &&
            item.title.isNotBlank() && item.artist.isNotBlank() &&
            known.add(item.masterId)
        ) added += item
    }
    return when (read) {
        is DiscogsGranularRead.Interrupted -> prior.copy(
            knownMasterIds = known,
            releases = prior.releases + added,
            cursor = read.retryCursor,
            loading = false,
            failed = true,
        )
        is DiscogsGranularRead.Page -> {
            val completeQuery = read.nextCursor == null
            val nextQuery = if (completeQuery) prior.queryIndex + 1
                else prior.queryIndex
            val nextCursor = if (!completeQuery) read.nextCursor
                else if (nextQuery < queryCount) DiscogsEditorialCursor()
                else null
            prior.copy(
                queryIndex = nextQuery.coerceAtMost(queryCount),
                cursor = nextCursor,
                knownMasterIds = known,
                releases = prior.releases + added,
                loading = false,
                failed = false,
                stoppedByPageLimit = prior.stoppedByPageLimit ||
                    read.stoppedByPageLimit,
            )
        }
    }
}

/**
 * A chunk containing only already-shown or rejected Masters is not a useful
 * viewport continuation. Allow a small, strictly bounded number of further
 * source-index chunks within that same gesture; do not trigger an unbounded
 * feed scanner or claim empty candidates mean source exhaustion.
 */
internal fun discogsShouldContinueEmptyCandidateScan(
    initial: DiscogsGenrePageSnapshot,
    current: DiscogsGenrePageSnapshot,
    batchesCompleted: Int,
    maxBatches: Int = 3,
): Boolean = batchesCompleted in 1 until maxBatches &&
    current.tag == initial.tag && current.hasMore && !current.failed &&
    current.releases.size == initial.releases.size

/**
 * No resorting, synthesized art, provider identity conversion or ranking.
 * A late Discogs record is rendered by the same HomeScreen/ShelfCard widgets.
 */
internal fun discogsGenreContinuationShelf(
    tag: String,
    selectedProviderIsDiscogs: Boolean,
    original: List<HomeShelf>,
    paging: DiscogsGenrePageSnapshot,
): HomeShelf? {
    if (!selectedProviderIsDiscogs || paging.tag != tag) return null
    val visibleIds = original.flatMap { it.items }
        .mapNotNull { it.browseId }.toMutableSet()
    val rows = paging.releases.asSequence()
        .map { row ->
            ShelfItem(
                title = row.title,
                subtitle = listOfNotNull(
                    discogsArtistNameForGranularShelf(row.artist),
                    row.year?.toString(),
                ).joinToString(" · "),
                thumbnailUrl = null,
                videoId = null,
                browseId = row.browseId,
            )
        }
        .filter { row ->
            row.browseId?.let(visibleIds::add) == true
        }
        .toList()
    return rows.takeIf { it.isNotEmpty() }?.let {
        HomeShelf("Mais do gênero", it)
    }
}

/** Preserve Discogs' existing verified display sanitization, not source IDs. */
private fun discogsArtistNameForGranularShelf(source: String): String =
    com.music.bitchord.ui.screens.discogsArtistDisplayName(source)

/** Do not page an artist supplement or an Apple/MusicBrainz release shelf. */
internal fun canonicalGenreShowsDiscogsReleases(state: UiState<List<HomeShelf>>): Boolean =
    (state as? UiState.Success)?.data.orEmpty().any { shelf ->
        shelf.title in setOf(
            "Para descobrir", "Álbuns", "Singles e EPs",
            "Mais para explorar", "Mais do gênero",
        ) && shelf.items.any { it.browseId.orEmpty().startsWith("discogs:release:") }
    }
