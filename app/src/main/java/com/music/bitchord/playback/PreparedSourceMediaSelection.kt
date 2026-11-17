package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * An authorized *winner* is discovered before Media3 builds a source parser.
 * The returned physical URI is also the base for relative HLS/DASH segments;
 * keeping the virtual bitchord URI as the playlist base would create invalid
 * bitchord://source/segment.ts requests instead of HTTP segment requests.
 *
 * Only the MediaSource's private child receives [item] with the physical URI.
 * The enclosing MediaSource retains the logical item and its media identity.
 */
internal data class PreparedSourceMediaSelection(
    val item: MediaItem,
    val resolved: DataSpec,
) {
    val playbackLease: ProviderPlaybackByteLease?
        get() = resolved.customData as? ProviderPlaybackByteLease
}

internal fun prepareSourceMediaSelection(
    original: MediaItem,
    resolved: DataSpec,
): PreparedSourceMediaSelection {
    val virtualUri = original.localConfiguration?.uri
        ?: throw IOException("Source media item has no URI")
    if (!isLogicalSourcePlaybackUri(virtualUri))
        throw IOException("Source preselection requires a logical source item")
    if (isLogicalSourcePlaybackUri(resolved.uri) || isRetiredWatchCacheRequest(resolved.uri))
        throw IOException("Provider did not resolve an openable media resource")
    val lease = resolved.customData as? ProviderPlaybackByteLease
    if (lease != null) {
        if (!lease.allowed()) throw IOException("Provider selection ticket is no longer valid")
        val scheme = resolved.uri.scheme
        if (scheme != "http" && scheme != "https")
            throw IOException("Provider resolved a forbidden non-HTTP media resource")
    }
    val mime = StreamContainer.manifestMimeOf(resolved.uri.toString())
    val child = original.buildUpon()
        .setUri(resolved.uri)
        .setMimeType(mime)
        .build()
    return PreparedSourceMediaSelection(child, resolved)
}
