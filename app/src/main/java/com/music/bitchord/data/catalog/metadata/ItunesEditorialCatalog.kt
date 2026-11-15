package com.music.bitchord.data.catalog.metadata

import java.util.Locale

/**
 * Apple supplies the list of visible commercial releases, their names, track
 * order and artwork. MusicBrainz is an optional identity annotation; it must
 * never create/remove an Apple editorial release or override its presentation.
 *
 * This is an isolated projection, not yet the live Home/artist/search source.
 */
internal enum class ItunesEditorialReleaseFormat { ALBUM, EP, SINGLE, UNCLASSIFIED }

internal data class ItunesVerifiedReleaseGroupLink(
    val appleScopedId: String,
    val musicBrainzReleaseGroupId: String,
)

/** Edition-specific MusicBrainz Release link, not a ReleaseGroup alias. */
internal data class ItunesVerifiedReleaseLink(
    val appleScopedId: String,
    val musicBrainzReleaseId: String,
)

/** These links are *already independently verified* by the crosswalk layer. */
internal data class ItunesVerifiedArtistLink(
    val appleScopedId: String,
    val musicBrainzArtistId: String,
)

internal data class ItunesVerifiedRecordingLink(
    val appleScopedId: String,
    val musicBrainzRecordingId: String,
)

internal data class ItunesEditorialRelease(
    val appleScopedId: String,
    val appleCollectionId: Long,
    val storefront: String,
    val title: String,
    val artistName: String,
    val artistId: Long?,
    val releaseDate: String?,
    val genre: String?,
    val format: ItunesEditorialReleaseFormat,
    val artworkUrl: String?,
    val trackCount: Int?,
    val tracks: List<ItunesCatalogItem>,
    val musicBrainzReleaseGroupId: String?,
    val musicBrainzArtistId: String?,
    /** Only matching Apple track IDs with independently verified MB recording IDs. */
    val musicBrainzRecordingIds: Map<String, String>,
    /** UPC from the exact Apple collection; not a unique identity proof by itself. */
    val upc: String? = null,
    /** Exact MusicBrainz Release MBID, distinct from the group annotation. */
    val musicBrainzReleaseId: String? = null,
    /** The exact Apple-supplied product URL, not a title-based guess. */
    val storeUrl: String? = null,
)

internal object ItunesEditorialCatalog {
    private val mbid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /**
     * Do not trust a crosswalk that produces two distinct MBIDs for one Apple
     * resource. Silence the annotation instead of arbitrarily picking one.
     */
    private fun verifiedIdentityMap(
        country: String,
        kind: ItunesCatalogItemType,
        links: List<Pair<String, String>>,
    ): Map<String, String> {
        val prefix = "itunes:$country:${kind.key}:"
        return links.asSequence()
            .filter { (key, value) ->
                key.startsWith(prefix) &&
                    key.removePrefix(prefix).toLongOrNull()?.let { it > 0L } == true &&
                    mbid.matches(value.lowercase(Locale.ROOT))
            }
            .groupBy({ it.first }, { it.second.lowercase(Locale.ROOT) })
            .mapNotNull { (key, values) ->
                values.distinct().singleOrNull()?.let { key to it }
            }
            .toMap()
    }

    /**
     * Apple's public collectionType=Album does not distinguish releases.
     * Its own explicit "- EP", "- Single" and numbered "2nd EP" labels win.
     * For unlabeled releases, Apple's own trackCount >= 7 is album evidence
     * under Apple's published Music Style Guide (without any MB title guess).
     * Four-to-six tracks are not enough to assert EP without total duration.
     */
    private val numberedAppleEp = Regex(
        """\b[1-9][0-9]*(?:st|nd|rd|th)\s+EP\b""", RegexOption.IGNORE_CASE,
    )
    fun formatFromAppleTitle(
        title: String,
        trackCount: Int? = null,
    ): ItunesEditorialReleaseFormat {
        val normalized = java.text.Normalizer.normalize(
            title.trim(), java.text.Normalizer.Form.NFKC,
        ).lowercase(Locale.ROOT)
        return when {
            normalized.endsWith(" - ep") || normalized.endsWith(" – ep") ||
                normalized.endsWith(" — ep") ||
                numberedAppleEp.containsMatchIn(normalized) -> ItunesEditorialReleaseFormat.EP
            normalized.endsWith(" - single") || normalized.endsWith(" – single") ||
                normalized.endsWith(" — single") -> ItunesEditorialReleaseFormat.SINGLE
            trackCount != null && trackCount >= 7 -> ItunesEditorialReleaseFormat.ALBUM
            else -> ItunesEditorialReleaseFormat.UNCLASSIFIED
        }
    }

    /**
     * It is intentional that only Apple rows form the output. MB-only releases
     * can be browsed in an explicitly separate archive, never injected here.
     * Callers must pass ONLY independently verified links; title resemblance,
     * UPC alone and ISRC alone are insufficient for canonical MBID attachment.
     */
    fun assemble(
        storefront: String,
        appleRows: List<ItunesCatalogItem>,
        verifiedLinks: List<ItunesVerifiedReleaseGroupLink> = emptyList(),
        verifiedArtistLinks: List<ItunesVerifiedArtistLink> = emptyList(),
        verifiedRecordingLinks: List<ItunesVerifiedRecordingLink> = emptyList(),
        verifiedReleaseLinks: List<ItunesVerifiedReleaseLink> = emptyList(),
    ): List<ItunesEditorialRelease> {
        val country = storefront.trim().lowercase(Locale.ROOT)
        if (!Regex("[a-z]{2}").matches(country)) return emptyList()
        // A source returning two contradictory snapshots under the same
        // Apple collection/track ID must not be collapsed by "first wins".
        // Drop disputed IDs; keep identical repeats without duplication.
        val rows = appleRows.asSequence()
            .filter { it.storefront == country }
            .groupBy { it.scopedId }
            .values.mapNotNull { observations ->
                observations.firstOrNull()?.takeIf { first ->
                    observations.all { it == first }
                }
            }

        val links = verifiedIdentityMap(
            country, ItunesCatalogItemType.ALBUM,
            verifiedLinks.map { it.appleScopedId to it.musicBrainzReleaseGroupId },
        )
        val editionLinks = verifiedIdentityMap(
            country, ItunesCatalogItemType.ALBUM,
            verifiedReleaseLinks.map { it.appleScopedId to it.musicBrainzReleaseId },
        )
        val artists = verifiedIdentityMap(
            country, ItunesCatalogItemType.ARTIST,
            verifiedArtistLinks.map { it.appleScopedId to it.musicBrainzArtistId },
        )
        val recordings = verifiedIdentityMap(
            country, ItunesCatalogItemType.TRACK,
            verifiedRecordingLinks.map { it.appleScopedId to it.musicBrainzRecordingId },
        )

        val tracksByCollection = rows.asSequence()
            .filter { it.type == ItunesCatalogItemType.TRACK && it.collectionId != null }
            .groupBy { it.collectionId }
            .mapValues { (_, tracks) ->
                tracks.sortedWith(compareBy<ItunesCatalogItem> { it.discNumber ?: Int.MAX_VALUE }
                    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                    .thenBy { it.id })
            }

        return rows.asSequence()
            .filter { it.type == ItunesCatalogItemType.ALBUM && it.title.isNotBlank() &&
                !it.artistName.isNullOrBlank() }
            .map { album ->
                val albumTracks = tracksByCollection[album.id].orEmpty()
                ItunesEditorialRelease(
                    appleScopedId = album.scopedId,
                    appleCollectionId = album.id,
                    storefront = country,
                    title = album.title,
                    artistName = album.artistName!!,
                    artistId = album.artistId,
                    releaseDate = album.releaseDate,
                    genre = album.genre,
                    format = formatFromAppleTitle(album.title, album.trackCount),
                    artworkUrl = album.artworkUrl,
                    trackCount = album.trackCount,
                    tracks = albumTracks,
                    musicBrainzReleaseGroupId = links[album.scopedId],
                    musicBrainzArtistId = album.artistId?.let {
                        artists["itunes:$country:artist:$it"]
                    },
                    musicBrainzRecordingIds = albumTracks.mapNotNull { row ->
                        recordings[row.scopedId]?.let { mbid -> row.scopedId to mbid }
                    }.toMap(),
                    upc = album.upc,
                    musicBrainzReleaseId = editionLinks[album.scopedId],
                    storeUrl = album.storeUrl,
                )
            }
            .sortedWith(compareByDescending<ItunesEditorialRelease> { it.releaseDate.orEmpty() }
                .thenBy { it.appleCollectionId })
            .toList()
    }
}
