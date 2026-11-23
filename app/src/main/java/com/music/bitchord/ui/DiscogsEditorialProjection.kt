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


/**
 * A top-level app genre may span several *actual* Discogs Styles. Each
 * candidate is still checked against the hydrated Master's exact Styles
 * field; none of these labels is inferred from a release title or artist.
 *
 * In particular, Discogs has no umbrella Genre named Metal or R&B.
 * Never map Metal to its much broader Rock Genre or R&B to Funk / Soul.
 */
internal fun discogsEditorialQueriesForTag(tag: String): List<DiscogsEditorialQuery> {
    discogsEditorialQueryForTag(tag)?.let { return listOf(it) }
    if (tag == "conscious hip hop") return listOf(
        DiscogsEditorialQuery(
            sourceName = "Conscious", isStyle = true, requiredGenre = "Hip Hop",
        ),
    )
    val styles = when (tag) {
        "metal" -> listOf(
            "Heavy Metal", "Thrash", "Death Metal", "Black Metal", "Doom Metal",
        )
        "rnb" -> listOf(
            "Contemporary R&B", "Rhythm & Blues", "Neo Soul",
        )
        "folk" -> listOf("Folk", "Folk Rock", "Bluegrass")
        // These are direct source style names, not approximate genres.
        "indie folk" -> listOf("Indie Folk")
        "alternative r&b" -> listOf("Alternative R&B")
        else -> emptyList()
    }
    return styles.map { DiscogsEditorialQuery(it, isStyle = true) }
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
    // A release may legitimately occur both in discovery and the Album/EP
    // shelf, just as on the native Apple/MB UI. Only duplicate Masters inside
    // the *same* shelf are redundant. A global consumed-ID set silently
    // removes the Album shelf when its records appear in "Lançamentos" first.
    fun make(title: String, releases: List<DiscogsEditorialCandidate>, limit: Int): HomeShelf? {
        val items = releases.asSequence().distinctBy { it.masterId }
            .take(limit).map { release ->
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
    // Artist membership is the verified release's explicit artist ID,
    // NOT a claim of genre-wide popularity or a search by stage name.
    val artists = unique.asSequence()
        .flatMap { it.creditedArtists.asSequence() }
        .filter { (id, name) -> id in 1..999_999_999_999L && name.isNotBlank() }
        .distinctBy { it.first }.take(12)
        .map { (id, name) ->
            ShelfItem(
                title = discogsArtistDisplayName(name),
                subtitle = "Artista",
                thumbnailUrl = null, videoId = null, browseId = "discogs:artist:$id",
            )
        }.toList()
    val artistShelf = artists.takeIf { it.isNotEmpty() }?.let {
        HomeShelf(if (home) "Artistas para descobrir" else "Artistas", it)
    }
    return if (home) listOfNotNull(
        make("Para descobrir", unique.take(2), 2),
        make("Álbuns", unique.filter { kind(it) == 1 }, 12),
        make("Singles e EPs", unique.filter { kind(it) == 2 }, 12),
        make("Mais para explorar", unique.drop(2), 12),
        artistShelf,
    ) else listOfNotNull(
        make("Lançamentos", unique.take(2), 2),
        make("Álbuns", unique.filter { kind(it) == 1 }, 12),
        make("Singles e EPs", unique.filter { kind(it) == 2 }, 12),
        make("Mais para explorar", unique, 12),
        artistShelf,
    )
}
