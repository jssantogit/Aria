package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.data.providers.ProviderCapability

import androidx.room.Ignore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Public MusicBrainz discovery returns identities and metadata, NEVER playable
 * assets. A result must be reconciled with a provider-owned MediaAsset before
 * PLAY_NOW or ADD_TO_QUEUE can be offered.
 */
internal data class MusicBrainzCatalogPage<out T>(
    val items: List<T>,
    val total: Int,
    val offset: Int,
    /** How many raw server rows this page consumed, including invalid/duplicate hits. */
    val consumedCount: Int = items.size,
) {
    /**
     * Never derive a server cursor from filtered UI results: rejected MBIDs and
     * duplicate rows have already consumed their upstream offset positions.
     * Browsing releases can also return fewer rows than the requested limit.
     */
    val nextOffset: Int? get() =
        if (consumedCount <= 0 || offset < 0 || offset >= total ||
            consumedCount >= total - offset
        ) null else offset + consumedCount
}

internal sealed interface MusicBrainzCatalogSearchResult<out T> {
    data class Success<T>(val page: MusicBrainzCatalogPage<T>) : MusicBrainzCatalogSearchResult<T>
    data class Failure(val reason: MusicBrainzCatalogSearchFailure) : MusicBrainzCatalogSearchResult<Nothing>
}

internal enum class MusicBrainzCatalogSearchFailure {
    INVALID_QUERY,
    INVALID_PAGE,
    UNAVAILABLE,
    MALFORMED_RESPONSE,
}

internal data class MusicBrainzArtistHit(
    val musicBrainzId: String,
    val name: String,
    val disambiguation: String?,
    val country: String?,
)

internal data class MusicBrainzReleaseGroupHit(
    val musicBrainzId: String,
    val title: String,
    val primaryType: String?,
    val firstReleaseDate: String?,
    val artistDisplay: String?,
    val artworkUri: String? = null,
    /** Pipe-separated MusicBrainz types: null = absent/unknown, "" = explicitly no tags. */
    val secondaryTypes: String? = null,
) {
    /** All credited artist MBIDs in source order. Null means incomplete proof.
     * Live search only: Room never reads or stores this field. */
    @Ignore var artistCreditMbids: List<String>? = null
    /** Search-only source tags, not persisted genre assignments. */
    @Ignore var editorialGenreTags: List<String> = emptyList()
    /** Positive linked-release evidence carried by this search response.
     * Never persisted or treated as a listener/popularity count. */
    @Ignore var searchLinkedReleaseMbids: Set<String> = emptySet()
}

internal data class MusicBrainzReleaseHit(
    val musicBrainzId: String,
    val title: String,
    val country: String?,
    val releaseDate: String?,
    val artistDisplay: String?,
    val artworkUri: String? = null,
    /** Release status is supplied by MusicBrainz, never inferred from its title. */
    val status: String? = null,
    /** Search response's exact Release -> ReleaseGroup association, not a title match. */
    val releaseGroupMbid: String? = null,
    /** Positive CAA Front availability flag attached to this *specific* Release. */
    val hasFrontCover: Boolean? = null,
    /** Linked ReleaseGroup primary type, when present; not guessed from title. */
    val releaseGroupPrimaryType: String? = null,
    /** Positive source evidence: every declared medium is explicitly video-only. */
    val isVideoOnlyMedia: Boolean? = null,
    /** Evidence from exact Release's Track/Recording graph, when cached. */
    val isVideoOnlyContent: Boolean? = null,
) {
    /** Source artist MBIDs, never inferred from display names or aliases.
     * Live search only: Room never reads or stores this field. */
    @Ignore var artistCreditMbids: List<String>? = null
}

// A MusicBrainz cover-art-archive.front flag is a presence hint only.
// Never synthesize direct /front-500 URLs from it: CAA may label the
// underlying image Poster/Track or leave its approval unverified.
// CoverArtArchive must validate typed image metadata before presentation.

internal data class MusicBrainzRecordingHit(
    val musicBrainzId: String,
    val title: String,
    val artistDisplay: String?,
    val durationMs: Long?,
    /** Art comes from an actual linked MusicBrainz Release, never title matching. */
    val coverReleaseMbid: String? = null,
    /** A matching published release is evidence, unlike an arbitrary Recording MBID. */
    // Nullable because Room's offline search projection does not select these
    // live-only evidence fields. Missing != positively verified Official.
    val hasOfficialAudioRelease: Boolean? = null,
    val isVideo: Boolean? = null,
    /** Direct MusicBrainz Recording -> Official Release -> audio ReleaseGroup. */
    val coverGroupMbid: String? = null,
) {
    /** Search response's distinct official audio release IDs, not popularity.
     * Never stored as recording identity or used to authorize playback. */
    @Ignore var searchOfficialAudioReleaseMbids: Set<String> = emptySet()
    /** Complete live source credit; never a name-to-MBID inference. */
    @Ignore var artistCreditMbids: List<String>? = null
}

/**
 * Read-only search endpoint for the independent catalog.
 *
 * Deliberately does not return SearchResult.Track/Song, because their legacy
 * videoId-based play path could silently resolve a metadata hit via YouTube.
 * The shared MusicBrainzClient rate limiter protects lookups and search alike.
 */
/**
 * Search's raw MusicBrainz corpus is retained in the index, but General's
 * streaming-oriented rows expose only recordings with an official audio
 * release. Avoid blocking legitimate label-issued remixes/instrumentals.
 * Reject video-only and user-edited titles even if mislabeled "Official".
 */
/** Explicit edition status is required for public streaming-style Release tiles. */
internal fun isCuratedMusicBrainzRelease(hit: MusicBrainzReleaseHit): Boolean =
    hit.status.equals("Official", ignoreCase = true) &&
        // Official is a publication status, not proof of an audio edition.
        hit.isVideoOnlyMedia != true && hit.isVideoOnlyContent != true &&
        canonicalStreamingTitleIsSafe(hit.title) &&
        // A legitimate Official edition may be a film/video or broadcast.
        // Reject only an explicitly unsupported primary type; unknown stays
        // browseable while a trusted group classification is unavailable.
        (hit.releaseGroupPrimaryType == null ||
            hit.releaseGroupPrimaryType.trim().lowercase(Locale.ROOT) in
                setOf("album", "ep", "single"))

/**
 * General Search is a music album/EP/single shelf, not the entire editable
 * MusicBrainz corpus. Retain all raw groups in the canonical index and their
 * MBIDs; only published-looking, typed groups with an actual artist credit
 * and a safe title become mainstream result cards. Secondary Live/Remix
 * tags alone are not proof of a bogus release.
 */
internal fun isCuratedMusicBrainzReleaseGroup(hit: MusicBrainzReleaseGroupHit): Boolean =
    (hit.primaryType?.trim()?.lowercase(Locale.ROOT) ?: "") in
        setOf("album", "ep", "single") &&
        !hit.artistDisplay.isNullOrBlank() && canonicalStreamingTitleIsSafe(hit.title)

internal fun isCuratedMusicBrainzRecording(hit: MusicBrainzRecordingHit): Boolean =
    hit.hasOfficialAudioRelease == true && hit.isVideo != true &&
        canonicalStreamingTitleIsSafe(hit.title)

internal fun canonicalStreamingTitleIsSafe(title: String): Boolean {
    if (title.isBlank()) return false
    val normalized = title.trim()
    if (NON_STREAMING_TITLE.containsMatchIn(normalized)) return false
    return !MEMBER_PRACTICE_VERSION.containsMatchIn(normalized)
}

private val NON_STREAMING_TITLE = Regex(
    """(?i)\b(?:performance\s*(?:ver(?:sion)?\.?|video)|dance\s*(?:practice|fix|performance)|practice\s*ver(?:sion)?\.?|jersey\s*flip|fan[- ]?(?:edit|made)|fancam|music\s*video|sped\s*up|slowed(?:\s*(?:and|&)\s*reverb)?|nightcore|mashup|rehearsal|fix\s*ver(?:sion)?\.?)\b"""
)
private val MEMBER_PRACTICE_VERSION = Regex(
    """\([A-Z]{3,}(?:\s*&\s*[A-Z]{3,})+\s+ver\.?\)"""
)

/** Relevance within one source page, not a popularity or identity assertion.
 * Exact title+credit in either order is stronger than a title-only match.
 * Preserve original source order for ties and leave upstream cursors intact. */
internal fun musicBrainzPublicSearchKey(value: String?): String =
    java.text.Normalizer.normalize(value.orEmpty().trim(), java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

internal fun musicBrainzPublicSearchRank(query: String, title: String, credit: String?): Int {
    val term = musicBrainzPublicSearchKey(query)
    val work = musicBrainzPublicSearchKey(title)
    val artist = musicBrainzPublicSearchKey(credit)
    if (term.isBlank() || work.isBlank()) return 3
    if (artist.isNotBlank() && term in listOf(
            "$artist $work", "$work $artist", "$artist - $work", "$work - $artist",
        )) return 0
    if (term == work) return 1
    if (artist.isNotBlank() && term == artist) return 2
    return 3
}

/** Public search display admission, not identity reconciliation. Keep the
 * source page's raw cursor and leave editorial/independent evidence queries
 * unfiltered. Exact artist namesakes retain distinct MBIDs. */
private fun publicSearchWholePrefix(value: String, term: String): Boolean =
    term.isNotBlank() && value.startsWith(term) &&
        (value.length == term.length || !value[term.length].isLetterOrDigit())

internal fun publicMusicBrainzArtistRows(
    query: String, hits: List<MusicBrainzArtistHit>,
): List<MusicBrainzArtistHit> {
    val term = musicBrainzPublicSearchKey(query)
    if (term.isBlank()) return emptyList()
    fun exact(name: String, key: String): Boolean =
        name == key || name.removePrefix("the ") == key.removePrefix("the ")
    val literal = hits.filter { exact(musicBrainzPublicSearchKey(it.name), term) }
    if (literal.isNotEmpty()) return literal
    val base = Regex("""^(.+?)\s+\(([2-9]|[1-9]\d{1,3})\)$""")
        .matchEntire(term)?.groupValues?.get(1)
    if (base != null) {
        val alternatives = hits.filter { exact(musicBrainzPublicSearchKey(it.name), base) }
        if (alternatives.isNotEmpty()) return alternatives
    }
    return hits.filter {
        val name = musicBrainzPublicSearchKey(it.name)
        publicSearchWholePrefix(name, term) ||
            publicSearchWholePrefix(name.removePrefix("the "), term.removePrefix("the "))
    }
}

internal fun publicMusicBrainzWorkMatches(query: String, title: String, credit: String?): Boolean {
    val term = musicBrainzPublicSearchKey(query)
    val work = musicBrainzPublicSearchKey(title)
    val artist = musicBrainzPublicSearchKey(credit)
    if (term.isBlank() || work.isBlank()) return false
    fun meaningfulPrefix(value: String, key: String): Boolean {
        if (!publicSearchWholePrefix(value, key)) return false
        if (value == key) return true
        // A fuzzy repeated-word result is not the requested literal work.
        // Still admit real partial titles, punctuation and edition suffixes.
        val tail = value.removePrefix(key).trim()
        val tokens = tail.split(Regex("""[^\p{L}\p{N}]+""")).filter(String::isNotBlank)
        val keyTokens = key.split(Regex("""[^\p{L}\p{N}]+""")).filter(String::isNotBlank)
        return keyTokens.size != 1 || tokens.isEmpty() || tokens.any { it != keyTokens.single() }
    }
    if (musicBrainzPublicSearchRank(query, title, credit) <= 2) return true
    // Both ends of a compound query must be explained by the same source row.
    // A matching credit alone cannot admit an unrelated album or recording.
    if (artist.isNotBlank()) {
        val prefix = listOf("$artist - ", "$artist ").firstOrNull(term::startsWith)
        val suffix = listOf(" - $artist", " $artist").firstOrNull(term::endsWith)
        if (prefix != null) return meaningfulPrefix(work, term.removePrefix(prefix))
        if (suffix != null) return meaningfulPrefix(work, term.removeSuffix(suffix))
    }
    return meaningfulPrefix(work, term) || publicSearchWholePrefix(artist, term)
}

internal fun <T> publicMusicBrainzWorkRows(
    query: String, hits: List<T>, title: (T) -> String, credit: (T) -> String?,
): List<T> = hits.filter { publicMusicBrainzWorkMatches(query, title(it), credit(it)) }

private fun <T> MusicBrainzCatalogSearchResult<T>.publicProjection(
    select: (List<T>) -> List<T>,
): MusicBrainzCatalogSearchResult<T> = when (this) {
    is MusicBrainzCatalogSearchResult.Failure -> this
    is MusicBrainzCatalogSearchResult.Success -> copy(page = page.copy(items = select(page.items)))
}

/** Never scope all search categories to the first of several namesakes. */
internal fun musicBrainzUnambiguousArtistScope(
    query: String, artists: List<MusicBrainzArtistHit>,
): String? {
    val key = musicBrainzPublicSearchKey(query)
    if (key.isBlank()) return null
    return artists.filter { musicBrainzPublicSearchKey(it.name) == key }
        .map { it.musicBrainzId }.distinct().singleOrNull()
}

private fun <T> MusicBrainzCatalogSearchResult<T>.rankPublicPage(
    query: String, title: (T) -> String, credit: (T) -> String? = { null },
): MusicBrainzCatalogSearchResult<T> = when (this) {
    is MusicBrainzCatalogSearchResult.Failure -> this
    is MusicBrainzCatalogSearchResult.Success -> copy(
        page = page.copy(items = page.items.sortedBy {
            musicBrainzPublicSearchRank(query, title(it), credit(it))
        }),
    )
}

/** One larger upstream landing page for unscoped work searches. No extra
 * request or fabricated offset: later scrolls start at the raw consumed count.
 * Artist-scoped discography searches retain their independent small budget. */
internal const val MUSICBRAINZ_PUBLIC_WORK_LANDING_LIMIT = 50

internal class MusicBrainzCatalogSearch(
    private val client: MusicBrainzClient = MusicBrainzClient(),
    private val capability: ProviderCapability = ProviderCapability.CATALOG_SEARCH,
) {
    init {
        require(capability == ProviderCapability.CATALOG_SEARCH ||
            capability == ProviderCapability.EDITORIAL_DISCOGRAPHY) {
            "MusicBrainz search supports only catalog or editorial reads"
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun searchArtists(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzArtistHit> =
        searchArtistsInternal(query, limit, offset)
            .rankPublicPage(query, MusicBrainzArtistHit::name)

    // Interactive searches only; raw adapter calls remain available for
    // indexing, editorial traversal and independent identity evidence.
    suspend fun searchPublicArtists(query: String, limit: Int = 15, offset: Int = 0) =
        projectPublicArtistLanding(query, searchArtists(query, limit, offset))

    /**
     * One bounded release-group index probe for ambiguous literal artist
     * landings. Only native MBIDs and distinct work titles count. This is
     * relevance evidence, not popularity or proof of intent; all identities
     * and the original artist cursor survive, including on probe failure.
     */
    internal suspend fun projectPublicArtistLanding(
        query: String, result: MusicBrainzCatalogSearchResult<MusicBrainzArtistHit>,
    ): MusicBrainzCatalogSearchResult<MusicBrainzArtistHit> {
        val display = result.publicProjection { publicMusicBrainzArtistRows(query, it) }
        if (display !is MusicBrainzCatalogSearchResult.Success ||
            display.page.offset != 0 || capability != ProviderCapability.CATALOG_SEARCH
        ) return display
        val term = musicBrainzPublicSearchKey(query).removePrefix("the ")
        val literal = display.page.items.filter {
            musicBrainzPublicSearchKey(it.name).removePrefix("the ") == term
        }
        val base = Regex("""^(.+?)\s+\(([2-9]|[1-9]\d{1,3})\)$""")
            .matchEntire(term)?.groupValues?.get(1)
        val exact = if (literal.isNotEmpty()) literal else display.page.items.filter {
            base != null && musicBrainzPublicSearchKey(it.name).removePrefix("the ") == base
        }
        val candidates = exact.associateBy { it.musicBrainzId }
        if (candidates.size < 2) return display
        val sourceName = exact.first().name
        if (sourceName.length !in 1..100 || sourceName.any(Char::isISOControl)) return display
        val quoted = "\"" + sourceName.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val evidence = searchReleaseGroupsInternal(
            "artist:$quoted", 50, 0, fieldedQuery = true,
        ) as? MusicBrainzCatalogSearchResult.Success ?: return display
        val support = evidence.page.items.asSequence().filter(::isCuratedMusicBrainzReleaseGroup)
            .filter { it.searchLinkedReleaseMbids.isNotEmpty() }
            .mapNotNull { work ->
                val id = work.artistCreditMbids?.singleOrNull() ?: return@mapNotNull null
                val candidate = candidates[id] ?: return@mapNotNull null
                if (musicBrainzPublicSearchKey(work.artistDisplay) != musicBrainzPublicSearchKey(candidate.name))
                    return@mapNotNull null
                id to musicBrainzPublicSearchKey(work.title)
            }.distinct().groupingBy { it.first }.eachCount()
        val ranked = display.page.items.sortedByDescending {
            (support[it.musicBrainzId] ?: 0).takeIf { count -> count >= 2 }?.coerceAtMost(3) ?: 0
        }
        return display.copy(page = display.page.copy(items = ranked))
    }

    suspend fun searchPublicRecordings(query: String, limit: Int = 50, offset: Int = 0) =
        searchRecordings(query, limit, offset, recoverWeakLanding = offset == 0)
            .publicProjection { publicMusicBrainzWorkRows(query, it,
                MusicBrainzRecordingHit::title, MusicBrainzRecordingHit::artistDisplay) }

    suspend fun searchPublicReleaseGroups(query: String, limit: Int = 50, offset: Int = 0) =
        searchReleaseGroups(query, limit, offset, recoverWeakLanding = offset == 0)
            .publicProjection { publicMusicBrainzWorkRows(query, it,
                MusicBrainzReleaseGroupHit::title, MusicBrainzReleaseGroupHit::artistDisplay) }

    suspend fun searchPublicReleases(query: String, limit: Int = 50, offset: Int = 0) =
        searchReleases(query, limit, offset).publicProjection { publicMusicBrainzWorkRows(query, it,
            MusicBrainzReleaseHit::title, MusicBrainzReleaseHit::artistDisplay) }

    /** A known genre tag is fielded source evidence, not a guessed artist rank. */
    suspend fun searchArtistsForTag(
        tag: String,
        limit: Int = 18,
    ): MusicBrainzCatalogSearchResult<MusicBrainzArtistHit> {
        val safe = safeMusicBrainzGenreTag(tag)
            ?: return MusicBrainzCatalogSearchResult.Failure(
                MusicBrainzCatalogSearchFailure.INVALID_QUERY,
            )
        return searchArtistsInternal("tag:\"$safe\"", limit, 0, fieldedQuery = true)
    }

    private suspend fun searchArtistsInternal(
        query: String,
        limit: Int,
        offset: Int,
        fieldedQuery: Boolean = false,
    ): MusicBrainzCatalogSearchResult<MusicBrainzArtistHit> =
        search("artist", query, limit, offset, fieldedQuery) { raw ->
            val result = json.decodeFromString<ArtistSearchEnvelope>(raw)
            result.pageOrNull(result.artists?.size, MusicBrainzArtistHit::musicBrainzId) {
                result.artists?.mapNotNull { artist ->
                    val mbid = artist.id.validSearchMbidOrNull() ?: return@mapNotNull null
                    val name = artist.name.nonEmpty() ?: return@mapNotNull null
                    MusicBrainzArtistHit(mbid, name, artist.disambiguation.nonEmpty(), artist.country.nonEmpty())
                }
            }
        }

    suspend fun searchReleaseGroups(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
        // Only the interactive first page may spend the extra recovery read.
        recoverWeakLanding: Boolean = false,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseGroupHit> {
        val initial = searchReleaseGroupsInternal(query, limit, offset)
            .rankPublicPage(query, MusicBrainzReleaseGroupHit::title, MusicBrainzReleaseGroupHit::artistDisplay)
        if (!recoverWeakLanding || capability != ProviderCapability.CATALOG_SEARCH ||
            initial !is MusicBrainzCatalogSearchResult.Success || offset != 0 ||
            limit != MUSICBRAINZ_PUBLIC_WORK_LANDING_LIMIT || query.trim().length !in 1..100 ||
            query.any(Char::isISOControl) ||
            initial.page.items.any {
                isCuratedMusicBrainzReleaseGroup(it) &&
                    musicBrainzPublicSearchRank(query, it.title, it.artistDisplay) <= 1
            }
        ) return initial
        val fielded = publicWorkProbeQuery("releasegroup", query,
            initial.page.items.map { it.artistDisplay })
        val probe = searchReleaseGroupsInternal(fielded, 12, 0, fieldedQuery = true)
            as? MusicBrainzCatalogSearchResult.Success ?: return initial
        val original = initial.page.items.associateBy { it.musicBrainzId }
        val recovered = probe.page.items.filter {
            isCuratedMusicBrainzReleaseGroup(it) &&
                musicBrainzPublicSearchRank(query, it.title, it.artistDisplay) <= 1 &&
                (original[it.musicBrainzId]?.let { old ->
                    old == it && old.artistCreditMbids == it.artistCreditMbids
                } ?: true)
        }
        if (recovered.isEmpty()) return initial
        return initial.copy(page = initial.page.copy(items =
            (initial.page.items + recovered).distinctBy { it.musicBrainzId }
                .sortedBy { musicBrainzPublicSearchRank(query, it.title, it.artistDisplay) },
        ))
    }

    /** Independent, bounded source corroboration of a known artist/work pair.
     * This is not a landing-ranking hint and cannot fabricate an artist ID. */
    suspend fun searchReleaseGroupsForTitleArtist(
        title: String, artist: String, limit: Int = 12,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseGroupHit> {
        if (title.isBlank() || artist.isBlank() || title.length > 80 || artist.length > 80 ||
            limit !in 1..12 || title.any(Char::isISOControl) || artist.any(Char::isISOControl)
        ) return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        fun quote(value: String): String =
            "\"" + value.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return searchReleaseGroupsInternal(
            "releasegroup:${quote(title)} AND artist:${quote(artist)}",
            limit, 0, fieldedQuery = true,
        )
    }

    /** Exact-title discovery within an independently selected artist MBID.
     * Used for independent corroboration; returned rows still require matching
     * title, credit and credited MBID. No name-only identity assignment. */
    suspend fun searchReleaseGroupsForArtistMbidAndTitle(
        artistMbid: String, title: String,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseGroupHit> {
        val id = artistMbid.validSearchMbidOrNull()
            ?: return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        if (title.isBlank() || title.length > 80 || title.any(Char::isISOControl))
            return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        val quoted = "\"" + title.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return searchReleaseGroupsInternal(
            "arid:$id AND releasegroup:$quoted", 12, 0, fieldedQuery = true,
        )
    }

    suspend fun searchReleaseGroupsForArtistMbid(
        artistMbid: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseGroupHit> {
        val id = artistMbid.validSearchMbidOrNull()
            ?: return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        return searchReleaseGroupsInternal("arid:$id", limit, offset, fieldedQuery = true)
    }

    /**
     * Public editorial discovery does not require a populated local index.
     * Fielded Lucene queries must use dismax=false; date/type are server
     * evidence, not inferred from a title or the app install date.
     */
    suspend fun searchRecentReleaseGroups(
        fromIsoDate: String,
        throughIsoDate: String,
        limit: Int = 100,
        /** Cursor counts source rows, not accepted editorial rows. */
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseGroupHit> {
        val date = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        if (!date.matches(fromIsoDate) || !date.matches(throughIsoDate) ||
            fromIsoDate > throughIsoDate
        ) return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        return searchReleaseGroupsInternal(
            "firstreleasedate:[$fromIsoDate TO $throughIsoDate] AND " +
                "(primarytype:album OR primarytype:ep OR primarytype:single)",
            limit, offset, fieldedQuery = true,
        )
    }

    /**
     * MusicBrainz's tag field accepts multiword genre names. Only ordinary
     * letters/digits, spaces, hyphens and ampersands are legal here; quotes,
     * wildcards, field separators and Lucene operators cannot be injected.
     * This is query escaping, NOT an authorization or a taxonomy assertion.
     */
    private fun safeMusicBrainzGenreTag(tag: String): String? {
        val normalized = tag.trim().lowercase(Locale.ROOT)
        if (!Regex("^[a-z][a-z0-9 &-]{1,48}$").matches(normalized) ||
            normalized.contains("  ") || normalized.contains("&&") ||
            normalized.endsWith("-") || normalized.endsWith("&")
        ) return null
        return when (normalized) {
            "rnb" -> "r&b"
            "hip-hop" -> "hip hop"
            else -> normalized
        }
    }

    /** Genre is a known MusicBrainz release-group tag, never arbitrary UI text. */
    suspend fun searchReleaseGroupsForTag(
        tag: String,
        limit: Int = 40,
        /** Upstream raw offset, never calculated from filtered/visible cards. */
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseGroupHit> {
        val musicBrainzTag = safeMusicBrainzGenreTag(tag)
            ?: return MusicBrainzCatalogSearchResult.Failure(
                MusicBrainzCatalogSearchFailure.INVALID_QUERY,
            )
        return searchReleaseGroupsInternal(
            "tag:\"$musicBrainzTag\" AND " +
                "(primarytype:album OR primarytype:ep OR primarytype:single)",
            limit, offset, fieldedQuery = true,
        )
    }

    /**
     * Current genre releases need an independent date-scoped query. A plain
     * tag search is relevance-ranked and can return sixty historical albums
     * without a single genuinely recent record.
     */
    suspend fun searchRecentReleaseGroupsForTag(
        tag: String,
        fromIsoDate: String,
        throughIsoDate: String,
        limit: Int = 40,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseGroupHit> {
        val musicBrainzTag = safeMusicBrainzGenreTag(tag)
        val date = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        if (musicBrainzTag == null ||
            !date.matches(fromIsoDate) || !date.matches(throughIsoDate) ||
            fromIsoDate > throughIsoDate
        ) return MusicBrainzCatalogSearchResult.Failure(
            MusicBrainzCatalogSearchFailure.INVALID_QUERY,
        )
        return searchReleaseGroupsInternal(
            "tag:\"$musicBrainzTag\" AND " +
                "firstreleasedate:[$fromIsoDate TO $throughIsoDate] AND " +
                "(primarytype:album OR primarytype:ep OR primarytype:single)",
            limit, 0, fieldedQuery = true,
        )
    }

    /**
     * Preserve genre album shelves even when the top relevance-ranked and
     * recent pages contain only EPs and singles. The server still establishes
     * both the tag association and the primary Album type.
     */
    suspend fun searchAlbumReleaseGroupsForTag(
        tag: String,
        limit: Int = 24,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseGroupHit> {
        val musicBrainzTag = safeMusicBrainzGenreTag(tag)
            ?: return MusicBrainzCatalogSearchResult.Failure(
                MusicBrainzCatalogSearchFailure.INVALID_QUERY,
            )
        return searchReleaseGroupsInternal(
            "tag:\"$musicBrainzTag\" AND primarytype:album",
            limit, 0, fieldedQuery = true,
        )
    }

    private suspend fun searchReleaseGroupsInternal(
        query: String, limit: Int, offset: Int, fieldedQuery: Boolean = false,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseGroupHit> =
        search("release-group", query, limit, offset, fieldedQuery) { raw ->
            val result = json.decodeFromString<ReleaseGroupSearchEnvelope>(raw)
            result.pageOrNull(result.releaseGroups?.size, MusicBrainzReleaseGroupHit::musicBrainzId) {
                result.releaseGroups?.mapNotNull { group ->
                    val mbid = group.id.validSearchMbidOrNull() ?: return@mapNotNull null
                    val title = group.title.nonEmpty() ?: return@mapNotNull null
                    MusicBrainzReleaseGroupHit(
                        mbid, title, group.primaryType.nonEmpty(),
                        group.firstReleaseDate.nonEmpty(), group.artistCredit.displayArtistCredit(),
                        // Explicit [] is a source correction: it means zero
                        // secondary types. Missing field remains unknown/null.
                        secondaryTypes = group.secondaryTypes
                            ?.map(String::trim)?.filter(String::isNotBlank)
                            ?.joinToString("|"),
                    ).also {
                        it.artistCreditMbids = group.artistCredit.completeArtistMbids()
                        it.searchLinkedReleaseMbids = group.releases.orEmpty()
                            .mapNotNull { release -> release.id.validSearchMbidOrNull() }
                            .toSet()
                        it.editorialGenreTags = group.tags.orEmpty().asSequence()
                            .filter { tag -> (tag.count ?: 0) > 0 }
                            .mapNotNull { tag -> tag.name.nonEmpty()?.lowercase(Locale.ROOT) }
                            .filter { tag -> tag.length in 2..50 &&
                                tag.none(Character::isISOControl) }
                            .distinct().take(64).toList()
                    }
                }
            }
        }

    suspend fun searchReleases(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseHit> =
        searchReleasesInternal(query, limit, offset)
            .rankPublicPage(query, MusicBrainzReleaseHit::title, MusicBrainzReleaseHit::artistDisplay)

    /**
     * Barcode is an exact *candidate index* only. A UPC can identify multiple
     * editions, so every returned MB Release still needs a complete, distinct
     * Release graph and an exact Apple tracklist match before a crosswalk.
     */
    suspend fun searchReleasesByBarcode(
        barcode: String, limit: Int = 12, offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseHit> {
        if (!Regex("^[0-9]{8,14}$").matches(barcode) || limit !in 1..100 ||
            offset < 0
        ) {
            return MusicBrainzCatalogSearchResult.Failure(
                MusicBrainzCatalogSearchFailure.INVALID_QUERY,
            )
        }
        return searchReleasesInternal(
            "barcode:$barcode", limit, offset, fieldedQuery = true,
        )
    }

    /**
     * Release and credited artist are official Lucene search fields. Escaping
     * both as literal quoted phrases prevents untrusted Apple display text
     * from becoming a query operator or borrowing an unrelated artist result.
     * This finds candidates only; exact edition identity still requires graph,
     * UPC association and complete tracklist proof.
     */
    suspend fun searchOfficialReleasesForTitleArtist(
        title: String,
        artist: String,
        limit: Int = 12,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseHit> {
        if (title.length !in 2..100 || artist.length !in 1..100 ||
            title.isBlank() || artist.isBlank() || limit !in 1..12 || offset < 0 ||
            title.any(Char::isISOControl) || artist.any(Char::isISOControl)
        ) return MusicBrainzCatalogSearchResult.Failure(
            MusicBrainzCatalogSearchFailure.INVALID_QUERY,
        )
        fun quote(value: String): String =
            "\"" + value.trim().replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\""
        return searchReleasesInternal(
            "release:${quote(title)} AND artist:${quote(artist)} AND status:official",
            limit, offset, fieldedQuery = true,
        )
    }

    suspend fun searchReleasesForArtistMbid(
        artistMbid: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseHit> {
        val id = artistMbid.validSearchMbidOrNull()
            ?: return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        return searchReleasesInternal("arid:$id", limit, offset, fieldedQuery = true)
    }

    private suspend fun searchReleasesInternal(
        query: String, limit: Int, offset: Int, fieldedQuery: Boolean = false,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseHit> =
        search("release", query, limit, offset, fieldedQuery) { raw ->
            val result = json.decodeFromString<ReleaseSearchEnvelope>(raw)
            result.pageOrNull(result.releases?.size, MusicBrainzReleaseHit::musicBrainzId) {
                result.releases?.mapNotNull { release ->
                    val mbid = release.id.validSearchMbidOrNull() ?: return@mapNotNull null
                    val title = release.title.nonEmpty() ?: return@mapNotNull null
                    MusicBrainzReleaseHit(
                        mbid, title, release.country.nonEmpty(),
                        release.date.nonEmpty(), release.artistCredit.displayArtistCredit(),
                        status = release.status.nonEmpty(),
                        releaseGroupMbid = release.releaseGroup?.id.validSearchMbidOrNull(),
                        releaseGroupPrimaryType = release.releaseGroup?.primaryType.nonEmpty(),
                    ).also { it.artistCreditMbids = release.artistCredit.completeArtistMbids() }
                }
            }
        }

    /** Only fixed internal fields and quoted user/source text enter the Lucene query. */
    private fun publicWorkProbeQuery(
        field: String, query: String, credits: List<String?>, suffix: String = "",
    ): String {
        val term = musicBrainzPublicSearchKey(query)
        val hints = credits.mapNotNull { value ->
            val credit = musicBrainzPublicSearchKey(value)
            if (credit.isBlank()) return@mapNotNull null
            val prefix = listOf("$credit - ", "$credit ").firstOrNull(term::startsWith)
            val suffix = listOf(" - $credit", " $credit").firstOrNull(term::endsWith)
            val work = when {
                prefix != null -> term.removePrefix(prefix)
                suffix != null -> term.removeSuffix(suffix)
                else -> return@mapNotNull null
            }
            if (work.isBlank()) null else work to credit
        }.distinct()
        val hint = hints.singleOrNull()
        fun quote(value: String): String =
            "\"" + value.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return if (hint != null)
            "$field:${quote(hint.first)} AND artist:${quote(hint.second)}$suffix"
        else "$field:${quote(query)}$suffix"
    }

    /**
     * Exact title ties may carry different amounts of positive source
     * evidence. Use the already-returned official audio releases as a
     * bounded tie-breaker, not a popularity assertion. No credit is guessed.
     */
    private fun rankPublicRecordingLanding(
        query: String, items: List<MusicBrainzRecordingHit>,
    ): List<MusicBrainzRecordingHit> = items.sortedWith(compareBy<MusicBrainzRecordingHit> {
        musicBrainzPublicSearchRank(query, it.title, it.artistDisplay)
    }.thenByDescending {
        if (isCuratedMusicBrainzRecording(it) &&
            musicBrainzPublicSearchRank(query, it.title, it.artistDisplay) <= 1)
            it.searchOfficialAudioReleaseMbids.size.coerceAtMost(20)
        else 0
    })

    suspend fun searchRecordings(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
        // Only the interactive first page may spend the extra recovery read.
        recoverWeakLanding: Boolean = false,
    ): MusicBrainzCatalogSearchResult<MusicBrainzRecordingHit> {
        val initial = searchRecordingsInternal(query, limit, offset)
            .rankPublicPage(query, MusicBrainzRecordingHit::title, MusicBrainzRecordingHit::artistDisplay)
        if (!recoverWeakLanding || capability != ProviderCapability.CATALOG_SEARCH ||
            initial !is MusicBrainzCatalogSearchResult.Success || offset != 0 ||
            limit != MUSICBRAINZ_PUBLIC_WORK_LANDING_LIMIT || query.trim().length !in 1..100 ||
            query.any(Char::isISOControl)
        ) return initial
        val ranked = initial.copy(page = initial.page.copy(
            items = rankPublicRecordingLanding(query, initial.page.items),
        ))
        if (ranked.page.items.any {
            isCuratedMusicBrainzRecording(it) &&
                musicBrainzPublicSearchRank(query, it.title, it.artistDisplay) <= 1
        }) return ranked

        // One bounded independent fielded probe for a weak public landing.
        // Source credits may nominate a compound split, but may never supply
        // an artist ID or override the full query's textual relevance.
        val fielded = publicWorkProbeQuery("recording", query,
            initial.page.items.map { it.artistDisplay }, " AND status:official")
        // A single literal work phrase without a source-supported credit
        // boundary can be ambiguous across many artists. On an already
        // weak landing, use the supported maximum of the SAME recovery
        // request. Compound credit probes retain the small budget.
        val probeLimit = if (fielded.contains(" AND artist:")) 12 else 100
        val probe = searchRecordingsInternal(fielded, probeLimit, 0, fieldedQuery = true)
            as? MusicBrainzCatalogSearchResult.Success ?: return ranked
        val original = initial.page.items.associateBy { it.musicBrainzId }
        val recovered = probe.page.items.filter {
            isCuratedMusicBrainzRecording(it) &&
                musicBrainzPublicSearchRank(query, it.title, it.artistDisplay) <= 1 &&
                (original[it.musicBrainzId]?.let { old -> old == it } ?: true)
        }
        if (recovered.isEmpty()) return ranked
        // Keep the original cursor: the independent query did not consume
        // any offsets of the user's original upstream result.
        val merged = (initial.page.items + recovered).distinctBy { it.musicBrainzId }
        return initial.copy(page = initial.page.copy(
            items = rankPublicRecordingLanding(query, merged),
        ))
    }

    /**
     * A recording's exact MusicBrainz tag is genre membership evidence, not
     * evidence of popularity. The ListenBrainz chart capability is separate.
     */
    suspend fun searchRecordingsForTag(
        tag: String,
        limit: Int = 40,
    ): MusicBrainzCatalogSearchResult<MusicBrainzRecordingHit> {
        val safe = safeMusicBrainzGenreTag(tag)
            ?: return MusicBrainzCatalogSearchResult.Failure(
                MusicBrainzCatalogSearchFailure.INVALID_QUERY,
            )
        return searchRecordingsInternal(
            "tag:\"$safe\" AND status:official", limit, 0, fieldedQuery = true,
        )
    }

    suspend fun searchRecordingsForArtistMbid(
        artistMbid: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzRecordingHit> {
        val id = artistMbid.validSearchMbidOrNull()
            ?: return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        return searchRecordingsInternal("arid:$id AND status:official", limit, offset, fieldedQuery = true)
    }

    private suspend fun searchRecordingsInternal(
        query: String, limit: Int, offset: Int, fieldedQuery: Boolean = false,
    ): MusicBrainzCatalogSearchResult<MusicBrainzRecordingHit> =
        search("recording", query, limit, offset, fieldedQuery) { raw ->
            val result = json.decodeFromString<RecordingSearchEnvelope>(raw)
            result.pageOrNull(result.recordings?.size, MusicBrainzRecordingHit::musicBrainzId) {
                result.recordings?.mapNotNull { recording ->
                    val mbid = recording.id.validSearchMbidOrNull() ?: return@mapNotNull null
                    val title = recording.title.nonEmpty() ?: return@mapNotNull null
                    val official = recording.releases.orEmpty().filter { release ->
                        release.status.equals("Official", ignoreCase = true) &&
                            release.id.validSearchMbidOrNull() != null
                    }
                    // An official release alone does not prove that its cover
                    // depicts an audio sleeve: a MusicBrainz "Other" / video
                    // release can have a video still marked Front by CAA.
                    val audioReleases = official.filter { release ->
                        release.releaseGroup?.primaryType?.trim()?.lowercase(Locale.ROOT) in
                            setOf("album", "ep", "single")
                    }
                    val selectedAudioRelease = if (recording.video == true) null else
                        audioReleases.sortedWith(compareBy<SearchRecordingRelease> {
                            it.date.orEmpty().ifBlank { "9999-12-31" }
                        }.thenBy { it.id.orEmpty() }).firstOrNull()
                    MusicBrainzRecordingHit(
                        mbid, title, recording.artistCredit.displayArtistCredit(),
                        recording.length?.takeIf { it >= 0 },
                        coverReleaseMbid = selectedAudioRelease?.id,
                        coverGroupMbid = selectedAudioRelease?.releaseGroup?.id
                            .validSearchMbidOrNull(),
                        // A clean recording title on an Official video/Other
                        // edition is still not positive evidence of official
                        // audio. Match the *same* supported Album/EP/Single
                        // evidence required for trustworthy cover association.
                        // Recording.video is tri-state in the search index.
                        // An omitted boolean or omitted release list is NOT
                        // a negative fact and must not clear Room proof from
                        // a previous complete lookup for this exact MBID.
                        hasOfficialAudioRelease = when {
                            recording.video == true -> false
                            recording.releases == null -> null
                            else -> audioReleases.isNotEmpty()
                        },
                        isVideo = recording.video,
                    ).also { hit ->
                        hit.artistCreditMbids = recording.artistCredit.completeArtistMbids()
                        if (recording.video != true) {
                            hit.searchOfficialAudioReleaseMbids = audioReleases
                                .mapNotNull { it.id.validSearchMbidOrNull() }.toSet()
                        }
                    }
                }
            }
        }

    private suspend fun <T> search(
        path: String,
        rawQuery: String,
        limit: Int,
        offset: Int,
        fieldedQuery: Boolean = false,
        decode: (String) -> MusicBrainzCatalogPage<T>?,
    ): MusicBrainzCatalogSearchResult<T> {
        val query = rawQuery.trim().replace(Regex("\\s+"), " ")
        if (query.isBlank() || query.length > MAX_QUERY_LENGTH ||
            query.any { it.isISOControl() }
        ) return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        if (limit !in 1..100 || offset < 0) {
            return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_PAGE)
        }

        val raw = try {
            client.get(path, mapOf(
                "fmt" to "json",
                "query" to query,
                // User input is ordinary text, never unescaped Lucene syntax.
                "dismax" to if (fieldedQuery) "false" else "true",
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            ), capability = capability)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.UNAVAILABLE)
        }
        val parsed = try {
            decode(raw)
        } catch (_: Exception) {
            null
        }
        // A response for a different upstream page cannot advance the
        // requested cursor. Reject oversized pages as well; retain raw row
        // consumption for valid pages, including filtered or duplicate IDs.
        return if (parsed == null || parsed.offset != offset || parsed.consumedCount > limit) {
            MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE)
        } else {
            MusicBrainzCatalogSearchResult.Success(parsed)
        }
    }

    private fun String?.nonEmpty(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun String?.validSearchMbidOrNull(): String? =
        nonEmpty()?.takeIf(MBID_PATTERN::matches)?.lowercase(java.util.Locale.ROOT)

    /** Artist identity is valid only if EVERY credited artist has a real MBID.
     * A partial response or an alias alone cannot establish equivalence. */
    private fun List<SearchArtistCredit>?.completeArtistMbids(): List<String>? {
        val credits = this?.takeIf { it.isNotEmpty() } ?: return null
        val ids = mutableListOf<String>()
        for (credit in credits) {
            ids += credit.artist?.id.validSearchMbidOrNull() ?: return null
        }
        return ids
    }

    private fun List<SearchArtistCredit>?.displayArtistCredit(): String? =
        this?.joinToString("") { credit ->
            (credit.name.nonEmpty() ?: credit.artist?.name.nonEmpty().orEmpty()) + credit.joinPhrase.orEmpty()
        }?.trim()?.takeIf(String::isNotEmpty)

    private fun <T> SearchEnvelope.pageOrNull(
        rawCount: Int?,
        identity: (T) -> String,
        items: () -> List<T>?, 
    ): MusicBrainzCatalogPage<T>? {
        val total = count?.takeIf { it >= 0 } ?: return null
        val start = offset?.takeIf { it >= 0 } ?: return null
        val consumed = rawCount?.takeIf { it >= 0 } ?: return null
        val rows = items() ?: return null
        return MusicBrainzCatalogPage(rows.distinctBy(identity), total, start, consumed)
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 200
        val MBID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE,
        )
    }
}

/** Serializable concrete envelopes own their count/offset properties. */
private interface SearchEnvelope {
    val count: Int?
    val offset: Int?
}

@Serializable
private data class SearchArtistReference(
    val name: String? = null,
    val id: String? = null,
)

@Serializable
private data class SearchArtistCredit(
    val name: String? = null,
    @SerialName("joinphrase") val joinPhrase: String? = null,
    val artist: SearchArtistReference? = null,
)

@Serializable
private data class SearchArtistEntry(
    val id: String? = null,
    val name: String? = null,
    val disambiguation: String? = null,
    val country: String? = null,
)

@Serializable
private data class SearchReleaseGroupTag(
    val name: String? = null,
    val count: Int? = null,
)

@Serializable
private data class SearchReleaseGroupEntry(
    val id: String? = null,
    val title: String? = null,
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String>? = null,
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("artist-credit") val artistCredit: List<SearchArtistCredit>? = null,
    val tags: List<SearchReleaseGroupTag>? = null,
    val releases: List<SearchReleaseGroupReleaseReference>? = null,
)

@Serializable
private data class SearchReleaseGroupReleaseReference(val id: String? = null)

@Serializable
private data class SearchReleaseGroupReference(
    val id: String? = null,
    @SerialName("primary-type") val primaryType: String? = null,
)

@Serializable
private data class SearchReleaseEntry(
    val id: String? = null,
    val title: String? = null,
    val status: String? = null,
    val date: String? = null,
    val country: String? = null,
    @SerialName("artist-credit") val artistCredit: List<SearchArtistCredit>? = null,
    @SerialName("release-group") val releaseGroup: SearchReleaseGroupReference? = null,
)

@Serializable
private data class SearchRecordingRelease(
    val id: String? = null,
    val date: String? = null,
    val status: String? = null,
    @SerialName("release-group") val releaseGroup: SearchReleaseGroupReference? = null,
)

@Serializable
private data class SearchRecordingEntry(
    val id: String? = null,
    val title: String? = null,
    val length: Long? = null,
    val video: Boolean? = null,
    @SerialName("artist-credit") val artistCredit: List<SearchArtistCredit>? = null,
    val releases: List<SearchRecordingRelease>? = null,
)

@Serializable
private class ArtistSearchEnvelope(
    override val count: Int? = null,
    override val offset: Int? = null,
    val artists: List<SearchArtistEntry>? = null,
) : SearchEnvelope

@Serializable
private class ReleaseGroupSearchEnvelope(
    override val count: Int? = null,
    override val offset: Int? = null,
    @SerialName("release-groups") val releaseGroups: List<SearchReleaseGroupEntry>? = null,
) : SearchEnvelope

@Serializable
private class ReleaseSearchEnvelope(
    override val count: Int? = null,
    override val offset: Int? = null,
    val releases: List<SearchReleaseEntry>? = null,
) : SearchEnvelope

@Serializable
private class RecordingSearchEnvelope(
    override val count: Int? = null,
    override val offset: Int? = null,
    val recordings: List<SearchRecordingEntry>? = null,
) : SearchEnvelope
