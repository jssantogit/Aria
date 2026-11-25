package com.music.bitchord.data.catalog.metadata

/**
 * PUBLIC iTunes Search API has a 200-item cap and no documented offset.
 * The app pages already-discovered Apple IDs locally and probes different
 * official search attributes when capped or empty, and bounded field-specific
 * continuation for compound queries with a source-supported credit boundary.
 * This is discovery, NOT a
 * server cursor or an assurance that the search results are exhaustive.
 */
internal interface ApplePublicSearchSource {
    suspend fun search(
        query: String,
        entity: ItunesCatalogEntity,
        country: String,
        attribute: ItunesCatalogSearchAttribute?,
    ): ItunesCatalogResult
    /** Optional exact collection discovery; test sources without this route
     * continue to exercise the original bounded Search contract. */
    val supportsArtistCollectionDiscovery: Boolean get() = false
    suspend fun discoverArtistCollections(
        originalQuery: String, artistId: Long, credit: String, country: String,
        knownIds: Set<Long>,
    ): ItunesCatalogResult = ItunesCatalogResult.Success(emptyList())
    val supportsSongCollectionDiscovery: Boolean get() = false
    suspend fun discoverSongCollections(
        originalQuery: String, workTerm: String, country: String, knownIds: Set<Long>,
    ): ItunesCatalogResult = ItunesCatalogResult.Success(emptyList())
}

internal class ItunesPublicSearchSource(
    private val client: ItunesCatalogClient = ItunesCatalogClient(),
) : ApplePublicSearchSource {
    override suspend fun search(
        query: String,
        entity: ItunesCatalogEntity,
        country: String,
        attribute: ItunesCatalogSearchAttribute?,
    ) = client.search(query, entity, country, limit = 200, attribute = attribute)

    override val supportsArtistCollectionDiscovery: Boolean get() = true
    override suspend fun discoverArtistCollections(
        originalQuery: String, artistId: Long, credit: String, country: String,
        knownIds: Set<Long>,
    ): ItunesCatalogResult {
        return when (val lookup = client.lookupArtistAlbums(
            artistId, country, limit = 200,
            capability = com.music.bitchord.data.providers.ProviderCapability.CATALOG_SEARCH,
        )) {
            is ItunesCatalogResult.Failure -> lookup
            is ItunesCatalogResult.Success -> lookup.copy(items = lookup.items.filter {
                it.type == ItunesCatalogItemType.ALBUM && it.storefront == country &&
                    it.artistId == artistId && applePublicSearchFold(it.artistName) == credit &&
                    it.id !in knownIds && (applePublicSearchMatchRank(originalQuery, it) ?: 99) <= 2
            })
        }
    }

    override val supportsSongCollectionDiscovery: Boolean get() = true

    override suspend fun discoverSongCollections(
        originalQuery: String, workTerm: String, country: String, knownIds: Set<Long>,
    ): ItunesCatalogResult {
        val songs = client.search(workTerm, ItunesCatalogEntity.SONG, country,
            limit = 100, attribute = if (workTerm == originalQuery) null
            else ItunesCatalogSearchAttribute.SONG_TERM)
        if (songs !is ItunesCatalogResult.Success) return songs
        val hints = songs.items.asSequence().filter {
            it.type == ItunesCatalogItemType.TRACK && it.storefront == country &&
                it.collectionId != null && it.collectionId > 0 && it.collectionId !in knownIds &&
                !it.collectionTitle.isNullOrBlank()
        }.mapNotNull { song ->
            // Synthetic projection is ONLY a relevance hint for nomination.
            // Never return it or use the song's artist as album ownership.
            val nomination = song.copy(
                type = ItunesCatalogItemType.ALBUM, title = checkNotNull(song.collectionTitle),
            )
            val rank = applePublicSearchMatchRank(originalQuery, nomination) ?: return@mapNotNull null
            if (rank > 2) return@mapNotNull null
            Triple(rank, if (applePublicQualifiedAlbumMatch(originalQuery, nomination)) 0 else 1,
                checkNotNull(song.collectionId))
        }.sortedWith(compareBy<Triple<Int, Int, Long>> { it.first }.thenBy { it.second })
            .map { it.third }.distinct().take(2).toList()
        val verified = mutableListOf<ItunesCatalogItem>()
        var uncertain = songs.possiblyTruncated
        for (id in hints) {
            when (val lookup = client.lookupSearchCollection(id, country)) {
                is ItunesCatalogResult.Failure -> {
                    if (verified.isEmpty()) return lookup
                    uncertain = true
                    break
                }
                is ItunesCatalogResult.Success -> {
                    uncertain = uncertain || lookup.possiblyTruncated
                    val album = lookup.items.singleOrNull {
                        it.id == id && it.type == ItunesCatalogItemType.ALBUM &&
                            it.storefront == country && !it.artistName.isNullOrBlank() &&
                            (applePublicSearchMatchRank(originalQuery, it) ?: 99) <= 2
                    }
                    if (album != null) verified += album
                }
            }
        }
        return ItunesCatalogResult.Success(verified, uncertain)
    }
}

internal data class ApplePublicSearchSnapshot(
    val visible: List<ItunesCatalogItem>,
    val discoveredCount: Int,
    val hasMore: Boolean,
    val coverageUncertain: Boolean,
)

internal sealed interface ApplePublicSearchPageLoad {
    data class Success(val snapshot: ApplePublicSearchSnapshot) : ApplePublicSearchPageLoad
    data class Failure(val reason: ItunesCatalogFailure) : ApplePublicSearchPageLoad
}


private fun applePublicSearchFold(value: String?): String = java.text.Normalizer.normalize(
    value.orEmpty(), java.text.Normalizer.Form.NFKD,
).replace(Regex("\\p{M}+"), "")
    .lowercase(java.util.Locale.ROOT)
    .replace(Regex("\\s+"), " ")
    .trim()

/**
 * Textual work relevance only, not an edition crosswalk or a type inference.
 * Strip a narrow allowlist of trailing commercial edition labels. Preserve
 * bare years, sequel numbers, remix/live/tribute names and unknown qualifiers.
 * Never change the source title or collection ID presented to the user.
 */
internal fun applePublicAlbumWorkKey(title: String): String {
    var key = applePublicSearchFold(title)
    val format = Regex("""(?:\s+[-–—]\s+(?:single|ep)|\s+\((?:single|ep)\)|\s+\[(?:single|ep)\])$""")
    val qualifiers = Regex("""(?:\s+\(([^()]{1,60})\)|\s+\[([^\[\]]{1,60})\])$""")
    val recognized = Regex("""(?:(?:19|20)\d{2}\s+)?(?:digital master|remaster(?:ed)?)(?:\s+(?:19|20)\d{2})?|(?:super\s+)?deluxe(?:\s+(?:edition|version))?|expanded\s+(?:edition|version)|\d{1,3}(?:st|nd|rd|th)\s+anniversary\s+edition""")
    repeat(3) {
        val withoutFormat = key.replace(format, "").trimEnd()
        if (withoutFormat != key && withoutFormat.isNotBlank()) {
            key = withoutFormat
        } else {
            val match = qualifiers.find(key) ?: return key
            val qualifier = match.groupValues[1].ifBlank { match.groupValues[2] }
            if (!recognized.matches(qualifier)) return key
            val base = key.substring(0, match.range.first).trimEnd()
            if (base.isBlank()) return key
            key = base
        }
    }
    return key
}

internal fun applePublicQualifiedAlbumMatch(query: String, row: ItunesCatalogItem): Boolean {
    if (row.type != ItunesCatalogItemType.ALBUM) return false
    val title = applePublicSearchFold(row.title)
    val work = applePublicAlbumWorkKey(row.title)
    if (work == title) return false
    val term = applePublicSearchFold(query)
    val credit = applePublicSearchFold(row.artistName)
    return term == work || (credit.isNotBlank() && term in listOf(
        "$credit $work", "$work $credit", "$credit - $work", "$work - $credit",
    ))
}

/**
 * A year-labelled Single/EP is useful search evidence, not proof of the
 * original work's edition. Do not strip the year from the displayed title,
 * merge IDs, or let it outrank an exact/recognized mastering match.
 */
internal fun applePublicRelatedYearEditionMatch(query: String, row: ItunesCatalogItem): Boolean {
    if (row.type != ItunesCatalogItemType.ALBUM ||
        ItunesEditorialCatalog.formatFromAppleTitle(row.title) !in
            setOf(ItunesEditorialReleaseFormat.SINGLE, ItunesEditorialReleaseFormat.EP)
    ) return false
    val base = applePublicAlbumWorkKey(row.title)
    val match = Regex("""^(.+)\s+(?:19|20)\d{2}$""").matchEntire(base) ?: return false
    val work = match.groupValues[1]
    val term = applePublicSearchFold(query)
    val credit = applePublicSearchFold(row.artistName)
    return term == work || (credit.isNotBlank() && term in listOf(
        "$credit $work", "$work $credit", "$credit - $work", "$work - $credit",
    ))
}

/** A returned source credit may reveal the correct boundary of a compound
 * query even when its other field is irrelevant. This nominates a search term
 * only: every recovered item still has to match the entire original query. */
internal data class ApplePublicCompoundHint(val work: String, val credit: String)

internal fun applePublicSearchCompoundHints(
    query: String,
    rows: List<ItunesCatalogItem>,
): List<ApplePublicCompoundHint> {
    val term = applePublicSearchFold(query)
    if (term.isBlank()) return emptyList()
    return rows.asSequence().filter { it.type != ItunesCatalogItemType.ARTIST }
        .mapNotNull { row ->
            val credit = applePublicSearchFold(row.artistName)
            if (credit.isBlank() || credit == term) return@mapNotNull null
            val prefix = listOf("$credit - ", "$credit ").firstOrNull(term::startsWith)
            val suffix = listOf(" - $credit", " $credit").firstOrNull(term::endsWith)
            val work = when {
                prefix != null -> term.removePrefix(prefix)
                suffix != null -> term.removeSuffix(suffix)
                else -> return@mapNotNull null
            }.trim()
            work.takeIf { it.length in 1..120 && it.any(Char::isLetterOrDigit) }
                ?.let { ApplePublicCompoundHint(it, credit) }
        }
        // A longer complete credit is a more informative boundary than a
        // short overlapping credit. No artist ID is inferred from this.
        .sortedByDescending { it.credit.length }
        .distinctBy { it.work }.take(2).toList()
}

internal fun applePublicSearchWorkHints(
    query: String,
    rows: List<ItunesCatalogItem>,
): List<String> = applePublicSearchCompoundHints(query, rows).map { it.work }

/**
 * Public iTunes search is fuzzy; only an independently visible title or artist
 * credit can make an item relevant to the typed query. This is textual search
 * evidence, NOT an artist identity crosswalk, popularity score or playable ID.
 *
 * Word-start matching intentionally rejects "Ill-It" for "illit" and
 * "MOKA ILLIT" for "illit", without removing legitimate artist-owned tracks
 * whose track titles do not contain the artist name.
 */
internal fun applePublicSearchMatchRank(
    query: String,
    row: ItunesCatalogItem,
): Int? {
    fun beginsAsWholeTerm(field: String, term: String): Boolean =
        field.startsWith(term) &&
            (field.length == term.length || !field[term.length].isLetterOrDigit())

    val term = applePublicSearchFold(query)
    if (term.isEmpty()) return null
    val title = applePublicSearchFold(row.title)
    val artist = applePublicSearchFold(row.artistName)
    // "artist title" and "title - artist" are common disambiguating
    // queries. Both fields must be present; do not strip punctuation or
    // accept arbitrary token/substring overlap.
    val compound = if (artist.isNotEmpty() && title.isNotEmpty()) listOf(
        "$artist $title", "$title $artist", "$artist - $title", "$title - $artist",
    ) else emptyList()
    // A title-first query must match the complete artist credit even when
    // Apple appends an edition qualifier to the work title.
    val titleFirstWork = if (artist.isNotEmpty()) {
        listOf(" - $artist", " $artist").firstNotNullOfOrNull { suffix ->
            term.takeIf { it.endsWith(suffix) }?.removeSuffix(suffix)
                ?.takeIf { it.isNotBlank() }
        }
    } else null
    val titleFirstEdition = titleFirstWork?.let { work ->
        beginsAsWholeTerm(title, work) && title.length > work.length &&
            title.substring(work.length).trimStart().firstOrNull() in
                listOf('(', '[', '-', '–', '—')
    } == true
    return when (row.type) {
        ItunesCatalogItemType.ARTIST -> when {
            title == term -> 0
            title.startsWith("the ") && title.drop(4) == term -> 1
            beginsAsWholeTerm(title, term) -> 2
            title.startsWith("the ") && beginsAsWholeTerm(title.drop(4), term) -> 3
            else -> null
        }
        ItunesCatalogItemType.TRACK, ItunesCatalogItemType.ALBUM -> when {
            artist == term -> 0
            title == term || term in compound -> 1
            beginsAsWholeTerm(title, term) ||
                compound.any { beginsAsWholeTerm(it, term) } || titleFirstEdition ||
                applePublicRelatedYearEditionMatch(query, row) -> 2
            beginsAsWholeTerm(artist, term) -> 3
            else -> null
        }
    }
}

internal class ApplePublicSearchPager(
    private val query: String,
    private val entity: ItunesCatalogEntity,
    private val country: String,
    private val pageSize: Int = 24,
    private val source: ApplePublicSearchSource = ItunesPublicSearchSource(),
) {
    init {
        require(Regex("[a-z]{2}").matches(country))
        require(pageSize in 1..100)
    }

    private val rows = linkedMapOf<Long, ItunesCatalogItem>()
    private val disputed = mutableSetOf<Long>()
    private val extraAttributes = when (entity) {
        ItunesCatalogEntity.ARTIST -> listOf(
            ItunesCatalogSearchAttribute.ARTIST_TERM,
        )
        ItunesCatalogEntity.ALBUM -> listOf(
            ItunesCatalogSearchAttribute.ALBUM_TERM,
            ItunesCatalogSearchAttribute.ARTIST_TERM,
        )
        ItunesCatalogEntity.SONG -> listOf(
            ItunesCatalogSearchAttribute.SONG_TERM,
            ItunesCatalogSearchAttribute.ARTIST_TERM,
        )
    }
    // Attribute probes with the complete query may still miss a compound
    // title/credit: albumTerm and songTerm address a single source field.
    // Use at most two additional field-specific probes after an empty result
    // or on continuation of a source-supported compound landing. Always rank
    // returned rows against the ORIGINAL query.
    // A split is a discovery hint, never identity evidence.
    private val fallbackCompoundTerms: List<String> = if (entity == ItunesCatalogEntity.ARTIST) {
        emptyList()
    } else {
        val explicit = query.split(" - ")
        val words = query.trim().split(Regex("\\s+"))
        when {
            explicit.size == 2 && explicit.all { it.isNotBlank() } ->
                explicit.map(String::trim).distinct()
            explicit.size == 1 && words.size in 4..8 -> {
                val cut = (words.size + 1) / 2
                listOf(words.take(cut).joinToString(" "), words.drop(cut).joinToString(" "))
                    .distinct()
            }
            // Longer compound works cannot be split at the midpoint without
            // frequently bisecting the title. Probe the complement of a
            // short leading/trailing credit only when other discovery is empty.
            explicit.size == 1 && words.size in 9..14 -> {
                val left = if (words.first().equals("the", true)) 3 else 2
                val right = if (words[words.size - 3].equals("the", true)) 3 else 2
                listOf(words.drop(left).joinToString(" "), words.dropLast(right).joinToString(" "))
                    .distinct()
            }
            else -> emptyList()
        }
    }
    private val compoundAttribute = when (entity) {
        ItunesCatalogEntity.ARTIST -> ItunesCatalogSearchAttribute.ARTIST_TERM
        ItunesCatalogEntity.ALBUM -> ItunesCatalogSearchAttribute.ALBUM_TERM
        ItunesCatalogEntity.SONG -> ItunesCatalogSearchAttribute.SONG_TERM
    }
    private val expected = when (entity) {
        ItunesCatalogEntity.ARTIST -> ItunesCatalogItemType.ARTIST
        ItunesCatalogEntity.ALBUM -> ItunesCatalogItemType.ALBUM
        ItunesCatalogEntity.SONG -> ItunesCatalogItemType.TRACK
    }
    private var initialized = false
    private var capped = false
    private var probeAttributes = false
    private var compoundContinuation = false
    private var visibleCount = 0
    private var nextAttribute = 0
    private var nextCompoundTerm = 0
    private var artistCollectionAttempted = false
    private val creditArtistIds = linkedMapOf<String, MutableSet<Long>>()
    private fun artistCollectionHint(): Pair<String, Long>? {
        if (entity != ItunesCatalogEntity.ALBUM || !source.supportsArtistCollectionDiscovery ||
            artistCollectionAttempted || rows.size >= pageSize
        ) return null
        // One unambiguous source credit, never a title-based artist-ID guess.
        val credit = creditHints.values.maxByOrNull { it.credit.length }?.credit ?: return null
        val id = creditArtistIds[credit]?.singleOrNull() ?: return null
        return credit to id
    }
    private var songCollectionAttempted = false
    private fun songCollectionTerm(): String? {
        if (entity != ItunesCatalogEntity.ALBUM || !source.supportsSongCollectionDiscovery ||
            songCollectionAttempted || rows.size >= pageSize
        ) return null
        // A corroborated credit boundary is preferred. Otherwise use the
        // original query only for an empty or sparse work-oriented search,
        // never an already productive exact artist-name listing.
        return creditHints.values.firstOrNull()?.work
            ?: query.takeIf { rows.isEmpty() || query.contains(" - ") ||
                rows.values.none { applePublicSearchMatchRank(query, it) == 0 }
            }
    }
    private val creditHints = linkedMapOf<String, ApplePublicCompoundHint>()
    private data class CompoundProbe(val term: String, val attribute: ItunesCatalogSearchAttribute)
    private var frozenCompoundProbes: List<CompoundProbe>? = null
    private val compoundProbes: List<CompoundProbe>
        get() = frozenCompoundProbes ?: (
            creditHints.values.map { CompoundProbe(it.work, compoundAttribute) } +
                creditHints.values.map { CompoundProbe(it.credit, ItunesCatalogSearchAttribute.ARTIST_TERM) } +
                fallbackCompoundTerms.map { CompoundProbe(it, compoundAttribute) }
            ).distinct().take(2)

    private fun hasPendingFieldProbe(): Boolean =
        (probeAttributes && nextAttribute < extraAttributes.size) ||
            ((rows.isEmpty() || compoundContinuation) && nextCompoundTerm < compoundProbes.size)

    private fun hasPendingProbe(): Boolean =
        hasPendingFieldProbe() || artistCollectionHint() != null || songCollectionTerm() != null

    fun snapshot() = ApplePublicSearchSnapshot(
        visible = rows.values.take(visibleCount),
        discoveredCount = rows.size,
        hasMore = rows.size > visibleCount || (initialized && hasPendingProbe()),
        coverageUncertain = capped || disputed.isNotEmpty() ||
            (initialized && compoundContinuation && nextCompoundTerm < compoundProbes.size) ||
            (initialized && (artistCollectionHint() != null || songCollectionTerm() != null)),
    )

    private fun merge(result: ItunesCatalogResult.Success) {
        if (entity == ItunesCatalogEntity.ALBUM) {
            val scoped = result.items.filter {
                it.type == expected && it.storefront == country && it.id > 0L &&
                    it.title.isNotBlank() && it.id !in disputed
            }
            for (hint in applePublicSearchCompoundHints(query, scoped)) {
                // Keep this set bounded by the same two credit hints. A second
                // distinct ID is sufficient to disqualify an ambiguous credit.
                if (hint.credit !in creditArtistIds && creditArtistIds.size >= 2) continue
                val ids = creditArtistIds.getOrPut(hint.credit) { linkedSetOf() }
                for (row in scoped) {
                    if (applePublicSearchFold(row.artistName) == hint.credit) {
                        val id = row.artistId
                        if (id != null && id > 0 && ids.size < 2) ids += id
                    }
                }
            }
        }
        if (entity != ItunesCatalogEntity.ARTIST && frozenCompoundProbes == null &&
            creditHints.size < 2
        ) {
            val scoped = result.items.filter { it.type == expected && it.storefront == country &&
                it.id > 0L && it.title.isNotBlank() && it.id !in disputed }
            for (hint in applePublicSearchCompoundHints(query, scoped)) {
                if (creditHints.size >= 2) break
                creditHints.putIfAbsent(hint.work, hint)
            }
        }
        // Rank each incoming official batch once, then append new verified
        // IDs. Never re-sort previously visible pages as portraits or later
        // public Search attributes arrive.
        result.items.asSequence()
            .filter { it.type == expected && it.storefront == country &&
                it.id > 0L && it.title.isNotBlank() && it.id !in disputed }
            .mapNotNull { row ->
                applePublicSearchMatchRank(query, row)?.let { it to row }
            }
            .sortedWith(compareBy<Pair<Int, ItunesCatalogItem>> { it.first }
                .thenBy { when {
                    applePublicQualifiedAlbumMatch(query, it.second) -> 0
                    applePublicRelatedYearEditionMatch(query, it.second) -> 2
                    else -> 1
                } })
            .forEach { (_, row) ->
            val prev = rows[row.id]
            if (prev == null) {
                rows[row.id] = row
            } else if (prev.title != row.title || prev.artistId != row.artistId ||
                prev.artistName != row.artistName || prev.collectionId != row.collectionId
            ) {
                rows.remove(row.id)
                disputed.add(row.id)
                visibleCount = minOf(visibleCount, rows.size)
            }
        }
    }

    suspend fun start(): ApplePublicSearchPageLoad {
        if (initialized) return ApplePublicSearchPageLoad.Success(snapshot())
        if (query.trim().isEmpty() || query.length > 120 ||
            query.any(Char::isISOControl)
        ) return ApplePublicSearchPageLoad.Failure(ItunesCatalogFailure.INVALID_QUERY)
        return when (val result = source.search(query, entity, country, null)) {
            is ItunesCatalogResult.Failure ->
                ApplePublicSearchPageLoad.Failure(result.reason)
            is ItunesCatalogResult.Success -> {
                initialized = true
                capped = result.possiblyTruncated
                merge(result)
                visibleCount = minOf(pageSize, rows.size)
                // An uncapped fuzzy batch can also be entirely irrelevant.
                // Spend the same bounded official attribute probes only for
                // an otherwise empty landing, never for a productive uncapped batch.
                probeAttributes = capped || rows.isEmpty()
                // A fuzzy but productive compound result can omit the intended
                // edition. Offer bounded title-field continuation even when the
                // first response is uncapped. Do not add landing latency or
                // probe ordinary artist-only queries.
                compoundContinuation = rows.isNotEmpty() && entity != ItunesCatalogEntity.ARTIST &&
                    (creditHints.isNotEmpty() || query.split(" - ").let { parts ->
                        parts.size == 2 && parts.all { it.isNotBlank() }
                    })
                if (rows.isEmpty() && probeAttributes) loadMore()
                else ApplePublicSearchPageLoad.Success(snapshot())
            }
        }
    }

    suspend fun loadMore(): ApplePublicSearchPageLoad {
        if (!initialized) return start()
        if (rows.size > visibleCount) {
            visibleCount = minOf(rows.size, visibleCount + pageSize)
            return ApplePublicSearchPageLoad.Success(snapshot())
        }
        val before = visibleCount
        while (hasPendingFieldProbe()) {
            val wholeQuery = probeAttributes && nextAttribute < extraAttributes.size
            if (!wholeQuery && frozenCompoundProbes == null) frozenCompoundProbes = compoundProbes
            val probe = if (wholeQuery) null else compoundProbes[nextCompoundTerm]
            val attribute = probe?.attribute ?: extraAttributes[nextAttribute]
            val term = probe?.term ?: query
            when (val result = source.search(term, entity, country, attribute)) {
                is ItunesCatalogResult.Failure ->
                    return ApplePublicSearchPageLoad.Failure(result.reason)
                is ItunesCatalogResult.Success -> {
                    if (wholeQuery) nextAttribute++ else nextCompoundTerm++
                    capped = capped || result.possiblyTruncated
                    merge(result)
                    if (rows.size > visibleCount) {
                        visibleCount = minOf(rows.size, visibleCount + pageSize)
                        break
                    }
                    // Duplicate-only/irrelevant probes cannot strand the
                    // next legitimate official search attribute.
                }
            }
        }
        if (visibleCount == before && rows.size <= visibleCount && !hasPendingFieldProbe()) {
            val hint = artistCollectionHint()
            if (hint != null) {
                artistCollectionAttempted = true
                when (val result = source.discoverArtistCollections(
                    query, hint.second, hint.first, country, rows.keys.toSet() + disputed,
                )) {
                    is ItunesCatalogResult.Failure -> {
                        capped = true
                        return ApplePublicSearchPageLoad.Failure(result.reason)
                    }
                    is ItunesCatalogResult.Success -> {
                        capped = capped || result.possiblyTruncated
                        merge(result)
                        visibleCount = minOf(rows.size, visibleCount + pageSize)
                        if (visibleCount > before) return ApplePublicSearchPageLoad.Success(snapshot())
                    }
                }
            }
            val term = songCollectionTerm()
            if (term != null) {
                // One song index plus at most two exact collection reads,
                // under the same shared limiter. Never loop on a bad source.
                songCollectionAttempted = true
                when (val result = source.discoverSongCollections(query, term, country, rows.keys.toSet() + disputed)) {
                    is ItunesCatalogResult.Failure -> {
                        capped = true
                        return ApplePublicSearchPageLoad.Failure(result.reason)
                    }
                    is ItunesCatalogResult.Success -> {
                        capped = capped || result.possiblyTruncated
                        merge(result)
                        visibleCount = minOf(rows.size, visibleCount + pageSize)
                    }
                }
            }
        }
        return ApplePublicSearchPageLoad.Success(snapshot())
    }
}
