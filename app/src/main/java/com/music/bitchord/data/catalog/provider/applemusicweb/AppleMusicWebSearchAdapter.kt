package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.CatalogNativeIdentity
import com.music.bitchord.data.providers.CatalogSearchContinuation
import com.music.bitchord.data.providers.CatalogSearchAdapter
import com.music.bitchord.data.providers.CatalogSearchItem
import com.music.bitchord.data.providers.CatalogSearchKind
import com.music.bitchord.data.providers.CatalogSearchPage
import com.music.bitchord.data.providers.CatalogSearchRequest
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderCapability

/**
 * The Apple Web client is an adapter of the #219 catalog policy, never a
 * competing primary catalog or a conversion to iTunes/MusicBrainz identity.
 *
 * ProviderQueryExecutor in the gateway handles admission and cancellation.
 * Provider ordering and fallback remain in #219's shared catalog router.
 */
internal class AppleMusicWebSearchAdapter(
    private val gateway: AppleMusicWebEditorialGateway,
) : CatalogSearchAdapter {
    override val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
    override val kinds: Set<CatalogSearchKind> = CatalogSearchKind.entries.toSet()

    override suspend fun search(
        request: CatalogSearchRequest,
    ): ProviderCallResult<CatalogSearchPage> {
        if (request.continuation?.matches(key, request) == false)
            return ProviderCallResult.Failed(IllegalArgumentException("Foreign Apple continuation"))
        val sourceKind = when (request.kind) {
            CatalogSearchKind.ARTIST -> AppleMusicWebKind.ARTIST
            CatalogSearchKind.ALBUM -> AppleMusicWebKind.ALBUM
            CatalogSearchKind.TRACK -> AppleMusicWebKind.SONG
        }
        val previous = request.continuation
        val seen = previous?.seenNativeIds.orEmpty().toMutableSet()
        val visited = previous?.visitedTokens.orEmpty().toMutableSet()
        var cursor = previous?.token
        var overlaps = 0
        // Every physical request still passes through the policy gateway.
        // Skip at most three entirely repeated upstream pages; never fabricate
        // a terminal page when the source still advertises more.
        repeat(3) {
            if (cursor != null && !visited.add(requireNotNull(cursor)))
                return ProviderCallResult.Failed(AppleMusicWebProtocolException("Cyclic cursor"))
            val response = gateway.search(
                sourceKind, request.query, request.storefront, request.page, request.limit, cursor,
            )
            val raw = when (response) {
                ProviderCallResult.Denied -> return ProviderCallResult.Denied
                ProviderCallResult.Stale -> return ProviderCallResult.Stale
                is ProviderCallResult.Failed -> return ProviderCallResult.Failed(response.cause)
                is ProviderCallResult.Success -> {
                    val source = response.value
                    if (source.provider != key || source.capability != ProviderCapability.CATALOG_SEARCH)
                        return ProviderCallResult.Failed(AppleMusicWebProtocolException("Wrong Apple catalog provenance"))
                    when (val outcome = source.outcome) {
                        is AppleMusicWebOutcome.Complete -> project(request, source.storefront, outcome.value, false)
                        is AppleMusicWebOutcome.Partial ->
                            return ProviderCallResult.Failed(AppleMusicWebProtocolException("Partial Apple search page"))
                        is AppleMusicWebOutcome.Unavailable ->
                            return ProviderCallResult.Failed(AppleMusicWebProtocolException("Apple catalog unavailable"))
                    }
                }
            }
            val unique = raw.items.filter { seen.add(it.identity.nativeId) }
            overlaps += raw.items.size - unique.size
            val next = raw.continuation?.token
            if (next != null && next in visited)
                return ProviderCallResult.Failed(AppleMusicWebProtocolException("Cyclic cursor"))
            if (unique.isNotEmpty() || !raw.hasMore) {
                return ProviderCallResult.Success(raw.copy(
                    items = rankAppleMusicWebSearchPage(request.query, request.kind, unique),
                    sourceOverlapCount = overlaps,
                    continuation = raw.continuation?.copy(
                        seenNativeIds = seen.toSet(), visitedTokens = visited.toSet(),
                    ),
                ))
            }
            if (next == null)
                return ProviderCallResult.Failed(AppleMusicWebProtocolException("Missing Apple continuation"))
            cursor = next
        }
        return ProviderCallResult.Failed(AppleMusicWebProtocolException("No unique Apple search progress"))
    }

    private fun project(
        request: CatalogSearchRequest,
        storefront: String,
        source: AppleMusicWebPage,
        partial: Boolean,
    ): CatalogSearchPage = CatalogSearchPage(
        provider = key,
        capability = ProviderCapability.CATALOG_SEARCH,
        storefront = storefront,
        kind = request.kind,
        items = source.items.mapIndexed { index, item ->
            val sourceKind = when (item.kind) {
                AppleMusicWebKind.ARTIST -> CatalogSearchKind.ARTIST
                AppleMusicWebKind.ALBUM -> CatalogSearchKind.ALBUM
                AppleMusicWebKind.SONG -> CatalogSearchKind.TRACK
                else -> throw AppleMusicWebProtocolException("Wrong Apple catalog type")
            }
            if (sourceKind != request.kind || item.storefront != storefront)
                throw AppleMusicWebProtocolException("Cross-source Apple catalog item")
            CatalogSearchItem(
                identity = CatalogNativeIdentity(
                    provider = key, kind = request.kind,
                    nativeId = item.id, storefront = item.storefront,
                ),
                title = item.title,
                subtitle = item.artistName,
                artworkUrl = item.artworkUrl,
                storeUrl = item.storeUrl,
                sourcePosition = index + 1,
                artistNativeId = item.artistId,
                albumNativeId = item.albumId,
                releaseDate = item.releaseDate,
                durationMs = item.durationMillis,
            )
        },
        page = source.page,
        hasMore = source.hasMore,
        partial = partial,
        continuation = source.nextCursor?.let {
            CatalogSearchContinuation(key, request.kind, request.query, storefront,
                source.page + 1, request.limit, it)
        },
    )
}
