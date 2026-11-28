package com.music.bitchord.ui

import com.music.bitchord.ui.screens.AppleEditorialAlbumDetailPage
import com.music.bitchord.ui.screens.AppleEditorialReleaseCategory

/**
 * Provider revocation removes only cross-provider metadata, never the
 * verified Apple storefront identity, original tracks, or Apple-native hero.
 * Missing/mismatched native baseline cannot authorize an external category.
 */
internal fun appleEditionDetailWithoutExternalEvidence(
    current: AppleEditorialAlbumDetailPage,
    native: AppleEditorialAlbumDetailPage?,
): AppleEditorialAlbumDetailPage {
    val trusted = native?.takeIf {
        it.album.browseId == current.album.browseId
    }
    val album = if (trusted != null) trusted.album.copy(
        // A separately verified Apple CDN upgrade is still Apple-owned.
        artworkUri = current.album.artworkUri ?: trusted.album.artworkUri,
    ) else current.album.copy(category = AppleEditorialReleaseCategory.UNCLASSIFIED)
    return current.copy(
        album = album,
        tracks = trusted?.tracks ?: current.tracks,
        partialTracklist = trusted?.partialTracklist ?: current.partialTracklist,
        discogsAttributionText = null,
        discogsAttributionUrl = null,
        discogsNonAffiliationNotice = null,
        appleStoreUrl = trusted?.appleStoreUrl ?: current.appleStoreUrl,
        appleStoreBadgeUrl = trusted?.appleStoreBadgeUrl ?: current.appleStoreBadgeUrl,
    )
}

/** External proof changes only edition classification and attribution.
 * A concurrent source-owned cover/sibling update must survive. */
internal fun appleEditionDetailWithExternalEvidence(
    current: AppleEditorialAlbumDetailPage,
    refined: AppleEditorialAlbumDetailPage,
): AppleEditorialAlbumDetailPage {
    if (current.album.browseId != refined.album.browseId) return current
    return current.copy(
        album = current.album.copy(category = refined.album.category),
        discogsAttributionText = refined.discogsAttributionText,
        discogsAttributionUrl = refined.discogsAttributionUrl,
        discogsNonAffiliationNotice = refined.discogsNonAffiliationNotice,
    )
}
