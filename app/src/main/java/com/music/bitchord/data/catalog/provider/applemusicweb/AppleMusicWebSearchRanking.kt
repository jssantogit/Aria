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
    fun compound(term: String, title: String, credit: String): Boolean =
        title.isNotBlank() && credit.isNotBlank() &&
            term in setOf("$title $credit", "$credit $title")
    fun containsPhrase(text: String, phrase: String): Boolean =
        phrase.isNotBlank() && " $text ".contains(" $phrase ")
    val term = fold(query)
    if (term.isBlank()) return rows
    // Source-returned credits can establish a title/artist boundary; never
    // guess an intended artist when the query is only a work title.
    val hasCompoundEvidence = kind != CatalogSearchKind.ARTIST && rows.any { row ->
        val credit = fold(row.subtitle)
        val title = fold(row.title)
        val work = fold(applePublicAlbumWorkKey(row.title))
        compound(term, title, credit) || compound(term, work, credit)
    }
    fun rank(row: CatalogSearchItem): Int {
        val title = fold(row.title)
        val credit = fold(row.subtitle)
        if (kind == CatalogSearchKind.ARTIST) {
            if (title == term) return 0
            if (title.removePrefix("the ") == term.removePrefix("the ")) return 1
            if (containsPhrase(title, term)) return 3
            return 5
        }
        val work = fold(applePublicAlbumWorkKey(row.title))
        if (compound(term, title, credit)) return 0
        if (compound(term, work, credit)) return 1
        // On title-only queries, preserve the provider's relative rank among
        // exact work titles and recognized commercial editions. Otherwise a
        // less popular namesake could displace the requested remaster.
        if (term == title || term == work) return if (hasCompoundEvidence) 3 else 0
        // Same-work variants are still useful; a full query may include credit
        // in either order. Keep them below exact and recognized mastering hits.
        val titlePart = when {
            credit.isNotBlank() && term.startsWith("$credit ") -> term.removePrefix("$credit ")
            credit.isNotBlank() && term.endsWith(" $credit") -> term.removeSuffix(" $credit")
            else -> term
        }
        if (containsPhrase(title, titlePart))
            return if (!hasCompoundEvidence || titlePart != term) 2 else 4
        if (term.split(' ').all { containsPhrase("$title $credit", it) }) return 3
        if (containsPhrase(credit, term)) return 4
        return 5
    }
    return rows.sortedBy(::rank)
}
