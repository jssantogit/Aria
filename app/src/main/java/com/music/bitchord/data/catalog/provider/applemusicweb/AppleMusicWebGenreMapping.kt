package com.music.bitchord.data.catalog.provider.applemusicweb

import java.text.Normalizer
import java.util.Locale

/** Exact semantic aliases for the frozen IDM corpus. The live native list
 * owns IDs and coverage. No parent fallback masquerades as a subgenre. */
internal fun resolveAppleMusicWebCanonicalGenre(
    native: List<AppleMusicWebGenre>, storefront: String, tag: String,
): AppleMusicWebGenre? {
    return native.filter { it.storefront == storefront && appleMusicWebGenreNameMatches(it.title, tag) }
        .distinctBy { it.id }.singleOrNull()
}

internal fun appleMusicWebGenreNameMatches(name: String, tag: String): Boolean {
    fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val aliases = when (tag) {
        "pop" -> setOf("Pop")
        "rock" -> setOf("Rock")
        "hip-hop" -> setOf("Hip-Hop/Rap", "Hip-Hop")
        "rap" -> setOf("Rap", "Hip-Hop/Rap")
        "soul" -> setOf("Soul")
        "samba" -> setOf("Samba")
        "blues" -> setOf("Blues")
        "pagode" -> setOf("Pagode")
        "forro" -> setOf("Forró")
        "reggae" -> setOf("Reggae", "Reggae Music")
        "indie" -> setOf("Indie", "Indie Music")
        "electronic" -> setOf("Electronic", "Eletrônica", "Electronic Music")
        "jazz" -> setOf("Jazz")
        "k-pop" -> setOf("K-Pop")
        "metal" -> setOf("Metal")
        "rnb" -> setOf("R&B/Soul", "R&B", "R&B e Soul")
        "folk" -> setOf("Folk", "Música Folk")
        "hard rock" -> setOf("Hard Rock")
        "house" -> setOf("House")
        "techno" -> setOf("Techno")
        "trance" -> setOf("Trance")
        "smooth jazz" -> setOf("Smooth Jazz")
        "bluegrass" -> setOf("Bluegrass")
        "alternative rock" -> setOf("Alternative Rock", "Rock alternativo")
        "progressive rock" -> setOf("Progressive Rock", "Rock progressivo")
        "post-rock" -> setOf("Post-Rock", "Pós-rock")
        "post-punk" -> setOf("Post-Punk", "Pós-punk")
        "conscious hip hop" -> setOf("Conscious Hip Hop", "Hip-hop consciente")
        "drum and bass" -> setOf("Drum and Bass", "Drum & Bass", "Drum n Bass", "Jungle/Drum’n’bass", "Jungle/Drum'n'bass")
        "contemporary r&b" -> setOf("Contemporary R&B", "R&B contemporâneo")
        "alternative r&b" -> setOf("Alternative R&B", "R&B alternativo")
        "baroque" -> setOf("Baroque", "Barroco", "Era barroca")
        "bebop" -> setOf("Bebop", "Bop")
        "jazz fusion" -> setOf("Jazz Fusion", "Fusão", "Fusion")
        "chamber music" -> setOf("Chamber Music", "Música de câmara")
        "opera" -> setOf("Opera", "Ópera")
        else -> if (tag in amsEditorialStyleParents) setOf(tag) else emptySet()
    }.map(::fold).toSet()
    return fold(name) in aliases
}
