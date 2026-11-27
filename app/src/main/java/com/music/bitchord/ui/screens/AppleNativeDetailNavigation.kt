package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.AppleCatalogBrowseRoute

/**
 * Search, Home and artist shelves navigate by a source-owned Apple storefront
 * product ID, never by a generic screen ("apple_native_artist") or MBID.
 */
internal fun appleNativeDetailRouteKey(
    kind: AppleNativeDetailKind,
    artist: AppleEditorialSearchRow?,
    album: AppleEditorialSearchRow?,
): String? {
    val row = when (kind) {
        AppleNativeDetailKind.ARTIST -> artist
        AppleNativeDetailKind.ALBUM -> album
        AppleNativeDetailKind.NONE -> null
    } ?: return null
    val route = AppleCatalogBrowseRoute.parse(row.browseId) ?: return null
    val type = when (kind) {
        AppleNativeDetailKind.ARTIST -> {
            if (row.kind != AppleEditorialSearchKind.ARTIST ||
                route.kind != ItunesCatalogItemType.ARTIST) return null
            "artist"
        }
        AppleNativeDetailKind.ALBUM -> {
            if (row.kind != AppleEditorialSearchKind.ALBUM ||
                route.kind != ItunesCatalogItemType.ALBUM) return null
            "album"
        }
        AppleNativeDetailKind.NONE -> return null
    }
    val exact = "itunes:${route.storefront}:$type:${route.id}"
    return exact.takeIf { it == row.browseId }
}

/**
 * An official, source-supplied storefront link is navigable only when it
 * resolves to the exact typed product already opened. Reject redirects,
 * non-Apple hosts, user-info, alternate IDs and title-based lookups.
 */
internal fun verifiedAppleDetailStoreUrl(raw: String?, browseId: String?): String? {
    val route = com.music.bitchord.ui.AppleCatalogBrowseRoute.parse(browseId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ALBUM ||
            it.kind == ItunesCatalogItemType.ARTIST } ?: return null
    val url = raw?.takeIf { it.length <= 2048 } ?: return null
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) ||
        !uri.host.equals("music.apple.com", ignoreCase = true) ||
        uri.userInfo != null || uri.port != -1 || uri.fragment != null
    ) return null
    val segments = uri.path.orEmpty().trim('/').split('/')
    val kind = when (route.kind) {
        ItunesCatalogItemType.ALBUM -> "album"
        ItunesCatalogItemType.ARTIST -> "artist"
        else -> return null
    }
    if (segments.size < 4 || segments[0] != route.storefront ||
        segments[1] != kind || segments.last() != route.id.toString()
    ) return null
    return url
}

/** Source-owned album overlay remains in the originating Home/Explore tab. */
internal fun appleDetailVisibleOnTab(
    selectedTab: Int,
    searchTab: Int,
    originatingTab: Int?,
): Boolean = if (originatingTab == null) selectedTab == searchTab
    else selectedTab == originatingTab

/**
 * Artist and album rails can both contain the SAME exact iTunes collection.
 * Distinct sources of presentation artwork do not make it two albums.
 * Conflicting source credits for one ID fail closed instead of guessing.
 */
internal fun appleNativeShelfEntry(
    browseId: String?,
    candidates: List<AppleEditorialSearchRow>,
): AppleEditorialSearchRow? {
    val matches = candidates.filter {
        it.browseId == browseId &&
            appleNativeDetailRouteKey(AppleNativeDetailKind.ALBUM, null, it) != null
    }
    val first = matches.firstOrNull() ?: return null
    if (matches.any {
        it.title != first.title || it.artistName != first.artistName ||
            it.kind != first.kind
    }) return null
    return matches.firstOrNull {
        appleProminentArtworkOrNull(it.artworkUri) != null
    } ?: first
}

/**
 * One admission contract for the visible related-release rail and its tap.
 * Conflicting observations of the same collection cannot create a dead card.
 * Missing optional artwork may be complemented without changing identity.
 */
internal fun appleNativeRelatedAlbumEntries(
    source: AppleEditorialAlbumDetailPage,
): List<AppleEditorialSearchRow> {
    val route = AppleCatalogBrowseRoute.parse(source.album.browseId)
        ?.takeIf { it.kind == ItunesCatalogItemType.ALBUM } ?: return emptyList()
    val artist = AppleCatalogBrowseRoute.parse(source.album.artistBrowseId)
        ?.takeIf {
            it.kind == ItunesCatalogItemType.ARTIST && it.storefront == route.storefront
        } ?: return emptyList()
    val artistId = "itunes:${artist.storefront}:artist:${artist.id}"
    return source.relatedAlbums.groupBy { it.browseId }.values.mapNotNull { observations ->
        val first = observations.first()
        val candidateRoute = AppleCatalogBrowseRoute.parse(first.browseId)
        if (candidateRoute?.kind != ItunesCatalogItemType.ALBUM ||
            candidateRoute.storefront != route.storefront ||
            first.browseId == source.album.browseId ||
            first.title.isBlank() ||
            observations.any {
                appleNativeDetailRouteKey(AppleNativeDetailKind.ALBUM, null, it) == null ||
                    (it.artistBrowseId != null && it.artistBrowseId != artistId)
            }
        ) return@mapNotNull null
        val admitted = appleNativeShelfEntry(first.browseId, observations)
            ?: return@mapNotNull null
        // Choose a row with independently proven credit even if a duplicate
        // without that optional field had more attractive artwork.
        val credited = observations.filter { it.artistBrowseId == artistId }
        if (admitted.artistBrowseId == artistId) admitted
        else appleNativeShelfEntry(first.browseId, credited)
    }.take(12)
}

/** Artist sections, recent release and navigation share one admitted catalog. */
internal fun appleNativeArtistAlbumEntries(
    source: AppleEditorialArtistDetailPage,
): List<AppleEditorialSearchRow> {
    val artistKey = appleNativeDetailRouteKey(
        AppleNativeDetailKind.ARTIST, source.artist, null,
    ) ?: return emptyList()
    val route = AppleCatalogBrowseRoute.parse(artistKey) ?: return emptyList()
    return source.albums.groupBy { it.browseId }.values.mapNotNull { observations ->
        val first = observations.first()
        val albumRoute = AppleCatalogBrowseRoute.parse(first.browseId)
        if (albumRoute?.storefront != route.storefront ||
            first.title.isBlank() ||
            observations.any {
                appleNativeDetailRouteKey(AppleNativeDetailKind.ALBUM, null, it) == null ||
                    (it.artistBrowseId != null && it.artistBrowseId != artistKey) ||
                    it.copy(artworkUri = null) != first.copy(artworkUri = null)
            }
        ) return@mapNotNull null
        appleNativeShelfEntry(first.browseId, observations)
    }
}

/** A failed page may not replace concurrently enriched artwork, tracks or relations. */
internal fun appleArtistPagingFailureState(
    current: UiState<AppleEditorialArtistDetailPage>?,
    expectedBrowseId: String,
): UiState<AppleEditorialArtistDetailPage>? {
    val latest = (current as? UiState.Success)?.data ?: return current
    if (latest.artist.browseId != expectedBrowseId) return current
    return UiState.Success(latest.copy(loadingMore = false, pageError = true))
}

/** A retained artist and an open album may coexist for Back navigation.
 * Only the visible route may nominate a related item. */
internal fun appleNativeShelfEntryForRoute(
    routeKey: String,
    browseId: String?,
    readAllowed: Boolean,
    artist: AppleEditorialArtistDetailPage?,
    album: AppleEditorialAlbumDetailPage?,
): AppleEditorialSearchRow? {
    if (!readAllowed) return null
    val candidates = when {
        artist?.artist?.browseId == routeKey -> appleNativeArtistAlbumEntries(artist)
        album?.album?.browseId == routeKey -> appleNativeRelatedAlbumEntries(album)
        else -> emptyList()
    }
    return appleNativeShelfEntry(browseId, candidates)
}

/**
 * Tapping a source result paints the SAME native DetailScreen immediately.
 * Network failure stays within its native artist/album shell; no extra page.
 */
internal fun appleNativePendingDetail(
    row: AppleEditorialSearchRow,
    kind: AppleNativeDetailKind,
    errorCode: String? = null,
): AppleSharedDetail? {
    if (appleNativeDetailRouteKey(
            kind, if (kind == AppleNativeDetailKind.ARTIST) row else null,
            if (kind == AppleNativeDetailKind.ALBUM) row else null,
        ) == null) return null
    val artist = kind == AppleNativeDetailKind.ARTIST
    val label = if (artist) "Artista" else row.artistName
    val errorMessage = when (errorCode) {
        null -> null
        "itunes_discography_disabled" -> "Ative a Discografia do iTunes em Provedores."
        else -> "Não foi possível carregar este catálogo do iTunes."
    }
    return AppleSharedDetail(
        page = DetailPage(
            browseId = row.browseId,
            title = row.title,
            subtitle = label,
            thumbnailUrl = if (errorMessage != null) null else
                appleProminentArtworkOrNull(row.artworkUri),
            type = if (artist) BrowseType.ARTIST else BrowseType.ALBUM,
            songs = if (errorMessage != null) UiState.Error(errorMessage)
            else UiState.Loading,
        ),
        artistHeroUrl = if (artist && errorMessage == null) row.artworkUri else null,
    )
}

/**
 * Revoked editorial permissions must not retain albums, track metadata or
 * artist shelves. The still-authorized Search may show an empty error page
 * identifying the denied capability, exactly as with Discogs.
 */
internal fun appleNativeDetailDisplayAllowed(
    detail: AppleSharedDetail,
    editorialAuthorized: Boolean,
    searchAuthorized: Boolean,
): Boolean = editorialAuthorized || (
    searchAuthorized &&
        detail.page.songs is UiState.Error &&
        detail.page.sections.isEmpty() &&
        detail.page.thumbnailUrl == null &&
        detail.metadataTracks.isEmpty() &&
        detail.artistTracks.isEmpty() &&
        detail.artistHeroUrl == null &&
        detail.verifiedArtistMbid == null &&
        detail.albumArtistBrowseId == null &&
        detail.albumArtistDisplayName == null &&
        detail.featuredRelease == null &&
        detail.notice == null &&
        detail.attribution == null
    )
