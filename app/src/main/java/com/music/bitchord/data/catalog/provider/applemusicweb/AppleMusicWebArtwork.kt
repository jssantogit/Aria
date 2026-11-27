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

/** An album's own editorial video is identity-backed, unlike name searches.
 * Only known motion fields are accepted. Other URLs remain metadata. */
internal fun appleMusicWebAlbumMotion(video: kotlinx.serialization.json.JsonObject?, tall: Boolean): Pair<String,String?>? {
    fun link(key: String): String? {
        val asset = video?.get(key) as? kotlinx.serialization.json.JsonObject ?: return null
        return listOf("video","videoUrl","hlsUrl","url").firstNotNullOfOrNull { field ->
            val value = asset[field] as? kotlinx.serialization.json.JsonPrimitive
            com.music.bitchord.data.canvas.appleEditorialMotionUrl(value?.takeIf { it.isString }?.content)
        }
    }
    val square = link("motionDetailSquare") ?: link("motionSquareVideo1x1")
    val vertical = link("motionDetailTall") ?: link("motionTallVideo3x4")
    val raw = link("motionDetailRaw")
    val candidates = (if (tall) listOfNotNull(vertical,square,raw) else listOfNotNull(square,raw,vertical)).distinct()
    return candidates.firstOrNull()?.let { it to candidates.getOrNull(1) }
}
