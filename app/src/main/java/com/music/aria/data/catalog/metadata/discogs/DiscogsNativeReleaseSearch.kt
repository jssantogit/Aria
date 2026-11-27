package com.music.aria.data.catalog.metadata.discogs

import com.music.aria.BuildConfig
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderRequestDeniedException
import com.music.aria.data.providers.RateLimitedProviderTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Release identity comes directly from a typed Discogs database result; the
 * search title is a hint. Full artist, format and track metadata is loaded
 * from GET /releases/{id} on navigation, never inferred from query text.
 */
internal data class DiscogsNativeReleaseSearchHit(
    val releaseId: Long,
    val title: String,
    /** Source index hint for grouping editions in Search only. */
    val masterId: Long? = null,
) {
    val browseId: String get() = "discogs:release:$releaseId"
}

/** Balance two source candidate streams without exceeding the existing detail
 * budget. Source IDs remain candidates until independently hydrated. */
internal fun discogsMultidiscSearchCandidates(
    cd: List<DiscogsNativeReleaseSearchHit>,
    unfiltered: List<DiscogsNativeReleaseSearchHit>,
): List<DiscogsNativeReleaseSearchHit> =
    (cd.take(4) + unfiltered.take(4) + cd + unfiltered)
        .distinctBy { it.releaseId }.take(8)

internal data class DiscogsNativeReleaseSearchPage(
    val releases: List<DiscogsNativeReleaseSearchHit>,
    val nextPage: Int?,
    val coverageUncertain: Boolean,
)

internal sealed interface DiscogsNativeReleaseSearchResult {
    data class Success(val page: DiscogsNativeReleaseSearchPage) :
        DiscogsNativeReleaseSearchResult
    data object Disabled : DiscogsNativeReleaseSearchResult
    data object AuthenticationRequired : DiscogsNativeReleaseSearchResult
    data object InvalidQuery : DiscogsNativeReleaseSearchResult
    data object Unavailable : DiscogsNativeReleaseSearchResult
    data class Throttled(val retryAfterSeconds: Long?) : DiscogsNativeReleaseSearchResult
}

internal object DiscogsNativeReleaseSearchParsing {
    private val json = Json { ignoreUnknownKeys = true }
    fun page(raw: String, requestedPage: Int): DiscogsNativeReleaseSearchPage? {
        if (raw.length > 131_072 || requestedPage !in 1..3) return null
        val root = try { json.parseToJsonElement(raw) as? JsonObject }
            catch (_: Exception) { null } ?: return null
        val paging = root["pagination"] as? JsonObject ?: return null
        val page = (paging["page"] as? JsonPrimitive)?.intOrNull
        val pages = (paging["pages"] as? JsonPrimitive)?.intOrNull
        val rows = root["results"] as? JsonArray ?: return null
        // A zero-page first response is a valid empty search, never an API error.
        // A populated zero-page response or a zero-page continuation is invalid.
        if (page == 1 && requestedPage == 1 && pages == 0 && rows.isEmpty())
            return DiscogsNativeReleaseSearchPage(emptyList(), null, false)
        if (page != requestedPage || pages == null ||
            pages !in requestedPage..10_000 || rows.size > 20) return null
        val releases = rows.mapNotNull { rawRow ->
            val item = rawRow as? JsonObject ?: return@mapNotNull null
            if ((item["type"] as? JsonPrimitive)?.contentOrNull != "release")
                return@mapNotNull null
            val id = (item["id"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { it in 1..999_999_999_999L } ?: return@mapNotNull null
            val title = (item["title"] as? JsonPrimitive)?.contentOrNull
                ?.trim()?.takeIf {
                    it.length in 1..240 && it.none(Character::isISOControl)
                } ?: return@mapNotNull null
            val masterId = (item["master_id"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { it in 1..999_999_999_999L }
            DiscogsNativeReleaseSearchHit(id, title, masterId)
        }.groupBy { it.releaseId }.values.mapNotNull { sameId ->
            val first = sameId.first()
            if (sameId.any { it.title != first.title }) return@mapNotNull null
            // Conflicting index hints cannot establish a Master grouping.
            first.copy(masterId = sameId.mapNotNull { it.masterId }.distinct().singleOrNull())
        }
        return DiscogsNativeReleaseSearchPage(
            releases = releases,
            nextPage = (requestedPage + 1).takeIf {
                requestedPage < pages && requestedPage < 3
            },
            coverageUncertain = pages > 3,
        )
    }
}

internal class DiscogsNativeReleaseSearchClient(
    private val transport: DiscogsNativeArtistSearchTransport =
        KtorDiscogsNativeArtistSearchTransport(entity = "release"),
    private val tokenProvider: () -> String? = { null },
    private val enabled: Boolean = false,
    private val limiter: DiscogsRateLimiter = DiscogsRequestBudget.limiter,
    private val admission: RateLimitedProviderTransport = RateLimitedProviderTransport(),
    private val userAgent: String =
        "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)",
) {
    suspend fun search(term: String, page: Int = 1): DiscogsNativeReleaseSearchResult {
        val query = term.trim()
        if (query.length !in 1..120 || query.any(Character::isISOControl) ||
            page !in 1..3) return DiscogsNativeReleaseSearchResult.InvalidQuery
        if (!enabled || !admission.permitted(
            ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.DISCOGS,
        )) return DiscogsNativeReleaseSearchResult.Disabled
        val token = tokenProvider()?.trim()?.takeIf(::isValidDiscogsSearchToken)
            ?: return DiscogsNativeReleaseSearchResult.AuthenticationRequired
        val response = try {
            admission.execute(
                capability = ProviderCapability.CATALOG_SEARCH,
                provider = BuiltInProviderKeys.DISCOGS,
                scheduleLimited = { block ->
                        limiter.runHttpLimited(authenticated = true) { block() }
                    },
                networkCall = { transport.search(query, page, token, userAgent) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ProviderRequestDeniedException) {
            return DiscogsNativeReleaseSearchResult.Disabled
        } catch (_: Exception) {
            return DiscogsNativeReleaseSearchResult.Unavailable
        }
        return when (response.status) {
            200 -> DiscogsNativeReleaseSearchParsing.page(response.body, page)?.let {
                DiscogsNativeReleaseSearchResult.Success(it)
            } ?: DiscogsNativeReleaseSearchResult.Unavailable
            401, 403 -> DiscogsNativeReleaseSearchResult.AuthenticationRequired
            429 -> DiscogsNativeReleaseSearchResult.Throttled(response.retryAfterSeconds)
            else -> DiscogsNativeReleaseSearchResult.Unavailable
        }
    }
}
