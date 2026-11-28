package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.providers.ProviderCapability
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Distinguishes valid complete, truncated/partial and unavailable results.
 * A page may legitimately be empty; that is not synonymous with unavailable.
 */
internal enum class AppleMusicWebCompleteness {
    PAGE_LIMIT,
    UPSTREAM_INTERRUPTED,
    MISSING_TRACKLIST,
    UNRESOLVED_EDITORIAL_REFERENCES,
    TRACK_COUNT_MISMATCH,
}

internal sealed interface AppleMusicWebOutcome<out T> {
    data class Complete<T>(val value: T) : AppleMusicWebOutcome<T>
    data class Partial<T>(
        val value: T,
        val reason: AppleMusicWebCompleteness,
    ) : AppleMusicWebOutcome<T>
    data class Unavailable(val reason: String) : AppleMusicWebOutcome<Nothing>
}

internal sealed interface AppleMusicWebBatchResult<out T> {
    val input: String
    data class Success<T>(override val input: String, val value: T) : AppleMusicWebBatchResult<T>
    data class Failure(
        override val input: String,
        val failure: AppleMusicWebBatchFailure,
        val httpStatus: Int? = null,
    ) : AppleMusicWebBatchResult<Nothing>
}

internal enum class AppleMusicWebBatchFailure {
    HTTP_ERROR,
    NETWORK_ERROR,
    INVALID_INPUT,
    INVALID_RESPONSE,
}

/**
 * One suspending implementation, bounded concurrency, ordered output.
 * No catch-all: cancellation and programming faults cancel the parent.
 * The caller's provider admission/revocation still owns every network request.
 */
internal class AppleMusicWebBatch(maxConcurrency: Int = 4) {
    private val semaphore = Semaphore(maxConcurrency.also {
        require(it in 1..16) { "Invalid Apple web batch concurrency" }
    })

    suspend fun <T> fetch(
        inputs: List<String>,
        fetchOne: suspend (String) -> T,
    ): List<AppleMusicWebBatchResult<T>> = coroutineScope {
        require(inputs.size <= 500) { "Unbounded Apple web batch" }
        inputs.map { input ->
            async {
                semaphore.withPermit {
                    try {
                        AppleMusicWebBatchResult.Success(input, fetchOne(input))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: AppleMusicWebHttpException) {
                        AppleMusicWebBatchResult.Failure(
                            input, AppleMusicWebBatchFailure.HTTP_ERROR, failure.status,
                        )
                    } catch (_: AppleMusicWebProtocolException) {
                        AppleMusicWebBatchResult.Failure(
                            input, AppleMusicWebBatchFailure.INVALID_RESPONSE,
                        )
                    } catch (_: IllegalArgumentException) {
                        AppleMusicWebBatchResult.Failure(
                            input, AppleMusicWebBatchFailure.INVALID_INPUT,
                        )
                    } catch (_: IOException) {
                        AppleMusicWebBatchResult.Failure(
                            input, AppleMusicWebBatchFailure.NETWORK_ERROR,
                        )
                    }
                }
            }
        }.awaitAll()
    }
}

/**
 * Contract for the existing provider cache — not a second storage engine.
 * No token, cookie, account URL, personal data or non-Apple request may
 * become a persisted cache entry. Disabled providers still need admission
 * before a cached response is ever served.
 */
internal object AppleMusicWebCacheContract {
    private val storefront = Regex("[a-z]{2}")
    private val locale = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}")
    private val forbidden = listOf("auth", "token", "cookie", "session", "secret", "password", "key")

    /** Provisional maximum ages per public operation. Unknown routes or
     * parameters are deliberately uncacheable, even when transport can read them. */
    fun maxAgeMs(
        capability: ProviderCapability, path: String, parameters: Map<String, String>,
    ): Long? {
        if (!AppleMusicWebOperationRegistry.allows(capability, path)) return null
        val parts = path.split('/').drop(1)
        val resource = parts[3]
        val keys = parameters.keys
        val minute = 60_000L
        val paging = setOf("limit", "offset", "l")
        val age = when {
            capability == ProviderCapability.CATALOG_SEARCH -> {
                if (parameters["term"].isNullOrBlank() ||
                    parameters["types"] !in setOf("artists", "albums", "songs", "apple-curators") ||
                    !keys.all { it in paging + setOf("term", "types") }) return null
                5 * minute
            }
            capability == ProviderCapability.CHART_MOST_PLAYED -> {
                if (resource == "charts" && parameters["chartId"] == "119") {
                    if (parts[2] != "us" || !keys.all { it in setOf("chartId", "limit", "l") }) return null
                } else if (resource == "charts") {
                    if (parameters["chart"] != "most-played" ||
                        parameters["types"] !in setOf("songs", "albums") ||
                        !keys.all { it in paging + setOf("chart", "types", "genre") }) return null
                } else if (parts.size == 5) {
                    if (parameters != mapOf("include" to "tracks")) return null
                } else if (!keys.all { it in paging }) return null
                15 * minute
            }
            parts[1] == "editorial" && resource == "groupings" -> {
                if (parameters["platform"] != "web" ||
                    !((parameters["name"] == "music" && "genre" !in parameters) ||
                        ("name" !in parameters && parameters["genre"]?.matches(Regex("[0-9]{1,12}")) == true)) ||
                    !keys.all { it in setOf("platform", "name", "genre", "l") }) return null
                15 * minute
            }
            parts[1] == "editorial" && resource == "rooms" -> {
                if (!keys.all { it in paging + "sort" }) return null
                15 * minute
            }
            // Exact song reads without relationships resolve current preview evidence.
            resource == "songs" && parts.size == 5 && parameters.isEmpty() -> return null
            resource == "genres" -> {
                if (!keys.all { it == "l" }) return null
                24 * 60 * minute
            }
            resource == "apple-curators" -> {
                if (parameters != mapOf("platform" to "web", "include" to "grouping,playlists")) return null
                15 * minute
            }
            else -> {
                if (!keys.all { it in paging + setOf("include", "views", "extend") }) return null
                60 * minute
            }
        }
        return age
    }

    fun key(
        capability: ProviderCapability,
        market: String,
        language: String,
        path: String,
        parameters: Map<String, String>,
    ): String? {
        val country = market.lowercase(Locale.ROOT)
        if (!storefront.matches(country) || !locale.matches(language) ||
            !path.startsWith("/v1/catalog/$country/") && !path.startsWith("/v1/editorial/$country/") ||
            maxAgeMs(capability, path, parameters) == null || parameters.size > 30
        ) return null
        if (parameters.any { (name, value) ->
                val normalized = name.lowercase(Locale.ROOT)
                forbidden.any(normalized::contains) ||
                    name.length !in 1..100 || value.length > 4096 ||
                    name.any(Char::isISOControl) || value.any(Char::isISOControl)
            }
        ) return null
        val record = buildString {
            append("apple_music_web:v2\n")
            append(capability.name).append('\n')
            append(country).append('\n')
            append(language).append('\n')
            append(path).append('\n')
            for ((name, value) in parameters.toSortedMap()) {
                append(name.length).append(':').append(name)
                append(value.length).append(':').append(value).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(record.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
