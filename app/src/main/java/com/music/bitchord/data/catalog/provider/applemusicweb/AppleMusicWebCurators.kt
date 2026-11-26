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

/** Public storefront-specific references observed on music.apple.com/br.
 * These are nomination evidence, never proof of a valid runtime page.
 * The exact Apple resource type, ID and semantic name must be revalidated.
 * No cross-market ID reuse and no parent substitution for a subgenre.
 */
internal fun appleMusicWebPublicCuratorReference(storefront: String, tag: String): String? =
    if (storefront != "br") null else when (tag) {
        "pop" -> "976439548"
        "rock" -> "976439554"
        "hip-hop", "rap" -> "989061413"
        "reggae" -> "976439552"
        "soul" -> "976439585"
        "samba", "pagode" -> "982356287"
        "forro" -> "1586015196"
        "blues" -> "976439528"
        "indie" -> "976439541"
        "electronic" -> "989076708"
        "jazz" -> "976439542"
        "k-pop" -> "988658197"
        "metal" -> "976439543"
        "rnb" -> "976439551"
        "hard rock" -> "979231690"
        else -> null
    }

internal fun appleMusicWebCuratorQueries(tag: String): List<String> {
    val localized = when (tag) {
        "hip-hop", "rap" -> "hip-hop/rap"
        "electronic" -> "eletrônica"
        "rnb" -> "R&B"
        "forro" -> "forró"
        else -> tag
    }
    val shared = when (tag) {
        "soul" -> "soul e funk"
        "samba", "pagode" -> "pagode e samba"
        "forro" -> "forró e piseiro"
        else -> localized
    }
    return listOf(localized, "Apple Music " + localized, "Apple Music " + shared, "Apple Music " + tag).distinct()
}

/** Require the official resource type (enforced by the parser), exact brand
 * name/short name, same market, and an unambiguous identity. */
internal fun resolveAppleMusicWebGenreCurator(
    candidates: List<AppleMusicWebCuratorRef>, storefront: String, tag: String,
): AppleMusicWebCuratorRef? {
    fun matches(name: String): Boolean {
        val folded = curatorFold(name)
        val label = folded.removePrefix("apple music ").removeSuffix(" apple music")
            .removePrefix("musica ").removeSuffix(" music")
        return appleMusicWebGenreNameMatches(label, tag) ||
            (tag == "rnb" && label in setOf("r b", "r b soul", "r b and soul"))
    }
    return candidates.filter { it.storefront == storefront &&
        (matches(it.title) || it.shortName?.let(::matches) == true) }
        .distinctBy { it.id }.singleOrNull()
}

/** The source itself groups these categories. This proves editorial origin,
 * not exact genre membership. Only explicitly labeled albums qualify. */
internal fun appleMusicWebCuratorIsSharedForTag(ref: AppleMusicWebCuratorRef, tag: String): Boolean {
    fun matches(name: String): Boolean {
        val label = curatorFold(name).removePrefix("apple music ").removeSuffix(" apple music")
        return when (tag) {
            "soul" -> label in setOf("soul e funk", "soul funk", "soul and funk")
            "samba", "pagode" -> label in setOf("pagode e samba", "samba e pagode", "samba and pagode", "pagode and samba")
            "forro" -> label in setOf("forro e piseiro", "forro and piseiro")
            else -> false
        }
    }
    return matches(ref.title) || ref.shortName?.let(::matches) == true
}

internal fun appleMusicWebCuratorMatches(ref: AppleMusicWebCuratorRef, storefront: String, tag: String): Boolean =
    ref.storefront == storefront &&
        (resolveAppleMusicWebGenreCurator(listOf(ref), storefront, tag) != null ||
            (appleMusicWebPublicCuratorReference(storefront, tag) == ref.id &&
                appleMusicWebCuratorIsSharedForTag(ref, tag)))

internal enum class AppleMusicWebCuratedAlbumEvidence {
    DEDICATED_CURATOR, EXACT_GENRE_LABEL, SOUL_COMBINED_LABEL, REJECTED,
}

/** A combined R&B/Soul label is evidence only inside a verified release
 * room of the official Soul/Funk curator. It is not an alias for Soul
 * in generic search or other curators. Preserve the distinction in reports. */
internal fun appleMusicWebCuratedAlbumEvidence(
    ref: AppleMusicWebCuratorRef, tag: String, item: AppleMusicWebItem,
    room: AppleMusicWebRoomRef? = null,
): AppleMusicWebCuratedAlbumEvidence {
    if (item.kind != AppleMusicWebKind.ALBUM || item.storefront != ref.storefront ||
        !appleMusicWebCuratorMatches(ref, ref.storefront, tag) ||
        (room != null && (room.curatorId != ref.id || room.storefront != ref.storefront ||
            room.genreId != null || !isAppleMusicWebCuratorReleaseRoom(room))))
        return AppleMusicWebCuratedAlbumEvidence.REJECTED
    if (!appleMusicWebCuratorIsSharedForTag(ref, tag))
        return AppleMusicWebCuratedAlbumEvidence.DEDICATED_CURATOR
    if (item.genreNames.any { appleMusicWebGenreNameMatches(it, tag) })
        return AppleMusicWebCuratedAlbumEvidence.EXACT_GENRE_LABEL
    // R&B alone is not equivalent; only Apple's explicit combined label.
    if (tag == "soul" && room != null &&
        item.genreNames.any { curatorFold(it) in setOf("r b soul", "r b e soul", "r b and soul") })
        return AppleMusicWebCuratedAlbumEvidence.SOUL_COMBINED_LABEL
    return AppleMusicWebCuratedAlbumEvidence.REJECTED
}

internal fun appleMusicWebCuratedAlbumMatches(
    ref: AppleMusicWebCuratorRef, tag: String, item: AppleMusicWebItem,
    room: AppleMusicWebRoomRef? = null,
): Boolean = appleMusicWebCuratedAlbumEvidence(ref, tag, item, room) !=
    AppleMusicWebCuratedAlbumEvidence.REJECTED

/** Native genre-curator album shelves only; never fill from unrelated playlists. */
internal fun isAppleMusicWebCuratorReleaseRoom(room: AppleMusicWebRoomRef): Boolean =
    room.curatorId != null && curatorFold(room.title) in setOf(
        "new releases", "recent releases", "latest releases", "new albums", "new this week",
        "lancamentos", "lancamentos recentes", "novidades da semana", "novos albuns",
        "nouveautes", "sorties recentes", "nouveaux albums", "nouveautes de la semaine",
    )
