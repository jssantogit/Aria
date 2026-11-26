package com.music.bitchord.ui

import android.content.Context
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
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
                !fields[1].matches(Regex("[a-z]{2}")) ||
                !fields[3].matches(Regex("[1-9][0-9]{0,11}"))) return null
            val kind = when (fields[2]) {
                "album" -> AppleMusicWebKind.ALBUM
                "artist" -> AppleMusicWebKind.ARTIST
                else -> return null
            }
            return AppleMusicWebNativeRoute(fields[1],kind,fields[3])
        }
    }
}

internal sealed interface AppleMusicWebNativeDetail {
    data class Album(val value: AppleMusicWebAlbum) : AppleMusicWebNativeDetail
    data class Artist(val value: AppleMusicWebArtist) : AppleMusicWebNativeDetail
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
            thumbnailUrl = item.artworkUrl, videoId = null, browseId = route.browseId,
            appleStoreUrl = item.storeUrl)
    }
    return if (items.isEmpty()) emptyList() else listOf(HomeShelf(releasesHeading,items))
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
        ProviderCallResult<AppleMusicWebOutcome<List<HomeShelf>>> =
        admitted(editorial) { gateway ->
            val result = gateway.curatedGenreLanding(tag, storefront, refresh)
            when (result) {
                is ProviderCallResult.Success -> {
                    val market = result.value.storefront
                    fun project(landing: AppleMusicWebCuratorLanding) =
                        projectAppleMusicWebGenreLanding(landing,market,tag)
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
    suspend fun home(storefront: String): ProviderCallResult<AppleMusicWebOutcome<List<HomeShelf>>> =
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
                if (page.nextCursor != null && incomplete == null)
                    incomplete = AppleMusicWebCompleteness.PAGE_LIMIT
                projectAppleMusicWebHomeRoom(room, page, storefront, kind, seen)?.let { shelf ->
                    shelves += shelf
                    seen += shelf.items.mapNotNull { it.browseId }
                }
            }
            ProviderCallResult.Success(when {
                shelves.isEmpty() && incomplete != null ->
                    AppleMusicWebOutcome.Unavailable("No verified global AMS album rooms")
                incomplete != null -> AppleMusicWebOutcome.Partial(shelves.toList(), incomplete)
                else -> AppleMusicWebOutcome.Complete(shelves.toList())
            })
        }

    suspend fun detail(browseId: String): ProviderCallResult<AppleMusicWebOutcome<AppleMusicWebNativeDetail>> {
        val route = AppleMusicWebNativeRoute.parse(browseId)
            ?: return ProviderCallResult.Failed(IllegalArgumentException("Invalid AMS route"))
        return admitted(editorial) { gateway ->
            when (route.kind) {
                AppleMusicWebKind.ALBUM -> mapDetail(gateway.album(route.id,route.storefront)) { AppleMusicWebNativeDetail.Album(it) }
                AppleMusicWebKind.ARTIST -> mapDetail(gateway.artist(route.id,route.storefront)) { AppleMusicWebNativeDetail.Artist(it) }
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

    /** Projection remains inside an outer editorial ticket. Reuse the
     * existing native DetailScreen payload, without publishing foreign IDs. */
    suspend fun sharedDetail(browseId: String):
        ProviderCallResult<AppleMusicWebOutcome<com.music.bitchord.ui.screens.AppleSharedDetail>> =
        admitted(editorial) {
            when (val result = detail(browseId)) {
                is ProviderCallResult.Success -> {
                    fun project(value: AppleMusicWebNativeDetail, incomplete: Boolean) =
                        com.music.bitchord.ui.screens.appleMusicWebAsSharedDetail(browseId,value,incomplete)
                    val outcome = when (val source = result.value) {
                        is AppleMusicWebOutcome.Complete -> project(source.value,false)?.let {
                            AppleMusicWebOutcome.Complete(it)
                        }
                        is AppleMusicWebOutcome.Partial -> project(source.value,true)?.let {
                            AppleMusicWebOutcome.Partial(it,source.reason)
                        }
                        is AppleMusicWebOutcome.Unavailable -> source
                    } ?: AppleMusicWebOutcome.Unavailable("Invalid AMS presentation identity")
                    ProviderCallResult.Success(outcome)
                }
                is ProviderCallResult.Failed -> result
                ProviderCallResult.Denied -> ProviderCallResult.Denied
                ProviderCallResult.Stale -> ProviderCallResult.Stale
            }
        }

    suspend fun search(request: CatalogSearchRequest): ProviderCallResult<CatalogSearchPage> =
        admitted(ProviderCapability.CATALOG_SEARCH) { gateway -> AppleMusicWebSearchAdapter(gateway).search(request) }
}
