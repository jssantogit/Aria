package com.music.bitchord.data.catalog.provider.applemusicweb

import java.text.Normalizer
import java.util.Locale

/** Exact semantic aliases for the frozen IDM corpus. The live native list
 * owns IDs and coverage. No parent fallback masquerades as a subgenre. */
internal fun resolveAppleMusicWebCanonicalGenre(
    native: List<AppleMusicWebGenre>, storefront: String, tag: String,
): AppleMusicWebGenre? {
    fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val aliases = when (tag) {
        "pop" -> setOf("Pop")
        "rock" -> setOf("Rock")
        "hip-hop" -> setOf("Hip-Hop/Rap", "Hip-Hop")
        "electronic" -> setOf("Electronic", "Eletrônica", "Electronic Music")
        "jazz" -> setOf("Jazz")
        "k-pop" -> setOf("K-Pop")
        "metal" -> setOf("Metal")
        "rnb" -> setOf("R&B/Soul", "R&B", "R&B e Soul")
        "folk" -> setOf("Folk")
        "hard rock" -> setOf("Hard Rock")
        "house" -> setOf("House")
        "techno" -> setOf("Techno")
        "trance" -> setOf("Trance")
        "smooth jazz" -> setOf("Smooth Jazz")
        "bluegrass" -> setOf("Bluegrass")
        else -> emptySet()
    }.map(::fold).toSet()
    return native.filter { it.storefront == storefront && fold(it.title) in aliases }
        .distinctBy { it.id }.singleOrNull()
}
