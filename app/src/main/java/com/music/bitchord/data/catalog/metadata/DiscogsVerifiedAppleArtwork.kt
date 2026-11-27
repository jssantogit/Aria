package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsKind
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsRecord
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderRuntime
import java.text.Normalizer
import java.util.Locale

/** A promotional Apple product, not the cover of an arbitrary Discogs pressing.
 * Retain the exact source ID and product link for the eventual attributed UI. */
internal data class DiscogsVerifiedAppleArtwork(
    val discogsReleaseId: Long,
    val appleScopedId: String,
    val artworkUrl: String,
    val storeUrl: String,
)

private fun artworkFold(value: String?): String =
    Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFC)
        .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

private fun artworkAlbumTitle(value: String): String =
    artworkFold(value).replace(Regex("""\s+[-–—]\s+(?:single|ep)$"""), "")

/** Restrict the first bridge to explicit digital, flat, numbered audio editions.
 * Unknown formats, physical packaging and mixed-media editions fail closed. */
internal fun discogsDigitalArtworkUpc(record: DiscogsRecord): String? {
    if (record.link.kind != DiscogsKind.RELEASE ||
        record.link.id !in 1..999_999_999_999L ||
        record.unofficial || record.nativeTracklistIncomplete ||
        record.artistIds.size != 1 || record.artistIds.single() <= 0L ||
        record.artistNames.size != 1 || record.artistNames.single().isBlank() ||
        record.title.isBlank() || record.year == null
    ) return null
    val formats = record.formats.map(::artworkFold).toSet()
    val digital = setOf("file", "digital", "digital media", "download")
    val allowed = digital + setOf("album", "ep", "single", "flac", "mp3",
        "aac", "wav", "alac", "aiff", "lossless", "stereo")
    if (formats.intersect(digital).isEmpty() || !allowed.containsAll(formats))
        return null
    val tracks = record.tracklist
    if (tracks.size !in 1..199 ||
        tracks.withIndex().any { (index, track) ->
            track.first.trim().toIntOrNull() != index + 1 || track.second.isBlank()
        } ||
        record.displayTracks.map { artworkFold(it.title) } != tracks.map { artworkFold(it.second) } ||
        record.displayTracks.any { it.workTitle != null }
    ) return null
    // Do not silently pick one of multiple asserted barcodes.
    val barcode = record.barcodes.map { it.replace(Regex("\\s+"), "") }.distinct()
        .singleOrNull() ?: return null
    if (!Regex("[0-9]{8,14}").matches(barcode)) return null
    return barcode
}

/** Only consume the result of lookup?upc=<barcode>&entity=song in this storefront.
 * An exact source title/credit, release year and ordered audio tracklist must
 * independently corroborate that association. No search, crosswalk or audio ID. */
internal fun discogsVerifiedAppleArtwork(
    record: DiscogsRecord,
    upc: String,
    response: ItunesCatalogResult,
    storefront: String,
): DiscogsVerifiedAppleArtwork? {
    if (discogsDigitalArtworkUpc(record) != upc ||
        !Regex("[a-z]{2}").matches(storefront)) return null
    val result = response as? ItunesCatalogResult.Success ?: return null
    if (result.possiblyTruncated) return null
    val album = result.items.filter { it.type == ItunesCatalogItemType.ALBUM }
        .singleOrNull() ?: return null
    val tracks = result.items.filter { it.type == ItunesCatalogItemType.TRACK }
    if (result.items.size != tracks.size + 1 ||
        album.storefront != storefront || album.id <= 0L ||
        album.collectionId != album.id || (album.artistId ?: 0L) <= 0L ||
        album.trackCount != tracks.size || tracks.size != record.tracklist.size ||
        album.upc?.let { it != upc } == true ||
        artworkAlbumTitle(album.title) != artworkAlbumTitle(record.title) ||
        artworkFold(album.artistName) != artworkFold(record.artistNames.single()) ||
        album.releaseDate?.take(4)?.toIntOrNull() != record.year ||
        tracks.any { it.storefront != storefront || it.collectionId != album.id ||
            it.discNumber != 1 }
    ) return null
    val release = ItunesEditorialCatalog.assemble(storefront, result.items).singleOrNull()
        ?: return null
    if (!ItunesEditorialCatalog.hasExactAudioMembership(release) ||
        release.tracks.withIndex().any { (index, row) ->
            row.trackNumber != index + 1 ||
                artworkFold(row.title) != artworkFold(record.tracklist[index].second)
        }
    ) return null
    val art = safeAppleArtwork(album.artworkUrl) ?: return null
    val store = appleStoreLinkForExactProduct(
        album.storeUrl, ItunesCatalogItemType.ALBUM, album.id, storefront,
    ) ?: return null
    return DiscogsVerifiedAppleArtwork(record.link.id, album.scopedId, art, store)
}

/** Capability-scoped transport. No cross-catalog permission or title search.
 * No persistent proof cache: admission is checked before and after the request. */
internal class DiscogsAppleArtworkResolver(
    private val lookup: AppleArtworkUpcSource = AppleArtworkUpcSource { upc, country ->
        ItunesCatalogClient().lookupUpcForArtwork(upc, country)
    },
    private val gateProvider: () -> ProviderPolicyGate? = { ProviderRuntime.gateOrNull() },
) {
    suspend fun resolve(record: DiscogsRecord, storefront: String = "br"):
        DiscogsVerifiedAppleArtwork? {
        val upc = discogsDigitalArtworkUpc(record) ?: return null
        if (!Regex("[a-z]{2}").matches(storefront)) return null
        val gate = gateProvider() ?: return null
        val ticket = gate.open(ProviderCapability.RELEASE_ARTWORK, BuiltInProviderKeys.ITUNES)
            ?: return null
        val result = lookup.lookup(upc, storefront)
        if (gateProvider() !== gate || !gate.accept(ticket)) return null
        return discogsVerifiedAppleArtwork(record, upc, result, storefront)
    }
}
