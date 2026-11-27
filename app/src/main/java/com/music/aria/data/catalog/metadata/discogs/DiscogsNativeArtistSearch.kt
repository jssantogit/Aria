package com.music.aria.data.catalog.metadata.discogs

import com.music.aria.BuildConfig
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderRequestDeniedException
import com.music.aria.data.providers.RateLimitedProviderTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Discogs artist search results are first-party Discogs identities. They do
 * not acquire an MBID by artist-name similarity. Search requires an explicit
 * user token, already approved for the separate edition metadata capability.
 */
internal data class DiscogsNativeArtistSearchHit(val artistId: Long, val name: String) {
    val browseId: String get() = "discogs:artist:$artistId"
}

internal data class DiscogsNativeArtistSearchPage(
    val artists: List<DiscogsNativeArtistSearchHit>,
    val nextPage: Int?,
    val coverageUncertain: Boolean,
)

internal sealed interface DiscogsNativeArtistSearchResult {
    data class Success(val page: DiscogsNativeArtistSearchPage) : DiscogsNativeArtistSearchResult
    data object Disabled : DiscogsNativeArtistSearchResult
    data object AuthenticationRequired : DiscogsNativeArtistSearchResult
    data object InvalidQuery : DiscogsNativeArtistSearchResult
    data object Unavailable : DiscogsNativeArtistSearchResult
    data class Throttled(val retryAfterSeconds: Long?) : DiscogsNativeArtistSearchResult
}

internal object DiscogsNativeArtistSearchParsing {
    private val json = Json { ignoreUnknownKeys = true }
    fun page(raw: String, requestedPage: Int): DiscogsNativeArtistSearchPage? {
        if (raw.length > 131_072 || requestedPage !in 1..3) return null
        val root = try { json.parseToJsonElement(raw) as? JsonObject }
            catch (_: Exception) { null } ?: return null
        val pagination = root["pagination"] as? JsonObject ?: return null
        val page = (pagination["page"] as? JsonPrimitive)?.intOrNull
        val pages = (pagination["pages"] as? JsonPrimitive)?.intOrNull
        val results = root["results"] as? JsonArray ?: return null
        // A zero-page first response is a valid empty search, never an API error.
        // A populated zero-page response or a zero-page continuation is invalid.
        if (page == 1 && requestedPage == 1 && pages == 0 && results.isEmpty())
            return DiscogsNativeArtistSearchPage(emptyList(), null, false)
        if (page != requestedPage || pages == null || pages !in requestedPage..10_000 ||
            results.size > 20) return null
        val rows = results.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "artist")
                return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { it in 1..999_999_999_999L } ?: return@mapNotNull null
            val name = (obj["title"] as? JsonPrimitive)?.contentOrNull
                ?.trim()?.takeIf {
                    it.length in 1..180 && it.none(Character::isISOControl)
                } ?: return@mapNotNull null
            DiscogsNativeArtistSearchHit(id, name)
        }.groupBy { it.artistId }.values.mapNotNull { duplicates ->
            duplicates.first().takeIf { first ->
                duplicates.all { it.name == first.name }
            }
        }
        return DiscogsNativeArtistSearchPage(
            artists = rows,
            nextPage = (requestedPage + 1).takeIf { requestedPage < pages && requestedPage < 3 },
            coverageUncertain = pages > 3,
        )
    }
}

internal interface DiscogsNativeArtistSearchTransport {
    suspend fun search(term: String, page: Int, token: String, agent: String): DiscogsHttpResponse
}

internal class KtorDiscogsNativeArtistSearchTransport(
    private val entity: String = "artist",
    private val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000L
            connectTimeoutMillis = 7_000L
            socketTimeoutMillis = 12_000L
        }
    },
    /** Optional fixed discovery filter for explicit CD edition selection. */
    private val cdEditionsOnly: Boolean = false,
) : DiscogsNativeArtistSearchTransport {
    override suspend fun search(
        term: String, page: Int, token: String, agent: String,
    ): DiscogsHttpResponse {
        require(entity in setOf("artist", "release") &&
            (!cdEditionsOnly || entity == "release") &&
            page in 1..3 && term.length in 1..120 && isValidDiscogsSearchToken(token))
        val response = client.get("https://api.discogs.com/database/search") {
            header(HttpHeaders.UserAgent, agent)
            header(HttpHeaders.Accept, "application/vnd.discogs.v2.discogs+json")
            header(HttpHeaders.Authorization, "Discogs token=$token")
            parameter("type", entity)
            if (cdEditionsOnly) parameter("format", "CD")
            parameter("q", term)
            parameter("page", page)
            parameter("per_page", 20)
        }
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > 131_072L) {
            response.bodyAsChannel().cancel()
            return DiscogsHttpResponse(413, "")
        }
        val buffer = ByteArray(8_192)
        val stream = response.bodyAsChannel()
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val count = stream.readAvailable(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size().toLong() + count > 131_072L) {
                stream.cancel()
                return DiscogsHttpResponse(413, "")
            }
            output.write(buffer, 0, count)
        }
        val retry = response.headers[HttpHeaders.RetryAfter]
            ?.toLongOrNull()?.takeIf { it in 0..86_400L }
        return DiscogsHttpResponse(
            response.status.value, output.toByteArray().toString(Charsets.UTF_8), retry,
        )
    }
}

internal class DiscogsNativeArtistSearchClient(
    private val transport: DiscogsNativeArtistSearchTransport =
        KtorDiscogsNativeArtistSearchTransport(),
    private val tokenProvider: () -> String? = { null },
    private val enabled: Boolean = false,
    private val limiter: DiscogsRateLimiter = DiscogsRequestBudget.limiter,
    private val admission: RateLimitedProviderTransport = RateLimitedProviderTransport(),
    private val userAgent: String =
        "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)",
) {
    suspend fun search(term: String, page: Int = 1): DiscogsNativeArtistSearchResult {
        val query = term.trim()
        if (query.length !in 1..120 || query.any(Character::isISOControl) ||
            page !in 1..3) return DiscogsNativeArtistSearchResult.InvalidQuery
        if (!enabled || !admission.permitted(
            ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.DISCOGS,
        )) return DiscogsNativeArtistSearchResult.Disabled
        val token = tokenProvider()?.trim()?.takeIf(::isValidDiscogsSearchToken)
            ?: return DiscogsNativeArtistSearchResult.AuthenticationRequired
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
            return DiscogsNativeArtistSearchResult.Disabled
        } catch (_: Exception) {
            return DiscogsNativeArtistSearchResult.Unavailable
        }
        return when (response.status) {
            200 -> DiscogsNativeArtistSearchParsing.page(response.body, page)?.let {
                DiscogsNativeArtistSearchResult.Success(it)
            } ?: DiscogsNativeArtistSearchResult.Unavailable
            401, 403 -> DiscogsNativeArtistSearchResult.AuthenticationRequired
            429 -> DiscogsNativeArtistSearchResult.Throttled(response.retryAfterSeconds)
            else -> DiscogsNativeArtistSearchResult.Unavailable
        }
    }
}


/**
 * Bare names can identify several Discogs artists. Keep one visible name,
 * but prefer an exact source ID corroborated by the source's own ranked
 * Release search and independently hydrated credits. This is relevance
 * evidence, NOT popularity or proof of the user's intended homonym.
 * No test anchor, cross-provider mapping or title-only identity inference.
 */
internal data class DiscogsArtistSearchResolution(
    val rows: List<com.music.aria.ui.screens.DiscogsNativeArtistSearchRow>,
    val ambiguousCandidates: Int = 0,
    val detailReads: Int = 0,
    val corroboratedSelection: Boolean = false,
)

internal suspend fun discogsResolveArtistSearchHomonyms(
    query: String,
    rows: List<com.music.aria.ui.screens.DiscogsNativeArtistSearchRow>,
    stillCurrent: () -> Boolean,
    searchReleases: suspend () -> DiscogsNativeReleaseSearchResult,
    readRelease: suspend (Long) -> DiscogsLookupResult,
): DiscogsArtistSearchResolution {
    val exact = rows.filter {
        (com.music.aria.ui.screens.discogsSearchArtistRank(query, it.name) ?: 99) <= 1
    }.distinctBy { it.artistId }
    if (exact.size < 2) return DiscogsArtistSearchResolution(rows)
    val initial = DiscogsArtistSearchResolution(rows, ambiguousCandidates = exact.size)
    if (!stillCurrent()) return initial
    val index = searchReleases() as? DiscogsNativeReleaseSearchResult.Success ?: return initial
    if (!stillCurrent()) return initial
    val candidates = index.page.releases.filter {
        (com.music.aria.ui.screens.discogsSearchReleaseRank(query, it.title) ?: 99) <= 2
    }.distinctBy { it.releaseId }.take(3)
    val exactIds = exact.map { it.artistId }.toSet()
    val evidence = linkedMapOf<Long, MutableSet<String>>()
    var reads = 0
    for (candidate in candidates) {
        if (!stillCurrent()) return initial.copy(detailReads = reads)
        reads++
        val lookup = readRelease(candidate.releaseId)
        if (!stillCurrent()) return initial.copy(detailReads = reads)
        if (lookup is DiscogsLookupResult.Throttled || lookup == DiscogsLookupResult.Disabled ||
            lookup == DiscogsLookupResult.Unauthorized || lookup == DiscogsLookupResult.Unavailable
        ) break
        val record = (lookup as? DiscogsLookupResult.Found)?.record ?: continue
        val creditId = record.artistIds.singleOrNull() ?: continue
        val creditName = record.artistNames.singleOrNull() ?: continue
        if (record.link.kind != DiscogsKind.RELEASE || record.link.id != candidate.releaseId ||
            record.unofficial || creditId !in exactIds ||
            (com.music.aria.ui.screens.discogsSearchArtistRank(query, creditName) ?: 99) > 1
        ) continue
        val work = record.masterId?.let { "master:$it" } ?: "release:${record.link.id}"
        evidence.getOrPut(creditId) { linkedSetOf() }.add(work)
    }
    // Equal evidence keeps source artist order. Multiple pressings of one
    // master do not vote multiple times for the same ambiguous credit.
    val preferred = exact.maxByOrNull { evidence[it.artistId]?.size ?: 0 }
        ?.takeIf { !evidence[it.artistId].isNullOrEmpty() }
        ?: return initial.copy(detailReads = reads)
    if (!stillCurrent()) return initial.copy(detailReads = reads)
    val firstPosition = rows.indexOfFirst { it.artistId in exactIds }
    val reordered = rows.toMutableList().apply {
        removeAll { it.artistId == preferred.artistId }
        add(firstPosition, preferred)
    }
    return DiscogsArtistSearchResolution(reordered, exact.size, reads, true)
}
