package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.catalog.metadata.safeAppleArtwork
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.AppleMusicWebNativeDetail
import com.music.bitchord.ui.AppleMusicWebNativeRoute
import java.net.URI
import java.time.LocalDate

/** Exact public metadata, rendered by the existing DetailScreen.
 * No iTunes aliases, MBIDs, fake Songs, or new visual hierarchy.
 */
private fun AppleMusicWebItem.validRoute(kind: AppleMusicWebKind, market: String): AppleMusicWebNativeRoute? =
    AppleMusicWebNativeRoute.parse(scopedId)?.takeIf {
        it.kind == kind && it.storefront == market && title.isNotBlank()
    }

private fun AppleMusicWebItem.cover(size: Int = 600): String? =
    safeAppleArtwork(artworkUrl?.replace("{w}",size.toString())
        ?.replace("{h}",size.toString())?.replace("{f}","jpg"))

private fun AppleMusicWebItem.year(): String = releaseDate?.take(10)?.let {
    runCatching { LocalDate.parse(it).year.toString() }.getOrNull()
}.orEmpty()

internal fun appleMusicWebVerifiedStoreUrl(item: AppleMusicWebItem): String? {
    val route = AppleMusicWebNativeRoute.parse(item.scopedId) ?: return null
    val link = item.storeUrl?.takeIf { it.length <= 2048 } ?: return null
    val uri = runCatching { URI(link) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host != "music.apple.com" || uri.userInfo != null ||
        uri.port != -1 || uri.rawQuery != null || uri.fragment != null) return null
    val fields = uri.path.orEmpty().trim('/').split('/')
    return link.takeIf { fields.size >= 3 && fields.first() == route.storefront &&
        fields[1] == route.kind.identityType && fields.last() == route.id }
}

private fun AppleMusicWebItem.card(kind: AppleMusicWebKind, market: String): ShelfItem? {
    val route = validRoute(kind,market) ?: return null
    return ShelfItem(title,if (kind == AppleMusicWebKind.ALBUM) year() else "",
        cover(),null,route.browseId,appleStoreUrl=appleMusicWebVerifiedStoreUrl(this))
}

private fun tracks(items: List<AppleMusicWebItem>, market: String): List<NativeMetadataTrack> =
    items.filter {
        it.kind == AppleMusicWebKind.SONG && it.storefront == market &&
            it.id.matches(Regex("[1-9][0-9]{0,11}")) && it.title.isNotBlank()
    }.groupBy { it.scopedId }.values.mapNotNull { group ->
        val song = group.first()
        if (group.any { it != song }) return@mapNotNull null
        NativeMetadataTrack(key=song.scopedId,title=song.title,credit=song.artistName,
            trackNumber=song.trackNumber?.takeIf { it > 0 },discNumber=song.discNumber?.takeIf { it > 0 },
            durationMs=song.durationMillis?.takeIf { it > 0 })
    }

internal fun appleMusicWebAsSharedDetail(
    requestedBrowseId: String,
    source: AppleMusicWebNativeDetail,
    incomplete: Boolean = false,
): AppleSharedDetail? {
    val route = AppleMusicWebNativeRoute.parse(requestedBrowseId) ?: return null
    val identity = when (source) {
        is AppleMusicWebNativeDetail.Album -> source.value.identity
        is AppleMusicWebNativeDetail.Artist -> source.value.identity
    }
    if (identity.validRoute(route.kind,route.storefront) != route) return null
    val market = route.storefront
    return when (source) {
        is AppleMusicWebNativeDetail.Album -> {
            if (route.kind != AppleMusicWebKind.ALBUM) return null
            val record = source.value
            val artists = record.artists.filter {
                it.validRoute(AppleMusicWebKind.ARTIST,market) != null
            }.distinctBy { it.scopedId }
            // A single positive relationship plus exact display credit is
            // required before offering album → artist navigation.
            val credit = identity.artistName.orEmpty()
            val artist = artists.singleOrNull()?.takeIf {
                credit.isNotBlank() && it.title.equals(credit,ignoreCase=true)
            }
            AppleSharedDetail(
                page=DetailPage(route.browseId,identity.title,
                    listOf(credit,identity.year()).filter(String::isNotBlank).joinToString(" • "),
                    identity.cover(1024),UiState.Success(emptyList()),BrowseType.ALBUM),
                metadataTracks=tracks(record.tracks,market),
                albumArtistBrowseId=artist?.scopedId,
                albumArtistDisplayName=artist?.title,
                notice=if (incomplete || record.possiblyTruncated)
                    "A lista de faixas deste lançamento pode estar incompleta." else null,
            )
        }
        is AppleMusicWebNativeDetail.Artist -> {
            if (route.kind != AppleMusicWebKind.ARTIST) return null
            val record = source.value
            // The source's exact full-albums and singles views determine
            // the editorial group. Conflicting memberships stay unresolved.
            val albums = record.albums.mapNotNull { it.card(AppleMusicWebKind.ALBUM,market) }.distinctBy { it.browseId }
            val shorts = record.singlesAndEps.mapNotNull { it.card(AppleMusicWebKind.ALBUM,market) }.distinctBy { it.browseId }
            val conflicts = albums.map { it.browseId }.toSet().intersect(shorts.map { it.browseId }.toSet())
            val shelves = buildList {
                fun addShelf(title: String, cards: List<ShelfItem>) {
                    if (cards.isNotEmpty()) add(HomeShelf(title,cards))
                }
                addShelf("Álbuns",albums.filterNot { it.browseId in conflicts })
                addShelf("Singles e EPs",shorts.filterNot { it.browseId in conflicts })
                addShelf("Outros lançamentos",albums.filter { it.browseId in conflicts })
                addShelf("Álbuns essenciais",record.featuredAlbums.mapNotNull {
                    it.card(AppleMusicWebKind.ALBUM,market)
                }.distinctBy { it.browseId })
                addShelf("Artistas semelhantes",record.similarArtists.filterNot { it.scopedId == route.browseId }
                    .mapNotNull { it.card(AppleMusicWebKind.ARTIST,market) }.distinctBy { it.browseId })
            }
            AppleSharedDetail(
                page=DetailPage(route.browseId,identity.title,"Artista",identity.cover(1024),
                    UiState.Success(emptyList()),BrowseType.ARTIST,sections=shelves),
                metadataTracks=tracks(record.topSongs,market).take(16),
                artistHeroUrl=identity.cover(1024),
                notice=if (incomplete || record.unresolvedShelves.isNotEmpty())
                    "Algumas seções deste artista podem estar incompletas." else null,
            )
        }
    }
}
