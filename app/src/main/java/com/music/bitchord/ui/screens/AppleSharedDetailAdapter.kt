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
internal data class AppleSharedDetail(
    val page: DetailPage,
    val metadataTracks: List<NativeMetadataTrack> = emptyList(),
    val artistHeroUrl: String? = null,
    val notice: String? = null,
    val attribution: NativeDetailAttribution? = null,
)

internal fun appleArtistAsSharedDetail(
    source: AppleEditorialArtistDetailPage,
): AppleSharedDetail? {
    val route = AppleCatalogBrowseRoute.parse(source.artist.browseId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ARTIST } ?: return null
    val releases = source.albums.filter { album ->
        val albumRoute = AppleCatalogBrowseRoute.parse(album.browseId)
        album.kind == AppleEditorialSearchKind.ALBUM &&
            albumRoute?.kind == ItunesCatalogItemType.ALBUM &&
            albumRoute.storefront == route.storefront
    }.groupBy { it.browseId }.values.mapNotNull { sameId ->
        sameId.singleOrNull() ?: sameId.firstOrNull()
            ?.takeIf { first -> sameId.all { it == first } }
    }
    val groups = listOf(
        AppleEditorialReleaseCategory.ALBUM to "Álbuns",
        AppleEditorialReleaseCategory.EP to "EPs",
        AppleEditorialReleaseCategory.SINGLE to "Singles",
        AppleEditorialReleaseCategory.UNCLASSIFIED to "Outros lançamentos",
    ).mapNotNull { (category, title) ->
        releases.filter { it.category == category }.takeIf(List<*>::isNotEmpty)
            ?.map { album ->
                ShelfItem(
                    title = album.title,
                    subtitle = album.releaseDate?.take(4).orEmpty(),
                    thumbnailUrl = appleProminentArtworkOrNull(album.artworkUri),
                    browseId = album.browseId,
                    videoId = null,
                )
            }?.let { HomeShelf(title = title, items = it) }
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
    )
}

internal fun appleAlbumAsSharedDetail(
    source: AppleEditorialAlbumDetailPage,
): AppleSharedDetail? {
    val route = AppleCatalogBrowseRoute.parse(source.album.browseId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ALBUM } ?: return null
    val year = source.album.releaseDate?.take(4)?.takeIf {
        it.length == 4 && it.all(Char::isDigit)
    }
    val credit = listOfNotNull(source.album.artistName.takeIf(String::isNotBlank), year)
        .joinToString(" • ")
    return AppleSharedDetail(
        page = DetailPage(
            browseId = source.album.browseId,
            title = source.album.title,
            subtitle = credit,
            thumbnailUrl = appleProminentArtworkOrNull(source.album.artworkUri),
            type = BrowseType.ALBUM,
            songs = UiState.Success(emptyList()),
        ),
        metadataTracks = source.tracks.filter { track ->
            val trackRoute = AppleCatalogBrowseRoute.parse(track.key)
            trackRoute?.kind == ItunesCatalogItemType.TRACK &&
                trackRoute.storefront == route.storefront
        }.map { track ->
            NativeMetadataTrack(
                key = track.key,
                title = track.title,
                credit = track.artistName,
                trackNumber = track.trackNumber,
                discNumber = track.discNumber,
            )
        },
        notice = if (source.partialTracklist)
            "A lista de faixas deste lançamento pode estar incompleta." else null,
        attribution = source.discogsAttributionText?.let {
            NativeDetailAttribution(it, source.discogsAttributionUrl,
                source.discogsNonAffiliationNotice)
        },
    )
}
