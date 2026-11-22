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
        detail.attribution == null
    )
