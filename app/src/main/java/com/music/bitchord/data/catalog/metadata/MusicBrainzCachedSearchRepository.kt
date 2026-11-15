package com.music.bitchord.data.catalog.metadata

import androidx.room.withTransaction
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.providers.ProviderCachedReadAccess
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderReactiveCache
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
 *
 * This is data-layer preparation; existing REA screens and MainViewModel are
 * intentionally unchanged until their definitive UI contract is merged.
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
 * ONE source policy admission wraps ALL indexed MusicBrainz search kinds.
 * If disabled, no SQL is executed and no cached MB-only result leaks out.
 * If revoked during Room work, the completed snapshot is suppressed.
 *
 * Importantly, this never deletes canonical identities, user ratings,
 * personally owned MediaAssets or Room cache rows.
 */
internal class MusicBrainzCachedSearchRepository(
    private val database: CanonicalCatalogDatabase,
    private val cacheRead: ProviderCachedReadAccess = ProviderCachedReadAccess(),
) {
    /**
     * A future REA observer can subscribe without recreating this repository
     * or forcing a Room refresh on unrelated provider setting changes.
     * Switching a provider off immediately emits an empty source snapshot.
     */
    fun observe(
        query: String,
        limits: MusicBrainzCachedSearchLimits = MusicBrainzCachedSearchLimits(),
        providerState: StateFlow<ProviderSessionSnapshot>,
    ): Flow<MusicBrainzCachedSearchSnapshot> =
        ProviderReactiveCache(providerState).observe(
            ProviderCapability.CATALOG_SEARCH,
            ProviderKey("musicbrainz"),
            MusicBrainzCachedSearchSnapshot(),
        ) { search(query, limits) }

    suspend fun search(
        query: String,
        limits: MusicBrainzCachedSearchLimits = MusicBrainzCachedSearchLimits(),
    ): MusicBrainzCachedSearchSnapshot =
        cacheRead.read(
            capability = ProviderCapability.CATALOG_SEARCH,
            provider = ProviderKey("musicbrainz"),
            unavailable = MusicBrainzCachedSearchSnapshot(),
        ) {
            database.withTransaction {
                val dao = database.catalogDao()
                val normalized = query.trim()
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
