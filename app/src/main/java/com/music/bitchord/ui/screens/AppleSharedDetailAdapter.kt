package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.ui.AppleCatalogBrowseRoute

/**
 * Provider data adapter, NOT another screen or rendering hierarchy.
 *
 * One Android DetailScreen paints local media, MB artist/album pages and Apple
 * releases alike. Apple IDs are retained as Apple IDs; no Song, MBID or playable
 * asset is manufactured just to pass through the existing native compositor.
 */
/** Pure navigation state; the compositor is always the same DetailScreen. */
internal enum class AppleNativeDetailKind { NONE, ARTIST, ALBUM }

internal fun appleNativeDetailKind(
    album: UiState<AppleEditorialAlbumDetailPage>?,
    artist: UiState<AppleEditorialArtistDetailPage>?,
): AppleNativeDetailKind = when {
    album != null -> AppleNativeDetailKind.ALBUM
    artist != null -> AppleNativeDetailKind.ARTIST
    else -> AppleNativeDetailKind.NONE
}

/** One classification source for both the native artist UI and its tests. */
internal fun appleNativeArtistSections(
    albums: List<AppleEditorialSearchRow>,
): List<Pair<String, List<AppleEditorialSearchRow>>> =
    listOf(
        "Álbuns" to setOf(AppleEditorialReleaseCategory.ALBUM),
        "Singles e EPs" to setOf(
            AppleEditorialReleaseCategory.SINGLE, AppleEditorialReleaseCategory.EP,
        ),
        "Outros lançamentos" to setOf(AppleEditorialReleaseCategory.UNCLASSIFIED),
    ).mapNotNull { (label, categories) ->
        albums.filter { it.category in categories }.takeIf { it.isNotEmpty() }
            ?.sortedWith(compareByDescending<AppleEditorialSearchRow> {
                it.releaseDate.orEmpty()
            }.thenBy { it.browseId })
            ?.let { label to it }
    }

internal data class AppleSharedDetail(
    val page: DetailPage,
    val metadataTracks: List<NativeMetadataTrack> = emptyList(),
    val artistTracks: List<AppleEditorialTrackSearchRow> = emptyList(),
    val verifiedArtistMbid: String? = null,
    /** Exact source-owned album credit route, never an MBID. */
    val albumArtistBrowseId: String? = null,
    val albumArtistDisplayName: String? = null,
    val artistHeroUrl: String? = null,
    val featuredRelease: ShelfItem? = null,
    val notice: String? = null,
    val attribution: NativeDetailAttribution? = null,
)

internal fun appleArtistAsSharedDetail(
    source: AppleEditorialArtistDetailPage,
): AppleSharedDetail? {
    val route = AppleCatalogBrowseRoute.parse(source.artist.browseId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ARTIST } ?: return null
    if (appleNativeDetailRouteKey(AppleNativeDetailKind.ARTIST, source.artist, null) == null)
        return null
    val releases = appleNativeArtistAlbumEntries(source)
    // Native Apple Music-style sections. EP and Single are one rail, while
    // "live", "compilation" and "featured on" require independent evidence:
    // names and artwork cannot prove such a relationship or release class.
    val groups = appleNativeArtistSections(releases).map { (title, albums) ->
        HomeShelf(title = title, items = albums.map { album ->
            ShelfItem(
                title = album.title,
                subtitle = album.releaseDate?.take(4).orEmpty(),
                thumbnailUrl = appleProminentArtworkOrNull(album.artworkUri),
                browseId = album.browseId,
                videoId = null,
            )
        })
    }
    return AppleSharedDetail(
        page = DetailPage(
            browseId = source.artist.browseId,
            title = source.artist.title,
            subtitle = "Artista",
            thumbnailUrl = source.artist.artworkUri,
            type = BrowseType.ARTIST,
            songs = UiState.Success(emptyList()),
            sections = groups,
        ),
        artistHeroUrl = source.artist.artworkUri,
        featuredRelease = releases.asSequence()
            .filter { row ->
                row.releaseDate?.take(10)?.let { date ->
                    runCatching { java.time.LocalDate.parse(date) }.isSuccess
                } == true
            }
            .sortedWith(compareByDescending<AppleEditorialSearchRow> {
                it.releaseDate.orEmpty()
            }.thenBy { it.browseId })
            .firstOrNull()?.let { newest ->
                ShelfItem(
                    title = newest.title,
                    subtitle = listOfNotNull(
                        when (newest.category) {
                            AppleEditorialReleaseCategory.SINGLE -> "Single"
                            AppleEditorialReleaseCategory.EP -> "EP"
                            AppleEditorialReleaseCategory.ALBUM -> "Álbum"
                            AppleEditorialReleaseCategory.UNCLASSIFIED -> null
                        },
                        newest.releaseDate?.take(4),
                    ).joinToString(" · "),
                    thumbnailUrl = appleProminentArtworkOrNull(newest.artworkUri),
                    browseId = newest.browseId,
                    videoId = null,
                )
            },
        verifiedArtistMbid = source.verifiedMusicBrainzArtistMbid?.takeIf {
            Regex("^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$").matches(it)
        },
        artistTracks = source.tracks.filter { track ->
            val trackRoute = AppleCatalogBrowseRoute.parse(track.key)
            trackRoute?.kind == ItunesCatalogItemType.TRACK &&
                trackRoute.storefront == route.storefront &&
                track.artistName.isNotBlank() && track.title.isNotBlank() &&
                (track.artistBrowseId == null || track.artistBrowseId == source.artist.browseId)
        }.groupBy { it.key }.values.mapNotNull { observations ->
            observations.distinct().singleOrNull()
        }.take(16),
    )
}

internal fun appleAlbumAsSharedDetail(
    source: AppleEditorialAlbumDetailPage,
): AppleSharedDetail? {
    val route = AppleCatalogBrowseRoute.parse(source.album.browseId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ALBUM } ?: return null
    if (appleNativeDetailRouteKey(AppleNativeDetailKind.ALBUM, null, source.album) == null)
        return null
    val year = source.album.releaseDate?.take(4)?.takeIf {
        it.length == 4 && it.all(Char::isDigit)
    }
    val credit = listOfNotNull(source.album.artistName.takeIf(String::isNotBlank), year)
        .joinToString(" • ")
    val sourceArtistRoute = AppleCatalogBrowseRoute.parse(source.album.artistBrowseId)
        ?.takeIf {
            it.kind == ItunesCatalogItemType.ARTIST &&
                it.storefront == route.storefront
        }
    val admittedTracks = source.tracks.filter { track ->
        val trackRoute = AppleCatalogBrowseRoute.parse(track.key)
        trackRoute?.kind == ItunesCatalogItemType.TRACK &&
            trackRoute.storefront == route.storefront && track.title.isNotBlank()
    }.groupBy { it.key }.values.mapNotNull { observations ->
        observations.distinct().singleOrNull()
    }
    val otherReleases = appleNativeRelatedAlbumEntries(source)
        .asSequence()
        .map { candidate ->
            ShelfItem(
                title = candidate.title,
                subtitle = candidate.releaseDate?.take(4).orEmpty(),
                thumbnailUrl = appleProminentArtworkOrNull(candidate.artworkUri),
                videoId = null,
                browseId = candidate.browseId,
            )
        }.toList()
    return AppleSharedDetail(
        albumArtistBrowseId = sourceArtistRoute?.let {
            "itunes:${it.storefront}:artist:${it.id}"
        },
        albumArtistDisplayName = source.album.artistName.takeIf {
            sourceArtistRoute != null && it.isNotBlank()
        },
        page = DetailPage(
            browseId = source.album.browseId,
            title = source.album.title,
            subtitle = credit,
            thumbnailUrl = appleProminentArtworkOrNull(source.album.artworkUri),
            type = BrowseType.ALBUM,
            songs = UiState.Success(emptyList()),
            sections = if (otherReleases.isNotEmpty()) listOf(
                HomeShelf("Mais de ${source.album.artistName}", otherReleases),
            ) else emptyList(),
        ),
        metadataTracks = admittedTracks.map { track ->
            NativeMetadataTrack(
                key = track.key,
                title = track.title,
                credit = track.artistName,
                trackNumber = track.trackNumber,
                discNumber = track.discNumber,
                durationMs = track.durationMs?.takeIf { it >= 0L },
            )
        },
        notice = if (source.partialTracklist || admittedTracks.size != source.tracks.size)
            "A lista de faixas deste lançamento pode estar incompleta." else null,
        attribution = source.discogsAttributionText?.let {
            NativeDetailAttribution(it, source.discogsAttributionUrl,
                source.discogsNonAffiliationNotice)
        },
    )
}
