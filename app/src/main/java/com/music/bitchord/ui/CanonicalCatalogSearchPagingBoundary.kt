package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.data.catalog.metadata.musicBrainzPublicSearchRank
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadLease

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
    fun isAlbum(row: CanonicalCatalogSearchRow) =
        row.kind == CanonicalSearchKind.RELEASE_GROUP ||
            row.kind == CanonicalSearchKind.RELEASE
    val ranked = rows.filter(::isAlbum).sortedBy {
        ranks[it.kind to it.musicBrainzId] ?: 3
    }.iterator()
    return rows.map { if (isAlbum(it)) ranked.next() else it }
}
