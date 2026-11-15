package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import java.util.Locale

private val canonicalArtistEvidenceMbidPattern =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * Use a cross-catalog ID exclusively to request *optional* PR212 sections:
 * the original Apple browse ID stays responsible for metadata, artwork,
 * release navigation, ratings and user context.
 *
 * A name collision and a forged Apple track/album product can never enable a
 * MusicBrainz artist chart, collaborations or related-artist shelf.
 */
internal fun applePr212ArtistEvidenceId(
    page: DetailPage,
    verifiedAppleToMbArtist: Map<String, String>,
): String? {
    if (page.type != BrowseType.ARTIST) return null
    val evidence = if (page.browseId.startsWith("catalog:ARTIST:")) {
        page.browseId.removePrefix("catalog:ARTIST:")
    } else {
        val route = ApplePr212CatalogRoute.parse(page.browseId)
            ?.takeIf { it.kind == ItunesCatalogItemType.ARTIST } ?: return null
        verifiedAppleToMbArtist[route.scopedId] ?: return null
    }.lowercase(Locale.ROOT)
    return evidence.takeIf(canonicalArtistEvidenceMbidPattern::matches)
}
