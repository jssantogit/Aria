package com.music.bitchord.data.catalog.provider.applemusicweb

import java.text.Normalizer
import java.util.Locale

internal data class AppleMusicWebCuratorRef(
    val id: String, val storefront: String, val title: String, val shortName: String? = null,
)
internal data class AppleMusicWebCurator(val identity: AppleMusicWebCuratorRef, val rooms: List<AppleMusicWebRoomRef>)

private fun curatorFold(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** Require the official resource type (enforced by the parser), exact brand
 * name/short name, same market, and an unambiguous identity. */
internal fun resolveAppleMusicWebGenreCurator(
    candidates: List<AppleMusicWebCuratorRef>, storefront: String, tag: String,
): AppleMusicWebCuratorRef? {
    fun matches(name: String): Boolean {
        val folded = curatorFold(name)
        val label = folded.removePrefix("apple music ").removeSuffix(" apple music")
        return appleMusicWebGenreNameMatches(label, tag) ||
            (tag == "rnb" && label in setOf("r b soul", "r b and soul"))
    }
    return candidates.filter { it.storefront == storefront &&
        (matches(it.title) || it.shortName?.let(::matches) == true) }
        .distinctBy { it.id }.singleOrNull()
}

/** Native genre-curator album shelves only; never fill from unrelated playlists. */
internal fun isAppleMusicWebCuratorReleaseRoom(room: AppleMusicWebRoomRef): Boolean =
    room.curatorId != null && curatorFold(room.title) in setOf(
        "new releases", "recent releases", "latest releases", "new albums", "new this week",
        "lancamentos", "lancamentos recentes", "novidades da semana", "novos albuns",
        "nouveautes", "sorties recentes", "nouveaux albums", "nouveautes de la semaine",
    )
