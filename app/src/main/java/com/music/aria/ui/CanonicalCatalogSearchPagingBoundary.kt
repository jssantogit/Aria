package com.music.aria.ui

import com.music.aria.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.aria.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.aria.data.catalog.metadata.musicBrainzPublicSearchRank
import com.music.aria.ui.screens.CanonicalCatalogSearchRow
import com.music.aria.ui.screens.CanonicalSearchKind
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderPolicyGate
import com.music.aria.data.providers.ProviderReadLease

/** Native MusicBrainz search-page cursors belong to CATALOG_SEARCH only. */
internal fun canonicalCatalogSearchPagingLease(
    gate: ProviderPolicyGate?,
): ProviderReadLease? = ProviderReadLease.acquire(gate, listOf(
    ProviderCapability.CATALOG_SEARCH to BuiltInProviderKeys.MUSICBRAINZ,
))

/** Groups and individual editions share the same Albums tab. Rank the
 * combined first batch against raw source titles/credits before publishing
 * it, without moving other result kinds or changing any upstream cursor.
 * Do not parse an artist name from a display subtitle (which may also carry
 * a country, a date or an edition label). */
internal fun rankMusicBrainzInitialAlbumRows(
    query: String,
    rows: List<CanonicalCatalogSearchRow>,
    groups: List<MusicBrainzReleaseGroupHit>,
    editions: List<MusicBrainzReleaseHit>,
): List<CanonicalCatalogSearchRow> {
    val ranks = buildMap<Pair<CanonicalSearchKind, String>, Int> {
        groups.forEach { hit ->
            put(CanonicalSearchKind.RELEASE_GROUP to hit.musicBrainzId,
                musicBrainzPublicSearchRank(query, hit.title, hit.artistDisplay))
        }
        editions.forEach { hit ->
            put(CanonicalSearchKind.RELEASE to hit.musicBrainzId,
                musicBrainzPublicSearchRank(query, hit.title, hit.artistDisplay))
        }
    }
    // The same title can identify dozens of unrelated works. Among equally
    // strong title matches, prefer positive catalogue evidence: distinct
    // linked release MBIDs, not invented popularity or a title-based merge.
    // Union group response evidence with official curated editions already
    // fetched for this exact group; never issue another lookup.
    val linkedByGroup = editions.filter { it.status.equals("Official", ignoreCase = true) }
        .mapNotNull { edition -> edition.releaseGroupMbid?.let { it to edition.musicBrainzId } }
        .groupBy({ it.first }, { it.second })
    val groupSupport = groups.associate { group ->
        group.musicBrainzId to (group.searchLinkedReleaseMbids +
            linkedByGroup[group.musicBrainzId].orEmpty()).size
    }
    val editionGroup = editions.associate { it.musicBrainzId to it.releaseGroupMbid }
    fun support(row: CanonicalCatalogSearchRow): Int {
        val rank = ranks[row.kind to row.musicBrainzId] ?: 3
        if (rank > 1) return 0
        val groupId = when (row.kind) {
            CanonicalSearchKind.RELEASE_GROUP -> row.musicBrainzId
            CanonicalSearchKind.RELEASE -> editionGroup[row.musicBrainzId]
            else -> null
        } ?: return 0
        return (groupSupport[groupId] ?: linkedByGroup[groupId].orEmpty().distinct().size)
            .coerceAtMost(20)
    }
    fun isAlbum(row: CanonicalCatalogSearchRow) =
        row.kind == CanonicalSearchKind.RELEASE_GROUP ||
            row.kind == CanonicalSearchKind.RELEASE
    val ranked = rows.filter(::isAlbum).sortedWith(compareBy<CanonicalCatalogSearchRow> {
        ranks[it.kind to it.musicBrainzId] ?: 3
    }.thenByDescending(::support)).iterator()
    return rows.map { if (isAlbum(it)) ranked.next() else it }
}
