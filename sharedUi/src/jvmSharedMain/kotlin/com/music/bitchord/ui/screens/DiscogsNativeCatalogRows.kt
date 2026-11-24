package com.music.bitchord.ui.screens

/**
 * Provider-scoped metadata in the common native search compositor.
 * Discogs ids must never be cast to MusicBrainz, Apple, Song or video IDs.
 */
data class DiscogsNativeArtistSearchRow(
    val artistId: Long,
    val name: String,
) {
    val browseId: String get() = "discogs:artist:$artistId"
}

/** Typed Release search hit. Its title is not used to infer another provider's ID. */
data class DiscogsNativeReleaseSearchRow(
    val releaseId: Long,
    val title: String,
) {
    val browseId: String get() = "discogs:release:$releaseId"
}

/**
 * Discogs Search is deliberately fuzzy. This ranks only matches supported by
 * complete artist names or full word starts; a substring ("Illiteracy" for
 * "illit") must never become an accepted music search result.
 *
 * Numeric Discogs suffixes disambiguate distinct artists. They are ignored
 * for text relevance ONLY; the exact source artist IDs remain independent.
 */
private fun discogsSearchFold(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .trim()

/** Discogs numeric suffix is an index discriminator, never a stage name. */
fun discogsArtistDisplayName(value: String): String =
    value.trim().replace(Regex(""" \([1-9][0-9]*\)$"""), "")

private fun discogsCoreArtistName(value: String): String =
    discogsSearchFold(discogsArtistDisplayName(value))

/**
 * Collapse duplicate visible names in Search only, never merge Discogs IDs.
 * The Discogs result order is the bounded fallback: a suffix (or lack of one)
 * does not establish popularity or the identity of a particular artist.
 */
fun discogsSearchDistinctArtists(
    query: String,
    artists: List<DiscogsNativeArtistSearchRow>,
): List<DiscogsNativeArtistSearchRow> =
    artists.asSequence()
        .filter { discogsSearchArtistRank(query, it.name) != null }
        .distinctBy { discogsSearchFold(discogsArtistDisplayName(it.name)) }
        .toList()

private fun discogsSearchWordStarts(text: String, query: String): Boolean =
    text.startsWith(query) && (text.length == query.length ||
        !text[query.length].isLetterOrDigit())

fun discogsSearchArtistRank(query: String, name: String): Int? {
    val q = discogsSearchFold(query)
    if (q.isBlank()) return null
    val full = discogsSearchFold(name)
    val core = discogsCoreArtistName(name)
    return when {
        full == q -> 0
        core == q -> 1
        full.startsWith("the ") && discogsCoreArtistName(full.drop(4)) == q -> 2
        discogsSearchWordStarts(core, q) -> 3
        else -> null
    }
}

/**
 * Discogs supplies combined "artist - release" Search labels, not full
 * release metadata. Splitting is for presentation ONLY: it neither
 * establishes a verified Artist ID nor an Album/EP/Single classification.
 */
data class DiscogsSearchReleaseDisplay(val title: String, val artistCredit: String?)

fun discogsSearchReleaseDisplay(rawTitle: String): DiscogsSearchReleaseDisplay {
    val cut = rawTitle.indexOf(" - ")
    if (cut <= 0 || cut + 3 >= rawTitle.length)
        return DiscogsSearchReleaseDisplay(rawTitle.trim(), null)
    val credit = rawTitle.substring(0, cut).trim()
    val title = rawTitle.substring(cut + 3).trim()
    return if (credit.isBlank() || title.isBlank()) {
        DiscogsSearchReleaseDisplay(rawTitle.trim(), null)
    } else DiscogsSearchReleaseDisplay(title, credit)
}

fun discogsSearchReleaseRank(query: String, combinedTitle: String): Int? {
    val q = discogsSearchFold(query)
    if (q.isBlank()) return null
    val display = discogsSearchReleaseDisplay(combinedTitle)
    val artistRank = display.artistCredit?.let { discogsSearchArtistRank(query, it) }
    val title = discogsSearchFold(display.title)
    val artist = display.artistCredit?.let(::discogsCoreArtistName).orEmpty()
    val fullArtist = display.artistCredit?.let(::discogsSearchFold).orEmpty()
    val compound = listOf(artist, fullArtist).filter(String::isNotBlank).distinct()
        .flatMap { credit -> listOf(
            "$credit $title", "$title $credit", "$credit - $title", "$title - $credit",
        ) }
    return when {
        artistRank != null && artistRank <= 2 -> artistRank
        title == q || q in compound -> 3
        discogsSearchWordStarts(title, q) ||
            compound.any { discogsSearchWordStarts(it, q) } -> 4
        artistRank == 3 -> 5
        else -> null
    }
}

sealed interface DiscogsUnifiedSearchEntry {
    data class Artist(val row: DiscogsNativeArtistSearchRow) : DiscogsUnifiedSearchEntry
    data class Release(val row: DiscogsNativeReleaseSearchRow) : DiscogsUnifiedSearchEntry
}

/**
 * General search mixes provider-owned Artist and Release IDs in one native
 * list. Typed tabs keep broader text-relevant matches. When an exact artist
 * exists, General shows that artist and works actually credited to its
 * name, rather than titles/works owned by unrelated artists.
 */
fun discogsUnifiedSearchEntries(
    query: String,
    artists: List<DiscogsNativeArtistSearchRow>,
    releases: List<DiscogsNativeReleaseSearchRow>,
): List<DiscogsUnifiedSearchEntry> {
    val rankedArtists = discogsSearchDistinctArtists(query, artists)
        .mapNotNull { row ->
            discogsSearchArtistRank(query, row.name)?.let { it to row }
        }.sortedWith(compareBy<Pair<Int, DiscogsNativeArtistSearchRow>> {
            it.first
        })
    val exactArtist = rankedArtists.any { it.first <= 2 }
    val leaders = if (exactArtist) rankedArtists.filter { it.first <= 2 }.take(2)
        else rankedArtists.take(2)
    val matches = releases.mapNotNull { release ->
        discogsSearchReleaseRank(query, release.title)?.let { rank ->
            (rank to release).takeIf { !exactArtist || rank <= 2 }
        }
    }.distinctBy { it.second.releaseId }.sortedWith(
        compareBy<Pair<Int, DiscogsNativeReleaseSearchRow>> { it.first }
            .thenBy { it.second.releaseId },
    )
    return buildList {
        leaders.forEach { add(DiscogsUnifiedSearchEntry.Artist(it.second)) }
        matches.forEach { add(DiscogsUnifiedSearchEntry.Release(it.second)) }
    }
}

/**
 * Fuzzy Discogs results can consume a whole server page without a legitimate
 * hit. Continue only if there are no displayed or newly accepted rows, and
 * only within the native Search client's existing three-page hard cap.
 */
fun discogsSearchNeedsNextRelevantPage(
    previousAccepted: Int,
    freshAccepted: Int,
    nextPage: Int?,
): Boolean = previousAccepted == 0 && freshAccepted == 0 &&
    nextPage != null && nextPage in 2..3

enum class DiscogsUnifiedSearchNext { ARTISTS, RELEASES }

/** A single "Mais resultados" request; do not repeatedly page fuzzy artists. */
fun discogsUnifiedNextPage(
    query: String,
    artists: List<DiscogsNativeArtistSearchRow>,
    hasMoreArtists: Boolean,
    hasMoreReleases: Boolean,
): DiscogsUnifiedSearchNext? {
    if (hasMoreReleases) return DiscogsUnifiedSearchNext.RELEASES
    if (hasMoreArtists && artists.none {
        (discogsSearchArtistRank(query, it.name) ?: 99) <= 2
    }) return DiscogsUnifiedSearchNext.ARTISTS
    return null
}
