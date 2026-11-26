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

internal fun appleMusicWebGenreSupported(tag: String): Boolean = tag in amsEditorialGenreCorpus

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
            thumbnailUrl = safeAppleArtwork(item.artworkUrl?.replace("{w}", "600")
                ?.replace("{h}", "600")?.replace("{f}", "jpg")),
            videoId = null, browseId = route.browseId,
            appleStoreUrl = appleMusicWebVerifiedStoreUrl(item))
    }
    return if (items.isEmpty()) emptyList() else listOf(HomeShelf(releasesHeading,items))
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
    if (shelf.kind != AppleMusicWebKind.ALBUM ||
        resolveAppleMusicWebHomeRoom(listOf(room), storefront, shelf) != room ||
        page.room != room || page.kind != shelf.kind) return null
    val cards = page.items.filter {
        it.kind == AppleMusicWebKind.ALBUM && it.storefront == storefront &&
            it.title.isNotBlank() && it.scopedId !in excluded
    }.groupBy { it.scopedId }.values.mapNotNull { group ->
        val item = group.first()
        if (group.any { it != item }) return@mapNotNull null
        val route = AppleMusicWebNativeRoute.parse(item.scopedId) ?: return@mapNotNull null
        ShelfItem(title = item.title, subtitle = item.artistName.orEmpty(),
            thumbnailUrl = safeAppleArtwork(item.artworkUrl?.replace("{w}", "600")
                ?.replace("{h}", "600")?.replace("{f}", "jpg")),
            videoId = null, browseId = route.browseId,
            appleStoreUrl = appleMusicWebVerifiedStoreUrl(item))
    }
    val heading = when (shelf) {
        AppleMusicWebHomeShelf.RECENT_RELEASES -> "Lançamentos recentes"
        AppleMusicWebHomeShelf.NEW_THIS_WEEK -> "Novidades da semana"
        else -> return null
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
        val result = ProviderQueryExecutor(gate).execute(capability, provider) { read(gateway(gate)) }
        if (currentGate() !== gate) return ProviderCallResult.Stale
        return when (result) {
            is ProviderCallResult.Success -> result.value
            is ProviderCallResult.Failed -> result
            ProviderCallResult.Denied -> ProviderCallResult.Denied
            ProviderCallResult.Stale -> ProviderCallResult.Stale
        }
    }

    suspend fun genre(tag: String, storefront: String, refresh: Boolean = false):
        ProviderCallResult<AppleMusicWebOutcome<AmsEditorialPage>> =
        admitted(editorial) { gateway ->
            val result = gateway.curatedGenreLanding(tag, storefront, refresh)
            when (result) {
                is ProviderCallResult.Success -> {
                    val market = result.value.storefront
                    fun project(landing: AppleMusicWebCuratorLanding): AmsEditorialPage {
                        val shelves = projectAppleMusicWebGenreLanding(landing,market,tag)
                        val requests = landing.continuations.map<AppleMusicWebRoomContinuation,AmsEditorialRequest> {
                            AmsEditorialRequest.Room(it, curator = landing.curator.identity, tag = tag)
                        }.ifEmpty {
                            if (landing.bounded && !refresh && shelves.isNotEmpty())
                                listOf(AmsEditorialRequest.RefreshGenre(tag,landing.curator.identity))
                            else emptyList()
                        }
                        return AmsEditorialPage(shelves,requests,landing.curator.identity)
                    }
                    ProviderCallResult.Success(when (val outcome = result.value.outcome) {
                        is AppleMusicWebOutcome.Complete -> AppleMusicWebOutcome.Complete(project(outcome.value))
                        is AppleMusicWebOutcome.Partial -> AppleMusicWebOutcome.Partial(project(outcome.value),outcome.reason)
                        is AppleMusicWebOutcome.Unavailable -> outcome
                    })
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
                AppleMusicWebHomeShelf.NEW_THIS_WEEK)) {
                val room = resolveAppleMusicWebHomeRoom(rooms, storefront, kind)
                if (room == null) {
                    incomplete = AppleMusicWebCompleteness.UPSTREAM_INTERRUPTED
                    continue
                }
                val outcome = when (val result = gateway.roomContents(room, kind.kind, limit = 25)) {
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
                        AppleMusicWebRoomContinuation(room,page.nextCursor), homeKind=kind)
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
        val valid = if (homeKind != null) curator == null && tag == null &&
            resolveAppleMusicWebHomeRoom(listOf(room),market,homeKind) == room &&
            homeKind.kind == AppleMusicWebKind.ALBUM
        else tag != null && curator != null && appleMusicWebGenreSupported(tag) &&
            appleMusicWebCuratorMatches(curator,market,tag) && room.curatorId == curator.id &&
            room.genreId == null && isAppleMusicWebCuratorReleaseRoom(room)
        if (!valid || (position.cursor != null && position.cursor in position.visited))
            return ProviderCallResult.Failed(AppleMusicWebProtocolException("Invalid editorial continuation"))
        return admitted(editorial) { gateway ->
            when (val result = gateway.roomContents(room,AppleMusicWebKind.ALBUM,cursor=position.cursor)) {
                is ProviderCallResult.Success -> {
                    val page = (result.value.outcome as? AppleMusicWebOutcome.Complete)?.value
                        ?: return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Incomplete editorial page"))
                    val visited = position.visited + listOfNotNull(position.cursor)
                    val next = page.nextCursor
                    if (page.room != room || page.kind != AppleMusicWebKind.ALBUM || (next != null && next in visited))
                        return@admitted ProviderCallResult.Failed(AppleMusicWebProtocolException("Invalid editorial page or cyclic cursor"))
                    val shelf = if (homeKind != null)
                        projectAppleMusicWebHomeRoom(room,page,market,homeKind)
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
                                thumbnailUrl=safeAppleArtwork(item.artworkUrl?.replace("{w}","600")
                                    ?.replace("{h}","600")?.replace("{f}","jpg")),
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
            AppleMusicWebArtistShelf.COMPILATIONS,AppleMusicWebArtistShelf.APPEARS_ON)
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
