package com.music.bitchord.ui

import android.content.Context
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.providers.*

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

    suspend fun search(request: CatalogSearchRequest): ProviderCallResult<CatalogSearchPage> =
        admitted(ProviderCapability.CATALOG_SEARCH) { gateway -> AppleMusicWebSearchAdapter(gateway).search(request) }
}
