package com.music.bitchord.data.catalog.provider.applemusicweb

import java.text.Normalizer
import java.util.Locale

@kotlinx.serialization.Serializable
internal data class AppleMusicWebCuratorRef(
    val id: String, val storefront: String, val title: String, val shortName: String? = null,
)
@kotlinx.serialization.Serializable
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
    return matches(ref.title) || ref.shortName?.let(::matches) == true || amsVerifiedStyleParent(ref,tag)
}

/** Exact public parent anchor only. Every child item still needs child evidence. */
internal fun amsVerifiedStyleParent(ref: AppleMusicWebCuratorRef, tag: String): Boolean {
    val parent = amsEditorialStyleParents[tag] ?: return false
    return appleMusicWebPublicCuratorReference(ref.storefront,parent) == ref.id &&
        resolveAppleMusicWebGenreCurator(listOf(ref),ref.storefront,parent) == ref
}

/** Official curator playlist room plus a precisely named style playlist is
 * source evidence for the PLAYLIST, never for every album/artist in it. */
internal fun amsExactStylePlaylistTitle(title: String,tag: String): Boolean {
    if (tag !in amsEditorialStyleParents) return false
    val name = curatorFold(title)
    val labels = listOf(name, name.removeSuffix(" essenciais"), name.removeSuffix(" essentials"),
        name.removePrefix("essenciais de "), name.removePrefix("essenciais do "),
        name.removePrefix("essenciais da "),name.removeSuffix(" essential"),
        name.removePrefix("o melhor do "),name.removePrefix("o melhor da "),
        name.removePrefix("o melhor de "),name.removePrefix("best of "),
        name.removeSuffix(" joias ocultas"), name.removeSuffix(" hidden gems"))
    return labels.any { appleMusicWebGenreNameMatches(it,tag) } ||
        (tag == "heavy metal" && labels.any { it in setOf("metal classico","classic metal") }) ||
        (tag == "thrash metal" && name == "thrash") ||
        (tag == "trap" && labels.any { it in setOf("trap stars brasil","trap stars brazil") }) ||
        (tag == "drill" && labels.any { it in setOf("drill de nova york","new york drill","drill atual") }) ||
        (tag == "alternative rock" && labels.any { it in setOf("rock alternativo para malhar","rock alternativo da madrugada") })
}

/** Explicit public Brazilian reference for traditional heavy metal, only a
 * nomination: runtime detail must prove exact ID, title, market and credit. */
internal fun amsStylePlaylistReference(tag: String, market: String): String? =
    if (tag == "heavy metal" && market == "br") "pl.cd0d991ea9b74d6298e094799ea94ee7" else null

internal fun amsVerifiedStylePlaylistReference(item: AppleMusicWebItem, tag: String, market: String): Boolean =
    item.kind == AppleMusicWebKind.PLAYLIST && item.storefront == market &&
        amsStylePlaylistReference(tag,market) == item.id &&
        curatorFold(item.artistName.orEmpty()) in setOf("apple music metal","metal apple music") &&
        amsExactStylePlaylistTitle(item.title,tag)

internal fun appleMusicWebCuratorMatches(ref: AppleMusicWebCuratorRef, storefront: String, tag: String): Boolean =
    ref.storefront == storefront &&
        (resolveAppleMusicWebGenreCurator(listOf(ref), storefront, tag) != null ||
            (appleMusicWebPublicCuratorReference(storefront, tag) == ref.id &&
                appleMusicWebCuratorIsSharedForTag(ref, tag)) || amsVerifiedStyleParent(ref,tag))

@kotlinx.serialization.Serializable
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

/** Only the source's explicit playlist room, never a global playlist search. */
internal fun isAppleMusicWebCuratorPlaylistRoom(room: AppleMusicWebRoomRef): Boolean =
    room.curatorId != null && curatorFold(room.title) in setOf(
        "playlists","featured playlists","essential playlists",
        "playlists em destaque","playlists essenciais","playlists a la une",
    )

internal fun appleMusicWebCuratedPlaylistMatches(
    ref: AppleMusicWebCuratorRef, tag: String, item: AppleMusicWebItem, room: AppleMusicWebRoomRef,
): Boolean = item.kind == AppleMusicWebKind.PLAYLIST && item.storefront == ref.storefront &&
    appleMusicWebCuratorMatches(ref,ref.storefront,tag) && room.curatorId == ref.id &&
    room.storefront == ref.storefront && room.genreId == null &&
    isAppleMusicWebCuratorPlaylistRoom(room) &&
    (!appleMusicWebCuratorIsSharedForTag(ref,tag) ||
        item.genreNames.any { appleMusicWebGenreNameMatches(it,tag) } ||
        (amsVerifiedStyleParent(ref,tag) && amsExactStylePlaylistTitle(item.title,tag)))

/** Native genre-curator album shelves only; never fill from unrelated playlists. */
internal fun isAppleMusicWebCuratorReleaseRoom(room: AppleMusicWebRoomRef): Boolean =
    room.curatorId != null && curatorFold(room.title) in setOf(
        "new releases", "recent releases", "latest releases", "new albums", "new this week",
        "lancamentos", "lancamentos recentes", "novidades da semana", "novos albuns",
        "nouveautes", "sorties recentes", "nouveaux albums", "nouveautes de la semaine",
    )

/** Additional typed editorial rails, not release classification or a generic search. */
internal data class AmsCuratorExtraShelf(val heading: String, val kind: AppleMusicWebKind)

internal fun amsCuratorExtraShelf(room: AppleMusicWebRoomRef): AmsCuratorExtraShelf? {
    if (room.curatorId == null || room.genreId != null) return null
    return when (curatorFold(room.title)) {
        "albuns essenciais", "essential albums" -> AmsCuratorExtraShelf("Álbuns essenciais", AppleMusicWebKind.ALBUM)
        "artistas que amamos", "artists we love" -> AmsCuratorExtraShelf("Artistas que amamos", AppleMusicWebKind.ARTIST)
        "playlists de artistas", "essenciais de artistas", "artist playlists", "artist essentials" -> AmsCuratorExtraShelf("Playlists de artistas", AppleMusicWebKind.PLAYLIST)
        "sucessos por decada", "hits by decade" -> AmsCuratorExtraShelf("Sucessos por década", AppleMusicWebKind.PLAYLIST)
        "sucessos por ano", "hits by year" -> AmsCuratorExtraShelf("Sucessos por ano", AppleMusicWebKind.PLAYLIST)
        "musica ao vivo", "live music" -> AmsCuratorExtraShelf("Música ao vivo", AppleMusicWebKind.ALBUM)
        "em breve", "coming soon" -> AmsCuratorExtraShelf("Em breve", AppleMusicWebKind.ALBUM)
        "artistas emergentes de r b e soul" -> AmsCuratorExtraShelf("Artistas emergentes de R&B e soul", AppleMusicWebKind.ARTIST)
        else -> null
    }
}

/** Search terms nominate candidates only. They never authorize a style label. */
internal fun amsStyleDiscoveryQueries(tag: String): List<String> {
    val localized = when (tag) {
        "dance-pop" -> "pop dance"
        "art pop" -> "art-pop"
        "synthpop" -> "synth pop"
        "alternative rock" -> "rock alternativo"
        "progressive rock" -> "rock progressivo"
        "post-rock" -> "pós-rock"
        "trap" -> "trap brasileiro"
        "drill" -> "drill rap"
        "boom bap" -> "boom-bap"
        "indie folk" -> "folk indie"
        "dark ambient" -> "dark ambient music"
        "drone" -> "drone ambient"
        "ambient techno" -> "ambient techno music"
        "hardcore punk" -> "punk hardcore"
        "post-punk" -> "pós-punk"
        "pop punk" -> "punk pop"
        else -> tag
    }
    return listOf(tag,localized).distinct()
}

/** A search hit is not curator membership. Require the public editorial
 * resource's own type, exact semantic title and Apple credit separately.
 * Revalidate on detail before taking a single linked album. */
internal fun amsVerifiedSearchedStylePlaylist(item: AppleMusicWebItem, tag: String, market: String): Boolean {
    if (item.kind != AppleMusicWebKind.PLAYLIST || item.storefront != market ||
        item.playlistType != "editorial" || !amsExactStylePlaylistTitle(item.title,tag)) return false
    val credit = curatorFold(item.artistName.orEmpty())
    val parent = amsEditorialStyleParents[tag] ?: return false
    val label = credit.removePrefix("apple music ").removeSuffix(" apple music")
    return credit == "apple music" || (credit != label &&
        (appleMusicWebGenreNameMatches(label,parent) ||
            appleMusicWebGenreNameMatches(label,tag) ||
            (parent == "rock" && label in setOf("alternative","alternativo","rock classico","classic rock","indie")) ||
            (parent == "pop" && label in setOf("pop","dance")) ||
            (parent == "rap" && label in setOf("hip hop","hip hop rap","hip hop nacional")) ||
            (parent == "ambient" && label in setOf("electronic","eletronica","ambient","ambiente")) ||
            (parent == "punk" && label in setOf("rock","punk","alternative","alternativo","indie","alternative indie")) ||
            (parent == "folk" && label in setOf("folk","indie","cantores compositores"))))
}

/** Public BR nominations, used only if dynamic exact discovery is underfilled.
 * Resource type, title, credit, identity and market still need live validation.
 * These are optional editorial seeds, not a style taxonomy or a guarantee.
 * Sources: music.apple.com/br/playlist/{slug}/{id}.
 */
internal fun amsStylePublicPlaylistCandidates(tag: String, market: String): List<String> =
    if (market != "br") emptyList() else when (tag) {
        "progressive rock" -> listOf("pl.573d5d1c54c34bee884fe83de0dfa548")
        "dark ambient" -> listOf("pl.63e840fa5b544de49f20421e5e80e7ee")
        "trap" -> listOf("pl.9b1b842f97254ed6a8bcbcba73f48643")
        "drill" -> listOf("pl.7a9e72bd385a4b6aa46695159978c847")
        "alternative rock" -> listOf("pl.82f8a66b185f4e94a3aa7e6afd2f648b")
        else -> emptyList()
    }
