package com.music.bitchord.ui

import android.content.Context
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.*
import com.music.bitchord.data.catalog.metadata.safeAppleArtwork
import com.music.bitchord.ui.screens.appleMusicWebVerifiedStoreUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native AMS identity, never an iTunes ID or a MediaAsset. */
internal data class AppleMusicWebNativeRoute(
    val storefront: String, val kind: AppleMusicWebKind, val id: String,
) {
    val browseId: String get() = "apple_music_web:" + storefront + ":" + kind.identityType + ":" + id
    companion object {
        fun parse(browseId: String?): AppleMusicWebNativeRoute? {
            val fields = browseId?.split(':') ?: return null
            if (fields.size != 4 || fields[0] != "apple_music_web" ||
                !fields[1].matches(Regex("[a-z]{2}"))) return null
            val kind = when (fields[2]) {
                "album" -> AppleMusicWebKind.ALBUM
                "artist" -> AppleMusicWebKind.ARTIST
                "playlist" -> AppleMusicWebKind.PLAYLIST
                else -> return null
            }
            val valid = if (kind == AppleMusicWebKind.PLAYLIST)
                fields[3].matches(Regex("pl\\.[A-Za-z0-9-]{6,120}"))
            else fields[3].matches(Regex("[1-9][0-9]{0,11}"))
            if (!valid) return null
            return AppleMusicWebNativeRoute(fields[1],kind,fields[3])
        }
    }
}

internal sealed interface AppleMusicWebNativeDetail {
    data class Album(val value: AppleMusicWebAlbum) : AppleMusicWebNativeDetail
    data class Artist(val value: AppleMusicWebArtist) : AppleMusicWebNativeDetail
    data class Playlist(val value: AppleMusicWebPlaylist) : AppleMusicWebNativeDetail
}

internal fun amsSongStorefront(key: String?): String? =
    key?.let { Regex("^apple_music_web:([a-z]{2}):song:([1-9][0-9]{0,11})$").matchEntire(it) }
        ?.groupValues?.get(1)

internal fun appleMusicWebGenreSupported(tag: String): Boolean = amsSupportsEditorialTag(tag)

/** Only a verified song URL containing the exact song query proves the
 * album link. Do not guess from names, genre labels or track artwork. */
private fun amsStyleSongUrlAlbumId(song: AppleMusicWebItem, market: String): String? {
    if (song.kind != AppleMusicWebKind.SONG || song.storefront != market) return null
    val uri = appleMusicWebVerifiedStoreUrl(song)?.let { java.net.URI(it) } ?: return null
    val fields = uri.path.trim('/').split('/')
    if (fields.getOrNull(1) != "album") return null
    return fields.last()
}

internal fun amsStyleSongAlbumId(song: AppleMusicWebItem, market: String): String? =
    amsStyleSongUrlAlbumId(song,market)?.takeIf { song.albumId == null || song.albumId == it }

/** Keep observations until the song-to-album linkage is validated. A
 * second response may supply missing fields but cannot contradict identity. */
internal fun amsStyleVerifiedSongRows(songs: List<AppleMusicWebItem>, market: String): List<AppleMusicWebItem> =
    songs.filter { it.kind == AppleMusicWebKind.SONG && it.storefront == market &&
        amsSongStorefront(it.scopedId) == market }.groupBy { it.scopedId }.values.flatMap { rows ->
        // Keep URL evidence even when an explicit albumId contradicts it.
        // Filtering that row out first would silently hide the contradiction.
        val album = rows.mapNotNull { amsStyleSongUrlAlbumId(it,market) }.distinct()
            .singleOrNull() ?: return@flatMap emptyList()
        if (rows.any { !it.albumId.isNullOrBlank() && it.albumId != album } ||
            rows.mapNotNull { it.isrc?.trim()?.takeIf(String::isNotEmpty) }.distinct().size > 1 ||
            rows.mapNotNull { it.title.trim().takeIf(String::isNotEmpty) }.distinct().size > 1 ||
            rows.mapNotNull { it.artistName?.trim()?.takeIf(String::isNotEmpty) }.distinct().size > 1)
            return@flatMap emptyList()
        rows.filter { amsStyleSongAlbumId(it,market) == album }
    }

/** A matching track proves that its containing album has a matching track,
 * not that every track on the album belongs to the style. */
internal fun amsStyleTrackAlbumIds(songs: List<AppleMusicWebItem>, market: String, tag: String): List<String> =
    if (tag !in amsEditorialStyleParents) emptyList() else amsStyleVerifiedSongRows(songs,market).asSequence()
        .filter { it.genreNames.any { label -> appleMusicWebGenreNameMatches(label,tag) } }
        .mapNotNull { amsStyleSongAlbumId(it,market) }.distinct().toList()

/** Search ranking nominates IDs, but only an exact album-owned label or
 * an exact linked track can authorize membership of the style shelf. */
internal fun amsStyleAlbumLabelIds(albums: List<AppleMusicWebItem>, market: String, tag: String): List<String> =
    if (tag !in amsEditorialStyleParents) emptyList() else albums.asSequence()
        .filter { it.kind == AppleMusicWebKind.ALBUM && it.storefront == market &&
            AppleMusicWebNativeRoute.parse(it.scopedId) != null &&
            it.genreNames.any { label -> appleMusicWebGenreNameMatches(label,tag) } }
        .map { it.id }.distinct().toList()

internal fun amsStyleSupplementalAlbumIds(
    songs: List<AppleMusicWebItem>, albumSearch: List<AppleMusicWebItem>,
    market: String, alreadyRequested: Set<String>,
): List<String> {
    val fromSearch = albumSearch.asSequence().filter { it.kind == AppleMusicWebKind.ALBUM &&
        it.storefront == market && AppleMusicWebNativeRoute.parse(it.scopedId) != null }.map { it.id }
    val fromSongs = amsStyleVerifiedSongRows(songs,market).asSequence().mapNotNull { amsStyleSongAlbumId(it,market) }
    // Alternate sources so one ranked result list cannot starve the other.
    val a = fromSearch.distinct().filterNot { it in alreadyRequested }.toList()
    val b = fromSongs.distinct().filterNot { it in alreadyRequested }.toList()
    return (0 until maxOf(a.size,b.size)).asSequence()
        .flatMap { index -> sequenceOf(a.getOrNull(index),b.getOrNull(index)).filterNotNull() }
        .distinct().take(50).toList()
}

internal const val AMS_STYLE_METADATA_TARGET = 12

/** Discovery budget is measured on admitted albums, not raw search hits. */
internal fun amsStyleNeedsTrackDiscovery(
    songs: List<AppleMusicWebItem>, market: String, tag: String, albums: List<AppleMusicWebItem>,
): Boolean = (amsStyleTrackAlbumShelf(amsStyleTrackAlbumIds(songs,market,tag),market,albums)
    ?.items?.size ?: 0) < AMS_STYLE_METADATA_TARGET

internal fun amsStyleTrackAlbumShelf(ids: List<String>, market: String, albums: List<AppleMusicWebItem>): HomeShelf? {
    val byId = albums.asSequence().filter {
        it.kind == AppleMusicWebKind.ALBUM && it.storefront == market && it.title.isNotBlank()
    }.groupBy { it.id }
    val cards = ids.distinct().mapNotNull { id ->
        val observations = byId[id] ?: return@mapNotNull null
        val album = observations.first()
        // Search and exact metadata batches can legitimately differ in optional
        // artwork/genre fields. Contradictory identity facts cannot be hidden by
        // first-wins deduplication.
        if (observations.map { it.title.trim() }.distinct().size != 1 ||
            observations.mapNotNull { it.artistName?.trim()?.takeIf(String::isNotEmpty) }.distinct().size > 1 ||
            observations.mapNotNull { it.artistId?.takeIf(String::isNotBlank) }.distinct().size > 1 ||
            observations.mapNotNull { it.upc?.takeIf(String::isNotBlank) }.distinct().size > 1)
            return@mapNotNull null
        val route = AppleMusicWebNativeRoute.parse(album.scopedId) ?: return@mapNotNull null
        // Reuse available optional fields only after all identity observations agree.
        ShelfItem(title=album.title,subtitle=observations.firstNotNullOfOrNull {
                it.artistName?.takeIf(String::isNotBlank) }.orEmpty(),
            thumbnailUrl=observations.firstNotNullOfOrNull { appleMusicWebArtworkUrl(it.artworkUrl) },videoId=null,
            browseId=route.browseId,appleStoreUrl=observations.firstNotNullOfOrNull { appleMusicWebVerifiedStoreUrl(it) })
    }
    return cards.take(24).takeIf { it.isNotEmpty() }?.let { HomeShelf("Álbuns do estilo",it) }
}

/** A playlist is already bounded by the catalog's track paging policy.
 * Allow up to four metadata batches for a preview, never consume visible
 * slots with missing or contradictory album metadata. */
internal fun amsStylePlaylistAlbumIds(
    playlist: AppleMusicWebPlaylist, market: String, existing: Set<String> = emptySet(),
): List<String> {
    if (playlist.identity.kind != AppleMusicWebKind.PLAYLIST || playlist.identity.storefront != market)
        return emptyList()
    return amsStyleVerifiedSongRows(playlist.tracks,market).asSequence().mapNotNull { amsStyleSongAlbumId(it,market) }.distinct()
        .filter { "apple_music_web:$market:album:$it" !in existing }.take(100).toList()
}

internal fun amsStylePlaylistAlbumShelf(
    playlist: AppleMusicWebPlaylist, market: String, albums: List<AppleMusicWebItem>,
): HomeShelf? =
    amsStyleTrackAlbumShelf(amsStylePlaylistAlbumIds(playlist,market),market,albums)
        ?.copy(title="Álbuns das playlists")

internal data class AmsStyleMetadataMerge(
    val shelves: List<HomeShelf>,
    val rejectedIds: Set<String>,
) {
    /** Rejected identities remain excluded when another producer runs. */
    val excludedIds: Set<String> get() = rejectedIds +
        shelves.flatMap { it.items }.mapNotNull { it.browseId }
}

/** Preserve the strongest available style evidence. A curated release must
 * not suppress the same album's independently proven metadata membership.
 * Contradictory album identity across sources is not resolved by ordering. */
internal fun amsMergeStyleMetadataShelf(
    metadata: HomeShelf?, curated: List<HomeShelf>, market: String,
): AmsStyleMetadataMerge {
    val candidates = metadata?.items.orEmpty().filter { card ->
        val route = AppleMusicWebNativeRoute.parse(card.browseId)
        route?.kind == AppleMusicWebKind.ALBUM && route.storefront == market &&
            card.videoId == null && card.title.isNotBlank()
    }.groupBy { it.browseId }
    val existing = curated.flatMap { it.items }.groupBy { it.browseId }
    val conflicted = hashSetOf<String>()
    val verified = candidates.values.mapNotNull { rows ->
        val first = rows.first()
        val observations = rows + existing[first.browseId].orEmpty()
        if (observations.map { it.title.trim() }.distinct().size > 1 ||
            observations.mapNotNull { it.subtitle.trim().takeIf(String::isNotEmpty) }.distinct().size > 1) {
            first.browseId?.let { conflicted += it }
            return@mapNotNull null
        }
        first.copy(
            subtitle=observations.firstNotNullOfOrNull { it.subtitle.takeIf(String::isNotBlank) }.orEmpty(),
            thumbnailUrl=observations.firstNotNullOfOrNull { appleMusicWebArtworkUrl(it.thumbnailUrl) },
            appleStoreUrl=observations.firstNotNullOfOrNull {
                com.music.bitchord.ui.screens.appleMusicWebVerifiedRouteUrl(it.appleStoreUrl,first.browseId)
            },
        )
    }
    val occupied = verified.mapTo(hashSetOf()) { it.browseId } + conflicted
    val remainder = curated.map { shelf ->
        shelf.copy(items=shelf.items.filterNot { it.browseId in occupied })
    }.filter { it.items.isNotEmpty() }
    return AmsStyleMetadataMerge(
        listOfNotNull(verified.takeIf { it.isNotEmpty() }?.let { HomeShelf("Álbuns do estilo",it) }) + remainder,
        conflicted.toSet(),
    )
}

/** Prioritize explicit style titles before generic room membership. Keep
 * stable room order within each evidence level and bound detail requests. */
internal fun amsStyleCuratorPlaylistCandidates(cards: List<ShelfItem>, tag: String): List<ShelfItem> =
    cards.groupBy { it.browseId }.values.mapNotNull { group ->
        group.first().takeIf { first -> group.all { it == first } &&
            AppleMusicWebNativeRoute.parse(first.browseId)?.kind == AppleMusicWebKind.PLAYLIST }
    }.sortedByDescending { amsExactStylePlaylistTitle(it.title,tag) }.take(2)

/** Identity and semantic membership are separate: a verified dedicated room
 * can contain an editorial playlist not suitable for the style album shelf. */
internal fun amsStyleCuratorPlaylistIdentityMatches(
    identity: AppleMusicWebItem, candidateId: String, market: String, roomCard: ShelfItem?,
): Boolean =
    identity.kind == AppleMusicWebKind.PLAYLIST && identity.id == candidateId &&
        identity.storefront == market && identity.playlistType == "editorial" &&
        appleMusicWebVerifiedStoreUrl(identity) != null && identity.title.isNotBlank() &&
        (roomCard == null || (roomCard.browseId == identity.scopedId &&
            roomCard.title.trim() == identity.title.trim() &&
            (roomCard.subtitle.isBlank() || roomCard.subtitle.trim() == identity.artistName.orEmpty().trim())))

/** Do not hydrate albums from a replaced or foreign list. */
internal fun amsStyleCuratorPlaylistDetailMatches(
    identity: AppleMusicWebItem, candidateId: String, market: String, tag: String,
    roomCard: ShelfItem?,
): Boolean {
    if (!amsStyleCuratorPlaylistIdentityMatches(identity,candidateId,market,roomCard)) return false
    if (roomCard == null) return amsVerifiedStylePlaylistReference(identity,tag,market)
    return amsExactStylePlaylistTitle(identity.title,tag) ||
        identity.genreNames.any { appleMusicWebGenreNameMatches(it,tag) }
}

/** Existing HomeShelf model; no new screen and no fake playable IDs. */
internal fun projectAppleMusicWebGenreLanding(
    landing: AppleMusicWebCuratorLanding, storefront: String, tag: String,
    releasesHeading: String = "Lançamentos",
): List<HomeShelf> {
    if (landing.tag != tag || !appleMusicWebGenreSupported(tag) ||
        !appleMusicWebCuratorMatches(landing.curator.identity, storefront, tag)) return emptyList()
    val seen = hashSetOf<String>()
    val items = landing.albums.mapNotNull { row ->
        val item = row.item
        val route = AppleMusicWebNativeRoute.parse(item.scopedId) ?: return@mapNotNull null
        if (route.kind != AppleMusicWebKind.ALBUM || route.storefront != storefront ||
            row.room !in landing.curator.rooms ||
            row.evidence == AppleMusicWebCuratedAlbumEvidence.REJECTED ||
            appleMusicWebCuratedAlbumEvidence(landing.curator.identity, tag, item, row.room) != row.evidence ||
            !seen.add(route.browseId)) return@mapNotNull null
        ShelfItem(title = item.title, subtitle = item.artistName.orEmpty(),
            thumbnailUrl = appleMusicWebArtworkUrl(item.artworkUrl),
            videoId = null, browseId = route.browseId,
            appleStoreUrl = appleMusicWebVerifiedStoreUrl(item))
    }
    return if (items.isEmpty()) emptyList() else listOf(HomeShelf(releasesHeading,items))
}

internal fun projectAppleMusicWebGenrePlaylistRoom(
    curator: AppleMusicWebCuratorRef, tag: String, page: AppleMusicWebRoomPage,
): HomeShelf? {
    if (page.kind != AppleMusicWebKind.PLAYLIST) return null
    val cards = page.items.filter {
        appleMusicWebCuratedPlaylistMatches(curator,tag,it,page.room)
    }.groupBy { it.scopedId }.values.mapNotNull { group ->
        val item = group.first()
        if (group.any { it != item } || item.title.isBlank()) return@mapNotNull null
        val route = AppleMusicWebNativeRoute.parse(item.scopedId) ?: return@mapNotNull null
        if (route.kind != AppleMusicWebKind.PLAYLIST) return@mapNotNull null
        ShelfItem(title=item.title,subtitle=item.artistName.orEmpty(),
            thumbnailUrl=appleMusicWebArtworkUrl(item.artworkUrl),
            videoId=null,browseId=route.browseId,appleStoreUrl=appleMusicWebVerifiedStoreUrl(item))
    }
    return cards.takeIf { it.isNotEmpty() }?.let { HomeShelf("Playlists",it) }
}

/** Admit extra rooms only from the same verified curator and market. Shared
 * curators still require exact item genre evidence. */
internal fun projectAmsCuratorExtraRoom(
    curator: AppleMusicWebCuratorRef, tag: String, page: AppleMusicWebRoomPage,
): HomeShelf? {
    val room = page.room
    val spec = amsCuratorExtraShelf(room) ?: return null
    if (page.kind != spec.kind || room.curatorId != curator.id ||
        room.storefront != curator.storefront ||
        !appleMusicWebCuratorMatches(curator,room.storefront,tag)) return null
    val shared = appleMusicWebCuratorIsSharedForTag(curator,tag)
    val cards = page.items.filter {
        it.kind == spec.kind && it.storefront == curator.storefront && it.title.isNotBlank() &&
            (!shared || it.genreNames.any { label -> appleMusicWebGenreNameMatches(label,tag) })
    }.groupBy { it.scopedId }.values.mapNotNull { group ->
        val item = group.first()
        if (group.any { it != item }) return@mapNotNull null
        val route = AppleMusicWebNativeRoute.parse(item.scopedId) ?: return@mapNotNull null
        if (route.kind != spec.kind) return@mapNotNull null
        ShelfItem(title=item.title,subtitle=if (item.kind == AppleMusicWebKind.ARTIST) "" else item.artistName.orEmpty(),
            thumbnailUrl=appleMusicWebArtworkUrl(item.artworkUrl),videoId=null,browseId=route.browseId,
            appleStoreUrl=appleMusicWebVerifiedStoreUrl(item))
    }
    return cards.takeIf { it.isNotEmpty() }?.let { HomeShelf(spec.heading,it) }
}

/** The existing Explore grid can use only art already admitted by its
 * exact curator and genre projection. No Home album is repurposed by name,
 * no other market, no eager 15-curator network fan-out.
 */
internal fun amsExploreGenreCoverMap(
    genres: Map<String, UiState<List<HomeShelf>>>,
    storefront: String = "br",
): Map<String, String> = genres.mapNotNull { (tag,state) ->
    if (!appleMusicWebGenreSupported(tag)) return@mapNotNull null
    val cover = (state as? UiState.Success)?.data.orEmpty().asSequence()
        .flatMap { it.items.asSequence() }
        .filter { card ->
            val route = AppleMusicWebNativeRoute.parse(card.browseId)
            route?.kind == AppleMusicWebKind.ALBUM && route.storefront == storefront &&
                card.videoId == null
        }.mapNotNull { safeAppleArtwork(it.thumbnailUrl) }.firstOrNull()
    cover?.let { tag to it }
}.toMap()

/** Select existing verified covers by configured Apple provider order.
 * No cross-source identity merge; the selected image is just genre art.
 */
internal fun orderedAppleExploreGenreCovers(
    order: List<ProviderKey>,
    itunes: Map<String,String>,
    ams: Map<String,String>,
): Map<String,String> = buildMap {
    for (provider in order.asReversed()) when (provider) {
        BuiltInProviderKeys.ITUNES -> putAll(itunes)
        BuiltInProviderKeys.APPLE_MUSIC_WEB -> putAll(ams)
        else -> Unit
    }
}

/** A globally linked room proves editorial membership, not playback or a
 * fabricated chart. Never reinterpret a genre/curator room as global Home.
 */
internal fun projectAppleMusicWebHomeRoom(
    room: AppleMusicWebRoomRef, page: AppleMusicWebRoomPage,
    storefront: String, shelf: AppleMusicWebHomeShelf,
    excluded: Set<String> = emptySet(),
): HomeShelf? {
    if (resolveAppleMusicWebHomeRoom(listOf(room), storefront, shelf) != room ||
        page.room != room || page.kind != shelf.kind) return null
    val cards = page.items.filter {
        it.kind == shelf.kind && it.storefront == storefront &&
            it.title.isNotBlank() && it.scopedId !in excluded
    }.groupBy { it.scopedId }.values.mapNotNull { group ->
        val item = group.first()
        if (group.any { it != item }) return@mapNotNull null
        val id = if (shelf.kind == AppleMusicWebKind.SONG) {
            if (amsSongStorefront(item.scopedId) != storefront ||
                appleMusicWebVerifiedStoreUrl(item) == null) return@mapNotNull null
            item.scopedId
        } else AppleMusicWebNativeRoute.parse(item.scopedId)?.browseId ?: return@mapNotNull null
        ShelfItem(title = item.title, subtitle = item.artistName.orEmpty(),
            thumbnailUrl = appleMusicWebArtworkUrl(item.artworkUrl),
            videoId = null, browseId = id,
            appleStoreUrl = appleMusicWebVerifiedStoreUrl(item))
    }
    val heading = when (shelf) {
        AppleMusicWebHomeShelf.RECENT_RELEASES -> "Lançamentos recentes"
        AppleMusicWebHomeShelf.NEW_THIS_WEEK -> "Novidades da semana"
        AppleMusicWebHomeShelf.TRENDING_SONGS -> "Músicas em alta"
        AppleMusicWebHomeShelf.BEST_NEW_SONGS -> "Novas músicas em destaque"
        AppleMusicWebHomeShelf.UPDATED_PLAYLISTS -> "Playlists atualizadas"
        AppleMusicWebHomeShelf.CITY_CHARTS -> "Mais tocadas por cidade"
        AppleMusicWebHomeShelf.ON_TOUR -> "Agora em turnê"
        AppleMusicWebHomeShelf.COMING_SOON -> "Em breve"
    }
    return cards.takeIf { it.isNotEmpty() }?.let { HomeShelf(heading, it) }
}

/** ViewModel-owned runtime boundary with the current provider gate and Room.
 * No reader creation before admission. Display callers still recheck consent.
 */
internal class AppleMusicWebNativeCatalogSession(
    private val currentGate: () -> ProviderPolicyGate?,
    private val createGateway: (ProviderPolicyGate) -> AppleMusicWebEditorialGateway,
) {
    private val provider = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private var owner: ProviderPolicyGate? = null
    private var reader: AppleMusicWebEditorialGateway? = null

    constructor(context: Context) : this(
        currentGate = { ProviderRuntime.gateOrNull() },
        createGateway = { gate ->
            AppleMusicWebEditorialGateway(
                AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
                    admission = RateLimitedProviderTransport { ProviderRuntime.gateOrNull() },
                )), gate,
                RoomAppleMusicWebLandingCache(
                    CanonicalCatalogDatabase.get(context.applicationContext),
                    ProviderCachedReadAccess { ProviderRuntime.gateOrNull() },
                ),
            )
        },
    )

    @Synchronized
    private fun gateway(gate: ProviderPolicyGate): AppleMusicWebEditorialGateway {
        if (owner !== gate || reader == null) {
            reader = createGateway(gate)
            owner = gate
        }
        return requireNotNull(reader)
    }

    private suspend fun <T> admitted(
        capability: ProviderCapability,
        read: suspend (AppleMusicWebEditorialGateway) -> ProviderCallResult<T>,
    ): ProviderCallResult<T> {
        val gate = currentGate() ?: return ProviderCallResult.Denied
        // The ViewModel callers publish UI state on Main, but the web bundle
        // can be several MB and catalog parsing/projection is synchronous.
        // Keep the entire admitted read, including gateway creation, off Main.
        val result = withContext(Dispatchers.IO) {
            if (currentGate() !== gate) ProviderCallResult.Stale
            else ProviderQueryExecutor(gate).execute(capability, provider) { read(gateway(gate)) }
        }
        if (currentGate() !== gate) return ProviderCallResult.Stale
        return when (result) {
            is ProviderCallResult.Success -> result.value
            is ProviderCallResult.Failed -> result
            ProviderCallResult.Denied -> ProviderCallResult.Denied
            ProviderCallResult.Stale -> ProviderCallResult.Stale
        }
    }

    /** Read the official directory once, under charts rather than editorial permission. */
    suspend fun dailyChartTiles(): ProviderCallResult<List<AmsDailyChartTile>> =
        admitted(ProviderCapability.CHART_MOST_PLAYED) { gateway ->
            when (val result = gateway.dailyChartDirectory()) {
                is ProviderCallResult.Success -> {
                    val directory = (result.value.outcome as? AppleMusicWebOutcome.Complete)?.value
                        ?: return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Incomplete chart directory"))
                    ProviderCallResult.Success(projectAmsDailyChartDirectory(directory))
                }
                is ProviderCallResult.Failed -> result
                ProviderCallResult.Denied -> ProviderCallResult.Denied
                ProviderCallResult.Stale -> ProviderCallResult.Stale
            }
        }

    suspend fun dailyChartSharedDetail(tile: AmsDailyChartTile):
        ProviderCallResult<AppleMusicWebOutcome<com.music.bitchord.ui.screens.AppleSharedDetail>> =
        admitted(ProviderCapability.CHART_MOST_PLAYED) { gateway ->
            val route = AppleMusicWebNativeRoute.parse(tile.card.browseId)
            if (route == null || route.kind != AppleMusicWebKind.PLAYLIST ||
                route.storefront != tile.country || route.id != tile.ref.id ||
                resolveAppleMusicWebDailyChart(listOf(tile.ref),tile.country) != tile.ref)
                return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Invalid daily chart identity"))
            when (val result = gateway.dailyChart(tile.country,tile.ref)) {
                is ProviderCallResult.Success -> {
                    val source = result.value.outcome
                    val chart = when (source) {
                        is AppleMusicWebOutcome.Complete -> source.value
                        is AppleMusicWebOutcome.Partial -> source.value
                        is AppleMusicWebOutcome.Unavailable -> return@admitted ProviderCallResult.Success(source)
                    }
                    if (chart.region != tile.country || chart.playlist.scopedId != route.browseId)
                        return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Wrong daily chart identity"))
                    val partial = source is AppleMusicWebOutcome.Partial
                    val identity = chart.playlist.copy(artworkUrl=chart.playlist.artworkUrl ?: tile.ref.artworkUrl)
                    val detail = com.music.bitchord.ui.screens.appleMusicWebAsSharedDetail(
                        route.browseId,AppleMusicWebNativeDetail.Playlist(AppleMusicWebPlaylist(identity,chart.songs)),partial)
                        ?: return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Invalid chart detail"))
                    ProviderCallResult.Success(if (source is AppleMusicWebOutcome.Partial)
                        AppleMusicWebOutcome.Partial(detail,source.reason) else AppleMusicWebOutcome.Complete(detail))
                }
                is ProviderCallResult.Failed -> result
                ProviderCallResult.Denied -> ProviderCallResult.Denied
                ProviderCallResult.Stale -> ProviderCallResult.Stale
            }
        }

    /** Country chart reads never borrow editorial or search permission.
     * Follow only the source cursor; do not re-sort, merge or infer positions. */
    suspend fun countryChart(country: String, albums: Boolean):
        ProviderCallResult<AmsCountryChartResult> =
        admitted(ProviderCapability.CHART_MOST_PLAYED) { gateway ->
            val territory = com.music.bitchord.data.catalog.metadata.knownAppleChartTerritory(country)
                ?: return@admitted ProviderCallResult.Failed(IllegalArgumentException("Unknown chart territory"))
            val kind = if (albums) AppleMusicWebKind.ALBUM else AppleMusicWebKind.SONG
            // Rankings can change between requests. Ask for one atomic Top 100
            // album snapshot first; the existing proven song path is unchanged.
            // If the API caps the page, follow only its own continuation.
            var limit = if (albums) 100 else 25
            var restarted = false
            while (true) {
                val rows = mutableListOf<AppleMusicWebItem>()
                val seen = hashSetOf<String>()
                val visited = hashSetOf<String>()
                var cursor: String? = null
                var restart = false
                for (index in 1..8) {
                    val page = when (val result = gateway.mostPlayedChart(kind,country,limit=limit,cursor=cursor)) {
                        is ProviderCallResult.Success -> (result.value.outcome as? AppleMusicWebOutcome.Complete)?.value
                            ?: return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Partial chart page"))
                        is ProviderCallResult.Failed -> {
                            // A storefront may reject a larger requested page.
                            // Retry once using the established smaller request,
                            // only for explicit request-validation HTTP errors.
                            if (index == 1 && limit == 100 &&
                                (result.cause as? AppleMusicWebHttpException)?.status in setOf(400,422)) {
                                limit = 25
                                restart = true
                                break
                            }
                            return@admitted result
                        }
                        ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                        ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                    }
                    if (page.storefront != country || page.genreId != null || page.kind != kind ||
                        page.items.any { it.kind != kind || it.storefront != country })
                        return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Invalid chart membership"))
                    if (page.items.any { !seen.add(it.scopedId) }) {
                        // Never deduplicate and renumber a moving chart. Discard
                        // the entire snapshot, then retry from the origin once.
                        if (index > 1 && !restarted) {
                            restarted = true
                            restart = true
                            break
                        }
                        return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Overlapping chart pages"))
                    }
                    rows += page.items
                    cursor = page.nextCursor
                    if (rows.size >= 100 || cursor == null) break
                    if (page.items.isEmpty() || !visited.add(cursor))
                        return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Invalid chart cursor"))
                }
                if (restart) continue
                if (rows.isEmpty() || (rows.size < 100 && cursor != null))
                    return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Incomplete chart"))
                val projected = projectAmsCountryChart(territory,albums,rows.take(100),System.currentTimeMillis())
                    ?: return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("No verified chart rows"))
                return@admitted ProviderCallResult.Success(projected)
            }
            @Suppress("UNREACHABLE_CODE")
            ProviderCallResult.Failed(AppleMusicWebProtocolException("Incomplete chart"))
        }

    /** Track evidence supplements curators and also works without a curator.
     * Search permission is independent and optional; never promote a search
     * keyword, broad parent label or foreign storefront into a style match. */
    suspend fun genre(tag: String, storefront: String, refresh: Boolean = false):
        ProviderCallResult<AppleMusicWebOutcome<AmsEditorialPage>> {
        if (tag !in amsEditorialStyleParents) return curatedGenre(tag,storefront,refresh)
        val gate = currentGate() ?: return ProviderCallResult.Denied
        val editorialTicket = gate.open(editorial,provider) ?: return ProviderCallResult.Denied
        val searchTicket = gate.open(ProviderCapability.CATALOG_SEARCH,provider)
        fun editorialCurrent() = currentGate() === gate && gate.accept(editorialTicket)
        val curated = curatedGenre(tag,storefront,refresh)
        if (!editorialCurrent()) return ProviderCallResult.Stale
        if (curated == ProviderCallResult.Denied || curated == ProviderCallResult.Stale ||
            searchTicket == null) return curated
        if (curated is ProviderCallResult.Failed &&
            (curated.cause as? AppleMusicWebHttpException)?.status in setOf(401,403,429)) return curated
        if (!editorialCurrent() || !gate.accept(searchTicket)) return ProviderCallResult.Stale
        val tracks = trackStyleShelf(tag,storefront)
        if (!editorialCurrent() || !gate.accept(searchTicket) || tracks == ProviderCallResult.Stale)
            return ProviderCallResult.Stale
        if (tracks is ProviderCallResult.Failed &&
            (tracks.cause as? AppleMusicWebHttpException)?.status in setOf(401,403,429)) return tracks
        if (tracks == ProviderCallResult.Denied) return ProviderCallResult.Denied
        val shelf = (tracks as? ProviderCallResult.Success)?.value
        val outcome = (curated as? ProviderCallResult.Success)?.value
            ?: AppleMusicWebOutcome.Partial(AmsEditorialPage(emptyList()),AppleMusicWebCompleteness.UPSTREAM_INTERRUPTED)
        val page = when (outcome) {
            is AppleMusicWebOutcome.Complete -> outcome.value
            is AppleMusicWebOutcome.Partial -> outcome.value
            is AppleMusicWebOutcome.Unavailable -> AmsEditorialPage(emptyList())
        }
        val composition = amsMergeStyleMetadataShelf(shelf,page.shelves,storefront)
        var rails = composition.shelves
        val albums = rails.flatMap { it.items }.mapNotNull { AppleMusicWebNativeRoute.parse(it.browseId) }
            .filter { it.kind == AppleMusicWebKind.ALBUM }.distinct().size
        if (albums < 6) {
            val fallback = searchedStylePlaylists(tag,storefront,composition.excludedIds)
            if (!editorialCurrent() || !gate.accept(searchTicket) || fallback == ProviderCallResult.Stale)
                return ProviderCallResult.Stale
            if (fallback == ProviderCallResult.Denied) return ProviderCallResult.Denied
            if (fallback is ProviderCallResult.Failed &&
                (fallback.cause as? AppleMusicWebHttpException)?.status in setOf(401,403,429)) return fallback
            for (observed in (fallback as? ProviderCallResult.Success)?.value.orEmpty()) {
                val extra = observed.copy(items=observed.items.filterNot { it.browseId in composition.rejectedIds })
                if (extra.items.isEmpty()) continue
                val index = rails.indexOfFirst { it.title == extra.title }
                rails = if (index < 0) rails + extra else rails.mapIndexed { i,rail ->
                    if (i == index) rail.copy(items=(rail.items + extra.items).distinctBy { it.browseId }) else rail
                }
            }
        }
        if (!editorialCurrent() || !gate.accept(searchTicket)) return ProviderCallResult.Stale
        // Never restore the pre-validation snapshot if all candidates conflicted.
        if (rails.isEmpty() && composition.rejectedIds.isEmpty()) return curated
        // Keep exclusion evidence even when no cards survive. A refresh may
        // need to withdraw those identities from an earlier visible snapshot.
        val merged = page.copy(shelves=rails,rejectedIds=page.rejectedIds + composition.rejectedIds)
        return ProviderCallResult.Success(if (outcome is AppleMusicWebOutcome.Partial)
            AppleMusicWebOutcome.Partial(merged,outcome.reason) else AppleMusicWebOutcome.Complete(merged))
    }

    /** Bounded supplemental discovery for underfilled styles. The playlist's
     * verified editorial membership is not a genre claim for every album. */
    private suspend fun searchedStylePlaylists(tag: String, market: String, existing: Set<String>):
        ProviderCallResult<List<HomeShelf>> = admitted(editorial) { gateway ->
            val candidates = mutableListOf<AppleMusicWebItem>()
            for (query in amsStyleDiscoveryQueries(tag)) {
                when (val read = gateway.search(AppleMusicWebKind.PLAYLIST,query,market,limit=25)) {
                    ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                    ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                    is ProviderCallResult.Failed -> {
                        if ((read.cause as? AppleMusicWebHttpException)?.status in setOf(401,403,429)) return@admitted read
                        continue
                    }
                    is ProviderCallResult.Success -> candidates += (read.value.outcome as? AppleMusicWebOutcome.Complete)
                        ?.value?.items.orEmpty().filter {
                            amsVerifiedSearchedStylePlaylist(it,tag,market) &&
                                appleMusicWebVerifiedStoreUrl(it) != null && it.scopedId !in existing
                        }
                }
                if (candidates.distinctBy { it.id }.size >= 2) break
            }
            val playlists = mutableListOf<ShelfItem>()
            val albums = mutableListOf<ShelfItem>()
            val candidateIds = (candidates.map { it.id } + amsStylePublicPlaylistCandidates(tag,market))
                .distinct().filter { "apple_music_web:$market:playlist:$it" !in existing }.take(2)
            for (candidateId in candidateIds) {
                val record = when (val read = gateway.playlist(candidateId,market)) {
                    ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                    ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                    is ProviderCallResult.Failed -> {
                        if ((read.cause as? AppleMusicWebHttpException)?.status in setOf(401,403,429)) return@admitted read
                        continue
                    }
                    is ProviderCallResult.Success -> when(val outcome = read.value.outcome) {
                        is AppleMusicWebOutcome.Complete -> outcome.value
                        is AppleMusicWebOutcome.Partial -> outcome.value
                        else -> continue
                    }
                }
                val identity = record.identity
                if (identity.id != candidateId || !amsVerifiedSearchedStylePlaylist(identity,tag,market) ||
                    appleMusicWebVerifiedStoreUrl(identity) == null) continue
                playlists += ShelfItem(title=identity.title,subtitle=identity.artistName.orEmpty(),
                    thumbnailUrl=appleMusicWebArtworkUrl(identity.artworkUrl),videoId=null,
                    browseId=identity.scopedId,appleStoreUrl=appleMusicWebVerifiedStoreUrl(identity))
                val ids = amsStylePlaylistAlbumIds(record,market,existing + albums.mapNotNull { it.browseId })
                val metadata = mutableListOf<AppleMusicWebItem>()
                for (batch in ids.chunked(25)) {
                    when (val read = gateway.linkedAlbumMetadata(batch,market)) {
                        ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                        ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                        is ProviderCallResult.Failed -> {
                            if ((read.cause as? AppleMusicWebHttpException)?.status in setOf(401,403,429)) return@admitted read
                        }
                        is ProviderCallResult.Success -> metadata +=
                            (read.value.outcome as? AppleMusicWebOutcome.Complete)?.value.orEmpty()
                    }
                    if ((amsStyleTrackAlbumShelf(ids,market,metadata)?.items?.size ?: 0) >= 24) break
                }
                albums += amsStyleTrackAlbumShelf(ids,market,metadata)?.items.orEmpty()
            }
            ProviderCallResult.Success(listOfNotNull(
                playlists.distinctBy { it.browseId }.takeIf { it.isNotEmpty() }?.let { HomeShelf("Playlists",it) },
                albums.distinctBy { it.browseId }.takeIf { it.isNotEmpty() }?.let { HomeShelf("Álbuns das playlists",it) },
            ))
        }

    private suspend fun trackStyleShelf(tag: String, storefront: String):
        ProviderCallResult<HomeShelf?> = admitted(editorial) { gateway ->
            val songs = mutableListOf<AppleMusicWebItem>()
            val albums = mutableListOf<AppleMusicWebItem>()
            val requested = hashSetOf<String>()
            suspend fun hydrateNewTrackCandidates(): ProviderCallResult<Unit> {
                val ids = amsStyleTrackAlbumIds(songs,storefront,tag)
                for (batch in ids.filterNot { it in requested }.chunked(25)) {
                    if ((amsStyleTrackAlbumShelf(ids,storefront,albums)?.items?.size ?: 0) >= 24) break
                    requested += batch
                    when (val read = gateway.linkedAlbumMetadata(batch,storefront)) {
                        ProviderCallResult.Denied -> return ProviderCallResult.Denied
                        ProviderCallResult.Stale -> return ProviderCallResult.Stale
                        is ProviderCallResult.Failed -> {
                            if ((read.cause as? AppleMusicWebHttpException)?.status in setOf(401,403,429)) return read
                        }
                        is ProviderCallResult.Success -> albums +=
                            (read.value.outcome as? AppleMusicWebOutcome.Complete)?.value.orEmpty()
                    }
                }
                return ProviderCallResult.Success(Unit)
            }
            var cursor: String? = null
            val visited = hashSetOf<String>()
            for (index in 1..4) {
                if (index > 2 && !amsStyleNeedsTrackDiscovery(songs,storefront,tag,albums)) break
                when (val read = gateway.search(AppleMusicWebKind.SONG,tag,storefront,
                    page=index,limit=25,continuation=cursor)) {
                    ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                    ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                    is ProviderCallResult.Failed -> {
                        if ((read.cause as? AppleMusicWebHttpException)?.status in setOf(401,403,429)) return@admitted read
                        break
                    }
                    is ProviderCallResult.Success -> {
                        val page = (read.value.outcome as? AppleMusicWebOutcome.Complete)?.value ?: break
                        songs += page.items
                        // Collect the first two pages together, then hydrate
                        // before deciding whether more discovery is necessary.
                        val next = page.nextCursor
                        if (index >= 2 || next == null) when (val result = hydrateNewTrackCandidates()) {
                            ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                            ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                            is ProviderCallResult.Failed -> return@admitted result
                            is ProviderCallResult.Success -> Unit
                        }
                        if (next == null || !visited.add(next)) break
                        cursor = next
                    }
                }
            }
            // Cover first-page candidates even if a second page failed.
            when (val result = hydrateNewTrackCandidates()) {
                ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                is ProviderCallResult.Failed -> return@admitted result
                is ProviderCallResult.Success -> Unit
            }
            if (amsStyleNeedsTrackDiscovery(songs,storefront,tag,albums)) {
                for (query in amsStyleDiscoveryQueries(tag).drop(1).take(1)) {
                    when (val read = gateway.search(AppleMusicWebKind.SONG,query,storefront,limit=25)) {
                        ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                        ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                        is ProviderCallResult.Failed -> {
                            if ((read.cause as? AppleMusicWebHttpException)?.status in setOf(401,403,429)) return@admitted read
                        }
                        is ProviderCallResult.Success -> songs += (read.value.outcome as? AppleMusicWebOutcome.Complete)?.value?.items.orEmpty()
                    }
                }
                when (val result = hydrateNewTrackCandidates()) {
                    ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                    ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                    is ProviderCallResult.Failed -> return@admitted result
                    is ProviderCallResult.Success -> Unit
                }
            }
            val ids = amsStyleTrackAlbumIds(songs,storefront,tag)
            // Album-owned exact labels are independent evidence and do not
            // require a specific playlist or that a song search ranks well.
            val direct = mutableListOf<AppleMusicWebItem>()
            fun admittedShelf(): HomeShelf? {
                val combined = albums + direct
                val verifiedIds = ids + amsStyleAlbumLabelIds(combined,storefront,tag)
                return amsStyleTrackAlbumShelf(verifiedIds,storefront,combined)
            }
            if ((admittedShelf()?.items?.size ?: 0) < AMS_STYLE_METADATA_TARGET) {
                var albumCursor: String? = null
                val albumVisited = hashSetOf<String>()
                for (index in 1..2) {
                    when (val read = gateway.search(AppleMusicWebKind.ALBUM,tag,storefront,
                        page=index,limit=25,continuation=albumCursor)) {
                        ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                        ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                        is ProviderCallResult.Failed -> {
                            if ((read.cause as? AppleMusicWebHttpException)?.status in setOf(401,403,429)) return@admitted read
                            break
                        }
                        is ProviderCallResult.Success -> {
                            val page = (read.value.outcome as? AppleMusicWebOutcome.Complete)?.value ?: break
                            direct += page.items.filter { it.kind == AppleMusicWebKind.ALBUM &&
                                it.storefront == storefront }
                            val next = page.nextCursor ?: break
                            if (!albumVisited.add(next)) break
                            albumCursor = next
                        }
                    }
                }
            }
            if ((admittedShelf()?.items?.size ?: 0) < AMS_STYLE_METADATA_TARGET) {
                for (query in amsStyleDiscoveryQueries(tag).drop(1).take(1)) {
                    when (val read = gateway.search(AppleMusicWebKind.ALBUM,query,storefront,limit=25)) {
                        ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                        ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                        is ProviderCallResult.Failed -> {
                            if ((read.cause as? AppleMusicWebHttpException)?.status in setOf(401,403,429)) return@admitted read
                        }
                        is ProviderCallResult.Success -> direct += (read.value.outcome as? AppleMusicWebOutcome.Complete)
                            ?.value?.items.orEmpty().filter { it.kind == AppleMusicWebKind.ALBUM &&
                                it.storefront == storefront }
                    }
                }
            }
            // Some search rows or linked songs carry only a broad label.
            // Reuse their exact album IDs in at most two additional batches.
            // Do not admit them unless the hydrated album itself has the style.
            if ((admittedShelf()?.items?.size ?: 0) < AMS_STYLE_METADATA_TARGET) {
                val supplemental = amsStyleSupplementalAlbumIds(songs,direct,storefront,requested + ids)
                for (batch in supplemental.chunked(25)) {
                    when (val read = gateway.linkedAlbumMetadata(batch,storefront)) {
                        ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                        ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                        is ProviderCallResult.Failed -> {
                            if ((read.cause as? AppleMusicWebHttpException)?.status in setOf(401,403,429)) return@admitted read
                        }
                        is ProviderCallResult.Success -> albums +=
                            (read.value.outcome as? AppleMusicWebOutcome.Complete)?.value.orEmpty()
                    }
                    if ((admittedShelf()?.items?.size ?: 0) >= AMS_STYLE_METADATA_TARGET) break
                }
            }
            ProviderCallResult.Success(admittedShelf())
        }

    private suspend fun curatedGenre(tag: String, storefront: String, refresh: Boolean):
        ProviderCallResult<AppleMusicWebOutcome<AmsEditorialPage>> =
        admitted(editorial) { gateway ->
            val result = gateway.curatedGenreLanding(tag, storefront, refresh)
            when (result) {
                is ProviderCallResult.Success -> {
                    val market = result.value.storefront
                    suspend fun project(landing: AppleMusicWebCuratorLanding): ProviderCallResult<AppleMusicWebOutcome<AmsEditorialPage>> {
                        val shelves = projectAppleMusicWebGenreLanding(landing,market,tag).toMutableList()
                        val requests = landing.continuations.map<AppleMusicWebRoomContinuation,AmsEditorialRequest> {
                            AmsEditorialRequest.Room(it, curator = landing.curator.identity, tag = tag)
                        }.ifEmpty {
                            if (landing.bounded && !refresh && (shelves.isNotEmpty() || tag in amsEditorialStyleParents))
                                listOf(AmsEditorialRequest.RefreshGenre(tag,landing.curator.identity))
                            else emptyList()
                        }
                        val pending = requests.toMutableList()
                        val playlistCards = mutableListOf<ShelfItem>()
                        var playlistIncomplete = false
                        // A style can have an exact official playlist but no album-level tag.
                        // Never promote playlist tracks to fabricated style release evidence.
                        if (shelves.isNotEmpty() || tag in amsEditorialStyleParents) {
                            val curator = landing.curator.identity
                            val playlistRooms = landing.curator.rooms.filter {
                                isAppleMusicWebCuratorPlaylistRoom(it) && it.storefront == market &&
                                    it.curatorId == curator.id && it.genreId == null
                            }
                            for (room in playlistRooms.take(2)) {
                                var cursor: String? = null
                                val visited = hashSetOf<String>()
                                val budget = if (tag in amsEditorialStyleParents) 3 else 1
                                for (index in 1..budget) {
                                val result = gateway.roomContents(room,AppleMusicWebKind.PLAYLIST,limit=25,cursor=cursor)
                                val page = when (result) {
                                    ProviderCallResult.Denied -> return ProviderCallResult.Denied
                                    ProviderCallResult.Stale -> return ProviderCallResult.Stale
                                    is ProviderCallResult.Failed -> {
                                        val failure = result.cause
                                        if (failure is AppleMusicWebHttpException && failure.status in listOf(401,403,429))
                                            return result
                                        playlistIncomplete = true
                                        continue
                                    }
                                    is ProviderCallResult.Success -> when (val outcome = result.value.outcome) {
                                        is AppleMusicWebOutcome.Complete -> outcome.value
                                        is AppleMusicWebOutcome.Partial -> { playlistIncomplete = true; outcome.value }
                                        is AppleMusicWebOutcome.Unavailable -> { playlistIncomplete = true; continue }
                                    }
                                }
                                playlistCards += projectAppleMusicWebGenrePlaylistRoom(curator,tag,page)?.items.orEmpty()
                                if (page.unresolvedReferences != 0) break
                                val next = page.nextCursor ?: break
                                if (!visited.add(next)) {
                                    playlistIncomplete = true
                                    break
                                }
                                if (index == budget) pending += AmsEditorialRequest.Room(
                                    AppleMusicWebRoomContinuation(room,next,visited - next),
                                    curator=curator,tag=tag,resourceKind=AppleMusicWebKind.PLAYLIST)
                                cursor = next
                                }
                            }
                            pending += playlistRooms.drop(2).map {
                                AmsEditorialRequest.Room(AppleMusicWebRoomContinuation(it,null),
                                    curator=curator,tag=tag,resourceKind=AppleMusicWebKind.PLAYLIST)
                            }
                        }
                        val playlistAlbums = mutableListOf<ShelfItem>()
                        // Revalidate up to two exact room candidates, only
                        // trying the second if the first is underfilled.
                        if (tag in amsEditorialStyleParents) {
                            val candidates = amsStyleCuratorPlaylistCandidates(playlistCards,tag).mapNotNull { card ->
                                AppleMusicWebNativeRoute.parse(card.browseId)?.id?.let { it to card }
                            }.take(2).ifEmpty {
                                listOfNotNull(amsStylePlaylistReference(tag,market)?.let { it to null })
                            }
                            val rejected = hashSetOf<String>()
                            for ((id,card) in candidates) {
                                val record = when (val result = gateway.playlist(id,market)) {
                                    ProviderCallResult.Denied -> return ProviderCallResult.Denied
                                    ProviderCallResult.Stale -> return ProviderCallResult.Stale
                                    is ProviderCallResult.Failed -> {
                                        if ((result.cause as? AppleMusicWebHttpException)?.status in listOf(401,403,429)) return result
                                        playlistIncomplete = true
                                        continue
                                    }
                                    is ProviderCallResult.Success -> when (val outcome = result.value.outcome) {
                                        is AppleMusicWebOutcome.Complete -> outcome.value
                                        is AppleMusicWebOutcome.Partial -> { playlistIncomplete = true; outcome.value }
                                        else -> { playlistIncomplete = true; continue }
                                    }
                                }
                                val identity = record.identity
                                if (!amsStyleCuratorPlaylistDetailMatches(identity,id,market,tag,card)) {
                                    playlistIncomplete = true
                                    // A generic title alone does not invalidate the room card.
                                    // Remove only an actually contradicted identity.
                                    if (!amsStyleCuratorPlaylistIdentityMatches(identity,id,market,card))
                                        rejected += "apple_music_web:$market:playlist:$id"
                                    continue
                                }
                                if (card == null) playlistCards += ShelfItem(title=identity.title,
                                    subtitle=identity.artistName.orEmpty(),thumbnailUrl=appleMusicWebArtworkUrl(identity.artworkUrl),
                                    videoId=null,browseId=identity.scopedId,appleStoreUrl=appleMusicWebVerifiedStoreUrl(identity))
                                val ids = amsStylePlaylistAlbumIds(record,market,playlistAlbums.mapNotNull { it.browseId }.toSet())
                                val metadata = mutableListOf<AppleMusicWebItem>()
                                for (batch in ids.chunked(25)) {
                                    when (val result = gateway.linkedAlbumMetadata(batch,market)) {
                                        ProviderCallResult.Denied -> return ProviderCallResult.Denied
                                        ProviderCallResult.Stale -> return ProviderCallResult.Stale
                                        is ProviderCallResult.Failed -> {
                                            if ((result.cause as? AppleMusicWebHttpException)?.status in listOf(401,403,429)) return result
                                            playlistIncomplete = true
                                        }
                                        is ProviderCallResult.Success -> metadata +=
                                            (result.value.outcome as? AppleMusicWebOutcome.Complete)?.value.orEmpty()
                                    }
                                    if ((amsStyleTrackAlbumShelf(ids,market,metadata)?.items?.size ?: 0) + playlistAlbums.size >= 24) break
                                }
                                playlistAlbums += amsStyleTrackAlbumShelf(ids,market,metadata)?.items.orEmpty()
                                if (playlistAlbums.size >= 6) break
                            }
                            playlistCards.removeAll { it.browseId in rejected }
                        }
                        val playlists = playlistCards.groupBy { it.browseId }.values.mapNotNull { group ->
                            group.first().takeIf { first -> group.all { it == first } }
                        }
                        if (playlists.isNotEmpty()) shelves += HomeShelf("Playlists",playlists)
                        if (playlistAlbums.isNotEmpty()) shelves += HomeShelf("Álbuns das playlists",
                            playlistAlbums.distinctBy { it.browseId }.take(24))
                        // Keep first paint bounded; remaining rails use native continuation.
                        if (shelves.isNotEmpty()) {
                            val curator = landing.curator.identity
                            val extras = landing.curator.rooms.mapNotNull { room ->
                                amsCuratorExtraShelf(room)?.takeIf {
                                    room.curatorId == curator.id && room.storefront == market
                                }?.let { room to it }
                            }.groupBy { it.second.heading }.values.mapNotNull { it.distinct().singleOrNull() }
                            for ((room,spec) in extras.take(3)) {
                                val result = gateway.roomContents(room,spec.kind,limit=12)
                                val page = when (result) {
                                    ProviderCallResult.Denied -> return ProviderCallResult.Denied
                                    ProviderCallResult.Stale -> return ProviderCallResult.Stale
                                    is ProviderCallResult.Failed -> {
                                        if ((result.cause as? AppleMusicWebHttpException)?.status in listOf(401,403,429)) return result
                                        playlistIncomplete = true
                                        continue
                                    }
                                    is ProviderCallResult.Success -> when (val outcome = result.value.outcome) {
                                        is AppleMusicWebOutcome.Complete -> outcome.value
                                        is AppleMusicWebOutcome.Partial -> { playlistIncomplete = true; outcome.value }
                                        is AppleMusicWebOutcome.Unavailable -> { playlistIncomplete = true; continue }
                                    }
                                }
                                projectAmsCuratorExtraRoom(curator,tag,page)?.let { shelves += it }
                                if (page.unresolvedReferences == 0) page.nextCursor?.let {
                                    pending += AmsEditorialRequest.Room(AppleMusicWebRoomContinuation(room,it),
                                        curator=curator,tag=tag,resourceKind=spec.kind)
                                }
                            }
                            pending += extras.drop(3).map { (room,spec) ->
                                AmsEditorialRequest.Room(AppleMusicWebRoomContinuation(room,null),
                                    curator=curator,tag=tag,resourceKind=spec.kind)
                            }
                        }
                        val page = AmsEditorialPage(shelves,pending,landing.curator.identity)
                        val previous = result.value.outcome as? AppleMusicWebOutcome.Partial<AppleMusicWebCuratorLanding>
                        return ProviderCallResult.Success(when {
                            previous != null -> AppleMusicWebOutcome.Partial(page,previous.reason)
                            playlistIncomplete -> AppleMusicWebOutcome.Partial(page,AppleMusicWebCompleteness.UPSTREAM_INTERRUPTED)
                            else -> AppleMusicWebOutcome.Complete(page)
                        })
                    }
                    when (val outcome = result.value.outcome) {
                        is AppleMusicWebOutcome.Complete -> project(outcome.value)
                        is AppleMusicWebOutcome.Partial -> project(outcome.value)
                        is AppleMusicWebOutcome.Unavailable -> ProviderCallResult.Success(outcome)
                    }
                }
                is ProviderCallResult.Failed -> result
                ProviderCallResult.Denied -> ProviderCallResult.Denied
                ProviderCallResult.Stale -> ProviderCallResult.Stale
            }
        }

    /** Bounded initial album sample, using the source's actual global rooms. */
    suspend fun home(storefront: String): ProviderCallResult<AppleMusicWebOutcome<AmsEditorialPage>> =
        admitted(editorial) { gateway ->
            val rooms = when (val result = gateway.browseRooms(storefront)) {
                ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                is ProviderCallResult.Failed -> return@admitted result
                is ProviderCallResult.Success -> when (val source = result.value.outcome) {
                    is AppleMusicWebOutcome.Complete -> source.value
                    else -> return@admitted ProviderCallResult.Success(
                        AppleMusicWebOutcome.Unavailable("Global AMS rooms unavailable"))
                }
            }
            val shelves = mutableListOf<HomeShelf>()
            val continuations = mutableListOf<AmsEditorialRequest>()
            val seen = hashSetOf<String>()
            var incomplete: AppleMusicWebCompleteness? = null
            for (kind in listOf(AppleMusicWebHomeShelf.RECENT_RELEASES,
                AppleMusicWebHomeShelf.NEW_THIS_WEEK,AppleMusicWebHomeShelf.TRENDING_SONGS,
                AppleMusicWebHomeShelf.BEST_NEW_SONGS,AppleMusicWebHomeShelf.UPDATED_PLAYLISTS,
                AppleMusicWebHomeShelf.CITY_CHARTS,AppleMusicWebHomeShelf.ON_TOUR,AppleMusicWebHomeShelf.COMING_SOON)) {
                val room = resolveAppleMusicWebHomeRoom(rooms, storefront, kind)
                if (room == null) {
                    if (kind in setOf(AppleMusicWebHomeShelf.RECENT_RELEASES,AppleMusicWebHomeShelf.NEW_THIS_WEEK)) incomplete = AppleMusicWebCompleteness.UPSTREAM_INTERRUPTED
                    continue
                }
                val outcome = when (val result = gateway.roomContents(room, kind.kind, limit = if (kind in setOf(AppleMusicWebHomeShelf.UPDATED_PLAYLISTS, AppleMusicWebHomeShelf.CITY_CHARTS, AppleMusicWebHomeShelf.ON_TOUR, AppleMusicWebHomeShelf.COMING_SOON)) 12 else 25)) {
                    ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                    ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                    is ProviderCallResult.Failed -> {
                        val failure = result.cause
                        if (failure is AppleMusicWebHttpException && failure.status in listOf(401,403,429))
                            return@admitted result
                        incomplete = AppleMusicWebCompleteness.UPSTREAM_INTERRUPTED
                        continue
                    }
                    is ProviderCallResult.Success -> result.value.outcome
                }
                val page = when (outcome) {
                    is AppleMusicWebOutcome.Complete -> outcome.value
                    is AppleMusicWebOutcome.Partial -> {
                        incomplete = outcome.reason
                        outcome.value
                    }
                    is AppleMusicWebOutcome.Unavailable -> {
                        incomplete = AppleMusicWebCompleteness.UPSTREAM_INTERRUPTED
                        continue
                    }
                }
                if (page.nextCursor != null && page.unresolvedReferences == 0) {
                    continuations += AmsEditorialRequest.Room(
                        AppleMusicWebRoomContinuation(room,page.nextCursor), homeKind=kind,resourceKind=kind.kind)
                    if (incomplete == null) incomplete = AppleMusicWebCompleteness.PAGE_LIMIT
                }
                projectAppleMusicWebHomeRoom(room, page, storefront, kind, seen)?.let { shelf ->
                    shelves += shelf
                    seen += shelf.items.mapNotNull { it.browseId }
                }
            }
            ProviderCallResult.Success(when {
                shelves.isEmpty() && incomplete != null ->
                    AppleMusicWebOutcome.Unavailable("No verified global AMS album rooms")
                incomplete != null -> AppleMusicWebOutcome.Partial(AmsEditorialPage(shelves.toList(),continuations), incomplete)
                else -> AppleMusicWebOutcome.Complete(AmsEditorialPage(shelves.toList(),continuations))
            })
        }

    /** One deliberate continuation, never a synthetic offset or another source. */
    suspend fun nextEditorial(request: AmsEditorialRequest):
        ProviderCallResult<AppleMusicWebOutcome<AmsEditorialPage>> {
        if (request is AmsEditorialRequest.RefreshGenre) {
            return when (val result = genre(request.tag,request.curator.storefront,true)) {
                is ProviderCallResult.Success -> {
                    val value = when (val outcome = result.value) {
                        is AppleMusicWebOutcome.Complete -> outcome.value
                        is AppleMusicWebOutcome.Partial -> outcome.value
                        is AppleMusicWebOutcome.Unavailable -> return result
                    }
                    if (value.curator?.id != request.curator.id ||
                        value.curator?.storefront != request.curator.storefront)
                        ProviderCallResult.Failed(AppleMusicWebProtocolException("Changed curator"))
                    else result
                }
                else -> result
            }
        }
        request as AmsEditorialRequest.Room
        val position = request.position
        val room = position.room
        val market = room.storefront
        val tag = request.tag
        val curator = request.curator
        val homeKind = request.homeKind
        val resourceKind = request.resourceKind
        val valid = if (homeKind != null) curator == null && tag == null &&
            resolveAppleMusicWebHomeRoom(listOf(room),market,homeKind) == room &&
            homeKind.kind == resourceKind
        else tag != null && curator != null && appleMusicWebGenreSupported(tag) &&
            appleMusicWebCuratorMatches(curator,market,tag) && room.curatorId == curator.id &&
            room.genreId == null && (amsCuratorExtraShelf(room)?.kind == resourceKind || when (resourceKind) {
                AppleMusicWebKind.ALBUM -> isAppleMusicWebCuratorReleaseRoom(room)
                AppleMusicWebKind.PLAYLIST -> isAppleMusicWebCuratorPlaylistRoom(room)
                else -> false
            })
        if (!valid || (position.cursor != null && position.cursor in position.visited))
            return ProviderCallResult.Failed(AppleMusicWebProtocolException("Invalid editorial continuation"))
        return admitted(editorial) { gateway ->
            when (val result = gateway.roomContents(room,resourceKind,cursor=position.cursor)) {
                is ProviderCallResult.Success -> {
                    val page = (result.value.outcome as? AppleMusicWebOutcome.Complete)?.value
                        ?: return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Incomplete editorial page"))
                    val visited = position.visited + listOfNotNull(position.cursor)
                    val next = page.nextCursor
                    if (page.room != room || page.kind != resourceKind || (next != null && next in visited))
                        return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Invalid editorial page or cyclic cursor"))
                    val shelf = if (homeKind != null)
                        projectAppleMusicWebHomeRoom(room,page,market,homeKind)
                    else if (amsCuratorExtraShelf(room) != null)
                        projectAmsCuratorExtraRoom(requireNotNull(curator),requireNotNull(tag),page)
                    else if (resourceKind == AppleMusicWebKind.PLAYLIST)
                        projectAppleMusicWebGenrePlaylistRoom(requireNotNull(curator),requireNotNull(tag),page)
                    else {
                        val verified = page.items.filter {
                            appleMusicWebCuratedAlbumEvidence(requireNotNull(curator),requireNotNull(tag),it,room) !=
                                AppleMusicWebCuratedAlbumEvidence.REJECTED
                        }
                        val cards = verified.mapNotNull { item ->
                            val route = AppleMusicWebNativeRoute.parse(item.scopedId)
                                ?: return@mapNotNull null
                            if (route.kind != AppleMusicWebKind.ALBUM || route.storefront != market ||
                                item.title.isBlank()) return@mapNotNull null
                            ShelfItem(title=item.title,subtitle=item.artistName.orEmpty(),
                                thumbnailUrl=appleMusicWebArtworkUrl(item.artworkUrl),
                                videoId=null,browseId=route.browseId,
                                appleStoreUrl=appleMusicWebVerifiedStoreUrl(item))
                        }
                        cards.takeIf { it.isNotEmpty() }?.let { HomeShelf("Lançamentos",it) }
                    }
                    val pending = next?.let {
                        request.copy(position=AppleMusicWebRoomContinuation(room,it,visited))
                    }
                    ProviderCallResult.Success(AppleMusicWebOutcome.Complete(
                        AmsEditorialPage(listOfNotNull(shelf),listOfNotNull(pending),curator)))
                }
                is ProviderCallResult.Failed -> result
                ProviderCallResult.Denied -> ProviderCallResult.Denied
                ProviderCallResult.Stale -> ProviderCallResult.Stale
            }
        }
    }

    suspend fun detail(browseId: String): ProviderCallResult<AppleMusicWebOutcome<AppleMusicWebNativeDetail>> {
        val route = AppleMusicWebNativeRoute.parse(browseId)
            ?: return ProviderCallResult.Failed(IllegalArgumentException("Invalid AMS route"))
        return admitted(editorial) { gateway ->
            when (route.kind) {
                AppleMusicWebKind.ALBUM -> mapDetail(gateway.album(route.id,route.storefront)) { AppleMusicWebNativeDetail.Album(it) }
                AppleMusicWebKind.ARTIST -> mapDetail(gateway.artist(route.id,route.storefront)) { AppleMusicWebNativeDetail.Artist(it) }
                AppleMusicWebKind.PLAYLIST -> mapDetail(gateway.playlist(route.id,route.storefront)) { AppleMusicWebNativeDetail.Playlist(it) }
                else -> error("Unsupported AMS route")
            }
        }
    }

    private fun <T> mapDetail(
        result: ProviderCallResult<AppleMusicWebEditorialResult<T>>,
        project: (T) -> AppleMusicWebNativeDetail,
    ): ProviderCallResult<AppleMusicWebOutcome<AppleMusicWebNativeDetail>> = when (result) {
        is ProviderCallResult.Success -> ProviderCallResult.Success(when (val outcome = result.value.outcome) {
            is AppleMusicWebOutcome.Complete -> AppleMusicWebOutcome.Complete(project(outcome.value))
            is AppleMusicWebOutcome.Partial -> AppleMusicWebOutcome.Partial(project(outcome.value),outcome.reason)
            is AppleMusicWebOutcome.Unavailable -> outcome
        })
        is ProviderCallResult.Failed -> result
        ProviderCallResult.Denied -> ProviderCallResult.Denied
        ProviderCallResult.Stale -> ProviderCallResult.Stale
    }

    /** A bounded first page per supplemental view. Only the artist's own
     * source identity authorizes the request; 401/403/429 never become a
     * successful partial. Unknown/failed optional views remain incomplete.
     */
    private suspend fun supplementalDetail(
        gateway: AppleMusicWebEditorialGateway,
        source: AppleMusicWebNativeDetail,
    ): ProviderCallResult<com.music.bitchord.ui.screens.AmsArtistShelfSnapshot?> {
        val artist = when (source) {
            is AppleMusicWebNativeDetail.Artist -> source.value.identity
            is AppleMusicWebNativeDetail.Playlist -> return ProviderCallResult.Success(null)
            is AppleMusicWebNativeDetail.Album -> {
                val album = source.value.identity
                val candidates = source.value.artists.filter {
                    it.kind == AppleMusicWebKind.ARTIST && it.storefront == album.storefront &&
                        AppleMusicWebNativeRoute.parse(it.scopedId) != null
                }.distinctBy { it.scopedId }
                candidates.singleOrNull()?.takeIf {
                    !album.artistName.isNullOrBlank() && it.title.equals(album.artistName,ignoreCase=true)
                }
            }
        } ?: return ProviderCallResult.Success(null)
        val route = AppleMusicWebNativeRoute.parse(artist.scopedId)
            ?.takeIf { it.kind == AppleMusicWebKind.ARTIST } ?: return ProviderCallResult.Success(null)
        val views = when (source) {
            is AppleMusicWebNativeDetail.Artist -> listOf(
                AppleMusicWebArtistShelf.LIVE_ALBUMS,
                AppleMusicWebArtistShelf.COMPILATIONS,
                AppleMusicWebArtistShelf.APPEARS_ON,
            )
            is AppleMusicWebNativeDetail.Playlist -> emptyList()
            is AppleMusicWebNativeDetail.Album -> listOf(
                AppleMusicWebArtistShelf.STUDIO_ALBUMS,
                AppleMusicWebArtistShelf.SINGLES_AND_EPS,
            )
        }
        val rows = linkedMapOf<AppleMusicWebArtistShelf,List<AppleMusicWebItem>>()
        val cursors = linkedMapOf<AppleMusicWebArtistShelf,String>()
        var incomplete = false
        for (view in views) {
            val result = gateway.artistCollection(route.id,route.storefront,view,limit=24)
            when (result) {
                ProviderCallResult.Denied -> return ProviderCallResult.Denied
                ProviderCallResult.Stale -> return ProviderCallResult.Stale
                is ProviderCallResult.Failed -> {
                    val failure = result.cause
                    if (failure is AppleMusicWebHttpException && failure.status in listOf(401,403,429))
                        return result
                    incomplete = true
                }
                is ProviderCallResult.Success -> when (val outcome = result.value.outcome) {
                    is AppleMusicWebOutcome.Complete -> {
                        rows[view] = outcome.value.items
                        if (source is AppleMusicWebNativeDetail.Artist) {
                            outcome.value.nextCursor?.let { cursors[view] = it }
                        } else incomplete = incomplete || outcome.value.hasMore
                    }
                    is AppleMusicWebOutcome.Partial -> {
                        rows[view] = outcome.value.items
                        incomplete = true
                    }
                    is AppleMusicWebOutcome.Unavailable -> incomplete = true
                }
            }
        }
        return ProviderCallResult.Success(com.music.bitchord.ui.screens.AmsArtistShelfSnapshot(
            route.browseId,rows,incomplete,cursors))
    }

    /** Projection remains inside an outer editorial ticket. Reuse the
     * existing native DetailScreen payload, without publishing foreign IDs. */
    suspend fun sharedDetail(browseId: String):
        ProviderCallResult<AppleMusicWebOutcome<com.music.bitchord.ui.screens.AppleSharedDetail>> =
        admitted(editorial) { gateway ->
            when (val result = detail(browseId)) {
                is ProviderCallResult.Success -> {
                    val source = result.value
                    val value = when (source) {
                        is AppleMusicWebOutcome.Complete -> source.value
                        is AppleMusicWebOutcome.Partial -> source.value
                        is AppleMusicWebOutcome.Unavailable ->
                            return@admitted ProviderCallResult.Success(source)
                    }
                    val extra = when (val supplemental = supplementalDetail(gateway,value)) {
                        is ProviderCallResult.Success -> supplemental.value
                        is ProviderCallResult.Failed -> return@admitted supplemental
                        ProviderCallResult.Denied -> return@admitted ProviderCallResult.Denied
                        ProviderCallResult.Stale -> return@admitted ProviderCallResult.Stale
                    }
                    val page = com.music.bitchord.ui.screens.appleMusicWebAsSharedDetail(
                        browseId,value,source is AppleMusicWebOutcome.Partial,extra)
                        ?: return@admitted ProviderCallResult.Success(
                            AppleMusicWebOutcome.Unavailable("Invalid AMS presentation identity"))
                    ProviderCallResult.Success(when {
                        source is AppleMusicWebOutcome.Partial -> AppleMusicWebOutcome.Partial(page,source.reason)
                        extra?.incomplete == true -> AppleMusicWebOutcome.Partial(page,AppleMusicWebCompleteness.PAGE_LIMIT)
                        else -> AppleMusicWebOutcome.Complete(page)
                    })
                }
                is ProviderCallResult.Failed -> result
                ProviderCallResult.Denied -> ProviderCallResult.Denied
                ProviderCallResult.Stale -> ProviderCallResult.Stale
            }
        }

    /** Advance one exact view and re-project through the same classification
     * rules, including conflicts across studio/singles/live/compilation views. */
    suspend fun nextArtistDetail(previous: com.music.bitchord.ui.screens.AppleSharedDetail,
        requestedView: AppleMusicWebArtistShelf? = null):
        ProviderCallResult<AppleMusicWebOutcome<com.music.bitchord.ui.screens.AppleSharedDetail>> {
        val paging = previous.amsArtistPaging
            ?: return ProviderCallResult.Failed(IllegalArgumentException("No artist continuation"))
        val route = AppleMusicWebNativeRoute.parse(previous.page.browseId)
        val request = paging.pending.firstOrNull { requestedView == null || it.view == requestedView }
        if (route == null || route.kind != AppleMusicWebKind.ARTIST || !paging.hasMore ||
            request == null || paging.source.identity.scopedId != route.browseId ||
            request.cursor in request.visited)
            return ProviderCallResult.Failed(IllegalArgumentException("Invalid artist continuation"))
        val allowed = setOf(AppleMusicWebArtistShelf.STUDIO_ALBUMS,AppleMusicWebArtistShelf.SINGLES_AND_EPS,
            AppleMusicWebArtistShelf.ESSENTIAL_ALBUMS,AppleMusicWebArtistShelf.LIVE_ALBUMS,
            AppleMusicWebArtistShelf.COMPILATIONS,AppleMusicWebArtistShelf.APPEARS_ON,
            AppleMusicWebArtistShelf.ARTIST_PLAYLISTS)
        if (request.view !in allowed)
            return ProviderCallResult.Failed(IllegalArgumentException("Unsupported artist view"))
        return admitted(editorial) { gateway ->
            when (val result = gateway.artistCollection(route.id,route.storefront,request.view,
                limit=24,continuation=request.cursor)) {
                is ProviderCallResult.Success -> {
                    val page = (result.value.outcome as? AppleMusicWebOutcome.Complete)?.value
                        ?: return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Incomplete artist continuation"))
                    val visited = request.visited + request.cursor
                    val next = page.nextCursor
                    if (next != null && next in visited)
                        return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Cyclic artist continuation"))
                    fun append(rows: List<AppleMusicWebItem>) = (rows + page.items).distinctBy { it.scopedId }
                    var source = paging.source
                    var extra = paging.supplemental
                    when (request.view) {
                        AppleMusicWebArtistShelf.STUDIO_ALBUMS -> source = source.copy(albums=append(source.albums))
                        AppleMusicWebArtistShelf.SINGLES_AND_EPS -> source = source.copy(singlesAndEps=append(source.singlesAndEps))
                        AppleMusicWebArtistShelf.ESSENTIAL_ALBUMS -> source = source.copy(featuredAlbums=append(source.featuredAlbums))
                        AppleMusicWebArtistShelf.ARTIST_PLAYLISTS -> source = source.copy(artistPlaylists=append(source.artistPlaylists))
                        else -> {
                            val old = extra?.takeIf { it.artistBrowseId == route.browseId }
                                ?: return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Missing artist view evidence"))
                            extra = old.copy(byView=old.byView + (request.view to append(old.byView[request.view].orEmpty())))
                        }
                    }
                    val advanced = paging.pending.filterNot { it == request } +
                        listOfNotNull(next?.let { request.copy(cursor=it,visited=visited) })
                    val projected = com.music.bitchord.ui.screens.appleMusicWebAsSharedDetail(
                        route.browseId,AppleMusicWebNativeDetail.Artist(source),paging.incomplete,extra)
                        ?: return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Invalid artist detail"))
                    ProviderCallResult.Success(AppleMusicWebOutcome.Complete(projected.copy(
                        amsArtistPaging=paging.copy(source=source,supplemental=extra,pending=advanced,pages=paging.pages+1))))
                }
                is ProviderCallResult.Failed -> result
                ProviderCallResult.Denied -> ProviderCallResult.Denied
                ProviderCallResult.Stale -> ProviderCallResult.Stale
            }
        }
    }

    /** Do not infer an album from a song title or query parameter. */
    suspend fun songAlbumRoute(key: String): String? {
        val parts = key.split(':')
        if (parts.size != 4 || parts[0] != "apple_music_web" || parts[2] != "song" ||
            !parts[1].matches(Regex("[a-z]{2}")) || !parts[3].matches(Regex("[1-9][0-9]{0,11}"))) return null
        val result = admitted(editorial) { gateway -> gateway.songCollections(parts[3],parts[1]) }
        val source = (result as? ProviderCallResult.Success)?.value?.outcome as? AppleMusicWebOutcome.Complete
            ?: return null
        val song = source.value
        if (song.song.scopedId != key || song.hasMore) return null
        val album = song.albumIds.singleOrNull() ?: return null
        return AppleMusicWebNativeRoute.parse("apple_music_web:${parts[1]}:album:$album")?.browseId
    }

    suspend fun search(request: CatalogSearchRequest): ProviderCallResult<CatalogSearchPage> =
        admitted(ProviderCapability.CATALOG_SEARCH) { gateway -> AppleMusicWebSearchAdapter(gateway).search(request) }
}
