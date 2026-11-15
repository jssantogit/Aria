package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderRequestDeniedException
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.data.providers.ProviderSingleFlight
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

internal const val HTTP_TOO_MANY_REQUESTS = 429
internal const val HTTP_SERVICE_UNAVAILABLE = 503
private const val MAX_ATTEMPTS = 3
private val BACKOFF_MILLIS = listOf(1_000L, 3_000L, 7_000L)

internal fun defaultMusicBrainzUserAgent(): String =
    "Bitchord/1.7.1 (https://github.com/jssantogit/BitChord)"

internal interface MusicBrainzTransport {
    suspend fun get(path: String, parameters: Map<String, String>, userAgent: String): String
}

internal class KtorMusicBrainzTransport(
    private val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = true
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000L
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = 15_000L
        }
    },
) : MusicBrainzTransport {
    override suspend fun get(
        path: String,
        parameters: Map<String, String>,
        userAgent: String,
    ): String = client.get("https://musicbrainz.org/ws/2/${path.trimStart('/')}") {
        headers.append(HttpHeaders.UserAgent, userAgent)
        parameters.forEach { (name, value) -> url.parameters.append(name, value) }
    }.bodyAsText()
}

internal class MusicBrainzRateLimiter(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val minimumIntervalMs: Long = 1_000L,
) {
    private var lastRequestAt: Long? = null

    suspend fun <T> runLimited(block: suspend () -> T): T {
        val remaining = synchronized(this) {
            val now = nowMillis()
            val last = lastRequestAt
            val wait = if (last == null) 0L else maxOf(0L, minimumIntervalMs - (now - last))
            lastRequestAt = now + wait
            wait
        }
        if (remaining > 0L) delayMillis(remaining)
        return block()
    }
}

/**
 * Shared MusicBrainz request budget across the Android application process.
 */
internal object MusicBrainzRequestBudget {
    val limiter = MusicBrainzRateLimiter()
    val transport: MusicBrainzTransport by lazy { KtorMusicBrainzTransport() }
    val inFlight = ProviderSingleFlight<String>()
}

private data class MusicBrainzInFlightKey(
    val transport: MusicBrainzTransport,
    val gate: ProviderPolicyGate?,
    val revision: Long?,
    val capability: ProviderCapability,
    val path: String,
    val parameters: Map<String, String>,
    val userAgent: String,
)

internal class MusicBrainzClient(
    private val transport: MusicBrainzTransport = MusicBrainzRequestBudget.transport,
    internal val rateLimiter: MusicBrainzRateLimiter = MusicBrainzRequestBudget.limiter,
    val userAgent: String = defaultMusicBrainzUserAgent(),
    private val retryDelayMillis: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val admission: () -> ProviderPolicyGate? = { ProviderRuntime.gateOrNull() },
    private val singleFlight: ProviderSingleFlight<String> = MusicBrainzRequestBudget.inFlight,
) {
    suspend fun get(
        path: String,
        parameters: Map<String, String> = mapOf("fmt" to "json"),
        capability: ProviderCapability = ProviderCapability.IDENTITY,
    ): String {
        val gate = admission()
        val provider = ProviderKey("musicbrainz")
        val ticket = gate?.open(capability, provider)
        if (gate != null && ticket == null) {
            throw ProviderRequestDeniedException(capability, provider)
        }

        fun ensureAdmitted() {
            if (gate != null && !gate.accept(requireNotNull(ticket))) {
                throw ProviderRequestDeniedException(capability, provider)
            }
        }

        val requestKey = MusicBrainzInFlightKey(
            transport = transport,
            gate = gate,
            revision = ticket?.revision,
            capability = capability,
            path = path,
            parameters = parameters.toMap(),
            userAgent = userAgent,
        )
        return singleFlight.runGuarded(
            gate = gate,
            capability = capability,
            provider = provider,
            key = requestKey,
        ) {
            var attempt = 1
            while (true) {
                try {
                    ensureAdmitted()
                    val response = rateLimiter.runLimited {
                        ensureAdmitted()
                        transport.get(path, parameters, userAgent).also {
                            ensureAdmitted()
                        }
                    }
                    ensureAdmitted()
                    return@runGuarded response
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (response: ResponseException) {
                    val status = response.response.status.value
                    if (status != HTTP_TOO_MANY_REQUESTS &&
                        status != HTTP_SERVICE_UNAVAILABLE || attempt >= MAX_ATTEMPTS
                    ) throw response

                    val retryAfter = response.response.headers[HttpHeaders.RetryAfter]
                    val wait = if (retryAfter == null) {
                        BACKOFF_MILLIS[attempt - 1]
                    } else {
                        retryAfter.retryAfterMillisOrNull() ?: throw response
                    }
                    retryDelayMillis(wait)
                    attempt += 1
                } catch (io: IOException) {
                    if (attempt >= MAX_ATTEMPTS) throw io
                    retryDelayMillis(BACKOFF_MILLIS[attempt - 1])
                    attempt += 1
                }
            }
            error("MusicBrainz request unexpectedly fell through")
        }
    }

    private fun String.retryAfterMillisOrNull(): Long? =
        toLongOrNull()?.takeIf { it in 0..86400L }?.times(1000L)
}
