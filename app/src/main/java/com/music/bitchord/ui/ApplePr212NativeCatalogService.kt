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
    ): DetailPage? {
        if (route.kind != ItunesCatalogItemType.ARTIST || name.isBlank()) return null
        val source = repository.artistDiscography(route.id, route.storefront)
        if (source !is AppleEditorialLoad.Success) return null
        val artist = ItunesCatalogItem(
            type = ItunesCatalogItemType.ARTIST,
            id = route.id,
            storefront = route.storefront,
            title = name,
            artistName = name,
            artistId = route.id,
            collectionId = null,
            storeUrl = null,
            releaseDate = null,
            genre = null,
            durationMs = null,
            trackNumber = null,
            discNumber = null,
            artworkUrl = artwork,
        )
        return ApplePr212DetailProjection.artistPage(artist, source.releases)
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
