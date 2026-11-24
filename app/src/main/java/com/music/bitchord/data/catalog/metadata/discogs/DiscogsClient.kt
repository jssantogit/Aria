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
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Fixed paths only; no untrusted web link is ever used as an API URL. */
internal interface DiscogsTransport {
    suspend fun get(path: String, userAgent: String): DiscogsHttpResponse

    /** Explicit opt-in: a fake/anonymous transport cannot claim auth quota. */
    val supportsTokenAuthorization: Boolean get() = false

    /** The production adapter authenticates only requests to the fixed host. */
    suspend fun getAuthenticated(
        path: String, userAgent: String, token: String,
    ): DiscogsHttpResponse = get(path, userAgent)
}

internal data class DiscogsHttpResponse(
    val status: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
)

internal class KtorDiscogsTransport(
    private val baseUrl: String = "https://api.discogs.com",
    private val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000L
            connectTimeoutMillis = 7_000L
            socketTimeoutMillis = 12_000L
        }
    },
) : DiscogsTransport {
    // Query parameters are a single hardcoded constant, never provider input.
    private val allowedPath = Regex(
        """^(?:releases|masters)/[1-9][0-9]{0,11}$|^masters/[1-9][0-9]{0,11}/versions\?page=1&per_page=8$""",
    )

    override val supportsTokenAuthorization: Boolean get() = true

    override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse =
        request(path, userAgent, null)

    override suspend fun getAuthenticated(
        path: String, userAgent: String, token: String,
    ): DiscogsHttpResponse = request(path, userAgent, token)

    private suspend fun request(
        path: String, userAgent: String, token: String?,
    ): DiscogsHttpResponse {
        require(allowedPath.matches(path)) { "Invalid Discogs endpoint" }
        require(token == null || (isValidDiscogsSearchToken(token) &&
            baseUrl.trimEnd('/') == "https://api.discogs.com")
        ) { "Discogs authorization requires the fixed official API host" }
        val response = client.get("${baseUrl.trimEnd('/')}/$path") {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/vnd.discogs.v2.discogs+json")
            if (token != null) header(HttpHeaders.Authorization, "Discogs token=$token")
        }
        val size = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (size != null && size > MAX_RESPONSE_BYTES) {
            response.bodyAsChannel().cancel()
            return DiscogsHttpResponse(413, "")
        }
        // Body size cannot rely on Content-Length: chunked/malicious responses
        // must be rejected BEFORE Ktor builds a potentially unbounded String.
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8_192)
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size().toLong() + count > MAX_RESPONSE_BYTES) {
                channel.cancel()
                return DiscogsHttpResponse(413, "")
            }
            output.write(buffer, 0, count)
        }
        val body = output.toByteArray().toString(Charsets.UTF_8)
        val retry = response.headers[HttpHeaders.RetryAfter]
            ?.toLongOrNull()?.takeIf { it in 0..MAX_RETRY_AFTER }
        return DiscogsHttpResponse(response.status.value, body, retry)
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 131_072L
        const val MAX_RETRY_AFTER = 86_400L
    }
}

/**
 * Discogs' documented anonymous ceiling is 25 requests in a moving minute.
 * Some artist and fallback calls remain anonymous, so the global scheduler
 * retains a separate 2.6 s anonymous floor. With a valid token, authenticated
 * reads may use a 1.1 s floor, still below 60 total requests per minute.
 */
internal const val DISCOGS_SAFE_MIN_INTERVAL_MS = 2_600L

/** One conservative process-wide budget; never a limiter per endpoint. */
internal const val DISCOGS_SAFE_AUTH_INTERVAL_MS = 1_100L

internal class DiscogsRateLimiter(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val minimumIntervalMs: Long = DISCOGS_SAFE_MIN_INTERVAL_MS,
) {
    private val mutex = Mutex()
    private var lastRequestAt: Long? = null
    private var lastAnonymousAt: Long? = null
    private var authTierRejected = false
    /** Monotonic deadline recorded under the same mutex as dispatch. */
    private var throttledUntilMs: Long? = null

    init { require(minimumIntervalMs >= 0L) }

    /**
     * Shared process budget: up to 55 total dispatches/min, of which no more
     * than 24 may be anonymous. Search and hydration cannot independently
     * spend more than the documented source quota. Tests may pass a lower
     * minimumIntervalMs to use a deterministic virtual clock.
     */
    private suspend fun <T> schedule(
        authenticated: Boolean, block: suspend () -> T,
    ): T {
        mutex.lock()
        try {
            val authInterval = minOf(
                minimumIntervalMs, DISCOGS_SAFE_AUTH_INTERVAL_MS,
            )
            val useAuthTier = authenticated && !authTierRejected
            val now = nowMillis()
            val totalPause = lastRequestAt?.let { authInterval - (now - it) } ?: 0L
            val anonymousPause = if (useAuthTier) 0L else
                lastAnonymousAt?.let { minimumIntervalMs - (now - it) } ?: 0L
            val cooldown = throttledUntilMs?.minus(now) ?: 0L
            val pause = maxOf(totalPause, anonymousPause, cooldown)
            if (pause > 0L) delayMillis(pause)
            val dispatchedAt = nowMillis()
            lastRequestAt = dispatchedAt
            if (!useAuthTier) lastAnonymousAt = dispatchedAt
            return block()
        } finally {
            mutex.unlock()
        }
    }

    suspend fun <T> runLimited(block: suspend () -> T): T =
        schedule(authenticated = false, block)

    /**
     * All endpoints use the same mutex and 429 cooldown. Authentication is
     * an explicit transport capability, never assumed from a stored token
     * alone; a 401/403 demotes future dispatches to the anonymous budget
     * until a successfully authenticated request restores confidence.
     */
    suspend fun runHttpLimited(
        authenticated: Boolean = false,
        block: suspend () -> DiscogsHttpResponse,
    ): DiscogsHttpResponse = schedule(authenticated) {
        val response = block()
        if (response.status == 429) {
            val seconds = (response.retryAfterSeconds ?: 30L).coerceIn(3L, 86_400L)
            val target = nowMillis() + seconds * 1_000L
            throttledUntilMs = maxOf(throttledUntilMs ?: Long.MIN_VALUE, target)
        }
        if (authenticated && response.status in listOf(401, 403)) {
            // Failed authentication may be billed against the anonymous
            // source quota; count this dispatch conservatively in both
            // buckets before allowing another credentialed attempt.
            authTierRejected = true
            lastAnonymousAt = lastRequestAt
        } else if (authenticated && response.status == 200) {
            authTierRejected = false
        }
        response
    }
}

internal object DiscogsRequestBudget {
    val limiter = DiscogsRateLimiter()
}

internal sealed interface DiscogsLookupResult {
    data class Found(val record: DiscogsRecord) : DiscogsLookupResult
    data class FoundMaster(val record: DiscogsMasterRecord) : DiscogsLookupResult
    data object Disabled : DiscogsLookupResult
    data object InvalidId : DiscogsLookupResult
    data object Missing : DiscogsLookupResult
    data object Unauthorized : DiscogsLookupResult
    data class Throttled(val retryAfterSeconds: Long?) : DiscogsLookupResult
    data object Unavailable : DiscogsLookupResult
    data object Malformed : DiscogsLookupResult
}

/**
 * A real HTTP adapter, intentionally disabled by default until Discogs terms
 * and the project's distribution constraints have been approved. The release
 * endpoint is read-only, anonymous, and does not request images or marketplace data.
 */
internal class DiscogsClient(
    private val transport: DiscogsTransport = KtorDiscogsTransport(),
    private val enabled: Boolean = false,
    private val capability: ProviderCapability = ProviderCapability.EDITION_METADATA,
    private val limiter: DiscogsRateLimiter = DiscogsRequestBudget.limiter,
    private val admission: RateLimitedProviderTransport = RateLimitedProviderTransport(),
    /** Token never appears in source URLs, logs, cache keys or exported metrics. */
    private val tokenProvider: () -> String? = { null },
    private val userAgent: String =
        "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)",
) {
    private fun currentToken(): String? =
        tokenProvider()?.trim()?.takeIf(::isValidDiscogsSearchToken)
            ?.takeIf { transport.supportsTokenAuthorization }

    private suspend fun sourceGet(path: String, token: String?): DiscogsHttpResponse =
        if (token == null) transport.get(path, userAgent)
        else transport.getAuthenticated(path, userAgent, token)

    /** Discogs Master is advisory group context only; never a verified Release. */
    suspend fun fetchMaster(id: Long): DiscogsLookupResult {
        if (!enabled) return DiscogsLookupResult.Disabled
        if (id !in 1..999_999_999_999L) return DiscogsLookupResult.InvalidId
        val token = currentToken()
        val authenticated = token != null
        val response = try {
            admission.execute(
                capability = capability,
                provider = BuiltInProviderKeys.DISCOGS,
                scheduleLimited = { block ->
                    limiter.runHttpLimited(authenticated = authenticated) { block() }
                },
                networkCall = { sourceGet("masters/$id", token) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ProviderRequestDeniedException) {
            return DiscogsLookupResult.Disabled
        } catch (_: Exception) {
            return DiscogsLookupResult.Unavailable
        }
        DiscogsDiagnosticStatus.httpResult(DiscogsKind.MASTER, response.status)
        return when (response.status) {
            200 -> {
                val link = DiscogsLink(DiscogsKind.MASTER, id)
                val record = DiscogsRecordParser.parseMaster(link, response.body)
                    ?: return DiscogsLookupResult.Malformed
                DiscogsLookupResult.FoundMaster(record)
            }
            401, 403 -> DiscogsLookupResult.Unauthorized
            404 -> DiscogsLookupResult.Missing
            429 -> DiscogsLookupResult.Throttled(response.retryAfterSeconds)
            else -> DiscogsLookupResult.Unavailable
        }
    }

    /**
     * Discogs Master versions are a paginated public read-only endpoint.
     * At most eight versions are examined; when pagination exceeds that
     * budget we return Incomplete, never a fabricated unique edition.
     */
    suspend fun fetchMasterVersions(id: Long): DiscogsMasterVersionsResult {
        if (!enabled) return DiscogsMasterVersionsResult.Disabled
        if (id !in 1..999_999_999_999L) return DiscogsMasterVersionsResult.Malformed
        val token = currentToken()
        val authenticated = token != null
        val response = try {
            admission.execute(
                capability = capability,
                provider = BuiltInProviderKeys.DISCOGS,
                scheduleLimited = { block ->
                    limiter.runHttpLimited(authenticated = authenticated) { block() }
                },
                networkCall = { sourceGet("masters/$id/versions?page=1&per_page=8", token) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ProviderRequestDeniedException) {
            return DiscogsMasterVersionsResult.Disabled
        } catch (_: Exception) {
            return DiscogsMasterVersionsResult.Unavailable
        }
        if (response.status != 200) return DiscogsMasterVersionsResult.Unavailable
        return try {
            if (response.body.length > 131_072) return DiscogsMasterVersionsResult.Malformed
            val obj = Json.parseToJsonElement(response.body) as? JsonObject
                ?: return DiscogsMasterVersionsResult.Malformed
            val paging = obj["pagination"] as? JsonObject
                ?: return DiscogsMasterVersionsResult.Malformed
            val page = (paging["page"] as? JsonPrimitive)?.intOrNull
            val pages = (paging["pages"] as? JsonPrimitive)?.intOrNull
            val perPage = (paging["per_page"] as? JsonPrimitive)?.intOrNull
            val items = (paging["items"] as? JsonPrimitive)?.intOrNull
            if (page != 1 || perPage != 8 || pages == null || pages !in 1..100_000 ||
                items == null || items !in 1..100_000
            ) return DiscogsMasterVersionsResult.Malformed
            if (pages != 1 || items > 8) return DiscogsMasterVersionsResult.Incomplete
            val rows = obj["versions"] as? JsonArray
                ?: return DiscogsMasterVersionsResult.Malformed
            if (rows.size != items) return DiscogsMasterVersionsResult.Malformed
            val ids = rows.map { row ->
                val value = ((row as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull
                    ?: return DiscogsMasterVersionsResult.Malformed
                if (value !in 1..999_999_999_999L) {
                    return DiscogsMasterVersionsResult.Malformed
                }
                value
            }
            if (ids.distinct().size != ids.size) DiscogsMasterVersionsResult.Malformed
            else DiscogsMasterVersionsResult.Found(ids)
        } catch (_: Exception) {
            DiscogsMasterVersionsResult.Malformed
        }
    }

    /**
     * A VISUAL preview is different from the exhaustive Master verification
     * used by the edition matcher above. Large Masters may have hundreds of
     * physical versions: reveal only a bounded, explicitly non-exhaustive
     * first page and re-verify each candidate Release before painting it.
     */
    suspend fun fetchMasterVersionPreview(id: Long): DiscogsMasterVersionPreviewResult {
        if (!enabled) return DiscogsMasterVersionPreviewResult.Disabled
        if (id !in 1..999_999_999_999L)
            return DiscogsMasterVersionPreviewResult.Malformed
        val token = currentToken()
        val authenticated = token != null
        val response = try {
            admission.execute(
                capability = capability,
                provider = BuiltInProviderKeys.DISCOGS,
                scheduleLimited = { block ->
                    limiter.runHttpLimited(authenticated = authenticated) { block() }
                },
                networkCall = {
                    sourceGet("masters/$id/versions?page=1&per_page=8", token)
                },
            )
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: ProviderRequestDeniedException) {
              return DiscogsMasterVersionPreviewResult.Disabled
          } catch (_: Exception) {
              return DiscogsMasterVersionPreviewResult.Unavailable
          }
        return when (response.status) {
            200 -> discogsMasterVersionPreviewPage(response.body)?.let {
                DiscogsMasterVersionPreviewResult.Page(it)
            } ?: DiscogsMasterVersionPreviewResult.Malformed
            429 -> DiscogsMasterVersionPreviewResult.Throttled(response.retryAfterSeconds)
            401, 403 -> DiscogsMasterVersionPreviewResult.Disabled
            else -> DiscogsMasterVersionPreviewResult.Unavailable
        }
    }

    suspend fun fetchRelease(id: Long): DiscogsLookupResult {
        if (!enabled) return DiscogsLookupResult.Disabled
        if (id !in 1..999_999_999_999L) return DiscogsLookupResult.InvalidId
        val token = currentToken()
        val authenticated = token != null
        val response = try {
            admission.execute(
                capability = capability,
                provider = BuiltInProviderKeys.DISCOGS,
                scheduleLimited = { block ->
                    limiter.runHttpLimited(authenticated = authenticated) { block() }
                },
                networkCall = { sourceGet("releases/$id", token) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ProviderRequestDeniedException) {
            return DiscogsLookupResult.Disabled
        } catch (_: Exception) {
            return DiscogsLookupResult.Unavailable
        }
        DiscogsDiagnosticStatus.httpResult(DiscogsKind.RELEASE, response.status)
        return when (response.status) {
            200 -> {
                val link = DiscogsLink(DiscogsKind.RELEASE, id)
                val record = DiscogsRecordParser.parse(link, response.body)
                    ?: return DiscogsLookupResult.Malformed
                DiscogsLookupResult.Found(record)
            }
            401, 403 -> DiscogsLookupResult.Unauthorized
            404 -> DiscogsLookupResult.Missing
            429 -> DiscogsLookupResult.Throttled(response.retryAfterSeconds)
            else -> DiscogsLookupResult.Unavailable
        }
    }
}

/** A complete, bounded list of versions is essential before claiming an
 * exact edition is unique. The Main Release alone is never exhaustive. */
internal sealed interface DiscogsMasterVersionsResult {
    data class Found(val releaseIds: List<Long>) : DiscogsMasterVersionsResult
    data object Incomplete : DiscogsMasterVersionsResult
    data object Disabled : DiscogsMasterVersionsResult
    data object Malformed : DiscogsMasterVersionsResult
    data object Unavailable : DiscogsMasterVersionsResult
}

internal data class DiscogsMasterVersionPreviewPage(
    val releaseIds: List<Long>,
    /** Never treat the first-page index as a complete pressing list. */
    val hasMore: Boolean,
)

internal sealed interface DiscogsMasterVersionPreviewResult {
    data class Page(val result: DiscogsMasterVersionPreviewPage) :
        DiscogsMasterVersionPreviewResult
    data class Throttled(val retryAfterSeconds: Long?) :
        DiscogsMasterVersionPreviewResult
    data object Disabled : DiscogsMasterVersionPreviewResult
    data object Malformed : DiscogsMasterVersionPreviewResult
    data object Unavailable : DiscogsMasterVersionPreviewResult
}

/** Strict candidate IDs only; the Release endpoint must corroborate Master ID. */
internal fun discogsMasterVersionPreviewPage(
    raw: String,
): DiscogsMasterVersionPreviewPage? {
    if (raw.length > 131_072) return null
    val root = try { Json.parseToJsonElement(raw) as? JsonObject }
        catch (_: Exception) { null } ?: return null
    val paging = root["pagination"] as? JsonObject ?: return null
    val page = (paging["page"] as? JsonPrimitive)?.intOrNull
    val pages = (paging["pages"] as? JsonPrimitive)?.intOrNull
    val perPage = (paging["per_page"] as? JsonPrimitive)?.intOrNull
    val items = (paging["items"] as? JsonPrimitive)?.intOrNull
    if (page != 1 || perPage != 8 || pages == null || pages !in 1..100_000 ||
        items == null || items !in 0..10_000_000
    ) return null
    val rows = root["versions"] as? JsonArray ?: return null
    if (rows.size > 8 || rows.size > items ||
        pages == 1 && rows.size != items || items > 0 && rows.isEmpty()
    ) return null
    val ids = rows.map { value ->
        ((value as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull
            ?.takeIf { it in 1..999_999_999_999L } ?: return null
    }
    if (ids.size != ids.distinct().size) return null
    return DiscogsMasterVersionPreviewPage(
        releaseIds = ids,
        hasMore = pages > 1 || items > ids.size,
    )
}

/**
 * Ephemeral source-specific cache; does not persist in the local music catalog,
 * backup, source-assets or Room. No stale-while-revalidate for API Content.
 */
internal class DiscogsMemoryEvidenceStore(private val maxEntries: Int = 128) {
    private data class Key(
        val canonicalMbid: String, val kind: DiscogsKind, val discogsId: Long,
    )
    private val entries = linkedMapOf<Key, DiscogsObservation>()

    init { require(maxEntries > 0) }

    @Synchronized
    fun put(value: DiscogsObservation) {
        val key = Key(value.canonicalMbid.lowercase(Locale.ROOT), value.kind, value.record.link.id)
        entries.remove(key)
        entries[key] = value
        if (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun get(canonicalMbid: String, link: DiscogsLink, nowMillis: Long): DiscogsObservation? {
        val key = Key(canonicalMbid.lowercase(Locale.ROOT), link.kind, link.id)
        val value = entries[key] ?: return null
        if (!value.isDisplayableAt(nowMillis)) {
            entries.remove(key)
            return null
        }
        return value
    }

    @Synchronized
    fun clear() = entries.clear()
}
