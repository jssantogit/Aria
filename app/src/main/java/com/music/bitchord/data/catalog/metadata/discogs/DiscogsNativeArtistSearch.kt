package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderRequestDeniedException
import com.music.bitchord.data.providers.RateLimitedProviderTransport
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
