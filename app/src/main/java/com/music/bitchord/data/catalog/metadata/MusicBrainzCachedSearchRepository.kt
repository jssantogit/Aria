package com.music.bitchord.data.catalog.metadata

import androidx.room.withTransaction
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.data.providers.CatalogEntryProvenance
import com.music.bitchord.data.providers.CatalogProvenanceFilter
import com.music.bitchord.data.providers.ProvenanceScopedItem
import com.music.bitchord.data.providers.ProviderCachedReadAccess
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderSessionSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged

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
 * - Editorial-only MusicBrainz rows are gated by the CALLER'S capability:
 *   [ProviderCapability.CATALOG_SEARCH] for search, or
 *   [ProviderCapability.EDITORIAL_DISCOGRAPHY] for native Home/discography.
 *   Neither capability silently inherits the other's permission.
 * - Recordings backed by personally owned [MediaAssetEntity] rows
 *   ([CatalogEntryProvenance.LocalOrPersonalAsset]) remain visible through
 *   [CatalogProvenanceFilter] even when MusicBrainz editorial search is turned off,
 *   never erasing or hiding user-owned media or canonical identities.
 */
internal class MusicBrainzCachedSearchRepository(
    private val database: CanonicalCatalogDatabase,
    private val cacheRead: ProviderCachedReadAccess = ProviderCachedReadAccess(),
    /** Current verified local/server account keys, never a blanket asset grant. */
    private val personalProviderKeys: () -> Set<String> = {
        setOf(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER)
    },
) {
    private val musicBrainz = ProviderKey("musicbrainz")

    /** Fail closed for unknown services even if a caller supplies their key. */
    private fun eligiblePersonalProviders(): List<String> = personalProviderKeys()
        .filter { provider ->
            provider == CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER ||
                (provider.startsWith("opensubsonic:") &&
                    provider.substringAfter(':').isNotBlank())
        }.distinct().sorted()

    /**
     * Re-read cached personal recordings on editorial opt-out. An empty
     * fallback would incorrectly hide personally owned assets with MBIDs.
     * mapLatest cancels stale editorial Room reads at capability changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(
        query: String,
        limits: MusicBrainzCachedSearchLimits = MusicBrainzCachedSearchLimits(),
        providerState: StateFlow<ProviderSessionSnapshot>,
        capability: ProviderCapability = ProviderCapability.CATALOG_SEARCH,
    ): Flow<MusicBrainzCachedSearchSnapshot> {
        requireSupportedRead(capability)
        return providerState.map { state ->
            musicBrainz in state.eligible[capability].orEmpty()
        }.distinctUntilChanged().mapLatest { search(query, limits, capability) }
    }

    private fun requireSupportedRead(capability: ProviderCapability) {
        require(
            capability == ProviderCapability.CATALOG_SEARCH ||
                capability == ProviderCapability.EDITORIAL_DISCOGRAPHY,
        ) { "MusicBrainz cache is search/editorial only" }
    }

    suspend fun search(
        query: String,
        limits: MusicBrainzCachedSearchLimits = MusicBrainzCachedSearchLimits(),
        capability: ProviderCapability = ProviderCapability.CATALOG_SEARCH,
    ): MusicBrainzCachedSearchSnapshot {
        requireSupportedRead(capability)
        val normalized = query.trim()
        val gate = cacheRead.currentGateOrNull()
        val editorialPermitted = gate?.open(capability, musicBrainz) != null

        // When MusicBrainz editorial search is disabled (or gate is missing/fail-closed),
        // skip editorial-only artist/group/release queries while preserving recordings
        // backed by personally owned local/server MediaAssets.
        if (!editorialPermitted) {
            val eligible = eligiblePersonalProviders()
            if (limits.recordings == 0 || eligible.isEmpty())
                return MusicBrainzCachedSearchSnapshot()
            return database.withTransaction {
                val dao = database.catalogDao()
                val candidateRecordings = dao.searchKnownMusicBrainzPersonalRecordings(
                    normalized, limits.recordings, eligible,
                )
                if (candidateRecordings.isEmpty())
                    return@withTransaction MusicBrainzCachedSearchSnapshot()
                // The Room query has already verified ownership before LIMIT.
                val withAssets = candidateRecordings.mapTo(LinkedHashSet()) {
                    it.musicBrainzId
                }
                val scopedRecordings = candidateRecordings.map { hit ->
                    val provenances = buildSet {
                        add(CatalogEntryProvenance.ProviderEditorial(musicBrainz, capability))
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
            capability = capability,
            provider = musicBrainz,
            unavailable = MusicBrainzCachedSearchSnapshot(),
        ) {
            database.withTransaction {
                val dao = database.catalogDao()
                val recordings = if (limits.recordings == 0) emptyList()
                    else dao.searchKnownMusicBrainzRecordings(normalized, limits.recordings)
                val eligible = eligiblePersonalProviders()
                val withAssets = if (recordings.isEmpty() || eligible.isEmpty()) emptySet()
                    else dao.musicBrainzRecordingMbidsWithPersonalAssets(
                        recordings.map { it.musicBrainzId }, eligible,
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
