package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.catalog.metadata.applePublicAlbumWorkKey
import com.music.bitchord.data.providers.CatalogSearchItem
import com.music.bitchord.data.providers.CatalogSearchKind
import java.text.Normalizer
import java.util.Locale

/** Stable, query-only relevance. Never consults the validation corpus, popularity,
 * target IDs or a competing provider. Nothing is removed and display titles and
 * native identity remain unchanged. The existing IDM work-key handles only
 * recognized commercial mastering/edition suffixes, not arbitrary variants.
 */
internal fun rankAppleMusicWebSearchPage(
    query: String, kind: CatalogSearchKind, rows: List<CatalogSearchItem>,
): List<CatalogSearchItem> {
    fun fold(value: String?): String = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    fun matches(term: String, title: String, credit: String): Boolean =
        title.isNotBlank() && (term == title || (credit.isNotBlank() &&
            term in setOf("$title $credit", "$credit $title")))
    fun containsPhrase(text: String, phrase: String): Boolean =
        phrase.isNotBlank() && " $text ".contains(" $phrase ")
    val term = fold(query)
    if (term.isBlank()) return rows
    fun rank(row: CatalogSearchItem): Int {
        val title = fold(row.title)
        val credit = fold(row.subtitle)
        if (kind == CatalogSearchKind.ARTIST) {
            if (title == term) return 0
            if (title.removePrefix("the ") == term.removePrefix("the ")) return 1
            if (containsPhrase(title, term)) return 3
            return 5
        }
        if (matches(term, title, credit)) return 0
        val work = fold(applePublicAlbumWorkKey(row.title))
        if (work != title && matches(term, work, credit)) return 1
        // Same-work variants are still useful; a full query may include credit
        // in either order. Keep them below exact and recognized mastering hits.
        val titlePart = when {
            credit.isNotBlank() && term.startsWith("$credit ") -> term.removePrefix("$credit ")
            credit.isNotBlank() && term.endsWith(" $credit") -> term.removeSuffix(" $credit")
            else -> term
        }
        if (containsPhrase(title, titlePart)) return 2
        if (term.split(' ').all { containsPhrase("$title $credit", it) }) return 3
        if (containsPhrase(credit, term)) return 4
        return 5
    }
    return rows.sortedBy(::rank)
}
