package com.music.bitchord.data.catalog.metadata

import java.text.Normalizer
import java.util.Locale

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
    )
    private fun normalized(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")

    fun supports(tag: String): Boolean = tag in known

    fun matches(tag: String, applePrimaryGenre: String?): Boolean {
        val allowed = known[tag] ?: return false
        if (applePrimaryGenre.isNullOrBlank()) return false
        return normalized(applePrimaryGenre) in allowed
    }
}

internal fun interface AppleGenreAlbumSearchSource {
    suspend fun search(genre: String, storefront: String): AppleEditorialLoad
}

internal class AppleExploreGenreDiscovery(
    private val catalog: AppleHomeEditorialDiscovery = AppleHomeEditorialDiscovery(),
    // Optional so injected test discovery never silently sends a real request.
    private val directApple: AppleGenreAlbumSearchSource? = null,
) {
    private val queryNames = mapOf(
        "pop" to "Pop", "rock" to "Rock", "hip-hop" to "Hip-Hop/Rap",
        "electronic" to "Electronic", "k-pop" to "K-Pop",
        "rnb" to "R&B/Soul", "jazz" to "Jazz", "metal" to "Metal",
        "classical" to "Classical", "folk" to "Folk",
        "ambient" to "Ambient", "punk" to "Punk",
    )

    suspend fun discover(
        tag: String,
        seeds: List<AppleHomeEditorialSeed>,
        storefront: String = "br",
        onProgress: suspend (List<ItunesEditorialRelease>) -> Unit = {},
    ): AppleEditorialLoad {
        val query = queryNames[tag]
        if (query == null || !AppleExploreGenrePolicy.supports(tag)) {
            return AppleEditorialLoad.Failure(ItunesCatalogFailure.INVALID_QUERY)
        }
        fun acceptable(release: ItunesEditorialRelease): Boolean =
            release.storefront == storefront && release.appleCollectionId > 0 &&
                release.appleScopedId ==
                    "itunes:$storefront:album:${release.appleCollectionId}" &&
                AppleExploreGenrePolicy.matches(tag, release.genre)

        fun merge(
            first: List<ItunesEditorialRelease>,
            second: List<ItunesEditorialRelease>,
        ): List<ItunesEditorialRelease> = (first + second).filter(::acceptable)
            .groupBy { it.appleScopedId }.values.mapNotNull { observations ->
                observations.firstOrNull()?.takeIf { candidate ->
                    observations.all { it == candidate }
                }
            }

        // The public Apple API documents genreIndex. Its own results can fill
        // a genre directly in one request instead of 8 serial title probes.
        // A term yielding no Apple products must not mask the prior safe path.
        val broad = try {
            directApple?.search(query, storefront)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppleEditorialLoad.Failure(ItunesCatalogFailure.UNAVAILABLE)
        }
        val direct = (broad as? AppleEditorialLoad.Success)
            ?.releases.orEmpty().filter(::acceptable)
        if (direct.isNotEmpty()) onProgress(direct)
        if (direct.size >= 8) return AppleEditorialLoad.Success(
            direct, (broad as AppleEditorialLoad.Success).state,
        )

        return when (val source = catalog.discover(
            seeds, storefront, maxProbes = 8,
            onProgress = { rows -> onProgress(merge(direct, rows)) },
        )) {
            is AppleEditorialLoad.Failure ->
                if (direct.isNotEmpty()) AppleEditorialLoad.Success(
                    direct, AppleEditorialReadState.POSSIBLY_TRUNCATED,
                ) else source
            is AppleEditorialLoad.Success -> {
                val matched = merge(direct, source.releases)
                AppleEditorialLoad.Success(
                    matched,
                    when {
                        source.state == AppleEditorialReadState.POSSIBLY_TRUNCATED ||
                            (broad is AppleEditorialLoad.Success &&
                                broad.state == AppleEditorialReadState.POSSIBLY_TRUNCATED) ->
                            AppleEditorialReadState.POSSIBLY_TRUNCATED
                        matched.isEmpty() -> AppleEditorialReadState.NOT_IN_STOREFRONT
                        else -> AppleEditorialReadState.COMPLETE
                    },
                    probeReport = source.probeReport,
                )
            }
        }
    }
}
