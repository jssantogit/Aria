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
 * Discogs tags are typed, verified source predicates. Genre and Style are NOT
 * interchangeable and neither is a music asset or a MusicBrainz identity.
 */
internal data class DiscogsEditorialQuery(
    val sourceName: String,
    val isStyle: Boolean,
) {
    fun isValid(): Boolean = sourceName.length in 2..80 &&
        sourceName.none(Character::isISOControl) &&
        sourceName.none { it == '\\' || it == '/' }
}

internal data class DiscogsEditorialCandidate(
    val masterId: Long,
    val releaseId: Long,
    val title: String,
    val artist: String,
    val year: Int?,
    val formats: List<String>,
    /** Exact artist IDs from the same verified release, never a name match. */
    val creditedArtists: List<Pair<Long, String>> = emptyList(),
) {
    val browseId: String get() = "discogs:release:$releaseId"
}

internal sealed interface DiscogsEditorialResult {
    data class Verified(val releases: List<DiscogsEditorialCandidate>) : DiscogsEditorialResult
    data object Unauthorized : DiscogsEditorialResult
    data object Disabled : DiscogsEditorialResult
    data object Unavailable : DiscogsEditorialResult
    data object InvalidQuery : DiscogsEditorialResult
    data class Throttled(val retryAfterSeconds: Long?) : DiscogsEditorialResult
}

internal interface DiscogsGenreSearchTransport {
    suspend fun search(
        query: DiscogsEditorialQuery,
        token: String,
        userAgent: String,
    ): DiscogsHttpResponse
}

/** No redirects: a Discogs token is sent only to the fixed API endpoint. */
internal class KtorDiscogsGenreSearchTransport(
    private val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000L
            connectTimeoutMillis = 7_000L
            socketTimeoutMillis = 12_000L
        }
    },
) : DiscogsGenreSearchTransport {
    override suspend fun search(
        query: DiscogsEditorialQuery,
        token: String,
        userAgent: String,
    ): DiscogsHttpResponse {
        require(query.isValid())
        val response = client.get("https://api.discogs.com/database/search") {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/vnd.discogs.v2.discogs+json")
            header(HttpHeaders.Authorization, "Discogs token=$token")
            parameter("type", "master")
            parameter(if (query.isStyle) "style" else "genre", query.sourceName)
            parameter("per_page", 12)
            parameter("page", 1)
        }
        if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) > 131_072L) {
            response.bodyAsChannel().cancel()
            return DiscogsHttpResponse(413, "")
        }
        val bytes = java.io.ByteArrayOutputStream()
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8_192)
        while (true) {
            val n = channel.readAvailable(buffer)
            if (n < 0) break
            if (n == 0) continue
            if (bytes.size() + n > 131_072) {
                channel.cancel()
                return DiscogsHttpResponse(413, "")
            }
            bytes.write(buffer, 0, n)
        }
        return DiscogsHttpResponse(
            response.status.value,
            bytes.toByteArray().toString(Charsets.UTF_8),
            response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
                ?.takeIf { it in 0..86_400L },
        )
    }
}

/** A structured search row is only a candidate until the Master and its
 * main Release independently corroborate its tag and source-owned linkage. */
internal object DiscogsEditorialMasterParsing {
    private val json = Json { ignoreUnknownKeys = true }
    fun candidates(body: String): List<Long>? {
        if (body.length > 131_072) return null
        val root = try { json.parseToJsonElement(body) as? JsonObject }
            catch (_: Exception) { null } ?: return null
        val paging = root["pagination"] as? JsonObject ?: return null
        if ((paging["page"] as? JsonPrimitive)?.intOrNull != 1) return null
        val rows = root["results"] as? JsonArray ?: return null
        if (rows.size > 12) return null
        return rows.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "master") {
                return@mapNotNull null
            }
            (obj["id"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { it in 1..999_999_999_999L }
        }.distinct()
    }
}

internal fun discogsEditorialMasterMatches(
    master: DiscogsMasterRecord,
    query: DiscogsEditorialQuery,
): Boolean {
    if (!query.isValid()) return false
    val entries = if (query.isStyle) master.styles else master.genres
    return entries.any { it.trim().equals(query.sourceName, ignoreCase = true) }
}

/** Independent of MusicBrainz and iTunes. Never uses image/search snippets as
 * identity evidence and never interprets result order as popularity. */
internal class DiscogsNativeGenreEditorial(
    private val transport: DiscogsGenreSearchTransport = KtorDiscogsGenreSearchTransport(),
    private val editions: DiscogsNativeEditionReader = DiscogsNativeEditionClient(),
    private val tokenProvider: () -> String? = { null },
    private val enabled: Boolean = false,
    private val limiter: DiscogsRateLimiter = DiscogsRequestBudget.limiter,
    private val admission: RateLimitedProviderTransport = RateLimitedProviderTransport(),
    private val userAgent: String =
        "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)",
) {
    suspend fun discover(
        query: DiscogsEditorialQuery,
        maxReleases: Int = 4,
    ): DiscogsEditorialResult {
        if (!query.isValid() || maxReleases !in 1..6)
            return DiscogsEditorialResult.InvalidQuery
        if (!enabled || !admission.permitted(
            ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.DISCOGS,
        ) || !admission.permitted(
            ProviderCapability.EDITORIAL_DISCOGRAPHY, BuiltInProviderKeys.DISCOGS,
        )) return DiscogsEditorialResult.Disabled
        val token = tokenProvider()?.trim()?.takeIf(::isValidDiscogsSearchToken)
            ?: return DiscogsEditorialResult.Unauthorized
        val response = try {
            admission.execute(
                capability = ProviderCapability.CATALOG_SEARCH,
                provider = BuiltInProviderKeys.DISCOGS,
                scheduleLimited = { block -> limiter.runHttpLimited { block() } },
                networkCall = { transport.search(query, token, userAgent) },
            )
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: ProviderRequestDeniedException) { return DiscogsEditorialResult.Disabled }
          catch (_: Exception) { return DiscogsEditorialResult.Unavailable }
        val ids = when (response.status) {
            200 -> DiscogsEditorialMasterParsing.candidates(response.body)
                ?: return DiscogsEditorialResult.Unavailable
            401, 403 -> return DiscogsEditorialResult.Unauthorized
            429 -> return DiscogsEditorialResult.Throttled(response.retryAfterSeconds)
            else -> return DiscogsEditorialResult.Unavailable
        }
        val verified = mutableListOf<DiscogsEditorialCandidate>()
        // A bounded scan is a sample, never a complete genre catalogue.
        for (masterId in ids.take(6)) {
            if (verified.size >= maxReleases) break
            val masterResult = editions.master(masterId)
            val master = (masterResult as? DiscogsLookupResult.FoundMaster)?.record
                ?: continue
            if (master.link.id != masterId ||
                !discogsEditorialMasterMatches(master, query)) continue
            val releaseId = master.mainReleaseId ?: continue
            val releaseResult = editions.release(releaseId)
            val release = (releaseResult as? DiscogsLookupResult.Found)?.record
                ?: continue
            // No unofficial/ambiguous edition, wrong Master or unverifiable
            // artist credit can become a native catalogue card.
            if (release.link.kind != DiscogsKind.RELEASE ||
                release.link.id != releaseId || release.masterId != masterId ||
                release.unofficial || release.title.isBlank() ||
                release.artistNames.isEmpty()) continue
            verified += DiscogsEditorialCandidate(
                masterId = masterId, releaseId = releaseId,
                title = release.title,
                artist = release.artistNames.joinToString(", "),
                year = release.year ?: master.year,
                formats = release.formats,
                creditedArtists = if (release.artistIds.size == release.artistNames.size)
                    release.artistIds.zip(release.artistNames) else emptyList(),
            )
        }
        return DiscogsEditorialResult.Verified(verified.distinctBy { it.masterId })
    }
}
