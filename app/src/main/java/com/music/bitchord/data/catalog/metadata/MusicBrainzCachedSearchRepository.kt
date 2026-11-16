package com.music.bitchord.data.catalog.metadata

import androidx.room.withTransaction
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.providers.CatalogEntryProvenance
import com.music.bitchord.data.providers.CatalogProvenanceFilter
import com.music.bitchord.data.providers.ProvenanceScopedItem
import com.music.bitchord.data.providers.ProviderCachedReadAccess
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReactiveCache
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.data.providers.ProviderSessionSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Bounded per-kind reads, usable by offline Home, typeahead and recovery. */
internal data class MusicBrainzCachedSearchLimits(
    val artists: Int = 12,
    val groups: Int = 24,
    val releases: Int = 24,
    val recordings: Int = 24,
) {
    init {
        require(listOf(artists, groups, releases, recordings).all { it in 0..300 }) {
            "Cached search limits must be between 0 and 300 per kind"
        }
    }
}

/**
 * A single, source-scoped Room snapshot. The presence of an indexed Recording
 * does not imply a playable file; local/authorized MediaAssets are queried
 * separately and their MBIDs remain evidence, not a playback instruction.
 */
internal data class MusicBrainzCachedSearchSnapshot(
    val artists: List<MusicBrainzArtistHit> = emptyList(),
    val groups: List<MusicBrainzReleaseGroupHit> = emptyList(),
    val releases: List<MusicBrainzReleaseHit> = emptyList(),
    val recordings: List<MusicBrainzRecordingHit> = emptyList(),
    val recordingMbidsWithAssets: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = artists.isEmpty() && groups.isEmpty() &&
            releases.isEmpty() && recordings.isEmpty()
}

/**
 * Provenance-aware Room search repository.
 *
 * - Editorial-only MusicBrainz rows are gated by [ProviderCapability.CATALOG_SEARCH]
 *   and suppressed when MusicBrainz search is disabled or revoked mid-read.
 * - Recordings backed by personally owned [MediaAssetEntity] rows
 *   ([CatalogEntryProvenance.LocalOrPersonalAsset]) remain visible through
 *   [CatalogProvenanceFilter] even when MusicBrainz editorial search is turned off,
 *   never erasing or hiding user-owned media or canonical identities.
 */
internal class MusicBrainzCachedSearchRepository(
    private val database: CanonicalCatalogDatabase,
    private val cacheRead: ProviderCachedReadAccess = ProviderCachedReadAccess(),
    private val gateProvider: () -> ProviderPolicyGate? = { ProviderRuntime.gateForCacheReadOrNull() },
) {
    private val musicBrainz = ProviderKey("musicbrainz")

    fun observe(
        query: String,
        limits: MusicBrainzCachedSearchLimits = MusicBrainzCachedSearchLimits(),
        providerState: StateFlow<ProviderSessionSnapshot>,
    ): Flow<MusicBrainzCachedSearchSnapshot> =
        ProviderReactiveCache(providerState).observe(
            ProviderCapability.CATALOG_SEARCH,
            musicBrainz,
            MusicBrainzCachedSearchSnapshot(),
        ) { search(query, limits) }

    suspend fun search(
        query: String,
        limits: MusicBrainzCachedSearchLimits = MusicBrainzCachedSearchLimits(),
    ): MusicBrainzCachedSearchSnapshot {
        val normalized = query.trim()
        val gate = gateProvider()
        val editorialPermitted = gate?.open(ProviderCapability.CATALOG_SEARCH, musicBrainz) != null

        // When MusicBrainz editorial search is disabled, skip editorial-only artist/group/release
        // queries completely, while still preserving recordings backed by personal MediaAssets.
        if (!editorialPermitted) {
            if (limits.recordings == 0) return MusicBrainzCachedSearchSnapshot()
            return database.withTransaction {
                val dao = database.catalogDao()
                val candidateRecordings = dao.searchKnownMusicBrainzRecordings(normalized, limits.recordings)
                if (candidateRecordings.isEmpty()) return@withTransaction MusicBrainzCachedSearchSnapshot()
                val withAssets = dao.musicBrainzRecordingMbidsWithAssets(
                    candidateRecordings.map { it.musicBrainzId },
                ).toSet()
                val scopedRecordings = candidateRecordings.map { hit ->
                    val provenances = buildSet {
                        add(CatalogEntryProvenance.ProviderEditorial(musicBrainz, ProviderCapability.CATALOG_SEARCH))
                        if (hit.musicBrainzId in withAssets) {
                            add(CatalogEntryProvenance.LocalOrPersonalAsset)
                        }
                    }
                    ProvenanceScopedItem(
                        value = hit,
                        mbid = hit.musicBrainzId,
                        provenances = provenances,
                    )
                }
                val visibleRecordings = CatalogProvenanceFilter.filterVisible(scopedRecordings, gate)
                val visibleAssetMbids = visibleRecordings.mapTo(LinkedHashSet()) { it.musicBrainzId }
                    .intersect(withAssets)
                MusicBrainzCachedSearchSnapshot(
                    recordings = visibleRecordings,
                    recordingMbidsWithAssets = visibleAssetMbids,
                )
            }
        }

        return cacheRead.read(
            capability = ProviderCapability.CATALOG_SEARCH,
            provider = musicBrainz,
            unavailable = MusicBrainzCachedSearchSnapshot(),
        ) {
            database.withTransaction {
                val dao = database.catalogDao()
                val recordings = if (limits.recordings == 0) emptyList()
                    else dao.searchKnownMusicBrainzRecordings(normalized, limits.recordings)
                val withAssets = if (recordings.isEmpty()) emptySet()
                    else dao.musicBrainzRecordingMbidsWithAssets(
                        recordings.map { it.musicBrainzId },
                    ).toSet()
                MusicBrainzCachedSearchSnapshot(
                    artists = if (limits.artists == 0) emptyList()
                        else dao.searchKnownMusicBrainzArtists(normalized, limits.artists),
                    groups = if (limits.groups == 0) emptyList()
                        else dao.searchKnownMusicBrainzGroups(normalized, limits.groups),
                    releases = if (limits.releases == 0) emptyList()
                        else dao.searchKnownMusicBrainzReleases(normalized, limits.releases),
                    recordings = recordings,
                    recordingMbidsWithAssets = withAssets,
                )
            }
        }
    }
}
