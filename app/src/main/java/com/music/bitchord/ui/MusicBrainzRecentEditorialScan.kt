package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearch
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow

/**
 * Shared, bounded source-cursor scanner for MusicBrainz Home discovery and its
 * live CI observation. Counts *raw* source positions even when the editorial
 * filter rejects a page, while keeping only distinct source-owned identities.
 * No ranking, cover or artist popularity is inferred from search order.
 */
internal data class MusicBrainzRecentEditorialScan(
    val rows: List<CanonicalCatalogSearchRow>,
    val attempts: Int,
    val sourcePositions: Int,
    val parsedRows: Int,
    val cursorRemaining: Boolean,
    val sourceFailure: Boolean,
)

internal suspend fun scanMusicBrainzRecentEditorial(
    source: MusicBrainzCatalogSearch,
    fromIsoDate: String,
    throughIsoDate: String,
    maxPages: Int = 3,
    pageSize: Int = 100,
    onSourcePage: (List<MusicBrainzReleaseGroupHit>) -> Unit = {},
): MusicBrainzRecentEditorialScan {
    require(maxPages in 1..5 && pageSize in 1..100)
    val admitted = LinkedHashMap<String, CanonicalCatalogSearchRow>()
    var offset = 0
    var attempts = 0
    var sourcePositions = 0
    var parsedRows = 0
    var cursorRemaining = false
    var sourceFailure = false
    for (batch in 0 until maxPages) {
        attempts++
        val result = source.searchRecentReleaseGroups(
            fromIsoDate, throughIsoDate, limit = pageSize, offset = offset,
        )
        val page = (result as?
            MusicBrainzCatalogSearchResult.Success<MusicBrainzReleaseGroupHit>)
            ?.page
        if (page == null) {
            sourceFailure = true
            break
        }
        if (page.offset != offset) {
            sourceFailure = true
            break
        }
        sourcePositions += page.consumedCount
        parsedRows += page.items.size
        onSourcePage(page.items)
        canonicalPublicReleaseRows(page.items, fromIsoDate, throughIsoDate)
            .forEach { row -> admitted.putIfAbsent(row.musicBrainzId, row) }

        val next = page.nextOffset
        cursorRemaining = next != null && next > offset
        if (!canonicalPublicNeedsMoreEditorialRows(admitted.values.toList()) ||
            !cursorRemaining
        ) break
        offset = requireNotNull(next)
    }
    return MusicBrainzRecentEditorialScan(
        admitted.values.toList(), attempts, sourcePositions,
        parsedRows, cursorRemaining, sourceFailure,
    )
}
