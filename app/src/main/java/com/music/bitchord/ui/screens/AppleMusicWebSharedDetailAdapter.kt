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

internal fun appleMusicWebVerifiedStoreUrl(item: AppleMusicWebItem): String? =
    appleMusicWebVerifiedRouteUrl(item.storeUrl, item.scopedId)

internal fun appleMusicWebVerifiedRouteUrl(url: String?, browseId: String?): String? {
    val songRoute = browseId?.let { Regex("^apple_music_web:([a-z]{2}):song:([1-9][0-9]{0,11})$").matchEntire(it) }
    val route = AppleMusicWebNativeRoute.parse(browseId)
    if (route == null && songRoute == null) return null
    val link = url?.takeIf { it.length <= 2048 } ?: return null
    val uri = runCatching { URI(link) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host != "music.apple.com" || uri.userInfo != null ||
        uri.port != -1 || uri.fragment != null) return null
    val fields = uri.path.orEmpty().trim('/').split('/')
    if (songRoute != null) {
        val market = songRoute.groupValues[1]
        val id = songRoute.groupValues[2]
        return link.takeIf { fields.size >= 3 && fields.first() == market &&
            ((fields[1] == "song" && fields.last() == id && uri.rawQuery == null) ||
                (fields[1] == "album" && fields.last().matches(Regex("[1-9][0-9]{0,11}")) &&
                    uri.rawQuery == "i=$id")) }
    }
    val detail = route ?: return null
    return link.takeIf { uri.rawQuery == null && fields.size >= 3 && fields.first() == detail.storefront &&
        fields[1] == detail.kind.identityType && fields.last() == detail.id }
}

private fun AppleMusicWebItem.card(kind: AppleMusicWebKind, market: String): ShelfItem? {
    val route = validRoute(kind,market) ?: return null
    return ShelfItem(title,if (kind == AppleMusicWebKind.ALBUM) year() else "",
        cover(),null,route.browseId,appleStoreUrl=appleMusicWebVerifiedStoreUrl(this))
}

private fun tracks(
    items: List<AppleMusicWebItem>,
    market: String,
    artistReleases: List<AppleMusicWebItem> = emptyList(),
): List<NativeMetadataTrack> =
    items.filter {
        it.kind == AppleMusicWebKind.SONG && it.storefront == market &&
            it.id.matches(Regex("[1-9][0-9]{0,11}")) && it.title.isNotBlank()
    }.groupBy { it.scopedId }.values.mapNotNull { group ->
        val song = group.first()
        if (group.any { it != song }) return@mapNotNull null
        val album = song.albumId?.let { albumId ->
            artistReleases.filter { it.kind == AppleMusicWebKind.ALBUM &&
                it.storefront == market && it.id == albumId &&
                it.validRoute(AppleMusicWebKind.ALBUM,market) != null }.distinct().singleOrNull()
        }
        val suppliedTitle = song.albumName?.trim()?.takeIf { it.isNotBlank() }
        val compatible = album?.takeIf { suppliedTitle == null || it.title == suppliedTitle }
        val releaseCaption = (suppliedTitle ?: compatible?.title)?.let { title ->
            listOf(title,compatible?.year().orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
        }
        NativeMetadataTrack(key=song.scopedId,title=song.title,credit=song.artistName,
            trackNumber=song.trackNumber?.takeIf { it > 0 },discNumber=song.discNumber?.takeIf { it > 0 },
            durationMs=song.durationMillis?.takeIf { it > 0 },
            artworkUri=song.cover(),releaseCaption=releaseCaption,
            sourceStoreUrl=appleMusicWebVerifiedStoreUrl(song))
    }

/** The supplemental pages were obtained from exact artist-view endpoints,
 * under the same editorial lease as the requested detail. Keep their
 * provenance tied to the artist route; no title-only crosswalk.
 */
internal data class AmsArtistShelfSnapshot(
    val artistBrowseId: String,
    val byView: Map<AppleMusicWebArtistShelf, List<AppleMusicWebItem>>,
    val incomplete: Boolean = false,
)

private fun amsShelfCards(
    items: List<AppleMusicWebItem>, market: String,
): List<ShelfItem> = items.groupBy { it.scopedId }.values.mapNotNull { group ->
    val first = group.first()
    if (group.any { it != first }) null else first.card(AppleMusicWebKind.ALBUM,market)
}

internal fun appleMusicWebAsSharedDetail(
    requestedBrowseId: String,
    source: AppleMusicWebNativeDetail,
    incomplete: Boolean = false,
    supplemental: AmsArtistShelfSnapshot? = null,
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
            val evidence = supplemental?.takeIf { artist != null && it.artistBrowseId == artist.scopedId }
            val related = evidence?.byView.orEmpty().filterKeys {
                it == AppleMusicWebArtistShelf.STUDIO_ALBUMS ||
                    it == AppleMusicWebArtistShelf.SINGLES_AND_EPS
            }.values.flatten().filter {
                it.scopedId != route.browseId &&
                    it.artistName?.equals(credit,ignoreCase=true) == true &&
                    (it.artistId == null || it.artistId == artist?.id)
            }
            val cards = amsShelfCards(related,market).take(24)
            val notices = listOfNotNull(
                "A lista de faixas deste lançamento pode estar incompleta."
                    .takeIf { incomplete || record.possiblyTruncated },
                "Alguns lançamentos relacionados podem estar incompletos."
                    .takeIf { evidence?.incomplete == true },
            )
            AppleSharedDetail(
                page=DetailPage(route.browseId,identity.title,
                    listOf(credit,identity.year()).filter(String::isNotBlank).joinToString(" • "),
                    identity.cover(1024),UiState.Success(emptyList()),BrowseType.ALBUM,
                    sections=if (cards.isEmpty()) emptyList() else listOf(HomeShelf("Mais do artista",cards))),
                metadataTracks=tracks(record.tracks,market),
                albumArtistBrowseId=artist?.scopedId,
                albumArtistDisplayName=artist?.title,
                notice=notices.joinToString(" ").takeIf { it.isNotBlank() },
                sourceStoreUrl=appleMusicWebVerifiedStoreUrl(identity),
            )
        }
        is AppleMusicWebNativeDetail.Artist -> {
            if (route.kind != AppleMusicWebKind.ARTIST) return null
            val record = source.value
            // The source's exact full-albums and singles views determine
            // the editorial group. Conflicting memberships stay unresolved.
            val evidence = supplemental?.takeIf { it.artistBrowseId == route.browseId }
            val byCategory = linkedMapOf(
                "Álbuns" to amsShelfCards(record.albums,market),
                "Singles e EPs" to amsShelfCards(record.singlesAndEps,market),
                "Álbuns ao vivo" to amsShelfCards(evidence?.byView?.get(AppleMusicWebArtistShelf.LIVE_ALBUMS).orEmpty(),market),
                "Coletâneas" to amsShelfCards(evidence?.byView?.get(AppleMusicWebArtistShelf.COMPILATIONS).orEmpty(),market),
            )
            val conflicts = byCategory.values.flatten().groupBy { it.browseId }
                .filterValues { it.size > 1 }.keys
            val ambiguous = byCategory.values.flatten().filter { it.browseId in conflicts }
                .groupBy { it.browseId }.values.mapNotNull { group ->
                    group.first().takeIf { first -> group.all { it == first } }
                }
            val shelves = buildList {
                fun addShelf(title: String, cards: List<ShelfItem>) {
                    if (cards.isNotEmpty()) add(HomeShelf(title,cards))
                }
                byCategory.forEach { (title,cards) ->
                    addShelf(title,cards.filterNot { it.browseId in conflicts })
                }
                addShelf("Outros lançamentos",ambiguous)
                addShelf("Com participação em",amsShelfCards(
                    evidence?.byView?.get(AppleMusicWebArtistShelf.APPEARS_ON).orEmpty(),market)
                    .filterNot { card -> byCategory.values.any { rows -> rows.any { it.browseId == card.browseId } } })
                addShelf("Álbuns essenciais",record.featuredAlbums.mapNotNull {
                    it.card(AppleMusicWebKind.ALBUM,market)
                }.distinctBy { it.browseId })
                addShelf("Artistas semelhantes",record.similarArtists.filterNot { it.scopedId == route.browseId }
                    .mapNotNull { it.card(AppleMusicWebKind.ARTIST,market) }.distinctBy { it.browseId })
            }
            AppleSharedDetail(
                page=DetailPage(route.browseId,identity.title,"Artista",identity.cover(1024),
                    UiState.Success(emptyList()),BrowseType.ARTIST,sections=shelves),
                metadataTracks=tracks(record.topSongs,market,record.albums + record.singlesAndEps +
                    record.featuredAlbums + listOfNotNull(record.latestRelease)).take(20),
                sourceArtistTracksRanked=true,
                artistHeroUrl=identity.cover(1024),
                featuredRelease=record.latestRelease?.card(AppleMusicWebKind.ALBUM,market),
                notice=if (incomplete || record.unresolvedShelves.isNotEmpty() || evidence?.incomplete == true)
                    "Algumas seções deste artista podem estar incompletas." else null,
                sourceStoreUrl=appleMusicWebVerifiedStoreUrl(identity),
            )
        }
    }
}
