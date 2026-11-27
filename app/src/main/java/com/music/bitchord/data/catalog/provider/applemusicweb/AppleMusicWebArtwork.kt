package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.catalog.metadata.safeAppleArtwork

/** Resolve the official source's artwork template, including crop and quality.
 * Unknown placeholders or foreign hosts remain invalid; never guess a URL. */
internal fun appleMusicWebArtworkUrl(raw: String?,size: Int = 600): String? {
    if (size !in 1..1600) return null
    return safeAppleArtwork(raw?.replace("{w}",size.toString())
        ?.replace("{h}",size.toString())?.replace("{c}","bb")
        ?.replace("{q}","85")?.replace("{f}","jpg"))
}
