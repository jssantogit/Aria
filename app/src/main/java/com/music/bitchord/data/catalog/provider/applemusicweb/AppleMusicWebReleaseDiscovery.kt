package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.catalog.metadata.applePublicAlbumWorkKey
import java.text.Normalizer
import java.util.Locale

/**
 * Candidate discovery for an explicitly requested artist/work, not a general
 * search filter and never identity proof. The caller must open the exact
 * returned album and verify its native artist relationship. Unknown suffixes,
 * homonyms, and editions remain distinct native resources.
 */
internal object AppleMusicWebReleaseDiscovery {
    private fun fold(text: String?): String = Normalizer.normalize(text.orEmpty().trim(), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    fun creditMatches(actual: String?, artist: String, aliases: Set<String> = emptySet()): Boolean =
        !actual.isNullOrBlank() && (aliases + artist).any { fold(it) == fold(actual) }

    fun titleMatches(actual: String, title: String, aliases: Set<String> = emptySet()): Boolean {
        val key = applePublicAlbumWorkKey(actual)
        return (aliases + title).any { fold(actual) == fold(it) ||
            key == applePublicAlbumWorkKey(it) }
    }

    /** A narrow additional candidate hint; not a proven release equivalence.
     * Require explicit native format/credit and a matching audio track later.
     * A trailing year in a bare studio album title is NOT treated as a suffix.
     */
    fun relatedSingleYear(actual: String, title: String): Boolean {
        if (!Regex("""(?:\s+[-–—]\s+(?:single|ep)|\s+[(\[](?:single|ep)[)\]])$""", RegexOption.IGNORE_CASE)
                .containsMatchIn(actual)) return false
        val match = Regex("""^(.+)\s+(?:19|20)\d{2}$""").matchEntire(applePublicAlbumWorkKey(actual))
            ?: return false
        return match.groupValues[1] == applePublicAlbumWorkKey(title)
    }

    /** Identical artist/title queries collapse to one term; aliases are
     * caller-supplied evidence, not hard-coded work-specific exceptions. */
    fun alternateQueries(artist: String, title: String, titleAliases: Set<String> = emptySet()): List<String> =
        (listOf("$artist $title", "$title $artist", title) +
            titleAliases.sorted().map { "$artist $it" })
            .map { query ->
                if (fold(query) == fold("$artist $artist") && fold(title) == fold(artist)) title else query
            }.distinctBy(::fold).filter { it.isNotBlank() && it.length <= 120 }.take(6)

    fun candidateTitle(actual: String, title: String, aliases: Set<String>, singleOrEp: Boolean): Boolean =
        titleMatches(actual, title, aliases) || (singleOrEp && relatedSingleYear(actual, title))
}
