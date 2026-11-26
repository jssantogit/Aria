package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.RateLimitedProviderTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.Locale

/**
 * Only public Apple Marketing Tools "most-played" territory feeds.
 *
 * - Chart positions belong to the Apple storefront, not a universal ranking.
 * - Chart entries are Apple song products, NOT canonical Recording MBIDs.
 * - The feed proves neither a playable MediaAsset nor an album association.
 * - fetchedAt is our observation time, never an Apple-reported chart period.
 */
internal data class AppleChartTerritory(val code: String, val displayName: String)

internal val appleChartTerritories = listOf(
    AppleChartTerritory("br", "Brasil"),
    AppleChartTerritory("us", "Estados Unidos"),
    AppleChartTerritory("jp", "Japão"),
    AppleChartTerritory("kr", "Coreia do Sul"),
    AppleChartTerritory("gb", "Reino Unido"),
    AppleChartTerritory("fr", "França"),
    AppleChartTerritory("de", "Alemanha"),
    AppleChartTerritory("mx", "México"),
    AppleChartTerritory("ca", "Canadá"),
    AppleChartTerritory("au", "Austrália"),
)

internal fun knownAppleChartTerritory(code: String?) =
    appleChartTerritories.firstOrNull { it.code == code }

internal fun appleChartBrowseId(country: String): String =
    "catalog:CHART:itunes:$country"

internal fun parseAppleChartBrowseId(id: String?): AppleChartTerritory? {
    if (id == null || !id.startsWith("catalog:CHART:itunes:")) return null
    return knownAppleChartTerritory(id.removePrefix("catalog:CHART:itunes:"))
}

internal data class AppleChartSong(
    val position: Int,
    val songId: Long,
    val country: String,
    val title: String,
    val artistName: String,
    val artworkUrl: String?,
    val officialUrl: String,
) {
    val songBrowseId: String get() = "itunes:$country:track:$songId"
}

internal data class AppleCountryChart(
    val territory: AppleChartTerritory,
    val songs: List<AppleChartSong>,
    val fetchedAtMs: Long,
)

internal sealed interface AppleCountryChartRead {
    data class Success(val chart: AppleCountryChart): AppleCountryChartRead
    data class Failure(val reason: Reason): AppleCountryChartRead
    enum class Reason { UNSUPPORTED_TERRITORY, NOT_AUTHORIZED, UNAVAILABLE, INVALID_RESPONSE }
}

private val chartSongHost = Regex("^(music\\.apple\\.com)$")
private val artworkHost = Regex("^is[0-9]+-ssl\\.mzstatic\\.com$")
private val positiveAppleNumber = Regex("^[1-9][0-9]{0,18}$")

private fun chartAppleSongUrl(url: String?, storefront: String, songId: Long): String? {
    if (url.isNullOrBlank() || url.length > 512) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || !chartSongHost.matches(uri.host.orEmpty()) ||
        uri.userInfo != null || uri.port != -1 ||
        !uri.path.orEmpty().startsWith("/$storefront/album/")
    ) return null
    val candidates = uri.rawQuery.orEmpty().split('&').filter { it.startsWith("i=") }
    if (candidates.size != 1 || candidates[0].substringAfter('=') != songId.toString()) return null
    return url
}

private fun chartAppleArtwork(url: String?): String? {
    if (url.isNullOrBlank() || url.length > 700) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    return url.takeIf {
        uri.scheme == "https" && artworkHost.matches(uri.host.orEmpty()) &&
            uri.userInfo == null && uri.port == -1 && uri.path.orEmpty().startsWith("/image/")
    }
}

/**
 * Malformed, duplicate and cross-storefront entries are not silently treated
 * as valid Apple chart products. A response with no valid rows is unavailable.
 * Preserve original positions, including gaps left by rejected records.
 */
internal fun parseAppleCountryChart(
    body: String,
    country: String,
    fetchedAtMs: Long,
): AppleCountryChart? {
    val territory = knownAppleChartTerritory(country) ?: return null
    if (body.isBlank() || body.length > 750_000 || fetchedAtMs <= 0) return null
    val feed = runCatching {
        Json.parseToJsonElement(body).jsonObject["feed"]?.jsonObject
    }.getOrNull() ?: return null
    val results = feed["results"] as? JsonArray ?: return null
    if (results.isEmpty() || results.size > 100) return null
    val ids = mutableSetOf<Long>()
    val valid = buildList {
        for ((index, raw) in results.withIndex()) {
            val obj = raw as? JsonObject ?: continue
            fun str(key: String) = runCatching {
                obj[key]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            val idText = str("id") ?: continue
            if (!positiveAppleNumber.matches(idText)) continue
            val id = idText.toLongOrNull() ?: continue
            if (str("kind") != "songs") continue
            val title = str("name")?.trim()?.takeIf { it.isNotBlank() && it.length <= 220 }
                ?: continue
            val artist = str("artistName")?.trim()?.takeIf {
                it.isNotBlank() && it.length <= 220
            } ?: continue
            val url = chartAppleSongUrl(str("url"), country, id) ?: continue
            if (!ids.add(id)) return null // conflicting rank for the same Apple product
            add(AppleChartSong(
                position = index + 1,
                songId = id,
                country = country,
                title = title,
                artistName = artist,
                artworkUrl = chartAppleArtwork(str("artworkUrl100")),
                officialUrl = url,
            ))
        }
    }
    if (valid.isEmpty()) return null
    return AppleCountryChart(territory, valid, fetchedAtMs)
}

internal fun interface AppleCountryChartTransport {
    suspend fun get(country: String): String
}

internal class HttpAppleCountryChartTransport(
    private val client: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000L
            requestTimeoutMillis = 18_000L
            socketTimeoutMillis = 18_000L
        }
    },
): AppleCountryChartTransport {
    override suspend fun get(country: String): String {
        require(knownAppleChartTerritory(country) != null)
        val response = client.get(
            "https://rss.applemarketingtools.com/api/v2/$country/music/most-played/100/songs.json"
        ) {
            header(HttpHeaders.UserAgent,
                "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)")
        }
        if (response.status.value != 200) error("Apple chart feed unavailable")
        val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length > 750_000L) error("Apple chart feed too large")
        return response.bodyAsText().also { if (it.length > 750_000) error("Chart feed too large") }
    }
}

/** All chart network calls obey the independent CHART_MOST_PLAYED authorization. */
internal class AppleCountryChartRepository(
    private val transport: AppleCountryChartTransport = HttpAppleCountryChartTransport(),
    private val admission: RateLimitedProviderTransport = RateLimitedProviderTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun read(country: String): AppleCountryChartRead {
        if (knownAppleChartTerritory(country) == null)
            return AppleCountryChartRead.Failure(
                AppleCountryChartRead.Reason.UNSUPPORTED_TERRITORY)
        if (!admission.permitted(ProviderCapability.CHART_MOST_PLAYED, BuiltInProviderKeys.ITUNES))
            return AppleCountryChartRead.Failure(AppleCountryChartRead.Reason.NOT_AUTHORIZED)
        return try {
            val raw = admission.execute(
                capability = ProviderCapability.CHART_MOST_PLAYED,
                provider = BuiltInProviderKeys.ITUNES,
                scheduleLimited = { block -> block() },
                networkCall = { transport.get(country) },
            )
            val chart = parseAppleCountryChart(raw, country, clock())
            if (chart == null) AppleCountryChartRead.Failure(
                AppleCountryChartRead.Reason.INVALID_RESPONSE,
            ) else AppleCountryChartRead.Success(chart)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
            AppleCountryChartRead.Failure(AppleCountryChartRead.Reason.UNAVAILABLE)
        }
    }
}
