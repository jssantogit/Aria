package com.music.bitchord.data.catalog.metadata

import java.text.Normalizer
import java.util.Locale
import com.music.bitchord.data.providers.ProviderCapability
import kotlinx.coroutines.CancellationException

/**
 * MusicBrainz tag browse yields search hints, but cannot assign a category to
 * an Apple product by name. The Apple album's primaryGenreName is the only
 * currently available storefront genre signal used by Explore.
 *
 * Conservative exact names avoid treating mainstream Pop as K-pop or broad
 * Alternative/Rock as evidence of a Metal/Punk edition.
 */
internal object AppleExploreGenrePolicy {
    private val known = mapOf(
        "pop" to setOf("pop"),
        "rock" to setOf("rock"),
        "hip-hop" to setOf("hip-hop/rap", "hip hop/rap", "hip-hop", "hip hop"),
        "electronic" to setOf("electronic", "electronica", "eletronica"),
        "k-pop" to setOf("k-pop", "kpop"),
        "rnb" to setOf("r&b/soul", "r&b", "rnb"),
        "jazz" to setOf("jazz"),
        "metal" to setOf("metal"),
        "classical" to setOf("classical", "classica"),
        "folk" to setOf("folk"),
        "ambient" to setOf("ambient"),
        "punk" to setOf("punk"),
        // Exact storefront genre names from Apple's genre taxonomy.
        // A search term alone, a parent genre or title text is not proof.
        "hard rock" to setOf("hard rock"),
        "house" to setOf("house"),
        "techno" to setOf("techno"),
        "trance" to setOf("trance"),
        "smooth jazz" to setOf("smooth jazz"),
        "bluegrass" to setOf("bluegrass"),
    )
    private fun normalized(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")

    fun supports(tag: String): Boolean = tag in known

    /** Search term is only a discovery hint; the Apple genre claim is verified below. */
    fun nativeSearchTerm(tag: String): String? = if (!supports(tag)) null else when (tag) {
        "rnb" -> "R&B"
        "hip-hop" -> "Hip-Hop"
        "k-pop" -> "K-Pop"
        else -> tag
    }

    fun matches(tag: String, applePrimaryGenre: String?): Boolean {
        val allowed = known[tag] ?: return false
        if (applePrimaryGenre.isNullOrBlank()) return false
        return normalized(applePrimaryGenre) in allowed
    }
}

internal fun interface AppleNativeGenreSearchSource {
    suspend fun search(term: String, storefront: String): AppleEditorialLoad

    // Optional exact artist lookup; never query another provider.
    suspend fun artistAlbums(artistId: Long, storefront: String): AppleEditorialLoad? = null
}

internal class ItunesNativeGenreSearchSource(
    private val repository: AppleEditorialCatalogRepository = AppleEditorialCatalogRepository(),
) : AppleNativeGenreSearchSource {
    override suspend fun search(term: String, storefront: String): AppleEditorialLoad =
        repository.searchAlbums(
            query = term, storefront = storefront, limit = 200,
            capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
            attribute = ItunesCatalogSearchAttribute.GENRE_INDEX,
        )

    override suspend fun artistAlbums(artistId: Long, storefront: String): AppleEditorialLoad =
        repository.artistDiscography(artistId, storefront, limit = 200)
}

internal class AppleExploreGenreDiscovery(
    private val catalog: AppleHomeEditorialDiscovery = AppleHomeEditorialDiscovery(),
    private val nativeSource: AppleNativeGenreSearchSource = ItunesNativeGenreSearchSource(),
) {
    /** Independent Apple search. No MusicBrainz call, seed, MBID or title crosswalk. */
    suspend fun discoverNative(
        tag: String,
        storefront: String = "br",
        expandSparseCapacity: Boolean = false,
        onProgress: suspend (List<ItunesEditorialRelease>) -> Unit = {},
    ): AppleEditorialLoad {
        val term = AppleExploreGenrePolicy.nativeSearchTerm(tag)
            ?: return AppleEditorialLoad.Failure(ItunesCatalogFailure.INVALID_QUERY)
        if (!Regex("^[a-z]{2}$").matches(storefront)) {
            return AppleEditorialLoad.Failure(ItunesCatalogFailure.INVALID_QUERY)
        }
        return when (val found = nativeSource.search(term, storefront)) {
            is AppleEditorialLoad.Failure -> found
            is AppleEditorialLoad.Success -> {
                val matching = found.releases.asSequence().filter { row ->
                    row.storefront == storefront &&
                        row.appleCollectionId > 0L &&
                        row.artistId?.let { it > 0L } == true &&
                        row.appleScopedId ==
                            "itunes:$storefront:album:${row.appleCollectionId}" &&
                        row.title.isNotBlank() && row.artistName.isNotBlank() &&
                        AppleExploreGenrePolicy.matches(tag, row.genre)
                }.map { row ->
                    row.copy(
                        musicBrainzReleaseGroupId = null, musicBrainzReleaseId = null,
                        musicBrainzArtistId = null, musicBrainzRecordingIds = emptyMap(),
                    )
                }.groupBy { it.appleScopedId }.values.mapNotNull { rows ->
                    rows.firstOrNull()?.takeIf { first -> rows.all { it == first } }
                }
                // iTunes Search has no offset cursor. Keep the complete
                // verified 200-response pool instead of discarding every
                // valid album beyond position 24 before the native pager
                // has an opportunity to reveal it. The UI stays bounded.
                onProgress(matching)
                // The capped genre index is not an exhaustive catalog. Only on
                // a dedicated capacity genre screen, use up to three exact Apple
                // artist IDs already evidenced in that genre. Home stays at one
                // request per genre. Every additional album must independently
                // satisfy the same storefront, ID and primaryGenreName checks.
                if (expandSparseCapacity && tag in setOf("rock", "electronic", "jazz") &&
                    found.state == AppleEditorialReadState.POSSIBLY_TRUNCATED &&
                    matching.isNotEmpty() && matching.size < 30
                ) return expandNativePool(tag, storefront, matching, onProgress)
                AppleEditorialLoad.Success(
                    matching,
                    when {
                        found.state == AppleEditorialReadState.POSSIBLY_TRUNCATED ->
                            AppleEditorialReadState.POSSIBLY_TRUNCATED
                        matching.isEmpty() -> AppleEditorialReadState.NOT_IN_STOREFRONT
                        else -> found.state
                    },
                    found.probeReport,
                )
            }
        }
    }

    private suspend fun expandNativePool(
        tag: String,
        storefront: String,
        initial: List<ItunesEditorialRelease>,
        onProgress: suspend (List<ItunesEditorialRelease>) -> Unit,
    ): AppleEditorialLoad.Success {
        val anchors = initial.mapNotNull { it.artistId }.distinct()
        val pool = initial.associateByTo(linkedMapOf()) { it.appleScopedId }
        val disputed = mutableSetOf<String>()
        var probes = 1
        var empty = 0
        var failures = 0
        var incomplete = 1
        for (artistId in anchors.take(3)) {
            if (pool.size >= 30) break
            val response = try {
                nativeSource.artistAlbums(artistId, storefront)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AppleEditorialLoad.Failure(ItunesCatalogFailure.UNAVAILABLE)
            } ?: break
            probes++
            if (response !is AppleEditorialLoad.Success) {
                failures++
                break
            }
            if (response.state == AppleEditorialReadState.POSSIBLY_TRUNCATED) incomplete++
            val before = pool.keys.toList()
            val rows = response.releases.filter {
                it.artistId == artistId && it.storefront == storefront &&
                    it.appleCollectionId > 0L &&
                    it.appleScopedId == "itunes:$storefront:album:${it.appleCollectionId}" &&
                    it.title.isNotBlank() && it.artistName.isNotBlank() &&
                    AppleExploreGenrePolicy.matches(tag, it.genre)
            }.map {
                it.copy(
                    musicBrainzReleaseGroupId = null, musicBrainzReleaseId = null,
                    musicBrainzArtistId = null, musicBrainzRecordingIds = emptyMap(),
                )
            }.groupBy { it.appleScopedId }
            var added = 0
            for ((id, observations) in rows) {
                if (id in disputed) continue
                val row = observations.first()
                val previous = pool[id]
                if (observations.any { it != row } || previous != null &&
                    (previous.artistId != row.artistId || previous.title != row.title ||
                        previous.upc != null && row.upc != null && previous.upc != row.upc)
                ) {
                    pool.remove(id)
                    disputed += id
                    continue
                }
                if (previous == null && added < 12 && pool.size < 30) {
                    pool[id] = row
                    added++
                }
            }
            if (added == 0) empty++
            if (before != pool.keys.toList()) onProgress(pool.values.toList())
        }
        return AppleEditorialLoad.Success(
            pool.values.toList(), AppleEditorialReadState.POSSIBLY_TRUNCATED,
            AppleEditorialProbeReport(
                eligibleSeeds = 1 + anchors.size,
                probesMade = probes,
                matchingProducts = pool.size,
                withoutMatchingProducts = empty,
                failedRequests = failures,
                incompleteResponses = incomplete,
                budgetLimited = anchors.size > probes - 1,
            ),
        )
    }

    suspend fun discover(
        tag: String,
        seeds: List<AppleHomeEditorialSeed>,
        storefront: String = "br",
        onProgress: suspend (List<ItunesEditorialRelease>) -> Unit = {},
    ): AppleEditorialLoad {
        if (!AppleExploreGenrePolicy.supports(tag)) {
            return AppleEditorialLoad.Failure(ItunesCatalogFailure.INVALID_QUERY)
        }
        return when (val source = catalog.discover(
            seeds, storefront, maxProbes = 8,
            onProgress = { rows ->
                onProgress(rows.filter {
                    AppleExploreGenrePolicy.matches(tag, it.genre)
                })
            },
        )) {
            is AppleEditorialLoad.Failure -> source
            is AppleEditorialLoad.Success -> {
                val matched = source.releases.filter {
                    AppleExploreGenrePolicy.matches(tag, it.genre)
                }
                AppleEditorialLoad.Success(
                    matched,
                    when {
                        source.state == AppleEditorialReadState.POSSIBLY_TRUNCATED ->
                            AppleEditorialReadState.POSSIBLY_TRUNCATED
                        matched.isEmpty() -> AppleEditorialReadState.NOT_IN_STOREFRONT
                        else -> AppleEditorialReadState.COMPLETE
                    },
                    // Genre filtering can reject a perfectly valid Apple
                    // album. Report actual API yield, not only visible tiles.
                    probeReport = source.probeReport,
                )
            }
        }
    }
}
