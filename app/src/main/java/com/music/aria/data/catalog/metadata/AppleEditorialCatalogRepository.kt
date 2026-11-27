package com.music.aria.data.catalog.metadata

import com.music.aria.data.providers.ProviderCapability

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
    val albumSongSearchAttempted: Boolean = false,
    val albumSongSearchVerified: Boolean = false,
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
    val nonAudio = read.verifiedMusicVideoRows + read.verifiedBookletRows
    if (mixed.possiblyTruncated || total !in 1..200 ||
        read.declaredResults != read.rawRows || read.rawRows != total + 1 ||
        read.foreignSongRows != 0 || read.rejectedSongRows != 0 ||
        read.removedDuplicateOrDisputedSongs != 0 ||
        read.matchingNonSongRows != nonAudio ||
        read.matchingSongRows + nonAudio != total
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
        nonAudio
}

/**
 * A song search may recover an omitted song, but only source-owned rows
 * belonging to the already opened exact collection can complete it. Search
 * title relevance alone is never membership. Preserve the lookup header and
 * all original rows; no foreign collection or conflicting snapshot may fill
 * a missing position.
 */
internal fun appleVerifiedAlbumSongSearch(
    original: ItunesEditorialRelease,
    search: ItunesCatalogResult.Success,
    /** Additional songs from the independently header-verified mixed lookup. */
    corroboratedMixedTracks: List<ItunesCatalogItem> = emptyList(),
): ItunesEditorialRelease? {
    val total = original.trackCount ?: return null
    if (search.possiblyTruncated || total !in 1..200 ||
        original.tracks.isEmpty() || original.tracks.size >= total ||
        original.artistId == null
    ) return null
    val exact = search.items.filter {
        it.type == ItunesCatalogItemType.TRACK &&
            it.collectionId == original.appleCollectionId &&
            it.storefront == original.storefront
    }
    if (exact.isEmpty() || exact.map { it.id }.distinct().size != exact.size ||
        corroboratedMixedTracks.map { it.id }.distinct().size != corroboratedMixedTracks.size
    ) return null
    val byId = original.tracks.associateByTo(linkedMapOf()) { it.id }
    if (byId.size != original.tracks.size) return null
    // The exact album lookup, mixed lookup and album-field search may omit
    // different rows. Combine only their corroborated Apple identities, never
    // an unrelated search result or a position guessed from the total.
    for (other in corroboratedMixedTracks + exact) {
        if (other.type != ItunesCatalogItemType.TRACK ||
            other.collectionId != original.appleCollectionId ||
            other.storefront != original.storefront ||
            other.collectionTitle != original.title || other.id <= 0L ||
            other.artistId == null || other.artistName.isNullOrBlank() ||
            other.discNumber == null || other.discNumber <= 0 ||
            other.trackNumber == null || other.trackNumber <= 0
        ) return null
        val row = byId[other.id]
        if (row != null) {
            if (row.title != other.title || row.artistId != other.artistId ||
                row.artistName != other.artistName || row.discNumber != other.discNumber ||
                row.trackNumber != other.trackNumber || row.durationMs != other.durationMs ||
                (other.trackCount != null && other.trackCount != total)
            ) return null
        } else {
            if (other.trackCount != total) return null
            byId[other.id] = other
        }
    }
    val merged = byId.values.toList()
    if (merged.size <= original.tracks.size) return null
    if (merged.size != total ||
        merged.any { it.discNumber == null || it.discNumber <= 0 ||
            it.trackNumber == null || it.trackNumber <= 0 } ||
        merged.map { it.id }.distinct().size != merged.size ||
        merged.map { it.discNumber to it.trackNumber }.distinct().size != merged.size
    ) return null
    return original.copy(tracks = merged.sortedWith(
        compareBy<ItunesCatalogItem> { it.discNumber ?: Int.MAX_VALUE }
            .thenBy { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.id },
    ))
}

internal class AppleEditorialCatalogRepository(
    private val itunes: ItunesCatalogClient = ItunesCatalogClient(),
    private val nowNanos: () -> Long = { System.nanoTime() },
) {
    // Reuse already obtained exact Apple audio evidence without one lookup per
    // discography/search card. Process-local, bounded and short-lived; never
    // create a product, import another storefront, or cache external types.
    private data class CommercialIdentity(
        val scopedId: String,
        val artistId: Long,
        val artistName: String,
        val title: String,
        val releaseDate: String,
        val trackCount: Int,
        val upc: String?,
    )
    private data class CommercialObservation(
        val identity: CommercialIdentity,
        val format: ItunesEditorialReleaseFormat,
        val observedAt: Long,
    )
    private val commercialObservations = linkedMapOf<String, CommercialObservation>()
    private val commercialTtlNanos = 15L * 60 * 1_000_000_000

    private fun commercialIdentity(row: ItunesEditorialRelease): CommercialIdentity? {
        val artistId = row.artistId?.takeIf { it > 0 } ?: return null
        val count = row.trackCount?.takeIf { it > 0 } ?: return null
        val date = row.releaseDate?.takeIf { it.isNotBlank() } ?: return null
        if (row.appleCollectionId <= 0 || !Regex("[a-z]{2}").matches(row.storefront) ||
            row.appleScopedId != "itunes:${row.storefront}:album:${row.appleCollectionId}" ||
            row.title.isBlank() || row.artistName.isBlank()
        ) return null
        return CommercialIdentity(row.appleScopedId, artistId, row.artistName, row.title,
            date, count, row.upc)
    }

    @Synchronized
    private fun rememberCommercialFormat(row: ItunesEditorialRelease) {
        commercialObservations.remove(row.appleScopedId)
        val identity = commercialIdentity(row) ?: return
        val format = row.commercialAudioFormat
        if (format == ItunesEditorialReleaseFormat.UNCLASSIFIED) return
        commercialObservations[identity.scopedId] = CommercialObservation(identity, format, nowNanos())
        while (commercialObservations.size > 256)
            commercialObservations.remove(commercialObservations.keys.first())
    }

    @Synchronized
    private fun forgetCommercialFormat(collectionId: Long, storefront: String) {
        commercialObservations.remove("itunes:$storefront:album:$collectionId")
    }

    /** Apply only to an already visible Apple product, preserving source order. */
    @Synchronized
    fun reuseCommercialFormat(row: ItunesEditorialRelease): ItunesEditorialRelease {
        // The album detail owns its fresh audio proof. A header-only row can
        // carry a previously reused value from a pager snapshot: strip it and
        // revalidate on every projection rather than prolonging the TTL.
        if (row.tracks.isNotEmpty()) return row
        val native = if (row.commercialAudioFormat == ItunesEditorialReleaseFormat.UNCLASSIFIED)
            row else row.copy(commercialAudioFormat = ItunesEditorialReleaseFormat.UNCLASSIFIED)
        val observation = commercialObservations[native.appleScopedId] ?: return native
        val elapsed = nowNanos() - observation.observedAt
        if (elapsed < 0 || elapsed >= commercialTtlNanos) {
            commercialObservations.remove(native.appleScopedId)
            return native
        }
        val identity = commercialIdentity(native) ?: return native
        if (identity != observation.identity) {
            commercialObservations.remove(native.appleScopedId)
            return native
        }
        if (native.format != ItunesEditorialReleaseFormat.UNCLASSIFIED) return native
        return native.copy(commercialAudioFormat = observation.format)
    }

    fun reuseCommercialFormats(rows: List<ItunesEditorialRelease>): List<ItunesEditorialRelease> =
        rows.map(::reuseCommercialFormat)

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
            releases = reuseCommercialFormats(ItunesEditorialCatalog.assemble(storefront, source.items)),
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
                val matched = reuseCommercialFormats(ItunesEditorialCatalog.assemble(storefront, source.items)
                    .filter { it.artistId == artistId })
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
            val releases = reuseCommercialFormats(ItunesEditorialCatalog.assemble(storefront, source.items)
                .filter { it.artistId == artistId })
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
        is ItunesCatalogResult.Failure -> {
            forgetCommercialFormat(collectionId, storefront)
            AppleEditorialLoad.Failure(source.reason)
        }
        is ItunesCatalogResult.Success -> {
            val release = ItunesEditorialCatalog.assemble(storefront, source.items)
                .singleOrNull { it.appleCollectionId == collectionId }
            if (release == null) {
                forgetCommercialFormat(collectionId, storefront)
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
                val mixedSource = if (cleanShortRead)
                    itunes.lookupAlbumMusicTracks(collectionId, storefront) as? ItunesCatalogResult.Success
                else null
                val mixed = mixedSource?.let { appleVerifiedMixedAudioRelease(release, it) }
                // A successful but still short same-collection observation
                // can justify one independent album-field song search. Do not
                // continue after a failed lookup (may be throttling or revoked
                // authorization), or when membership metadata is absent.
                val searchEligible = mixed == null && mixedSource != null &&
                    !mixedSource.possiblyTruncated &&
                    mixedSource.albumLookupObservation?.let {
                        it.foreignSongRows == 0 && it.rejectedSongRows == 0 &&
                            it.removedDuplicateOrDisputedSongs == 0 &&
                            it.matchingNonSongRows == 0 &&
                            it.declaredResults == it.rawRows
                    } == true &&
                    mixedSource.items.singleOrNull {
                        it.type == ItunesCatalogItemType.ALBUM
                    }?.let {
                        it.id == release.appleCollectionId &&
                            it.storefront == release.storefront &&
                            it.title == release.title && it.artistId == release.artistId &&
                            it.artistName == release.artistName && it.trackCount == release.trackCount &&
                            it.releaseDate == release.releaseDate && it.upc == release.upc
                    } == true &&
                    mixedSource.items.filter { it.type == ItunesCatalogItemType.TRACK }.let { observed ->
                        val originalById = release.tracks.associateBy { it.id }
                        observed.all { other ->
                            originalById[other.id]?.let { row ->
                                row.title == other.title && row.artistId == other.artistId &&
                                    row.artistName == other.artistName &&
                                    row.discNumber == other.discNumber &&
                                    row.trackNumber == other.trackNumber && row.durationMs == other.durationMs
                            } ?: true
                        }
                    } &&
                    release.tracks.all { it.collectionTitle == release.title } &&
                    release.title.length in 1..120 && release.title.none(Char::isISOControl)
                val recovered = if (searchEligible) {
                    (itunes.search(release.title, ItunesCatalogEntity.SONG, storefront, 200,
                        attribute = ItunesCatalogSearchAttribute.ALBUM_TERM,
                        capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
                    ) as? ItunesCatalogResult.Success)
                        ?.let { appleVerifiedAlbumSongSearch(
                            release, it, mixedSource?.items.orEmpty()
                                .filter { row -> row.type == ItunesCatalogItemType.TRACK },
                        ) }
                } else null
                val audioComplete = verifiedComplete || mixed != null || recovered != null
                val finalAudio = mixed?.first ?: recovered ?: release
                val explicitFormat = ItunesEditorialCatalog.formatFromVerifiedTrackLabels(
                    finalAudio, audioComplete,
                )
                val cleanOriginalAudio = !source.possiblyTruncated &&
                    source.albumLookupObservation?.let {
                        it.declaredResults == it.rawRows &&
                            it.matchingSongRows == release.tracks.size &&
                            it.foreignSongRows == 0 && it.rejectedSongRows == 0 &&
                            it.removedDuplicateOrDisputedSongs == 0
                    } == true
                val commercialFormat = ItunesEditorialCatalog.commercialAudioFormat(
                    finalAudio,
                    cleanAudioEvidence = cleanOriginalAudio || mixed != null || recovered != null,
                    complete = audioComplete,
                    verifiedExcludedNonAudio = mixed?.second ?: 0,
                )
                val finalRelease = finalAudio.copy(
                    format = explicitFormat,
                    commercialAudioFormat = commercialFormat,
                )
                rememberCommercialFormat(finalRelease)
                AppleEditorialLoad.Success(
                    listOf(finalRelease),
                    if (audioComplete) AppleEditorialReadState.COMPLETE
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
                        albumSongSearchAttempted = searchEligible,
                        albumSongSearchVerified = recovered != null,
                    ),
                )
            }
        }
    }
}
