package com.music.bitchord.data.catalog.metadata

import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** A public Apple catalogue query, not a search of MusicBrainz titles. */
internal fun interface AppleNativeHomeGenreSource {
    suspend fun discover(tag: String, storefront: String): AppleEditorialLoad
}

/**
 * Apple-only Home discovery using the documented iTunes genreIndex search.
 *
 * This is an intentionally bounded SAMPLE of storefront albums, NOT a claim
 * of all new releases, popularity order or personalized recommendations.
 * Only the returned Apple releaseDate can qualify a Home card as recent.
 * Identity, genre and storefront must ALL come from Apple itself.
 */
internal class AppleNativeHomeDiscovery(
    private val source: AppleNativeHomeGenreSource = run {
        val genre = AppleExploreGenreDiscovery()
        AppleNativeHomeGenreSource { tag, storefront ->
            genre.discoverNative(tag, storefront)
        }
    },
) {
    companion object {
        private val GENRES = listOf(
            "pop", "hip-hop", "rock", "electronic",
            "k-pop", "rnb", "jazz", "classical",
        )

        /** Rotate a bounded genre sample by UTC date without any network seed. */
        fun sampleTags(today: LocalDate, maxGenres: Int = 3): List<String> {
            require(maxGenres in 1..3)
            val first = Math.floorMod(today.toEpochDay(), GENRES.size.toLong()).toInt()
            return (0 until maxGenres).map { index ->
                GENRES[(first + index * 3) % GENRES.size]
            }
        }
    }

    suspend fun discover(
        storefront: String,
        todayIsoDate: String,
        maxGenres: Int = 3,
        onProgress: suspend (List<ItunesEditorialRelease>) -> Unit = {},
        /** Separately verified Apple products NOT clipped to Home's 36 cards. */
        onVerifiedPool: suspend (List<ItunesEditorialRelease>) -> Unit = {},
    ): AppleEditorialLoad {
        val today = runCatching { LocalDate.parse(todayIsoDate) }.getOrNull()
            ?: return AppleEditorialLoad.Failure(ItunesCatalogFailure.INVALID_QUERY)
        if (!Regex("^[a-z]{2}$").matches(storefront) || maxGenres !in 1..3) {
            return AppleEditorialLoad.Failure(ItunesCatalogFailure.INVALID_QUERY)
        }
        val tags = sampleTags(today, maxGenres)
        val verified = linkedMapOf<String, ItunesEditorialRelease>()
        val disputed = mutableSetOf<String>()
        // Make all three source genres eligible before early probes fill the
        // 36-card Home sample. Preserve source-local append order: do not
        // reshuffle existing cards after every incremental network response.
        val sourceIdsByGenre = tags.associateWith { linkedSetOf<String>() }
        val perGenreShare = 36 / tags.size
        fun balancedSample(): List<ItunesEditorialRelease> {
            val ids = linkedSetOf<String>()
            for (tag in tags) {
                sourceIdsByGenre.getValue(tag).asSequence()
                    .filter { it in verified }
                    .take(perGenreShare)
                    .forEach { ids.add(it) }
            }
            return ids.mapNotNull(verified::get)
        }
        val requestedHomeSlots = 36
        var successfulProbes = 0
        var failedProbes = 0
        var emptyProbes = 0
        var incomplete = 0
        var lastPublished: List<ItunesEditorialRelease>? = null
        for (tag in tags) {
            val response = try {
                source.discover(tag, storefront)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AppleEditorialLoad.Failure(ItunesCatalogFailure.UNAVAILABLE)
            }
            when (response) {
                is AppleEditorialLoad.Failure -> failedProbes++
                is AppleEditorialLoad.Success -> {
                    successfulProbes++
                    if (response.state == AppleEditorialReadState.POSSIBLY_TRUNCATED ||
                        response.state == AppleEditorialReadState.PARTIAL_TRACKLIST
                    ) incomplete++
                    var accepted = 0
                    response.releases.forEach { row ->
                        if (row.appleCollectionId <= 0L ||
                            row.artistId?.let { it > 0L } != true ||
                            row.storefront != storefront ||
                            row.appleScopedId !=
                                "itunes:$storefront:album:${row.appleCollectionId}" ||
                            row.title.isBlank() || row.artistName.isBlank() ||
                            !AppleExploreGenrePolicy.matches(tag, row.genre)
                        ) return@forEach
                        val date = row.releaseDate?.let {
                            runCatching { LocalDate.parse(it.take(10)) }.getOrNull()
                                ?: return@forEach
                        }
                        if (date != null && date > today) return@forEach
                        accepted++
                        // Never import MBIDs, regardless of any upstream hint.
                        val native = row.copy(
                            musicBrainzReleaseGroupId = null,
                            musicBrainzReleaseId = null,
                            musicBrainzArtistId = null,
                            musicBrainzRecordingIds = emptyMap(),
                        )
                        val id = native.appleScopedId
                        if (id in disputed) return@forEach
                        val previous = verified[id]
                        if (previous == null) {
                            verified[id] = native
                            sourceIdsByGenre.getValue(tag).add(id)
                        } else if (previous != native) {
                            verified.remove(id)
                            disputed += id
                        }
                    }
                    if (accepted == 0) emptyProbes++
                    val snapshot = balancedSample()
                    if (snapshot != lastPublished &&
                        (snapshot.isNotEmpty() || lastPublished != null)
                    ) {
                        lastPublished = snapshot
                        onProgress(snapshot)
                    }
                }
            }
        }
        // Vacant genre reservations return to verified products from other
        // genres after all probes finish. Never synthesize a record to reach
        // the shelf target, and never reintroduce disputed Apple IDs.
        val finalIds = linkedSetOf<String>()
        balancedSample().forEach { finalIds.add(it.appleScopedId) }
        verified.keys.forEach { id ->
            if (finalIds.size < requestedHomeSlots) finalIds.add(id)
        }
        val finalSample = finalIds.mapNotNull(verified::get)
        if (finalSample != lastPublished &&
            (finalSample.isNotEmpty() || lastPublished != null)
        ) onProgress(finalSample)
        // Save actual API-verified identities for native incremental reveal.
        // The original 36-card editorial order and sections stay unchanged.
        // A disputed storefront ID has already been removed from verified.
        onVerifiedPool(verified.values.toList())
        val report = AppleEditorialProbeReport(
            eligibleSeeds = GENRES.size,
            probesMade = tags.size,
            matchingProducts = verified.size,
            withoutMatchingProducts = emptyProbes,
            failedRequests = failedProbes,
            incompleteResponses = incomplete,
            budgetLimited = tags.size < GENRES.size,
        )
        if (successfulProbes == 0) return AppleEditorialLoad.Failure(
            ItunesCatalogFailure.UNAVAILABLE, report,
        )
        return AppleEditorialLoad.Success(
            releases = finalSample,
            state = when {
                incomplete > 0 || failedProbes > 0 || disputed.isNotEmpty() ->
                    AppleEditorialReadState.POSSIBLY_TRUNCATED
                verified.isEmpty() -> AppleEditorialReadState.NOT_IN_STOREFRONT
                else -> AppleEditorialReadState.COMPLETE
            },
            probeReport = report,
        )
    }
}
