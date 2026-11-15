package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.data.DebugLog as Log

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Public Apple Marketing Tools RSS charts, never a MusicKit/developer-token endpoint. */
internal enum class ApplePublicChartKind(val segment: String) {
    SONGS("songs"), ALBUMS("albums"),
}

/** Distinguish streaming charts from the legacy iTunes Store sales fallback. */
internal enum class ApplePublicChartSource {
    APPLE_MUSIC_MOST_PLAYED, ITUNES_STORE_TOP_SALES,
}

internal data class ApplePublicChartEntry(
    val storefront: String,
    val kind: ApplePublicChartKind,
    val id: Long,
    /** One-based position in this country-specific published chart. */
    val rank: Int,
    val name: String,
    val artistName: String,
    val artistId: Long?,
    val primaryGenre: String?,
    val releaseDate: String?,
    val artwork: String?,
    val source: ApplePublicChartSource = ApplePublicChartSource.APPLE_MUSIC_MOST_PLAYED,
) {
    val scopedId: String
        get() = "itunes:$storefront:${if (kind == ApplePublicChartKind.SONGS) "track" else "album"}:$id"
}

internal data class ApplePublicChartsSnapshot(
    val songs: List<ApplePublicChartEntry> = emptyList(),
    val albums: List<ApplePublicChartEntry> = emptyList(),
    val finishedMarkets: Set<String> = emptySet(),
)

private fun JsonObject.chartText(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf {
        it.isNotEmpty() && it.length <= 300 && it.none(Char::isISOControl)
    }

private fun appleChartUri(raw: String?): URI? {
    if (raw.isNullOrBlank() || raw.length > 2048) return null
    return try {
        URI(raw).takeIf {
            it.scheme == "https" && it.userInfo == null &&
                it.port == -1 && it.rawFragment == null &&
                it.host?.lowercase(Locale.ROOT) in setOf(
                    "music.apple.com", "itunes.apple.com",
                )
        }
    } catch (_: Exception) { null }
}

private fun appleChartArtistId(url: String?, market: String): Long? {
    val uri = appleChartUri(url) ?: return null
    val sections = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
    if (sections.size < 3 || sections.first() != market ||
        sections[1] != "artist" || uri.rawQuery != null
    ) return null
    return sections.last().toLongOrNull()?.takeIf { it > 0L }
}

/** Discard chart IDs that do not match the official storefront product URL. */
private fun appleChartProductUrlMatches(
    url: String?, kind: ApplePublicChartKind, market: String, id: Long,
): Boolean {
    val uri = appleChartUri(url) ?: return false
    val sections = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
    if (sections.size < 3 || sections.first() != market ||
        sections[1] !in setOf("album", "song")
    ) return false
    val numeric = sections.last().toLongOrNull()
    val query = uri.rawQuery.orEmpty().split('&').filter { it.startsWith("i=") }
    if (query.size > 1) return false
    return when (kind) {
        ApplePublicChartKind.ALBUMS ->
            sections[1] == "album" && numeric == id && query.isEmpty()
        ApplePublicChartKind.SONGS ->
            (sections[1] == "album" && query.singleOrNull() == "i=$id") ||
                (sections[1] == "song" && numeric == id && query.isEmpty())
    }
}

internal object ApplePublicChartParser {
    fun parse(
        payload: String,
        market: String,
        kind: ApplePublicChartKind,
    ): List<ApplePublicChartEntry>? {
        if (!Regex("[a-z]{2}").matches(market) ||
            payload.length > 900_000
        ) return null
        val feed = try {
            (Json.parseToJsonElement(payload) as? JsonObject)
                ?.get("feed") as? JsonObject
        } catch (_: Exception) { null } ?: return null
        if (feed.chartText("country")?.lowercase(Locale.ROOT)
                ?.let { it != market } == true
        ) return null
        val results = feed["results"] as? JsonArray ?: return null
        if (results.size > 200) return null
        val accepted = results.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            if (obj.chartText("kind") != kind.segment) return@mapIndexedNotNull null
            val id = obj.chartText("id")?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@mapIndexedNotNull null
            if (!appleChartProductUrlMatches(obj.chartText("url"), kind, market, id))
                return@mapIndexedNotNull null
            val title = obj.chartText("name") ?: return@mapIndexedNotNull null
            val artist = obj.chartText("artistName") ?: return@mapIndexedNotNull null
            val genres = obj["genres"] as? JsonArray
            val genre = genres?.mapNotNull { (it as? JsonObject)?.chartText("name") }
                ?.firstOrNull { it != "Music" && it.length <= 100 }
            ApplePublicChartEntry(
                storefront = market, kind = kind, id = id, rank = index + 1,
                name = title, artistName = artist,
                artistId = appleChartArtistId(obj.chartText("artistUrl"), market)
                    ?.takeIf { linkedId ->
                        obj.chartText("artistId")?.toLongOrNull()?.let {
                            it == linkedId
                        } ?: true
                    },
                primaryGenre = genre,
                releaseDate = obj.chartText("releaseDate")
                    ?.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) },
                artwork = safeAppleArtwork(obj.chartText("artworkUrl100")),
            )
        }
        val disputed = accepted.groupBy { it.scopedId }
            .filterValues { items ->
                items.any { it.name != items[0].name ||
                    it.artistName != items[0].artistName }
            }.keys
        return accepted.filterNot { it.scopedId in disputed }
            .distinctBy { it.scopedId }
    }
}

internal fun interface ApplePublicChartTransport {
    suspend fun get(market: String, kind: ApplePublicChartKind): String
}

private const val MAX_CHART_RESPONSE_BYTES = 900_000

/**
 * The legacy rss.applemarketingtools.com hostname was replaced by the
 * rss.marketingtools.apple.com host. Prefer the published current hostname,
 * while retaining a bounded legacy attempt for older network paths.
 * Every URL is generated from a validated market and fixed chart kind.
 */
internal fun applePublicChartFeedUrls(
    market: String, kind: ApplePublicChartKind,
): List<String> {
    if (!Regex("[a-z]{2}").matches(market)) return emptyList()
    val suffix = "/api/v2/$market/music/most-played/100/${kind.segment}.json"
    return listOf(
        "https://rss.marketingtools.apple.com$suffix",
        "https://rss.applemarketingtools.com$suffix",
    )
}

/** Only fixed two-letter market and enum endpoints; no caller-supplied URL. */
private class AppleMarketingRssTransport : ApplePublicChartTransport {
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000L
            connectTimeoutMillis = 6_000L
            socketTimeoutMillis = 12_000L
        }
    }

    override suspend fun get(market: String, kind: ApplePublicChartKind): String {
        require(Regex("[a-z]{2}").matches(market))
        var lastFailure: Exception? = null
        for (url in applePublicChartFeedUrls(market, kind)) {
            try {
                return readAppleChartResponse(url, kind)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { lastFailure = error }
        }
        throw lastFailure ?: java.io.IOException("Public Apple chart unavailable")
    }

    private suspend fun readAppleChartResponse(
        url: String, kind: ApplePublicChartKind,
    ): String {
        val response = measureMetadataHttp<io.ktor.client.statement.HttpResponse>(
            provider = "apple_marketing_rss",
            endpoint = kind.segment,
            status = { it.status.value },
        ) { http.get(url) }
        if (response.status.value != 200) {
            response.bodyAsChannel().cancel(java.io.IOException("Apple chart response rejected"))
            error("Public Apple chart unavailable")
        }
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > MAX_CHART_RESPONSE_BYTES) {
            response.bodyAsChannel().cancel(java.io.IOException("Apple chart response rejected"))
            error("Oversized Apple chart")
        }
        val out = ByteArrayOutputStream()
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8192)
        while (true) {
            val n = channel.readAvailable(
                buffer, 0, minOf(buffer.size, MAX_CHART_RESPONSE_BYTES - out.size() + 1),
            )
            if (n < 0) break
            if (n == 0) continue
            if (out.size() + n > MAX_CHART_RESPONSE_BYTES) {
                channel.cancel(java.io.IOException("Oversized Apple chart"))
                error("Oversized Apple chart")
            }
            out.write(buffer, 0, n)
        }
        if (declared != null && out.size().toLong() != declared) {
            error("Incomplete Apple chart")
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

internal class ApplePublicChartsClient(
    private val transport: ApplePublicChartTransport = AppleMarketingRssTransport(),
) {
    suspend fun load(market: String, kind: ApplePublicChartKind): List<ApplePublicChartEntry>? {
        if (!Regex("[a-z]{2}").matches(market)) return null
        val payload = try {
            transport.get(market, kind)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
              Log.d("AppleChart", "storefront=$market kind=${kind.segment} fetch=failed")
              return null
          }
        val parsed = ApplePublicChartParser.parse(payload, market, kind)
        // Only market, kind, yield and payload byte count; no user data,
        // title, artist, URL or identifying catalog product is logged.
        Log.d("AppleChart",
            "storefront=$market kind=${kind.segment} bytes=${payload.length} " +
                "parse=${if (parsed == null) "invalid" else "ok"} rows=${parsed?.size ?: 0}")
        return parsed
    }
}
