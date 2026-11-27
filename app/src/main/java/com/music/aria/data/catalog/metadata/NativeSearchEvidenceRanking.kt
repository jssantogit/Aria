package com.music.aria.data.catalog.metadata

/**
 * Native search order remains the primary signal. Allow only disjoint
 * adjacent swaps: every candidate can move at most one position and none
 * can be removed. Distinct-work support is not a popularity assertion.
 */
internal fun <T : Any> boundedNativeArtistEvidenceOrder(
    rows: List<T>, support: (T) -> Int,
): List<T> = buildList {
    var index = 0
    while (index < rows.size) {
        val first = rows[index]
        val second = rows.getOrNull(index + 1)
        val firstSupport = support(first).coerceIn(0, 3)
        val secondSupport = second?.let(support)?.coerceIn(0, 3) ?: 0
        if (second != null && secondSupport >= 2 && secondSupport > firstSupport) {
            add(second)
            add(first)
            index += 2
        } else {
            add(first)
            index++
        }
    }
}

/**
 * Visual diversity only. Preserve the first two rows per source-owned
 * artist/title family before excess lookalikes, then append every deferred
 * row in its original order. Missing credit IDs never form a family.
 * No identity, playback or provider cursor is modified.
 */
internal fun <T, K : Any> diversifyNativeTrackLanding(
    rows: List<T>, family: (T) -> K?,
): List<T> {
    val seen = mutableMapOf<K, Int>()
    val leading = mutableListOf<T>()
    val deferred = mutableListOf<T>()
    for (row in rows) {
        val key = family(row)
        if (key == null) {
            leading += row
            continue
        }
        val count = seen.getOrDefault(key, 0)
        seen[key] = count + 1
        if (count < 2) leading += row else deferred += row
    }
    return leading + deferred
}

/**
 * A single exact title/credit split in the actual native results supplies
 * stronger textual evidence than a title prefix which merely contains the
 * entire query. Keep alternative native identities in the tail, never
 * infer an artist ID or guess a split from arbitrary tokens.
 * Literal title queries and ambiguous compound splits retain their order.
 */
internal fun <T> prioritizeNativeCompoundCredit(
    rows: List<T>,
    query: String,
    title: (T) -> String,
    credit: (T) -> String,
): List<T> {
    if (query.isBlank() || rows.any { title(it) == query }) return rows
    val exactCredits = rows.mapNotNull { row ->
        val work = title(row)
        val artist = credit(row)
        artist.takeIf { it.isNotBlank() && work.isNotBlank() &&
            query in listOf("$artist $work", "$work $artist",
                "$artist - $work", "$work - $artist") }
    }.distinct()
    val explicit = exactCredits.singleOrNull() ?: return rows
    val (matching, alternatives) = rows.partition { credit(it) == explicit }
    return matching + alternatives
}
