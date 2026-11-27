package com.music.bitchord.ui.screens

/** A MusicBrainz recording is an exact UUID, never a title, URL, videoId or MediaAsset.
 * Callers must already hold a Recording MBID obtained from a trusted catalog response.
 */
private val CATALOG_RECORDING_MBID = Regex(
    """^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$"""
)

internal fun isVerifiedCatalogRecordingId(id: String): Boolean =
    CATALOG_RECORDING_MBID.matches(id)

/** Local metadata actions also accept a typed source-owned song reference.
 * Never broaden MBID verification or treat a source reference as a MediaAsset. */
internal fun isLocalMetadataRecordingId(id: String): Boolean =
    isVerifiedCatalogRecordingId(id) || com.music.bitchord.ui.amsSongStorefront(id) != null

internal fun amsLocalRecordingLabel(id: String?, title: String, credit: String): CatalogMetadataRecordingLabel? =
    id?.takeIf { com.music.bitchord.ui.amsSongStorefront(it) != null }
        ?.let { CatalogMetadataRecordingLabel(it,title.trim(),credit.trim()) }
        ?.takeIf(::validCatalogMetadataRecordingLabel)

internal fun amsCollectionRecordingLabels(
    browseId: String, tracks: List<NativeMetadataTrack>, complete: Boolean,
): List<CatalogMetadataRecordingLabel>? {
    val route = com.music.bitchord.ui.AppleMusicWebNativeRoute.parse(browseId) ?: return null
    if (!complete || route.kind !in setOf(
        com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind.ALBUM,
        com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind.PLAYLIST) ||
        tracks.isEmpty() || tracks.size > 500) return null
    val labels = tracks.map { track ->
        if (com.music.bitchord.ui.amsSongStorefront(track.key) != route.storefront) return null
        amsLocalRecordingLabel(track.key,track.title,track.credit.orEmpty()) ?: return null
    }
    if (labels.groupBy { it.recordingId }.values.any { it.distinct().size != 1 }) return null
    return labels.distinctBy { it.recordingId }
}

/** A metadata-only Recording may be SAVED to a normal playlist but NEVER queued
 * until playback independently resolves a verified playable MediaAsset. */
internal fun planMetadataOnlyPlaylistMembers(
    existing: List<String>,
    recordingId: String,
): List<String>? {
    if (!isLocalMetadataRecordingId(recordingId) ||
        existing.size >= 50_000 && recordingId !in existing
    ) return null
    return if (recordingId in existing) existing else existing + recordingId
}
