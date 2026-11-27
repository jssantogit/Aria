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

/** Preserve source aspect ratio; a square cover cannot masquerade as a tall hero. */
internal fun appleMusicWebTallHeroUrl(raw: String?, width: Int, height: Int): String? {
    if (width !in 1..10000 || height !in 1..10000 || height.toLong() * 10 < width.toLong() * 11) return null
    val w = minOf(width, 900, (1600L * width / height).toInt())
    if (w < 1) return null
    val h = (w.toLong() * height / width).toInt()
    return safeAppleArtwork(raw?.replace("{w}", w.toString())?.replace("{h}", h.toString())
        ?.replace("{c}", "bb")?.replace("{q}", "85")?.replace("{f}", "jpg"))
}
