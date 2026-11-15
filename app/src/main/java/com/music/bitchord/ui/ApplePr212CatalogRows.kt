package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind

/** A typed product key is never a MusicBrainz UUID or permission to play. */
internal data class ApplePr212CatalogRoute(
    val storefront: String,
    val kind: ItunesCatalogItemType,
    val id: Long,
) {
    val scopedId: String get() = "itunes:$storefront:${kind.key}:$id"

    companion object {
        private val key = Regex("^itunes:([a-z]{2}):(artist|album|track):([1-9][0-9]*)$")
        fun parse(value: String?): ApplePr212CatalogRoute? {
            val match = key.matchEntire(value ?: return null) ?: return null
            val kind = when (match.groupValues[2]) {
                "artist" -> ItunesCatalogItemType.ARTIST
                "album" -> ItunesCatalogItemType.ALBUM
                "track" -> ItunesCatalogItemType.TRACK
                else -> return null
            }
            val id = match.groupValues[3].toLongOrNull()?.takeIf { it > 0L }
                ?: return null
            return ApplePr212CatalogRoute(match.groupValues[1], kind, id)
        }
    }
}

/**
 * Projection into the PR212 Search/Detail *data model*, not its UI code.
 * musicBrainzId is the legacy field name for the row's opaque key. The content
 * is ALWAYS an explicit itunes: product ID here; a UUID is never manufactured.
 * Playback and MBID-only metadata actions fail closed on this non-UUID key.
 */
internal object ApplePr212CatalogRows {
    fun artist(item: ItunesCatalogItem): CanonicalCatalogSearchRow? {
        val route = ApplePr212CatalogRoute.parse(item.scopedId)
            ?.takeIf { it.kind == ItunesCatalogItemType.ARTIST } ?: return null
        if (item.type != ItunesCatalogItemType.ARTIST ||
            item.title.isBlank() || route.id != item.id) return null
        return CanonicalCatalogSearchRow(
            musicBrainzId = route.scopedId,
            kind = CanonicalSearchKind.ARTIST,
            title = item.title,
            subtitle = null,
            artworkUri = item.artworkUrl,
        )
    }

    fun album(item: ItunesEditorialRelease): CanonicalCatalogSearchRow? {
        val route = ApplePr212CatalogRoute.parse(item.appleScopedId)
            ?.takeIf { it.kind == ItunesCatalogItemType.ALBUM } ?: return null
        if (item.appleCollectionId != route.id ||
            item.storefront != route.storefront ||
            item.title.isBlank() || item.artistName.isBlank()) return null
        return CanonicalCatalogSearchRow(
            musicBrainzId = route.scopedId,
            kind = CanonicalSearchKind.RELEASE,
            title = item.title,
            subtitle = item.artistName,
            releaseDate = item.releaseDate,
            releaseKind = when (item.format) {
                ItunesEditorialReleaseFormat.ALBUM -> "Album"
                ItunesEditorialReleaseFormat.EP -> "EP"
                ItunesEditorialReleaseFormat.SINGLE -> "Single"
                ItunesEditorialReleaseFormat.UNCLASSIFIED -> null
            },
            artworkUri = item.artworkUrl,
        )
    }

    fun tracks(album: ApplePr212AlbumDetailData): List<CanonicalCatalogSearchRow> =
        album.tracks.map { track ->
            CanonicalCatalogSearchRow(
                musicBrainzId = track.scopedId,
                kind = CanonicalSearchKind.RECORDING,
                title = track.title,
                subtitle = track.artist,
                hasCatalogedAsset = false,
            )
        }
}
