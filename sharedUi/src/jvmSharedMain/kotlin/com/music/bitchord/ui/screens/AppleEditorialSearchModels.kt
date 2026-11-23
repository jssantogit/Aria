package com.music.bitchord.ui.screens

/**
 * Native search presentations of Apple commercial products. These are NOT
 * MusicBrainz IDs, Song/videoId assets or a source of playable media.
 * Browse IDs remain storefront-scoped and must be routed by Apple handlers.
 */
/** Only the three public iTunes Search entities displayed in General Search. */
enum class AppleSearchSection { ARTISTS, TRACKS, ALBUMS }

data class AppleSearchPageStatus(
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val retryableError: Boolean = false,
    val coverageUncertain: Boolean = false,
)

enum class AppleEditorialSearchKind { ARTIST, ALBUM }

/** Editorial label, not the API entity type or MusicBrainz identity. */
enum class AppleEditorialReleaseCategory { ALBUM, EP, SINGLE, UNCLASSIFIED }

data class AppleEditorialSearchRow(
    val browseId: String,
    val title: String,
    val artistName: String,
    val artworkUri: String?,
    val releaseDate: String?,
    val kind: AppleEditorialSearchKind = AppleEditorialSearchKind.ALBUM,
    val category: AppleEditorialReleaseCategory = AppleEditorialReleaseCategory.UNCLASSIFIED,
    /** Exact Apple storefront product link, never inferred from a title. */
    val appleStoreUrl: String? = null,
    /** Unmodified official badge only when appleStoreUrl passed validation. */
    val appleStoreBadgeUrl: String? = null,
    /** Exact storefront artist ID supplied by the album, not a title match. */
    val artistBrowseId: String? = null,
)

data class AppleEditorialTrackRow(
    val key: String,
    val title: String,
    val artistName: String?,
    val discNumber: Int?,
    val trackNumber: Int?,
)

data class AppleEditorialAlbumDetailPage(
    val album: AppleEditorialSearchRow,
    val tracks: List<AppleEditorialTrackRow>,
    val partialTracklist: Boolean,
    /** Verified Discogs edition evidence must always carry its source link. */
    val discogsAttributionText: String? = null,
    val discogsAttributionUrl: String? = null,
    val discogsNonAffiliationNotice: String? = null,
    /** The storefront-owned link accompanying promotional album artwork. */
    val appleStoreUrl: String? = null,
    val appleStoreBadgeUrl: String? = null,
    /** Independently verified same-artist storefront releases, not recommendations inferred by title. */
    val relatedAlbums: List<AppleEditorialSearchRow> = emptyList(),
)

data class AppleEditorialArtistDetailPage(
    val artist: AppleEditorialSearchRow,
    val albums: List<AppleEditorialSearchRow>,
    /** Known additional display pages or pending public iTunes probes. */
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val pageError: Boolean = false,
    val noAdditionalResults: Boolean = false,
    /** A capped endpoint never proves that every Apple edition was found. */
    val possiblyTruncated: Boolean,
    /** Official exact-ID Apple artist songs; order does not imply popularity. */
    val tracks: List<AppleEditorialTrackSearchRow> = emptyList(),
    /** Verified Apple artist URL → MusicBrainz relation; never a name match. */
    val verifiedMusicBrainzArtistMbid: String? = null,
)

/** Apple song search is catalog metadata only, not a playable Song or MediaAsset. */
data class AppleEditorialTrackSearchRow(
    val key: String,
    val title: String,
    val artistName: String,
    val artworkUri: String?,
    val albumBrowseId: String?,
    val durationMs: Long?,
    val appleStoreUrl: String? = null,
    val appleStoreBadgeUrl: String? = null,
    /** Exact source-supplied artist route; absent means no verified artist ID. */
    val artistBrowseId: String? = null,
)

/**
 * One mixed public-search surface regardless of metadata provider. These
 * variants preserve source-owned IDs and their original click handlers; no
 * Apple product is converted to a MusicBrainz recording or playable Song.
 */
sealed interface AppleUnifiedSearchEntry {
    data class Artist(val row: AppleEditorialSearchRow) : AppleUnifiedSearchEntry
    data class Track(val row: AppleEditorialTrackSearchRow) : AppleUnifiedSearchEntry
    data class Album(val row: AppleEditorialSearchRow) : AppleUnifiedSearchEntry
}

/**
 * A precise artist hit leads General Search, like MusicBrainz. Subsequent
 * result rows mix recordings and releases in source order rather than
 * recreating provider-specific "Artistas/Músicas/Álbuns" subsections.
 * The Artists tab still lists every returned artist.
 */
fun appleUnifiedSearchEntries(
    query: String,
    artists: List<AppleEditorialSearchRow>,
    tracks: List<AppleEditorialTrackSearchRow>,
    albums: List<AppleEditorialSearchRow>,
): List<AppleUnifiedSearchEntry> = buildList {
    val normalized = query.trim().replace(Regex("\\s+"), " ")
    val exact = artists.filter { it.title.equals(normalized, ignoreCase = true) }
    val leaders = if (exact.isNotEmpty()) exact.take(1) else artists.take(3)
    leaders.distinctBy { it.browseId }.forEach { add(AppleUnifiedSearchEntry.Artist(it)) }
    // An exact Apple artist hit scopes General to THAT artist's products,
    // not songs called "ILLIT ..." by an unrelated artist. Exact storefront
    // artist ID beats a misleading display credit; where Apple omits the ID
    // an exact credit is only weak search relevance, never an identity join.
    // The typed Songs/Albums tabs retain all independently matched products.
    val primary = exact.firstOrNull()
    fun creditedToPrimary(artistRoute: String?, credit: String): Boolean =
        primary == null || if (artistRoute != null) artistRoute == primary.browseId
        else credit.equals(primary.title, ignoreCase = true)
    val uniqueTracks = tracks.asSequence()
        .filter { creditedToPrimary(it.artistBrowseId, it.artistName) }
        .distinctBy { it.key }.toList()
    val uniqueAlbums = albums.asSequence()
        .filter { creditedToPrimary(it.artistBrowseId, it.artistName) }
        .distinctBy { it.browseId }.toList()
    val maxRows = maxOf(uniqueTracks.size, uniqueAlbums.size)
    for (index in 0 until maxRows) {
        uniqueTracks.getOrNull(index)?.let { add(AppleUnifiedSearchEntry.Track(it)) }
        uniqueAlbums.getOrNull(index)?.let { add(AppleUnifiedSearchEntry.Album(it)) }
    }
}

/**
 * A single continuation for the mixed tab. Retriable failures get priority;
 * otherwise read a source-backed page from the shorter visible track/album
 * stream. Do not spend repeated requests on fuzzy artist names if an exact
 * Artist product has already been found.
 */
fun appleUnifiedNextPage(
    query: String,
    artists: List<AppleEditorialSearchRow>,
    tracks: List<AppleEditorialTrackSearchRow>,
    albums: List<AppleEditorialSearchRow>,
    paging: Map<AppleSearchSection, AppleSearchPageStatus>,
): AppleSearchSection? {
    val exactArtist = artists.any { it.title.equals(query.trim(), ignoreCase = true) }
    val candidates = listOf(
        AppleSearchSection.TRACKS to tracks.size,
        AppleSearchSection.ALBUMS to albums.size,
        AppleSearchSection.ARTISTS to artists.size,
    ).filter { (type, _) ->
        (type != AppleSearchSection.ARTISTS || !exactArtist) &&
            paging[type]?.let { !it.loadingMore && (it.hasMore || it.retryableError) } == true
    }
    return candidates.sortedWith(
        compareBy<Pair<AppleSearchSection, Int>> {
            if (paging[it.first]?.retryableError == true) 0 else 1
        }.thenBy { it.second },
    ).firstOrNull()?.first
}
