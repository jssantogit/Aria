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
    /** Exact source-owned cursor to resume, including the failed offset on retry. */
    val nextOffset: Int?,
)

internal suspend fun scanMusicBrainzRecentEditorial(
    source: MusicBrainzCatalogSearch,
    fromIsoDate: String,
    throughIsoDate: String,
    maxPages: Int = 3,
    pageSize: Int = 100,
    /** Supports one-page scroll loads without treating filtered item counts as offsets. */
    startOffset: Int = 0,
    onSourcePage: (List<MusicBrainzReleaseGroupHit>) -> Unit = {},
    /** Extra unranked Home rail only; never the recent-release landing. */
    includeCoarseDatesForUnrankedHome: Boolean = false,
): MusicBrainzRecentEditorialScan {
    require(maxPages in 1..5 && pageSize in 1..100 && startOffset >= 0)
    val admitted = LinkedHashMap<String, CanonicalCatalogSearchRow>()
    var offset = startOffset
    var resumeOffset: Int? = null
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
        if (page == null || page.offset != offset ||
            page.consumedCount > pageSize ||
            (page.consumedCount == 0 && page.total > offset)
        ) {
            // An interrupted/invalid page is NOT proof of a completed feed.
            // Retry exactly the last requested raw offset, never items.size.
            sourceFailure = true
            resumeOffset = offset
            break
        }
        sourcePositions += page.consumedCount
        parsedRows += page.items.size
        onSourcePage(page.items)
        val selected = if (includeCoarseDatesForUnrankedHome) {
            // The original Home continues to demand day-precise dates.
            // Only an explicitly unranked supplemental catalogue rail may
            // show source-owned year/month/unknown dates without inventing a
            // day, chart rank or a source relationship.
            if (page.items.isEmpty()) emptyList()
            else canonicalPublicGenreRows(
                page.items, throughIsoDate, maxItems = page.items.size,
            )
        } else canonicalPublicReleaseRows(page.items, fromIsoDate, throughIsoDate)
        selected.forEach { row -> admitted.putIfAbsent(row.musicBrainzId, row) }

        val next = page.nextOffset
        cursorRemaining = next != null && next > offset
        resumeOffset = next?.takeIf { it > offset }
        if (!canonicalPublicNeedsMoreEditorialRows(admitted.values.toList()) ||
            !cursorRemaining
        ) break
        offset = requireNotNull(next)
    }
    return MusicBrainzRecentEditorialScan(
        admitted.values.toList(), attempts, sourcePositions,
        parsedRows, cursorRemaining, sourceFailure, resumeOffset,
    )
}

/** Conservative positive genre evidence for the Home parity audit. Only
 * displayed, source-owned ReleaseGroup IDs count. A single cross-tagged
 * release cannot satisfy several diversity slots. */
internal fun musicBrainzHomeVerifiedGenreCount(
    sourceTagsByMbid: Map<String, Set<String>>,
    displayedBrowseIds: Set<String>,
): Int {
    val aliases = musicBrainzHomeGenreAliases.values.toList()
    val visible = sourceTagsByMbid.filterKeys {
        "catalog:RELEASE_GROUP:$it" in displayedBrowseIds
    }
    // Maximum bipartite matching: each genre needs a distinct release.
    val matched = mutableMapOf<String, Int>()
    fun assign(genre: Int, visited: MutableSet<String>): Boolean {
        for ((id, tags) in visible) {
            if (tags.none { it in aliases[genre] } || !visited.add(id)) continue
            val previous = matched[id]
            if (previous == null || assign(previous, visited)) {
                matched[id] = genre
                return true
            }
        }
        return false
    }
    return aliases.indices.count { assign(it, mutableSetOf()) }
}


/** Same conservative taxonomy in the landing sampler and the live audit. */
private val musicBrainzHomeGenreAliases = linkedMapOf(
    "pop" to setOf("pop"), "rock" to setOf("rock"),
    "hip-hop" to setOf("hip hop", "hip-hop"),
    "electronic" to setOf("electronic", "electronica"), "jazz" to setOf("jazz"),
    "k-pop" to setOf("k-pop", "kpop"), "metal" to setOf("metal", "heavy metal"),
    "rnb" to setOf("r&b", "rhythm and blues", "rnb"),
    "folk" to setOf("folk"), "classical" to setOf("classical"),
)

/** Match distinct releases to distinct positively evidenced genres. The
 * augmenting path avoids wasting a multi-tagged release on a genre which
 * has another candidate. Source order is the deterministic tie-breaker. */
internal fun musicBrainzHomeGenreAnchors(
    rows: List<CanonicalCatalogSearchRow>,
): List<CanonicalCatalogSearchRow> {
    val candidates = rows.distinctBy { it.musicBrainzId }
    val aliases = musicBrainzHomeGenreAliases.values.toList()
    val matched = mutableMapOf<String, Int>()
    fun assign(genre: Int, visited: MutableSet<String>): Boolean {
        for (row in candidates) {
            if (row.editorialGenreTags.none { it in aliases[genre] } ||
                !visited.add(row.musicBrainzId)) continue
            val previous = matched[row.musicBrainzId]
            if (previous == null || assign(previous, visited)) {
                matched[row.musicBrainzId] = genre
                return true
            }
        }
        return false
    }
    aliases.indices.forEach { assign(it, mutableSetOf()) }
    return candidates.filter { it.musicBrainzId in matched }
}

internal data class MusicBrainzHomeGenreSupplement(
    val rows: List<CanonicalCatalogSearchRow>,
    val attempts: Int,
    val sourcePositions: Int,
    val parsedRows: Int,
    val sourceFailure: Boolean,
)

/** The worldwide date index often has no positive tags. A bounded, independent
 * historical tag query can supply honest discovery, but never manufacture
 * genre membership from the query alone or count a repeated identity twice.
 * It leaves every chronological pagination cursor untouched. */
internal suspend fun supplementMusicBrainzHomeGenres(
    source: MusicBrainzCatalogSearch,
    existing: List<CanonicalCatalogSearchRow>,
    todayIsoDate: String,
    onSourcePage: (List<MusicBrainzReleaseGroupHit>) -> Unit = {},
): MusicBrainzHomeGenreSupplement {
    val known = existing.associateByTo(linkedMapOf()) { it.musicBrainzId }
    var attempts = 0
    var positions = 0
    var parsed = 0
    var failed = false
    for ((tag, aliases) in musicBrainzHomeGenreAliases) {
        if (musicBrainzHomeGenreAnchors(known.values.toList()).size >= 5 ||
            attempts >= 6 || existing.isEmpty()) break
        if (known.values.any { row -> row.editorialGenreTags.any { it in aliases } })
            continue
        attempts++
        val page = (source.searchReleaseGroupsForTag(tag, limit = 20) as?
            MusicBrainzCatalogSearchResult.Success<MusicBrainzReleaseGroupHit>)?.page
        if (page == null || page.offset != 0 || page.consumedCount > 20 ||
            (page.consumedCount == 0 && page.total > 0)) {
            failed = true
            break
        }
        positions += page.consumedCount
        parsed += page.items.size
        onSourcePage(page.items)
        if (page.items.isEmpty()) continue
        val eligible = canonicalPublicGenreRows(page.items, todayIsoDate, maxItems = 20)
            .filter { row -> row.editorialGenreTags.any { it in aliases } }
        // Keep a small number of distinct choices for the existing discovery
        // rail; the rest of this tag index is not the worldwide scroll cursor.
        eligible.filterNot { it.musicBrainzId in known }.take(2).forEach {
            known[it.musicBrainzId] = it
        }
    }
    return MusicBrainzHomeGenreSupplement(
        known.values.toList(), attempts, positions, parsed, failed,
    )
}
