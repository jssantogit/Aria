package com.music.bitchord.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.util.Locale

/**
 * A genre's existing 40-item editorial snapshot remains fixed when the user
 * scrolls. Additional source-owned ReleaseGroups belong to a separate append-only
 * rail, never to a recomputed Home hero or an inferred popularity chart.
 */
internal data class CanonicalGenrePageSnapshot(
    val tag: String? = null,
    val nextOffset: Int? = null,
    val rows: List<CanonicalCatalogSearchRow> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** A malformed page is retryable at its *raw* offset, not proof of exhaustion. */
internal fun appendCanonicalGenrePage(
    previous: CanonicalGenrePageSnapshot,
    tag: String,
    offset: Int,
    pageSize: Int,
    page: MusicBrainzCatalogPage<MusicBrainzReleaseGroupHit>,
    todayIsoDate: String,
): CanonicalGenrePageSnapshot? {
    if (previous.tag != tag || previous.nextOffset != offset ||
        page.offset != offset || page.total < offset ||
        (page.consumedCount == 0 && page.total > offset) ||
        page.consumedCount !in 0..pageSize ||
        page.items.size > page.consumedCount ||
        page.nextOffset?.let { it <= offset } == true
    ) return null
    // Each raw page may have more valid items than the 40-item landing sample.
    // Preserve all verified, typed entries before deduplication.
    val additions = if (page.items.isEmpty()) emptyList() else
        canonicalPublicGenreRows(page.items, todayIsoDate, maxItems = page.items.size)
    val known = previous.rows.mapTo(hashSetOf()) {
        it.musicBrainzId.lowercase(Locale.ROOT)
    }
    val newRows = additions.filter {
        known.add(it.musicBrainzId.lowercase(Locale.ROOT))
    }
    return previous.copy(
        nextOffset = page.nextOffset,
        rows = previous.rows + newRows,
        loading = false,
        failed = false,
    )
}

/**
 * The initial editorial shelves keep their order and bounded size. Additional
 * verified MusicBrainz records appear only after them; they never contaminate
 * an Apple or Discogs-selected genre page.
 */
internal fun canonicalGenreContinuationShelf(
    tag: String,
    selectedProviderIsMusicBrainz: Boolean,
    original: List<HomeShelf>,
    paging: CanonicalGenrePageSnapshot,
): HomeShelf? {
    if (!selectedProviderIsMusicBrainz || paging.tag != tag) return null
    val shownIds = original.flatMap { it.items }
        .mapNotNull { it.browseId }.toHashSet()
    val rows = paging.rows.asSequence()
        .map { row ->
            ShelfItem(
                title = row.title,
                subtitle = row.subtitle.orEmpty(),
                thumbnailUrl = row.artworkUri,
                videoId = null,
                browseId = canonicalNativeBrowseId(row),
            )
        }
        .filter { card -> card.browseId != null && shownIds.add(card.browseId) }
        .toList()
    return rows.takeIf { it.isNotEmpty() }?.let {
        HomeShelf("Mais do gênero", it)
    }
}

/** Avoid paging a supplementary MB artist rail while Apple/Discogs owns releases. */
internal fun canonicalGenreShowsMusicBrainzReleases(state: UiState<List<HomeShelf>>): Boolean =
    (state as? UiState.Success)?.data.orEmpty().any { shelf ->
        shelf.title in setOf("Lançamentos", "Álbuns", "Singles e EPs", "Mais para explorar", "Mais do gênero") &&
            shelf.items.any { it.browseId.orEmpty().startsWith("catalog:RELEASE_GROUP:") }
    }

internal fun canonicalGenreNearEnd(
    scrolling: Boolean,
    lastVisible: Int,
    totalItems: Int,
): Boolean = scrolling && totalItems > 0 &&
    lastVisible >= (totalItems - 3).coerceAtLeast(0)

/**
 * A scroll edge causes at most one load until the viewport moves away and
 * approaches the end again. No automatic request storm on empty/failed pages.
 */
@Composable
internal fun CanonicalGenreGranularPagingEffect(
    tag: String?,
    enabled: Boolean,
    snapshot: CanonicalGenrePageSnapshot,
    listState: LazyListState,
    onLoadMore: (String) -> Unit,
) {
    val active by rememberUpdatedState(enabled && snapshot.tag == tag &&
        snapshot.nextOffset != null && !snapshot.loading)
    val callback by rememberUpdatedState(onLoadMore)
    LaunchedEffect(tag, listState) {
        val requestedTag = tag ?: return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            canonicalGenreNearEnd(
                listState.isScrollInProgress,
                layout.visibleItemsInfo.lastOrNull()?.index ?: -1,
                layout.totalItemsCount,
            )
        }.distinctUntilChanged().filter { it }.collect {
            if (active) callback(requestedTag)
        }
    }
}
