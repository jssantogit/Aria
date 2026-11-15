package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState

/**
 * Metadata-only bridge for the exact PR #212 visual composition.
 * This is NOT a Composable, screen, alternate route hierarchy or player source.
 * The existing PR212 DetailScreen owns every pixel and gesture.
 */
internal data class ApplePr212TrackMetadata(
    val scopedId: String,
    val title: String,
    val artist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
)

internal data class ApplePr212AlbumDetailData(
    val page: DetailPage,
    val tracks: List<ApplePr212TrackMetadata>,
)

/** Apply only CDN URLs already verified for this exact Apple product and
 * original thumbnail. A later Apple storefront snapshot cannot inherit
 * another edition's cover from an earlier asynchronous probe. */
internal fun applePr212ArtistReleasesWithVerifiedCovers(
    releases: List<ItunesEditorialRelease>,
    verified: Map<String, Pair<String, String>>,
): List<ItunesEditorialRelease> = releases.map { release ->
    val proof = verified[release.appleScopedId]
    if (proof != null && release.artworkUrl == proof.first &&
        com.music.bitchord.data.catalog.metadata.safeAppleArtwork(proof.second) != null
    ) release.copy(artworkUrl = proof.second) else release
}

internal object ApplePr212DetailProjection {
    private val storefrontPattern = Regex("[a-z]{2}")

    private fun validAlbum(release: ItunesEditorialRelease): Boolean =
        storefrontPattern.matches(release.storefront) &&
            release.appleCollectionId > 0L &&
            release.appleScopedId ==
                "itunes:${release.storefront}:album:${release.appleCollectionId}" &&
            release.title.isNotBlank() && release.artistName.isNotBlank()

    /**
     * A storefront may sell multiple unlabeled product IDs for the same
     * release. The native artist carousel is an editorial summary, not
     * an edition browser. Preserve every exact Apple ID in search, but
     * show only the first encountered product for an indistinguishable
     * title / format / year. Distinct Apple editorial version names,
     * formats and years remain independent.
     *
     * The first-wins policy deliberately preserves the ID already displayed
     * when an asynchronous pagination batch finds a redundant edition.
     */
    private fun artistEditionDisplayKey(release: ItunesEditorialRelease): String {
        val normalized = java.text.Normalizer.normalize(
            release.title, java.text.Normalizer.Form.NFKC,
        ).trim().lowercase(java.util.Locale.ROOT).replace(Regex("""\s+"""), " ")
        val year = release.releaseDate?.take(4)?.takeIf { it.length == 4 &&
            it.all(Char::isDigit) }.orEmpty()
        return "${release.artistId}|${release.format}|$year|$normalized"
    }

    /**
     * Artist ID, not a similar artist title, authorizes membership.
     * Contradictory responses about one Apple product are excluded instead of
     * making an unrelated release appear in the approved artist carousel.
     */
    fun artistPage(
        artist: ItunesCatalogItem,
        releases: List<ItunesEditorialRelease>,
    ): DetailPage? {
        if (artist.type != ItunesCatalogItemType.ARTIST || artist.id <= 0L ||
            artist.title.isBlank() || !storefrontPattern.matches(artist.storefront)
        ) return null
        val exact = releases.asSequence().filter { release ->
            validAlbum(release) && release.storefront == artist.storefront &&
                release.artistId == artist.id &&
                release.artistName.trim().equals(artist.title.trim(), ignoreCase = true)
        }.groupBy { it.appleScopedId }.values.mapNotNull { candidates ->
            candidates.firstOrNull()?.takeIf { chosen -> candidates.all { it == chosen } }
        }.distinctBy(::artistEditionDisplayKey)
        val headings = listOf(
            "Álbuns" to setOf(ItunesEditorialReleaseFormat.ALBUM),
            "Singles e EPs" to setOf(
                ItunesEditorialReleaseFormat.EP, ItunesEditorialReleaseFormat.SINGLE,
            ),
            "Outros lançamentos" to setOf(ItunesEditorialReleaseFormat.UNCLASSIFIED),
        )
        val sections = headings.mapNotNull { (heading, formats) ->
            exact.filter { it.format in formats }.takeIf { it.isNotEmpty() }
                ?.map { release ->
                    ShelfItem(
                        title = release.title,
                        subtitle = release.releaseDate?.take(4).orEmpty(),
                        thumbnailUrl = release.artworkUrl,
                        videoId = null,
                        browseId = release.appleScopedId,
                    )
                }?.let { HomeShelf(title = heading, items = it) }
        }
        return DetailPage(
            browseId = artist.scopedId,
            title = artist.title,
            subtitle = "Artista",
            thumbnailUrl = artist.artworkUrl,
            songs = UiState.Success(emptyList()),
            type = BrowseType.ARTIST,
            sections = sections,
        )
    }

    /** The existing album layout receives Apple tracks without manufacturing Song. */
    fun albumPage(release: ItunesEditorialRelease): ApplePr212AlbumDetailData? {
        if (!validAlbum(release)) return null
        val rows = release.tracks.asSequence().filter { row ->
            row.type == ItunesCatalogItemType.TRACK && row.id > 0L &&
                row.storefront == release.storefront &&
                row.collectionId == release.appleCollectionId && row.title.isNotBlank()
        }.groupBy { it.scopedId }.values.mapNotNull { sameId ->
            sameId.firstOrNull()?.takeIf { first -> sameId.all { it == first } }
        }.map { row ->
            ApplePr212TrackMetadata(
                scopedId = row.scopedId,
                title = row.title,
                artist = row.artistName,
                trackNumber = row.trackNumber,
                discNumber = row.discNumber,
            )
        }
        val year = release.releaseDate?.take(4)?.takeIf { y ->
            y.length == 4 && y.all(Char::isDigit)
        }
        val subtitle = listOfNotNull(release.artistName, year).joinToString(" • ")
        val page = DetailPage(
            browseId = release.appleScopedId,
            title = release.title,
            subtitle = subtitle,
            thumbnailUrl = release.artworkUrl,
            songs = UiState.Success(emptyList()),
            type = BrowseType.ALBUM,
        )
        return ApplePr212AlbumDetailData(page, rows)
    }
}
