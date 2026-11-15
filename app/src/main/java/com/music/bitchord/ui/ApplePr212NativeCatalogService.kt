package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.AppleEditorialArtistLoad
import com.music.bitchord.data.catalog.metadata.AppleEditorialCatalogRepository
import com.music.bitchord.data.catalog.metadata.AppleEditorialLoad
import com.music.bitchord.data.catalog.metadata.AppleEditorialTrackLoad
import java.util.concurrent.ConcurrentHashMap
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/** Source-owned metadata requests behind PR212's unchanged navigation surface. */
internal class ApplePr212NativeCatalogService(
    private val repository: AppleEditorialCatalogRepository = AppleEditorialCatalogRepository(),
    private val artwork: com.music.bitchord.data.catalog.metadata.AppleHighResolutionArtwork =
        com.music.bitchord.data.catalog.metadata.AppleHighResolutionArtwork(),
    private val artistIdentityResolver:
        com.music.bitchord.data.catalog.metadata.ApplePr212ArtistIdentityResolver =
        com.music.bitchord.data.catalog.metadata.ApplePr212ArtistIdentityResolver(),
) {
    private val trackAlbumRoutes = ConcurrentHashMap<String, ApplePr212CatalogRoute>()

    fun albumRouteForTrack(scopedTrackId: String): ApplePr212CatalogRoute? =
        trackAlbumRoutes[scopedTrackId].takeIf {
            ApplePr212CatalogRoute.parse(scopedTrackId)?.kind ==
                ItunesCatalogItemType.TRACK
        }

    /** Search snapshots are source-scoped; never retain a previous query's track routes. */
    fun acceptSearchPage(page: ApplePr212SearchPage) {
        trackAlbumRoutes.clear()
        trackAlbumRoutes.putAll(page.trackAlbums)
    }

    fun clearSearchTracks() = trackAlbumRoutes.clear()

    /** Incremental snapshots are data only; caller owns cancellation and UI state. */
    suspend fun search(
        query: String,
        storefront: String = "br",
        onProgress: (List<CanonicalCatalogSearchRow>) -> Unit = {},
    ): List<CanonicalCatalogSearchRow>? {
        val results = linkedMapOf<String, CanonicalCatalogSearchRow>()
        trackAlbumRoutes.clear()
        var anySucceeded = false
        val artists = repository.searchArtists(query, storefront, 50)
        if (artists is AppleEditorialArtistLoad.Success) {
            anySucceeded = true
            artists.artists.mapNotNull(ApplePr212CatalogRows::artist).forEach {
                results[it.musicBrainzId] = it
            }
            onProgress(results.values.toList())
        }
        val albums = repository.searchAlbums(query, storefront, 100)
        if (albums is AppleEditorialLoad.Success) {
            anySucceeded = true
            albums.releases.mapNotNull(ApplePr212CatalogRows::album).forEach {
                results[it.musicBrainzId] = it
            }
            onProgress(results.values.toList())
        }
        val tracks = repository.searchTracks(query, storefront, 100)
        if (tracks is AppleEditorialTrackLoad.Success) {
            anySucceeded = true
            tracks.tracks.forEach { track ->
                val row = ApplePr212CatalogRows.track(track) ?: return@forEach
                val collection = track.collectionId ?: return@forEach
                val albumRoute = ApplePr212CatalogRoute.parse(
                    "itunes:$storefront:album:$collection",
                ) ?: return@forEach
                trackAlbumRoutes[row.musicBrainzId] = albumRoute
                results[row.musicBrainzId] = row
            }
            onProgress(results.values.toList())
        }
        return results.values.toList().takeIf { anySucceeded }
    }

    suspend fun artist(
        route: ApplePr212CatalogRoute,
        name: String,
        artwork: String?,
        onProgress: suspend (DetailPage) -> Unit = {},
        onVerifiedArtistMbid: suspend (String) -> Unit = {},
    ): DetailPage? {
        if (route.kind != ItunesCatalogItemType.ARTIST || name.isBlank()) return null
        val artist = ItunesCatalogItem(
            type = ItunesCatalogItemType.ARTIST,
            id = route.id, storefront = route.storefront,
            title = name, artistName = name, artistId = route.id,
            collectionId = null, storeUrl = null, releaseDate = null,
            genre = null, durationMs = null, trackNumber = null,
            discNumber = null, artworkUrl = artwork,
        )
        val pager = com.music.bitchord.data.catalog.metadata.AppleArtistDiscographyPager(
            artistId = route.id,
            artistName = name,
            country = route.storefront,
            pageSize = 24,
            source = com.music.bitchord.data.catalog.metadata.ItunesArtistAlbumDiscoverySource(
                repository,
            ),
            titleHintSource = com.music.bitchord.data.catalog.metadata
                .ApplePr212MusicBrainzTitleHints(),
        )
        val first = pager.start() as?
            com.music.bitchord.data.catalog.metadata.AppleArtistPageLoad.Success
            ?: return null
        val initial = ApplePr212DetailProjection.artistPage(artist, first.snapshot.visible)
            ?: return null
        return coroutineScope {
            var page = initial
            onProgress(page)
            val snapshot = AtomicReference(first.snapshot.visible)
            val coverProofs = ConcurrentHashMap<String, Pair<String, String>>()
            val publishMutex = Mutex()

            suspend fun publish(releases: List<com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease>) {
                val enriched = applePr212ArtistReleasesWithVerifiedCovers(releases, coverProofs)
                val projected = ApplePr212DetailProjection.artistPage(artist, enriched)
                    ?: return
                publishMutex.withLock {
                    if (projected.sections != page.sections) {
                        page = projected
                        onProgress(projected)
                    }
                }
            }

            // Artwork starts in parallel with the slower optional MB crosswalk
            // and further paged Apple queries. The first 24 native cards need
            // not remain 100px thumbnails until the entire discography finishes.
            val artworkJob = launch {
                for (batch in first.snapshot.visible.take(24)
                    .withIndex().toList().chunked(4)) {
                    val results = batch.map { entry ->
                        async {
                            val release = entry.value
                            val original = release.artworkUrl ?: return@async null
                            val improved = this@ApplePr212NativeCatalogService.artwork.upgrade(
                                original, if (entry.index == 0) 1024 else 600,
                            )
                            if (improved == null || improved == original) null
                            else release.appleScopedId to (original to improved)
                        }
                    }.awaitAll().filterNotNull()
                    if (results.isNotEmpty()) {
                        results.forEach { (id, proof) -> coverProofs[id] = proof }
                        publish(snapshot.get())
                    }
                }
            }

            // Only a proven Apple/MusicBrainz identity enables optional
            // "Included in"/"Top songs" shelves; names alone are insufficient.
            var verifiedMbid = artistIdentityResolver.resolve(
                name, route.id, route.storefront, first.snapshot.visible,
            )
            verifiedMbid?.let { onVerifiedArtistMbid(it) }
            for (pageIndex in 0 until 12) {
                if (!pager.snapshot().hasMore) break
                val next = pager.loadMore() as?
                    com.music.bitchord.data.catalog.metadata.AppleArtistPageLoad.Success
                    ?: break
                snapshot.set(next.snapshot.visible)
                publish(next.snapshot.visible)
            }
            val visible = pager.snapshot().visible
            if (verifiedMbid == null && visible.size > first.snapshot.visible.size) {
                verifiedMbid = artistIdentityResolver.resolve(
                    name, route.id, route.storefront, visible,
                )
                verifiedMbid?.let { onVerifiedArtistMbid(it) }
            }
            artworkJob.join()
            page
        }
    }

    suspend fun album(
        route: ApplePr212CatalogRoute,
        onProgress: suspend (ApplePr212AlbumDetailData) -> Unit = {},
    ): ApplePr212AlbumDetailData? {
        if (route.kind != ItunesCatalogItemType.ALBUM) return null
        val source = repository.albumDetail(route.id, route.storefront)
        if (source !is AppleEditorialLoad.Success) return null
        val exact = source.releases.singleOrNull { it.appleScopedId == route.scopedId }
            ?: return null
        val initial = ApplePr212DetailProjection.albumPage(exact) ?: return null
        onProgress(initial)
        val verified = artwork.upgrade(exact.artworkUrl, 1024)
        if (verified == exact.artworkUrl) return initial
        val updated = ApplePr212DetailProjection.albumPage(
            exact.copy(artworkUrl = verified),
        ) ?: return initial
        onProgress(updated)
        return updated
    }
}
