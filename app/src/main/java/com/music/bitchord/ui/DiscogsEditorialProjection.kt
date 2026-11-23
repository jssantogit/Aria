package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialCandidate
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialQuery
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.screens.discogsArtistDisplayName
import java.util.Locale

/**
 * Explicit UI taxonomy bridge. Discogs' Genre/Style membership is checked
 * against hydrated Master facts by the source adapter, not guessed from
 * app navigation labels or similar-sounding artist/release names.
 *
 * A partial category mapping is preferable to a false equivalence.
 */
internal fun discogsEditorialQueryForTag(tag: String): DiscogsEditorialQuery? {
    val genre = mapOf(
        "pop" to "Pop",
        "rock" to "Rock",
        "hip-hop" to "Hip Hop",
        "electronic" to "Electronic",
        "jazz" to "Jazz",
        "classical" to "Classical",
    )[tag]
    if (genre != null) return DiscogsEditorialQuery(genre, isStyle = false)
    val style = mapOf(
        "k-pop" to "K-Pop",
        "ambient" to "Ambient",
        "punk" to "Punk",
        "indie pop" to "Indie Pop",
        "dance-pop" to "Dance-pop",
        "art pop" to "Art Pop",
        "synthpop" to "Synth-pop",
        "alternative rock" to "Alternative Rock",
        "hard rock" to "Hard Rock",
        "progressive rock" to "Prog Rock",
        "post-rock" to "Post Rock",
        "trap" to "Trap",
        "drill" to "Drill",
        "boom bap" to "Boom Bap",
        "house" to "House",
        "techno" to "Techno",
        "trance" to "Trance",
        "drum and bass" to "Drum n Bass",
        "neo soul" to "Neo Soul",
        "contemporary r&b" to "Contemporary R&B",
        "bebop" to "Bop",
        "cool jazz" to "Cool Jazz",
        "jazz fusion" to "Fusion",
        "smooth jazz" to "Smooth Jazz",
        "heavy metal" to "Heavy Metal",
        "thrash metal" to "Thrash",
        "death metal" to "Death Metal",
        "black metal" to "Black Metal",
        "doom metal" to "Doom Metal",
        "baroque" to "Baroque",
        "chamber music" to "Chamber Music",
        "opera" to "Opera",
        "bluegrass" to "Bluegrass",
        "folk rock" to "Folk Rock",
        "dark ambient" to "Dark Ambient",
        "drone" to "Drone",
        "ambient techno" to "Ambient Techno",
        "hardcore punk" to "Hardcore",
        "post-punk" to "Post-Punk",
        "pop punk" to "Pop Punk",
    )[tag] ?: return null
    return DiscogsEditorialQuery(style, isStyle = true)
}

internal fun discogsEditorialShelves(
    candidates: List<DiscogsEditorialCandidate>,
    home: Boolean = false,
): List<HomeShelf> {
    val unique = candidates
        .filter { it.releaseId in 1..999_999_999_999L &&
            it.masterId in 1..999_999_999_999L &&
            it.title.isNotBlank() && it.artist.isNotBlank()
        }
        .distinctBy { it.masterId }
        .sortedWith(compareByDescending<DiscogsEditorialCandidate> { it.year ?: 0 }
            .thenBy { it.masterId })
    val assigned = HashSet<Long>()
    fun make(title: String, releases: List<DiscogsEditorialCandidate>, limit: Int): HomeShelf? {
        val items = releases.asSequence().filter { it.masterId !in assigned }
            .take(limit).onEach { assigned.add(it.masterId) }.map { release ->
            ShelfItem(
                title = release.title,
                subtitle = listOfNotNull(
                    discogsArtistDisplayName(release.artist),
                    release.year?.toString(),
                ).joinToString(" · "),
                thumbnailUrl = null, videoId = null, browseId = release.browseId,
            )
        }.toList()
        return items.takeIf { it.isNotEmpty() }?.let { HomeShelf(title, it) }
    }
    fun kind(release: DiscogsEditorialCandidate): Int {
        val tags = release.formats.map { it.lowercase(Locale.ROOT) }
        val album = "album" in tags
        val single = "single" in tags || "ep" in tags
        return when {
            album && !single -> 1
            single && !album -> 2
            else -> 0
        }
    }
    return if (home) listOfNotNull(
        make("Para descobrir", unique.take(2), 2),
        make("Álbuns", unique.filter { kind(it) == 1 }, 12),
        make("Singles e EPs", unique.filter { kind(it) == 2 }, 12),
        make("Mais para explorar", unique, 12),
    ) else listOfNotNull(
        make("Lançamentos", unique.take(2), 2),
        make("Álbuns", unique.filter { kind(it) == 1 }, 12),
        make("Singles e EPs", unique.filter { kind(it) == 2 }, 12),
        make("Mais para explorar", unique, 12),
    )
}
