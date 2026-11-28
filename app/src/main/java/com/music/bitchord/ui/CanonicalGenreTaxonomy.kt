package com.music.bitchord.ui

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import java.text.Normalizer
import java.util.Locale

/**
 * Small, curated navigation taxonomy. These are well-established musical
 * styles, NOT provider records or proof that a source has matching releases.
 * Providers still need to return actual evidence for every editorial shelf.
 *
 * The parent edge is our editorial navigation relation; it is intentionally
 * not asserted as a MusicBrainz or Apple Music taxonomy relationship.
 */
internal val canonicalExploreSubgenres: List<CanonicalExploreGenre> = listOf(
    CanonicalExploreGenre("indie pop", "Indie Pop", "pop"),
    CanonicalExploreGenre("dance-pop", "Dance-pop", "pop"),
    CanonicalExploreGenre("art pop", "Art Pop", "pop"),
    CanonicalExploreGenre("synthpop", "Synthpop", "pop"),
    CanonicalExploreGenre("alternative rock", "Rock alternativo", "rock"),
    CanonicalExploreGenre("hard rock", "Hard Rock", "rock"),
    CanonicalExploreGenre("progressive rock", "Rock progressivo", "rock"),
    CanonicalExploreGenre("post-rock", "Post-rock", "rock"),
    CanonicalExploreGenre("trap", "Trap", "hip-hop"),
    CanonicalExploreGenre("drill", "Drill", "hip-hop"),
    CanonicalExploreGenre("boom bap", "Boom Bap", "hip-hop"),
    CanonicalExploreGenre("conscious hip hop", "Hip-hop consciente", "hip-hop"),
    CanonicalExploreGenre("house", "House", "electronic"),
    CanonicalExploreGenre("techno", "Techno", "electronic"),
    CanonicalExploreGenre("trance", "Trance", "electronic"),
    CanonicalExploreGenre("drum and bass", "Drum and Bass", "electronic"),
    CanonicalExploreGenre("neo soul", "Neo Soul", "rnb"),
    CanonicalExploreGenre("contemporary r&b", "R&B contemporâneo", "rnb"),
    CanonicalExploreGenre("alternative r&b", "R&B alternativo", "rnb"),
    CanonicalExploreGenre("bebop", "Bebop", "jazz"),
    CanonicalExploreGenre("cool jazz", "Cool Jazz", "jazz"),
    CanonicalExploreGenre("jazz fusion", "Jazz Fusion", "jazz"),
    CanonicalExploreGenre("smooth jazz", "Smooth Jazz", "jazz"),
    CanonicalExploreGenre("heavy metal", "Heavy Metal", "metal"),
    CanonicalExploreGenre("thrash metal", "Thrash Metal", "metal"),
    CanonicalExploreGenre("death metal", "Death Metal", "metal"),
    CanonicalExploreGenre("black metal", "Black Metal", "metal"),
    CanonicalExploreGenre("doom metal", "Doom Metal", "metal"),
    CanonicalExploreGenre("baroque", "Barroco", "classical"),
    CanonicalExploreGenre("chamber music", "Música de câmara", "classical"),
    CanonicalExploreGenre("opera", "Ópera", "classical"),
    CanonicalExploreGenre("bluegrass", "Bluegrass", "folk"),
    CanonicalExploreGenre("folk rock", "Folk Rock", "folk"),
    CanonicalExploreGenre("indie folk", "Indie Folk", "folk"),
    CanonicalExploreGenre("dark ambient", "Dark Ambient", "ambient"),
    CanonicalExploreGenre("drone", "Drone", "ambient"),
    CanonicalExploreGenre("ambient techno", "Ambient Techno", "ambient"),
    CanonicalExploreGenre("hardcore punk", "Hardcore Punk", "punk"),
    CanonicalExploreGenre("post-punk", "Post-punk", "punk"),
    CanonicalExploreGenre("pop punk", "Pop Punk", "punk"),
)

internal val canonicalAllExploreGenres: List<CanonicalExploreGenre> =
    canonicalExploreGenres + canonicalExploreSubgenres

/** Frozen #227 matrix. Do not replace sparse categories after observing live results. */
internal val idmParityMainGenres = listOf(
    "pop", "rock", "hip-hop", "electronic", "jazz", "k-pop", "metal", "rnb", "folk",
)
internal val idmParitySubgenres = listOf(
    "hard rock", "house", "techno", "trance", "smooth jazz", "bluegrass",
)
internal val idmParityCapacityGenres = listOf("rock", "electronic", "jazz")

internal fun canonicalGenreBrowseId(genre: CanonicalExploreGenre): String =
    "catalog:GENRE:" + genre.tag

internal fun canonicalExploreGenreRelationsShelf(genre: CanonicalExploreGenre): HomeShelf? {
    val neighbours = if (genre.parentTag == null) {
        canonicalExploreSubgenres.filter { it.parentTag == genre.tag }
    } else {
        // A subgenre points to OTHER styles under the same parent; no self-link,
        // unrelated style or unsupported invented popularity ordering.
        canonicalExploreSubgenres.filter {
            it.parentTag == genre.parentTag && it.tag != genre.tag
        }
    }
    if (neighbours.isEmpty()) return null
    val label = if (genre.parentTag == null) "Subgêneros" else "Estilos relacionados"
    return HomeShelf(label, neighbours.map { related ->
        ShelfItem(
            title = related.title,
            subtitle = "",
            thumbnailUrl = null,
            videoId = null,
            browseId = canonicalGenreBrowseId(related),
        )
    })
}

private fun normalizedGenreQuery(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

/** Search result categories are local navigation metadata, never music matches. */
internal fun canonicalExploreGenreSearchHits(
    query: String,
    limit: Int = 6,
): List<ShelfItem> {
    val term = normalizedGenreQuery(query)
    if (term.length < 2) return emptyList()
    // Match only at a word boundary, not the suffix of alternative/progressive.
    fun matches(value: String): Boolean {
        val normalized = normalizedGenreQuery(value)
        return normalized == term || normalized.startsWith(term) ||
            normalized.indices.any { index ->
                index > 0 && !normalized[index-1].isLetterOrDigit() &&
                    normalized.startsWith(term,index)
            }
    }
    return canonicalAllExploreGenres.asSequence()
        .filter { genre -> matches(genre.title) || matches(genre.tag) }
        .sortedWith(compareBy<CanonicalExploreGenre> { genre ->
            val title = normalizedGenreQuery(genre.title)
            val tag = normalizedGenreQuery(genre.tag)
            when {
                title == term || tag == term -> 0
                title.startsWith(term) || tag.startsWith(term) -> 1
                else -> 2
            }
        }.thenBy { if (it.parentTag == null) 0 else 1 }.thenBy { it.title })
        .take(limit.coerceIn(0, 12))
        .map { genre ->
            ShelfItem(
                title = genre.title,
                // Both parent genres and child styles are category destinations.
                // Their taxonomy relationship lives on the genre page,
                // not in a second gray label in Apple-style Search.
                subtitle = "Categoria",
                thumbnailUrl = null,
                videoId = null,
                browseId = canonicalGenreBrowseId(genre),
            )
        }
        .toList()
}
