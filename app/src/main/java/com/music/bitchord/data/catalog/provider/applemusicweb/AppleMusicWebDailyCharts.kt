package com.music.bitchord.data.catalog.provider.applemusicweb

import java.text.Normalizer
import java.util.Locale

internal data class AppleMusicWebDailyChartRef(
    val id: String, val title: String, val artworkUrl: String? = null,
)
internal data class AppleMusicWebDailyChart(val region: String, val playlist: AppleMusicWebItem, val songs: List<AppleMusicWebItem>)
internal data class AppleMusicWebGenreAlbumPage(val items: List<AppleMusicWebItem>, val sourceRows: Int, val nextCursor: String?)

internal val appleMusicWebDailyRegions = listOf("global", "br", "us", "gb", "fr", "de", "jp", "kr", "in", "mx", "ca")

/** Country labels from the English chart directory; IDs are never hardcoded. */
internal fun resolveAppleMusicWebDailyChart(
    directory: List<AppleMusicWebDailyChartRef>, region: String,
): AppleMusicWebDailyChartRef? {
    fun fold(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()
    val aliases = when (region) {
        "global" -> setOf("Global")
        "br" -> setOf("Brazil", "Brasil")
        "us" -> setOf("USA", "United States")
        "gb" -> setOf("UK", "United Kingdom")
        "fr" -> setOf("France")
        "de" -> setOf("Germany")
        "jp" -> setOf("Japan")
        "kr" -> setOf("South Korea", "Korea, Republic of")
        "in" -> setOf("India")
        "mx" -> setOf("Mexico")
        "ca" -> setOf("Canada")
        "au" -> setOf("Australia")
        else -> emptySet()
    }.map(::fold).toSet()
    val prefix = Regex("^Top\\s+100\\s*:\\s*", RegexOption.IGNORE_CASE)
    return directory.filter { prefix.containsMatchIn(it.title) &&
        fold(it.title.replace(prefix, "")) in aliases }.distinctBy { it.id }.singleOrNull()
}
