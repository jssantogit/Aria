package com.music.bitchord.data.catalog.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Fallback from an independent, public Apple source: iTunes Store's
 * top-SOLD tracks, not Apple Music streaming counts or user recommendations.
 * Only use when the newer Apple Music Marketing RSS is sparse/unavailable.
 * Its provenance is recorded separately from most-played chart entries.
 */
private fun JsonObject.appleStoreText(field: String): String? =
    (this[field] as? JsonPrimitive)?.contentOrNull
        ?.trim()?.takeIf { it.isNotBlank() && it.length <= 350 && it.none(Char::isISOControl) }

private fun JsonObject.appleStoreNestedLabel(field: String): String? =
    (this[field] as? JsonObject)?.appleStoreText("label")

private fun appleStoreChartLink(raw: String?, market: String): URI? {
    if (raw.isNullOrBlank() || raw.length > 2048) return null
    return try {
        URI(raw).takeIf { uri ->
            uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
                uri.rawFragment == null && uri.host?.lowercase(Locale.ROOT) in
                    setOf("itunes.apple.com", "music.apple.com") &&
                uri.path.orEmpty().startsWith("/$market/")
        }
    } catch (_: Exception) { null }
}

private fun appleStoreNumericalId(segment: String): Long? =
    segment.removePrefix("id").toLongOrNull()?.takeIf { it > 0L }

private fun verifiedStoreTrackUrl(
    raw: String?, market: String, trackId: Long,
): Boolean {
    val uri = appleStoreChartLink(raw, market) ?: return false
    val parts = uri.path.split('/').filter { it.isNotBlank() }
    if (parts.size < 3 || parts.first() != market ||
        parts[1] !in setOf("album", "song")) return false
    val product = appleStoreNumericalId(parts.last()) ?: return false
    val trackParams = uri.rawQuery.orEmpty().split('&')
        .filter { it.startsWith("i=") }
    return when (parts[1]) {
        "album" -> product > 0L &&
            trackParams.size == 1 && trackParams.single() == "i=$trackId"
        "song" -> product == trackId && trackParams.isEmpty()
        else -> false
    }
}

private fun appleStoreArtistId(link: String?, market: String): Long? {
    val uri = appleStoreChartLink(link, market) ?: return null
    val parts = uri.path.split('/').filter { it.isNotBlank() }
    if (parts.size < 3 || parts.first() != market ||
        parts[1] != "artist") return null
    return appleStoreNumericalId(parts.last())
}

internal object AppleStoreSalesChartParser {
    fun parse(payload: String, market: String): List<ApplePublicChartEntry>? {
        if (!Regex("[a-z]{2}").matches(market) || payload.length > 900_000)
            return null
        val feed = try {
            (Json.parseToJsonElement(payload) as? JsonObject)
                ?.get("feed") as? JsonObject
        } catch (_: Exception) { null } ?: return null
        val entries = feed["entry"] as? JsonArray ?: return null
        if (entries.size > 200) return null
        val values = entries.mapIndexedNotNull { rank, entry ->
            val song = entry as? JsonObject ?: return@mapIndexedNotNull null
            val idBlock = song["id"] as? JsonObject
                ?: return@mapIndexedNotNull null
            val trackId = (idBlock["attributes"] as? JsonObject)
                ?.appleStoreText("im:id")?.toLongOrNull()
                ?.takeIf { it > 0L } ?: return@mapIndexedNotNull null
            if (!verifiedStoreTrackUrl(idBlock.appleStoreText("label"), market, trackId))
                return@mapIndexedNotNull null
            val title = song.appleStoreNestedLabel("im:name")
                ?: return@mapIndexedNotNull null
            val artist = song["im:artist"] as? JsonObject
                ?: return@mapIndexedNotNull null
            val name = artist.appleStoreText("label")
                ?: return@mapIndexedNotNull null
            val artistId = appleStoreArtistId(
                (artist["attributes"] as? JsonObject)?.appleStoreText("href"),
                market,
            )
            val images = song["im:image"] as? JsonArray
            val artwork = images?.mapNotNull {
                (it as? JsonObject)?.appleStoreText("label")
                    ?.let(::safeAppleArtwork)
            }?.lastOrNull()
            val genre = ((song["category"] as? JsonObject)
                ?.get("attributes") as? JsonObject)?.appleStoreText("term")
            val date = song.appleStoreNestedLabel("im:releaseDate")
                ?.take(10)?.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) }
            ApplePublicChartEntry(
                storefront = market,
                kind = ApplePublicChartKind.SONGS,
                id = trackId,
                rank = rank + 101, // Always after valid streaming positions.
                name = title,
                artistName = name,
                artistId = artistId,
                primaryGenre = genre,
                releaseDate = date,
                artwork = artwork,
                source = ApplePublicChartSource.ITUNES_STORE_TOP_SALES,
            )
        }
        val conflicted = values.groupBy { it.scopedId }
            .filterValues { rows ->
                rows.any { it.name != rows.first().name ||
                    it.artistName != rows.first().artistName }
            }.keys
        return values.filterNot { it.scopedId in conflicted }
            .distinctBy { it.scopedId }
    }
}

internal fun interface AppleStoreSalesChartTransport {
    suspend fun get(market: String): String
}

private class KtorAppleStoreSalesChartTransport : AppleStoreSalesChartTransport {
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000L
            connectTimeoutMillis = 6_000L
            socketTimeoutMillis = 12_000L
        }
    }

    override suspend fun get(market: String): String {
        require(Regex("[a-z]{2}").matches(market))
        val response = measureMetadataHttp<io.ktor.client.statement.HttpResponse>(
            provider = "itunes_store_rss", endpoint = "topsongs",
            status = { it.status.value },
        ) { http.get("https://itunes.apple.com/$market/rss/topsongs/limit=100/json") }
        val body = response.bodyAsChannel()
        if (response.status.value != 200 ||
            (response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) > 900_000L
        ) {
            body.cancel(IOException("iTunes Store sales chart rejected"))
            throw IOException("Sales chart unavailable")
        }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = body.readAvailable(buf, 0, minOf(buf.size, 900_001 - out.size()))
            if (n < 0) break
            if (n == 0) continue
            if (out.size() + n > 900_000) {
                body.cancel(IOException("Oversized sales chart"))
                throw IOException("Oversized sales chart")
            }
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

internal class AppleStoreSalesChartsClient(
    private val transport: AppleStoreSalesChartTransport = KtorAppleStoreSalesChartTransport(),
) {
    suspend fun load(market: String): List<ApplePublicChartEntry>? {
        if (!Regex("[a-z]{2}").matches(market)) return null
        val payload = try { transport.get(market) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        return AppleStoreSalesChartParser.parse(payload, market)
    }
}
