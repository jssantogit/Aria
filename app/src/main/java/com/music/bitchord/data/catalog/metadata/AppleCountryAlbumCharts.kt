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
 * - Chart entries are Apple album products, NOT canonical Recording MBIDs.
 * - The feed proves neither a playable MediaAsset nor an album association.
 * - fetchedAt is our observation time, never an Apple-reported chart period.
 */
internal fun appleAlbumChartBrowseId(country: String): String =
    "catalog:ALBUM_CHART:itunes:$country"

internal fun parseAppleAlbumChartBrowseId(id: String?): AppleChartTerritory? {
    if (id == null || !id.startsWith("catalog:ALBUM_CHART:itunes:")) return null
    return knownAppleChartTerritory(id.removePrefix("catalog:ALBUM_CHART:itunes:"))
}

internal data class AppleChartAlbum(
    val position: Int,
    val albumId: Long,
    val country: String,
    val title: String,
    val artistName: String,
    val artworkUrl: String?,
    val officialUrl: String,
) {
    val albumBrowseId: String get() = "itunes:$country:album:$albumId"
}

internal data class AppleCountryAlbumChart(
    val territory: AppleChartTerritory,
    val albums: List<AppleChartAlbum>,
    val fetchedAtMs: Long,
)

internal sealed interface AppleCountryAlbumChartRead {
    data class Success(val chart: AppleCountryAlbumChart): AppleCountryAlbumChartRead
    data class Failure(val reason: Reason): AppleCountryAlbumChartRead
    enum class Reason { UNSUPPORTED_TERRITORY, NOT_AUTHORIZED, UNAVAILABLE, INVALID_RESPONSE }
}

private val chartAlbumHost = Regex("^(music\\.apple\\.com)$")
private val artworkHost = Regex("^is[0-9]+-ssl\\.mzstatic\\.com$")
private val positiveAppleNumber = Regex("^[1-9][0-9]{0,18}$")

private fun chartAppleAlbumUrl(url: String?, storefront: String, albumId: Long): String? {
    if (url.isNullOrBlank() || url.length > 512) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || !chartAlbumHost.matches(uri.host.orEmpty()) ||
        uri.userInfo != null || uri.port != -1 || uri.fragment != null ||
        !uri.path.orEmpty().startsWith("/$storefront/album/") ||
        uri.path.orEmpty().substringAfterLast('/') != albumId.toString()
    ) return null
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
internal fun parseAppleCountryAlbumChart(
    body: String,
    country: String,
    fetchedAtMs: Long,
): AppleCountryAlbumChart? {
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
            if (str("kind") != "albums") continue
            val title = str("name")?.trim()?.takeIf { it.isNotBlank() && it.length <= 220 }
                ?: continue
            val artist = str("artistName")?.trim()?.takeIf {
                it.isNotBlank() && it.length <= 220
            } ?: continue
            val url = chartAppleAlbumUrl(str("url"), country, id) ?: continue
            if (!ids.add(id)) return null // conflicting rank for the same Apple product
            add(AppleChartAlbum(
                position = index + 1,
                albumId = id,
                country = country,
                title = title,
                artistName = artist,
                artworkUrl = chartAppleArtwork(str("artworkUrl100")),
                officialUrl = url,
            ))
        }
    }
    if (valid.isEmpty()) return null
    return AppleCountryAlbumChart(territory, valid, fetchedAtMs)
}

internal fun interface AppleCountryAlbumChartTransport {
    suspend fun get(country: String): String
}

internal class HttpAppleCountryAlbumChartTransport(
    private val client: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000L
            requestTimeoutMillis = 18_000L
            socketTimeoutMillis = 18_000L
        }
    },
): AppleCountryAlbumChartTransport {
    override suspend fun get(country: String): String {
        require(knownAppleChartTerritory(country) != null)
        val response = client.get(
            "https://rss.applemarketingtools.com/api/v2/$country/music/most-played/100/albums.json"
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
internal class AppleCountryAlbumChartRepository(
    private val transport: AppleCountryAlbumChartTransport = HttpAppleCountryAlbumChartTransport(),
    private val admission: RateLimitedProviderTransport = RateLimitedProviderTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun read(country: String): AppleCountryAlbumChartRead {
        if (knownAppleChartTerritory(country) == null)
            return AppleCountryAlbumChartRead.Failure(
                AppleCountryAlbumChartRead.Reason.UNSUPPORTED_TERRITORY)
        if (!admission.permitted(ProviderCapability.CHART_MOST_PLAYED, BuiltInProviderKeys.ITUNES))
            return AppleCountryAlbumChartRead.Failure(AppleCountryAlbumChartRead.Reason.NOT_AUTHORIZED)
        return try {
            val raw = admission.execute(
                capability = ProviderCapability.CHART_MOST_PLAYED,
                provider = BuiltInProviderKeys.ITUNES,
                scheduleLimited = { block -> block() },
                networkCall = { transport.get(country) },
            )
            val chart = parseAppleCountryAlbumChart(raw, country, clock())
            if (chart == null) AppleCountryAlbumChartRead.Failure(
                AppleCountryAlbumChartRead.Reason.INVALID_RESPONSE,
            ) else AppleCountryAlbumChartRead.Success(chart)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
            AppleCountryAlbumChartRead.Failure(AppleCountryAlbumChartRead.Reason.UNAVAILABLE)
        }
    }
}
