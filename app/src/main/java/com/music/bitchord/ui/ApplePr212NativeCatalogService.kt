package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.AppleEditorialArtistLoad
import com.music.bitchord.data.catalog.metadata.AppleEditorialCatalogRepository
import com.music.bitchord.data.catalog.metadata.AppleEditorialLoad
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow

/** Source-owned metadata requests behind PR212's unchanged navigation surface. */
internal class ApplePr212NativeCatalogService(
    private val repository: AppleEditorialCatalogRepository = AppleEditorialCatalogRepository(),
) {
    /** Incremental snapshots are data only; caller owns cancellation and UI state. */
    suspend fun search(
        query: String,
        storefront: String = "br",
        onProgress: (List<CanonicalCatalogSearchRow>) -> Unit = {},
    ): List<CanonicalCatalogSearchRow>? {
        val results = linkedMapOf<String, CanonicalCatalogSearchRow>()
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
        return results.values.toList().takeIf { anySucceeded }
    }

    suspend fun artist(
        route: ApplePr212CatalogRoute,
        name: String,
        artwork: String?,
        onProgress: suspend (DetailPage) -> Unit = {},
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
        var page = ApplePr212DetailProjection.artistPage(artist, first.snapshot.visible)
            ?: return null
        onProgress(page)
        // Publish append-only local display pages without ever rebuilding
        // another screen. The page's native scroll identity stays unchanged.
        for (pageIndex in 0 until 12) {
            if (!pager.snapshot().hasMore) break
            val next = pager.loadMore() as?
                com.music.bitchord.data.catalog.metadata.AppleArtistPageLoad.Success
                ?: break
            val updated = ApplePr212DetailProjection.artistPage(
                artist, next.snapshot.visible,
            ) ?: continue
            if (updated.sections != page.sections) {
                page = updated
                onProgress(page)
            }
        }
        return page
    }

    suspend fun album(route: ApplePr212CatalogRoute): ApplePr212AlbumDetailData? {
        if (route.kind != ItunesCatalogItemType.ALBUM) return null
        val source = repository.albumDetail(route.id, route.storefront)
        if (source !is AppleEditorialLoad.Success) return null
        val exact = source.releases.singleOrNull { it.appleScopedId == route.scopedId }
            ?: return null
        return ApplePr212DetailProjection.albumPage(exact)
    }
}
