package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.data.providers.ProviderCapability

/**
 * Android catalog reader. iTunes controls commercial product existence and
 * storefront identity; MBIDs/playback are deliberately absent here.
 * This layer distinguishes a returned (possibly partial) catalog from an
 * unavailable catalog, so callers never silently substitute MB discovery.
 */
internal enum class AppleEditorialReadState {
    COMPLETE,
    POSSIBLY_TRUNCATED,
    PARTIAL_TRACKLIST,
    NOT_IN_STOREFRONT,
}

/**
 * Diagnostic coverage from actual Apple search probes, not a claim to know
 * the number of products available in the store. Does not store query text.
 */
internal data class AppleEditorialProbeReport(
    val eligibleSeeds: Int,
    val probesMade: Int,
    val matchingProducts: Int,
    val withoutMatchingProducts: Int,
    val failedRequests: Int,
    val incompleteResponses: Int,
    val budgetLimited: Boolean,
)

/** Diagnostic evidence from one exact Apple collection lookup, not a
 * fabricated count or a claim that every raw row is an audio track. */
internal data class AppleAlbumReadDiagnostics(
    val declaredTrackCount: Int?,
    val decodedTracks: Int,
    val positionlessTracks: Int,
    val duplicatePositions: Int,
    val sourcePossiblyTruncated: Boolean,
    val lookup: ItunesAlbumLookupObservation?,
    val mixedLookupAttempted: Boolean = false,
    val mixedLookupVerified: Boolean = false,
    val verifiedExcludedNonAudioRows: Int = 0,
)

internal sealed interface AppleEditorialLoad {
    data class Success(
        val releases: List<ItunesEditorialRelease>,
        val state: AppleEditorialReadState,
        val probeReport: AppleEditorialProbeReport? = null,
        val albumDiagnostics: AppleAlbumReadDiagnostics? = null,
    ) : AppleEditorialLoad

    data class Failure(
        val reason: ItunesCatalogFailure,
        val probeReport: AppleEditorialProbeReport? = null,
    ) : AppleEditorialLoad
}

internal sealed interface AppleEditorialArtistLoad {
    data class Success(
        val artists: List<ItunesCatalogItem>,
        val possiblyTruncated: Boolean,
    ) : AppleEditorialArtistLoad

    data class Failure(val reason: ItunesCatalogFailure) : AppleEditorialArtistLoad
}

internal sealed interface AppleEditorialTrackLoad {
    data class Success(
        val tracks: List<ItunesCatalogItem>,
        val possiblyTruncated: Boolean,
    ) : AppleEditorialTrackLoad

    data class Failure(val reason: ItunesCatalogFailure) : AppleEditorialTrackLoad
}

/** Full mixed membership proof from a second exact collection response.
 * Do not merge different editions, trust a count alone, or discard songs
 * that conflict between observations. The original collection header stays. */
internal fun appleVerifiedMixedAudioRelease(
    original: ItunesEditorialRelease,
    mixed: ItunesCatalogResult.Success,
): Pair<ItunesEditorialRelease, Int>? {
    val total = original.trackCount ?: return null
    val read = mixed.albumLookupObservation ?: return null
    if (mixed.possiblyTruncated || total !in 1..200 ||
        read.declaredResults != read.rawRows || read.rawRows != total + 1 ||
        read.foreignSongRows != 0 || read.rejectedSongRows != 0 ||
        read.removedDuplicateOrDisputedSongs != 0 ||
        read.matchingNonSongRows != read.verifiedMusicVideoRows ||
        read.matchingSongRows + read.verifiedMusicVideoRows != total
    ) return null
    val release = ItunesEditorialCatalog.assemble(original.storefront, mixed.items)
        .singleOrNull() ?: return null
    if (release.appleScopedId != original.appleScopedId ||
        release.artistId == null || release.artistId != original.artistId ||
        release.artistName != original.artistName || release.title != original.title ||
        release.releaseDate != original.releaseDate || release.trackCount != total ||
        release.upc != original.upc || release.tracks.isEmpty() ||
        release.tracks.size != read.matchingSongRows ||
        release.tracks.any { it.collectionId != original.appleCollectionId ||
            it.storefront != original.storefront || it.discNumber == null ||
            it.discNumber <= 0 || it.trackNumber == null || it.trackNumber <= 0 } ||
        release.tracks.map { it.id }.distinct().size != release.tracks.size ||
        release.tracks.map { it.discNumber to it.trackNumber }.distinct().size != release.tracks.size
    ) return null
    val observed = release.tracks.associateBy { it.id }
    // Exact identity, title, credit, position and duration must survive.
    if (original.tracks.any { row ->
            val other = observed[row.id]
            other == null || row.title != other.title ||
                row.artistId != other.artistId || row.artistName != other.artistName ||
                row.discNumber != other.discNumber || row.trackNumber != other.trackNumber ||
                row.durationMs != other.durationMs
        }) return null
    val originalById = original.tracks.associateBy { it.id }
    return original.copy(tracks = release.tracks.map { originalById[it.id] ?: it }) to
        read.verifiedMusicVideoRows
}

internal class AppleEditorialCatalogRepository(
    private val itunes: ItunesCatalogClient = ItunesCatalogClient(),
) {
    /** Artist presence and attribution come from Apple, not name-matched MBIDs. */
    suspend fun searchArtists(
        query: String,
        storefront: String = "br",
        limit: Int = 50,
    ): AppleEditorialArtistLoad = when (val source = itunes.search(
        query, ItunesCatalogEntity.ARTIST, country = storefront, limit = limit,
    )) {
        is ItunesCatalogResult.Failure ->
            AppleEditorialArtistLoad.Failure(source.reason)
        is ItunesCatalogResult.Success ->
            AppleEditorialArtistLoad.Success(
                artists = source.items.filter {
                    it.type == ItunesCatalogItemType.ARTIST && it.storefront == storefront
                },
                possiblyTruncated = source.possiblyTruncated,
            )
    }

    /** Apple songs are editorial recordings, never Song/videoId/playable assets. */
    suspend fun searchTracks(
        query: String,
        storefront: String = "br",
        limit: Int = 100,
    ): AppleEditorialTrackLoad = when (val source = itunes.search(
        query, ItunesCatalogEntity.SONG, country = storefront, limit = limit,
    )) {
        is ItunesCatalogResult.Failure ->
            AppleEditorialTrackLoad.Failure(source.reason)
        is ItunesCatalogResult.Success ->
            AppleEditorialTrackLoad.Success(
                tracks = source.items.filter {
                    it.type == ItunesCatalogItemType.TRACK && it.storefront == storefront
                },
                possiblyTruncated = source.possiblyTruncated,
            )
    }

    suspend fun searchAlbums(
        query: String,
        storefront: String = "br",
        limit: Int = 50,
        capability: ProviderCapability = ProviderCapability.CATALOG_SEARCH,
        attribute: ItunesCatalogSearchAttribute? = null,
    ): AppleEditorialLoad = when (val source = itunes.search(
        query, ItunesCatalogEntity.ALBUM, country = storefront, limit = limit,
        attribute = attribute, capability = capability,
    )) {
        is ItunesCatalogResult.Failure -> AppleEditorialLoad.Failure(source.reason)
        is ItunesCatalogResult.Success -> AppleEditorialLoad.Success(
            releases = ItunesEditorialCatalog.assemble(storefront, source.items),
            state = when {
                source.items.isEmpty() -> AppleEditorialReadState.NOT_IN_STOREFRONT
                source.possiblyTruncated -> AppleEditorialReadState.POSSIBLY_TRUNCATED
                else -> AppleEditorialReadState.COMPLETE
            },
        )
    }

    /**
     * Extra PUBLIC iTunes discovery pass. We never use a MusicBrainz or
     * Discogs title as a visible release; every result must carry the exact
     * selected Apple artist ID and storefront. Can still hit the 200 cap.
     */
    suspend fun searchArtistAlbumCandidates(
        query: String,
        artistId: Long,
        storefront: String,
        attribute: ItunesCatalogSearchAttribute,
    ): AppleEditorialLoad {
        if (artistId <= 0) return AppleEditorialLoad.Failure(
            ItunesCatalogFailure.INVALID_QUERY)
        return when (val source = itunes.search(
            query, ItunesCatalogEntity.ALBUM, storefront, 200, attribute,
            capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
        )) {
            is ItunesCatalogResult.Failure ->
                AppleEditorialLoad.Failure(source.reason)
            is ItunesCatalogResult.Success -> {
                val matched = ItunesEditorialCatalog.assemble(storefront, source.items)
                    .filter { it.artistId == artistId }
                AppleEditorialLoad.Success(
                    releases = matched,
                    state = when {
                        source.possiblyTruncated ->
                            AppleEditorialReadState.POSSIBLY_TRUNCATED
                        matched.isEmpty() ->
                            AppleEditorialReadState.NOT_IN_STOREFRONT
                        else -> AppleEditorialReadState.COMPLETE
                    },
                )
            }
        }
    }

    suspend fun artistDiscography(
        artistId: Long,
        storefront: String = "br",
        limit: Int = 200,
    ): AppleEditorialLoad = when (val source = itunes.lookupArtistAlbums(
        artistId = artistId, country = storefront, limit = limit,
    )) {
        is ItunesCatalogResult.Failure -> AppleEditorialLoad.Failure(source.reason)
        is ItunesCatalogResult.Success -> {
            val releases = ItunesEditorialCatalog.assemble(storefront, source.items)
                .filter { it.artistId == artistId }
            AppleEditorialLoad.Success(
                releases = releases,
                state = when {
                    releases.isEmpty() -> AppleEditorialReadState.NOT_IN_STOREFRONT
                    source.possiblyTruncated -> AppleEditorialReadState.POSSIBLY_TRUNCATED
                    else -> AppleEditorialReadState.COMPLETE
                },
            )
        }
    }

    /** Metadata-only artist song lookup; source order is not a popularity chart. */
    suspend fun artistTracks(
        artistId: Long,
        storefront: String = "br",
        limit: Int = 40,
    ): AppleEditorialTrackLoad = when (val response = itunes.lookupArtistTracks(
        artistId, country = storefront, limit = limit,
    )) {
        is ItunesCatalogResult.Failure ->
            AppleEditorialTrackLoad.Failure(response.reason)
        is ItunesCatalogResult.Success -> AppleEditorialTrackLoad.Success(
            tracks = response.items.filter {
                it.type == ItunesCatalogItemType.TRACK &&
                    it.artistId == artistId && it.storefront == storefront
            },
            possiblyTruncated = response.possiblyTruncated,
        )
    }

    suspend fun albumDetail(
        collectionId: Long,
        storefront: String = "br",
    ): AppleEditorialLoad = when (val source = itunes.lookupAlbum(
        collectionId, country = storefront,
    )) {
        is ItunesCatalogResult.Failure -> AppleEditorialLoad.Failure(source.reason)
        is ItunesCatalogResult.Success -> {
            val release = ItunesEditorialCatalog.assemble(storefront, source.items)
                .singleOrNull { it.appleCollectionId == collectionId }
            if (release == null) {
                AppleEditorialLoad.Success(emptyList(), AppleEditorialReadState.NOT_IN_STOREFRONT)
            } else {
                val positionless = release.tracks.count {
                    it.discNumber == null || it.discNumber <= 0 ||
                        it.trackNumber == null || it.trackNumber <= 0
                }
                val positioned = release.tracks.filter {
                    it.discNumber != null && it.discNumber > 0 &&
                        it.trackNumber != null && it.trackNumber > 0
                }
                val duplicatePositions = positioned.size -
                    positioned.map { it.discNumber to it.trackNumber }.distinct().size
                val complete = release.trackCount != null && release.trackCount > 0 &&
                    release.tracks.size == release.trackCount &&
                    release.tracks.all { track ->
                        track.type == ItunesCatalogItemType.TRACK &&
                            track.collectionId == collectionId &&
                            track.discNumber != null && track.discNumber > 0 &&
                            track.trackNumber != null && track.trackNumber > 0
                    } &&
                    release.tracks.map { it.discNumber to it.trackNumber }.distinct().size ==
                        release.tracks.size
                val verifiedComplete = complete && !source.possiblyTruncated
                val cleanShortRead = !verifiedComplete && !source.possiblyTruncated &&
                    release.tracks.isNotEmpty() && release.trackCount != null &&
                    release.trackCount > release.tracks.size && release.trackCount <= 200 &&
                    positionless == 0 && duplicatePositions == 0 &&
                    source.albumLookupObservation?.let {
                        it.foreignSongRows == 0 && it.rejectedSongRows == 0 &&
                            it.removedDuplicateOrDisputedSongs == 0
                    } == true
                val mixed = if (cleanShortRead) {
                    (itunes.lookupAlbumMusicTracks(collectionId, storefront) as? ItunesCatalogResult.Success)
                        ?.let { appleVerifiedMixedAudioRelease(release, it) }
                } else null
                val finalRelease = mixed?.first ?: release
                AppleEditorialLoad.Success(
                    listOf(finalRelease),
                    if (verifiedComplete || mixed != null) AppleEditorialReadState.COMPLETE
                    else AppleEditorialReadState.PARTIAL_TRACKLIST,
                    albumDiagnostics = AppleAlbumReadDiagnostics(
                        declaredTrackCount = release.trackCount,
                        decodedTracks = finalRelease.tracks.size,
                        positionlessTracks = positionless,
                        duplicatePositions = duplicatePositions,
                        sourcePossiblyTruncated = source.possiblyTruncated,
                        lookup = source.albumLookupObservation,
                        mixedLookupAttempted = cleanShortRead,
                        mixedLookupVerified = mixed != null,
                        verifiedExcludedNonAudioRows = mixed?.second ?: 0,
                    ),
                )
            }
        }
    }
}
