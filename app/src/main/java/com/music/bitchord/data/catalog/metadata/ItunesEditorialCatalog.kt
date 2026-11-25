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
    /** Derived commercial format under Apple's audio rules; not a source-declared or historical type. */
    val commercialAudioFormat: ItunesEditorialReleaseFormat = ItunesEditorialReleaseFormat.UNCLASSIFIED,
) {
    val displayFormat: ItunesEditorialReleaseFormat
        get() = if (format != ItunesEditorialReleaseFormat.UNCLASSIFIED) format else commercialAudioFormat
}

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
     * The Search API returns collectionType=Album for EPs and singles as well.
     * Only a suffix explicitly present in Apple's own editorial display name
     * can qualify EP/Single at this stage. Unknown is NOT silently an Album,
     * nor is a MusicBrainz primary type allowed to control this label.
     */
    fun formatFromAppleTitle(title: String): ItunesEditorialReleaseFormat {
        val normalized = title.trim().lowercase(Locale.ROOT)
        return when {
            normalized.endsWith(" - ep") || normalized.endsWith(" – ep") ||
                normalized.endsWith(" — ep") || normalized.endsWith(" (ep)") ||
                normalized.endsWith(" [ep]") -> ItunesEditorialReleaseFormat.EP
            normalized.endsWith(" - single") || normalized.endsWith(" – single") ||
                normalized.endsWith(" — single") || normalized.endsWith(" (single)") ||
                normalized.endsWith(" [single]") -> ItunesEditorialReleaseFormat.SINGLE
            else -> ItunesEditorialReleaseFormat.UNCLASSIFIED
        }
    }

    /**
     * Exact audio rows can repeat a source collection display label that the
     * collection header omitted. Require a complete, consistent edition and
     * an explicit Apple EP/Single suffix, never infer from the number of tracks.
     * The header title and all Apple identities remain unchanged.
     */
    fun formatFromVerifiedTrackLabels(
        release: ItunesEditorialRelease,
        complete: Boolean,
    ): ItunesEditorialReleaseFormat {
        val header = formatFromAppleTitle(release.title)
        if (header != ItunesEditorialReleaseFormat.UNCLASSIFIED) return header
        if (!complete || !hasExactAudioMembership(release)) return header
        val label = release.tracks.map { it.collectionTitle?.trim() }.distinct().singleOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return header
        val explicit = formatFromAppleTitle(label)
        if (explicit == ItunesEditorialReleaseFormat.UNCLASSIFIED) return header
        val base = label.replace(
            Regex("""\s+(?:[-–—]\s+(?:ep|single)|\((?:ep|single)\)|\[(?:ep|single)\])$""",
                RegexOption.IGNORE_CASE), "",
        ).trim()
        return if (base.equals(release.title.trim(), ignoreCase = true)) explicit else header
    }

    private fun hasExactAudioMembership(release: ItunesEditorialRelease): Boolean =
        hasVerifiedAudioSubset(release) && release.tracks.size == release.trackCount

    private fun hasVerifiedAudioSubset(release: ItunesEditorialRelease): Boolean {
        val count = release.trackCount ?: return false
        val observed = release.tracks.size
        if (count !in 1..500 || observed !in 1..count ||
            release.appleCollectionId <= 0L ||
            !Regex("[a-z]{2}").matches(release.storefront) ||
            release.appleScopedId != "itunes:${release.storefront}:album:${release.appleCollectionId}"
        ) return false
        return release.tracks.all {
            it.type == ItunesCatalogItemType.TRACK && it.storefront == release.storefront &&
                it.collectionId == release.appleCollectionId && it.id > 0 &&
                (it.discNumber ?: 0) > 0 && (it.trackNumber ?: 0) > 0
        } && release.tracks.map { it.id }.distinct().size == observed &&
            release.tracks.map { it.discNumber to it.trackNumber }.distinct().size == observed
    }

    /**
     * Commercial display fallback, distinct from source-declared/historical format.
     * Seven distinct verified songs exclude both of Apple's short-release categories,
     * even if additional audio is missing. Never infer a short release from a partial
     * tracklist or a collection's declared count alone.
     */
    fun commercialAudioFormat(
        release: ItunesEditorialRelease,
        cleanAudioEvidence: Boolean,
        complete: Boolean,
        verifiedExcludedNonAudio: Int = 0,
    ): ItunesEditorialReleaseFormat {
        if (!cleanAudioEvidence || !hasVerifiedAudioSubset(release))
            return ItunesEditorialReleaseFormat.UNCLASSIFIED
        if (release.tracks.size >= 7) return ItunesEditorialReleaseFormat.ALBUM
        val declared = release.trackCount ?: return ItunesEditorialReleaseFormat.UNCLASSIFIED
        if (!complete || verifiedExcludedNonAudio !in 0..declared ||
            release.tracks.size + verifiedExcludedNonAudio != declared)
            return ItunesEditorialReleaseFormat.UNCLASSIFIED
        // This temporary copy changes only the rule's denominator, never the stored source count.
        return observeAppleAudioRuleCandidate(release.copy(trackCount = release.tracks.size), true)
    }

    /**
     * Diagnostic only: expected commercial format under Apple Music Style
     * Guide 3.3/3.4 (singles and EPs). Not a source-declared release type.
     * Requires a complete exact audio edition with all durations; historical
     * classifications and explicit Apple labels may differ.
     * https://help.apple.com/itc/musicstyleguide/en.lproj/static.html
     */
    fun observeAppleAudioRuleCandidate(
        release: ItunesEditorialRelease,
        complete: Boolean,
    ): ItunesEditorialReleaseFormat {
        if (!complete || !hasExactAudioMembership(release))
            return ItunesEditorialReleaseFormat.UNCLASSIFIED
        val durations = release.tracks.mapNotNull { it.durationMs }
        if (durations.size != release.tracks.size ||
            durations.any { it !in 1L..86_400_000L })
            return ItunesEditorialReleaseFormat.UNCLASSIFIED
        val count = durations.size
        val total = durations.sum()
        return when {
            count <= 3 && durations.all { it < 600_000L } -> ItunesEditorialReleaseFormat.SINGLE
            count <= 6 && total <= 1_800_000L -> ItunesEditorialReleaseFormat.EP
            else -> ItunesEditorialReleaseFormat.ALBUM
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
                    format = formatFromAppleTitle(album.title),
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
