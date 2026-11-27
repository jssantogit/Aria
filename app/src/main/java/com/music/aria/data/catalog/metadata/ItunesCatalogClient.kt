package com.music.aria.data.catalog.metadata

import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderPolicyGate
import com.music.aria.data.providers.ProviderRuntime
import com.music.aria.data.providers.RateLimitedProviderTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Public iTunes Search API only. This is an independent, storefront-scoped
 * metadata catalog, NOT Apple Music playback or an entitlement to promo assets.
 *
 * No external result is a MusicBrainz identity, a playable MediaAsset, an
 * official MusicBrainz classification, or permission to republish cover art.
 */
internal enum class ItunesCatalogEntity(val apiValue: String) {
    ARTIST("musicArtist"),
    ALBUM("album"),
    SONG("song"),
}

/** Documented PUBLIC Search API attributes, not invented offset pagination. */
internal enum class ItunesCatalogSearchAttribute(val apiValue: String) {
    /** Apple-documented genre field; not a free-text album-title search. */
    GENRE_INDEX("genreIndex"),
    ARTIST_TERM("artistTerm"),
    ALBUM_TERM("albumTerm"),
    SONG_TERM("songTerm"),
}

internal enum class ItunesCatalogItemType(val key: String) {
    ARTIST("artist"),
    ALBUM("album"),
    TRACK("track"),
}

internal data class ItunesCatalogItem(
    val type: ItunesCatalogItemType,
    val id: Long,
    val storefront: String,
    val title: String,
    val artistName: String?,
    val artistId: Long?,
    val collectionId: Long?,
    val storeUrl: String?,
    val releaseDate: String?,
    val genre: String?,
    val durationMs: Long?,
    val trackNumber: Int?,
    val discNumber: Int?,
    /** Edition-scoped remote artwork URL, for private experimental presentation. */
    val artworkUrl: String? = null,
    val upc: String? = null,
    val trackCount: Int? = null,
    /** Source collection label on a song, a discovery hint only. */
    val collectionTitle: String? = null,
) {
    val scopedId: String get() = "itunes:$storefront:${type.key}:$id"

    // Deliberately not derived from titles, ISRC, UPC, artwork or a store ID.
    val musicBrainzId: String? get() = null
    val isPlayable: Boolean get() = false

    // No audio asset or preview is ever created from storefront metadata.
    val previewUrl: String? get() = null
}

internal enum class ItunesCatalogFailure {
    INVALID_QUERY,
    UNAVAILABLE,
    MALFORMED_RESPONSE,
}

/** Counts only, never raw source payloads or IDs. Kept separate from
 * completeness: a collection count may include rows outside the song entity. */
internal data class ItunesAlbumLookupObservation(
    val declaredResults: Int,
    val rawRows: Int,
    val matchingSongRows: Int,
    val matchingNonSongRows: Int,
    val foreignSongRows: Int,
    val rejectedSongRows: Int,
    val removedDuplicateOrDisputedSongs: Int,
    /** Exact collection-owned unique video IDs; never returned as media. */
    val verifiedMusicVideoRows: Int = 0,
    /** Explicit collection-owned interactive booklet IDs, never rendered. */
    val verifiedBookletRows: Int = 0,
)

internal sealed interface ItunesCatalogResult {
    data class Success(
        val items: List<ItunesCatalogItem>,
        /** Returned rows filled the API's cap; completeness is unproven. */
        val possiblyTruncated: Boolean = false,
        val albumLookupObservation: ItunesAlbumLookupObservation? = null,
    ) : ItunesCatalogResult
    data class Failure(val reason: ItunesCatalogFailure) : ItunesCatalogResult
}

/** Injected boundary: fake transport tests never send requests or consume rate budget. */
internal interface ItunesCatalogTransport {
    suspend fun get(path: String, parameters: Map<String, String>): String

    /** An editorial Search API request is not automatically a General search. */
    suspend fun get(
        path: String,
        parameters: Map<String, String>,
        capability: ProviderCapability,
    ): String = get(path, parameters)
}

/**
 * Apple documents approximately 20 search/lookup requests per minute. A single
 * process-wide budget serializes starts at >=3.1s spacing, including across
 * independent clients and both endpoints. No automatic retries on HTTP 429.
 */
internal class ItunesCatalogRateLimiter(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val minimumIntervalMs: Long = 3_100L,
) {
    private val mutex = Mutex()
    private var lastStartedMs: Long? = null

    init { require(minimumIntervalMs >= 3_000L) }

    suspend fun <T> run(block: suspend () -> T): T {
        mutex.lock()
        try {
            lastStartedMs?.let { last ->
                val remaining = minimumIntervalMs - (clockMs() - last)
                if (remaining > 0L) sleep(remaining)
            }
            lastStartedMs = clockMs()
            return block()
        } finally {
            mutex.unlock()
        }
    }
}

internal object ItunesCatalogRequestBudget {
    val limiter = ItunesCatalogRateLimiter()
}

internal class KtorItunesCatalogTransport(
    private val client: HttpClient = defaultItunesHttpClient,
    private val limiter: ItunesCatalogRateLimiter = ItunesCatalogRequestBudget.limiter,
    private val admission: RateLimitedProviderTransport = RateLimitedProviderTransport(),
    private val testExchange: (suspend (String, Map<String, String>) -> String)? = null,
) : ItunesCatalogTransport {
    override suspend fun get(path: String, parameters: Map<String, String>): String =
        get(path, parameters, if (path == "search") ProviderCapability.CATALOG_SEARCH
            else ProviderCapability.EDITORIAL_DISCOGRAPHY)

    override suspend fun get(
        path: String,
        parameters: Map<String, String>,
        capability: ProviderCapability,
    ): String {
        require(path == "search" || path == "lookup")
        // Artwork proof may read only the exact album-ID or UPC lookup
        // required to certify an edition. It never authorizes a search,
        // artist discography, arbitrary collection relation or playback.
        require(capability == ProviderCapability.CATALOG_SEARCH ||
            capability == ProviderCapability.EDITORIAL_DISCOGRAPHY ||
            (capability == ProviderCapability.RELEASE_ARTWORK &&
                path == "lookup" && parameters["entity"] == "song" &&
                parameters["limit"] == "200" &&
                parameters["country"]?.matches(Regex("[a-z]{2}")) == true &&
                ((parameters.keys == setOf("id", "country", "entity", "limit") &&
                    parameters["id"]?.toLongOrNull()?.let { it > 0L } == true) ||
                 (parameters.keys == setOf("upc", "country", "entity", "limit") &&
                    parameters["upc"]?.matches(Regex("[0-9]{8,14}")) == true))))
        return admission.execute(
            capability = capability,
            provider = BuiltInProviderKeys.ITUNES,
            scheduleLimited = { block -> limiter.run(block) },
            networkCall = {
                testExchange?.invoke(path, parameters) ?: run {
            // Measure the real HTTP exchange, not the 3.1s global-budget
            // queue wait. Endpoint is strictly "search" or "lookup"; never
            // emit a query, UPC, artist, album, URL or personal identifier.
            val response = measureMetadataHttp<io.ktor.client.statement.HttpResponse>(
                provider = "itunes", endpoint = path,
                status = { it.status.value },
            ) {
                client.get("https://itunes.apple.com/$path") {
                    parameters.forEach { (name, value) -> parameter(name, value) }
                }
            }
            if (response.status.value != 200) {
                response.bodyAsChannel().cancel(IOException("iTunes HTTP ${response.status.value}"))
                throw IOException("iTunes metadata endpoint unavailable")
            }
            val body = response.bodyAsChannel()
            val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > MAX_ITUNES_JSON_BYTES) {
                body.cancel(IOException("Oversized iTunes response"))
                throw IOException("Oversized iTunes response")
            }
            val buffer = ByteArray(8_192)
            val output = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = body.readAvailable(
                    buffer, 0, minOf(buffer.size, MAX_ITUNES_JSON_BYTES - total + 1)
                )
                if (read == -1) break
                if (read == 0) continue
                total += read
                if (total > MAX_ITUNES_JSON_BYTES) {
                    body.cancel(IOException("Oversized iTunes response"))
                    throw IOException("Oversized iTunes response")
                }
                output.write(buffer, 0, read)
            }
            if (declared != null && total.toLong() != declared) {
                throw IOException("Incomplete iTunes response")
            }
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray())).toString()
                }
            },
        )
    }
}

private const val MAX_ITUNES_JSON_BYTES = 512_000

private val defaultItunesHttpClient: HttpClient by lazy {
    HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000L
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = 15_000L
        }
    }
}

/**
 * The Search API has neither offset pagination nor authoritative Album/EP/Single
 * types. Never synthesize missing identity/classification from a track count or
 * title; expose independently sourced rows for deliberate human comparison.
 */
internal class ItunesCatalogClient(
    private val transport: ItunesCatalogTransport = KtorItunesCatalogTransport(),
    private val artworkGateProvider: () -> ProviderPolicyGate? = {
        ProviderRuntime.gateForCacheReadOrNull()
    },
) {
    suspend fun search(
        term: String,
        entity: ItunesCatalogEntity,
        country: String = "br",
        limit: Int = 20,
        attribute: ItunesCatalogSearchAttribute? = null,
        capability: ProviderCapability = ProviderCapability.CATALOG_SEARCH,
    ): ItunesCatalogResult {
        if (capability != ProviderCapability.CATALOG_SEARCH &&
            capability != ProviderCapability.EDITORIAL_DISCOGRAPHY
        ) return ItunesCatalogResult.Failure(ItunesCatalogFailure.INVALID_QUERY)
        val query = term.trim()
        val storefront = normalizedStorefront(country)
        if (query.isEmpty() || query.length > 120 || query.any(Char::isISOControl) ||
            storefront == null || limit !in 1..200
        ) return ItunesCatalogResult.Failure(ItunesCatalogFailure.INVALID_QUERY)
        val params = linkedMapOf(
            "term" to query, "country" to storefront, "media" to "music",
            "entity" to entity.apiValue, "limit" to limit.toString(),
        )
        if (attribute != null) params["attribute"] = attribute.apiValue
        return request("search", params, storefront, expectedType = when (entity) {
            ItunesCatalogEntity.ARTIST -> ItunesCatalogItemType.ARTIST
            ItunesCatalogEntity.ALBUM -> ItunesCatalogItemType.ALBUM
            ItunesCatalogEntity.SONG -> ItunesCatalogItemType.TRACK
        }, capability = capability)
    }

    /** A song can nominate a collection, but only this exact typed metadata
     * lookup may publish the album in Search. It uses Search consent, not
     * discography consent, and requests no song detail or playback assets. */
    suspend fun lookupSearchCollection(
        collectionId: Long,
        country: String,
    ): ItunesCatalogResult {
        val storefront = normalizedStorefront(country)
        if (collectionId <= 0L || storefront == null)
            return ItunesCatalogResult.Failure(ItunesCatalogFailure.INVALID_QUERY)
        return request("lookup", linkedMapOf(
            "id" to collectionId.toString(), "country" to storefront,
        ), storefront, expectedType = ItunesCatalogItemType.ALBUM,
            collectionId = collectionId, capability = ProviderCapability.CATALOG_SEARCH)
    }

    suspend fun lookupAlbum(
        collectionId: Long,
        country: String = "br",
    ): ItunesCatalogResult = lookupAlbumWithCapability(
        collectionId, country, ProviderCapability.EDITORIAL_DISCOGRAPHY,
    )

    /** Internal exact edition evidence only; never a catalog browsing route. */
    suspend fun lookupAlbumForArtwork(
        collectionId: Long, country: String = "br",
    ): ItunesCatalogResult = lookupAlbumWithCapability(
        collectionId, country, ProviderCapability.RELEASE_ARTWORK,
    )

    private suspend fun lookupAlbumWithCapability(
        collectionId: Long, country: String, capability: ProviderCapability,
    ): ItunesCatalogResult {
        val storefront = normalizedStorefront(country)
        if (collectionId <= 0L || storefront == null) {
            return ItunesCatalogResult.Failure(ItunesCatalogFailure.INVALID_QUERY)
        }
        return request("lookup", linkedMapOf(
            "id" to collectionId.toString(), "country" to storefront,
            "entity" to "song", "limit" to "200",
        ), storefront, collectionId = collectionId, capability = capability)
    }

    /**
     * musicTrack is a documented music search entity. Its support on a
     * collection lookup is not guaranteed: callers must verify the exact
     * returned collection and every member, not assume a complete response.
     */
    suspend fun lookupAlbumMusicTracks(
        collectionId: Long,
        country: String = "br",
    ): ItunesCatalogResult {
        val storefront = normalizedStorefront(country)
        if (collectionId <= 0L || storefront == null)
            return ItunesCatalogResult.Failure(ItunesCatalogFailure.INVALID_QUERY)
        return request("lookup", linkedMapOf(
            "id" to collectionId.toString(), "country" to storefront,
            "entity" to "musicTrack", "limit" to "200",
        ), storefront, collectionId = collectionId)
    }

    /**
     * Observational lookup for an album declaring more items than its song
     * response returns. The public musicVideo entity is a probe, not proof
     * that every collection supports this relationship. No video objects,
     * URLs or playback assets escape the existing audio-only decoder.
     */
    suspend fun observeAlbumMusicVideoRows(
        collectionId: Long,
        country: String = "br",
    ): ItunesCatalogResult {
        val storefront = normalizedStorefront(country)
        if (collectionId <= 0L || storefront == null)
            return ItunesCatalogResult.Failure(ItunesCatalogFailure.INVALID_QUERY)
        return request("lookup", linkedMapOf(
            "id" to collectionId.toString(), "country" to storefront,
            "entity" to "musicVideo", "limit" to "200",
        ), storefront, collectionId = collectionId)
    }

    suspend fun lookupArtistAlbums(
        artistId: Long,
        country: String = "br",
        limit: Int = 30,
        capability: ProviderCapability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
    ): ItunesCatalogResult {
        val storefront = normalizedStorefront(country)
        if (artistId <= 0L || storefront == null || limit !in 1..200 ||
            capability !in setOf(ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY)
        ) {
            return ItunesCatalogResult.Failure(ItunesCatalogFailure.INVALID_QUERY)
        }
        return request("lookup", linkedMapOf(
            "id" to artistId.toString(), "country" to storefront,
            "entity" to "album", "limit" to limit.toString(),
        ), storefront, artistId = artistId, capability = capability)
    }

    /** Apple storefront tracks for ONE exact artist ID; never rank by title. */
    suspend fun lookupArtistTracks(
        artistId: Long,
        country: String = "br",
        limit: Int = 40,
    ): ItunesCatalogResult {
        val storefront = normalizedStorefront(country)
        if (artistId <= 0L || storefront == null || limit !in 1..200)
            return ItunesCatalogResult.Failure(ItunesCatalogFailure.INVALID_QUERY)
        return request("lookup", linkedMapOf(
            "id" to artistId.toString(), "country" to storefront,
            "entity" to "song", "limit" to limit.toString(),
        ), storefront, artistId = artistId, expectedType = ItunesCatalogItemType.TRACK)
    }

    /** UPC is only a candidate identity signal; never silently claim an MBID. */
    suspend fun lookupUpc(upc: String, country: String = "br"): ItunesCatalogResult =
        lookupUpcWithCapability(upc, country, ProviderCapability.EDITORIAL_DISCOGRAPHY)

    /** Internal UPC evidence for artwork; no change to catalog permissions. */
    suspend fun lookupUpcForArtwork(upc: String, country: String = "br"): ItunesCatalogResult =
        lookupUpcWithCapability(upc, country, ProviderCapability.RELEASE_ARTWORK)

    private suspend fun lookupUpcWithCapability(
        upc: String, country: String, capability: ProviderCapability,
    ): ItunesCatalogResult {
        val storefront = normalizedStorefront(country)
        if (storefront == null || !Regex("[0-9]{8,14}").matches(upc)) {
            return ItunesCatalogResult.Failure(ItunesCatalogFailure.INVALID_QUERY)
        }
        return request("lookup", linkedMapOf(
            "upc" to upc, "country" to storefront, "entity" to "song", "limit" to "200",
        ), storefront, capability = capability)
    }

    private suspend fun request(
        path: String,
        params: Map<String, String>,
        storefront: String,
        expectedType: ItunesCatalogItemType? = null,
        collectionId: Long? = null,
        artistId: Long? = null,
        capability: ProviderCapability = if (path == "search")
            ProviderCapability.CATALOG_SEARCH else ProviderCapability.EDITORIAL_DISCOGRAPHY,
    ): ItunesCatalogResult {
        val json = try {
            transport.get(path, params, capability)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ItunesCatalogResult.Failure(ItunesCatalogFailure.UNAVAILABLE)
        }
        return try {
            val root = Json.parseToJsonElement(json) as? JsonObject
                ?: return ItunesCatalogResult.Failure(ItunesCatalogFailure.MALFORMED_RESPONSE)
            val count = (root["resultCount"] as? JsonPrimitive)?.intOrNull
            val rows = root["results"] as? JsonArray
            if (count == null || count < 0 || rows == null || rows.size > 201 ||
                count < rows.size
            ) return ItunesCatalogResult.Failure(ItunesCatalogFailure.MALFORMED_RESPONSE)
            val filtered = rows.mapNotNull { (it as? JsonObject)?.toCatalogItem(storefront) }
                .filter { expectedType == null || it.type == expectedType }
                .filter { collectionId == null ||
                    (it.type == ItunesCatalogItemType.ALBUM && it.id == collectionId) ||
                    (it.type == ItunesCatalogItemType.TRACK && it.collectionId == collectionId)
                }
                .filter { artistId == null ||
                    (it.type == ItunesCatalogItemType.ARTIST && it.id == artistId) ||
                    (it.type == ItunesCatalogItemType.ALBUM && it.artistId == artistId) ||
                    (it.type == ItunesCatalogItemType.TRACK && it.artistId == artistId)
                }
            // First-wins dedup must never hide conflicting commercial identity
            // from a single Apple response. Optional enrichment differences
            // are not identity disputes (e.g. one row lacks a duration).
            val disputed = filtered.groupBy { it.scopedId }
                .filterValues { snapshots ->
                    val first = snapshots.first()
                    snapshots.any { row ->
                        row.title != first.title || row.artistId != first.artistId ||
                            row.collectionId != first.collectionId ||
                            row.artistName != first.artistName
                    }
                }.keys
            val items = filtered.filterNot { it.scopedId in disputed }
                .distinctBy { it.scopedId }
            val requestedLimit = params["limit"]?.toIntOrNull()
            val albumObservation = collectionId?.let { requested ->
                val rawTracks = rows.mapNotNull { it as? JsonObject }
                    .filter { it.text("wrapperType") == "track" }
                val matching = rawTracks.filter { it.positiveId("collectionId") == requested }
                val matchingSongs = matching.count { it.text("kind") == "song" }
                val parsedSongs = filtered.count { it.type == ItunesCatalogItemType.TRACK }
                // Count only explicit known non-audio kinds, with one unique
                // positive ID across ALL raw track rows. A collision or an
                // unknown kind cannot be treated as a harmless missing song.
                val rawIdCounts = rawTracks.mapNotNull { it.positiveId("trackId") }
                    .groupingBy { it }.eachCount()
                fun verifiedNonAudio(kind: String): Int = matching.count {
                    it.text("kind") == kind &&
                        it.positiveId("trackId")?.let { id -> rawIdCounts[id] == 1 } == true
                }
                val verifiedVideos = verifiedNonAudio("music-video")
                val verifiedBooklets = verifiedNonAudio("interactive-booklet")
                ItunesAlbumLookupObservation(
                    declaredResults = count,
                    rawRows = rows.size,
                    matchingSongRows = matchingSongs,
                    matchingNonSongRows = matching.count {
                        it.text("kind")?.let { kind -> kind != "song" } == true
                    },
                    foreignSongRows = rawTracks.count {
                        it.text("kind") == "song" && it.positiveId("collectionId") != requested
                    },
                    rejectedSongRows = (matchingSongs - parsedSongs).coerceAtLeast(0),
                    removedDuplicateOrDisputedSongs = parsedSongs -
                        items.count { it.type == ItunesCatalogItemType.TRACK },
                    verifiedMusicVideoRows = verifiedVideos,
                    verifiedBookletRows = verifiedBooklets,
                )
            }
            // The editorial listing and Apple CDN imagery have separate opt-
            // outs. Keep catalog metadata visible without publishing images
            // (including low-res fallbacks) from a revoked artwork capability.
            val artGate = artworkGateProvider()
            val artTicket = artGate?.open(
                ProviderCapability.RELEASE_ARTWORK, BuiltInProviderKeys.ITUNES,
            )
            val permittedArtwork = artTicket != null && artGate?.accept(artTicket) == true
            ItunesCatalogResult.Success(
                items = if (permittedArtwork) items else items.map {
                    it.copy(artworkUrl = null)
                },
                possiblyTruncated = disputed.isNotEmpty() ||
                    (requestedLimit != null && rows.size >= requestedLimit) ||
                    count > rows.size,
                albumLookupObservation = albumObservation,
            )
        } catch (_: Exception) {
            ItunesCatalogResult.Failure(ItunesCatalogFailure.MALFORMED_RESPONSE)
        }
    }
}

private fun normalizedStorefront(country: String): String? =
    country.trim().lowercase(Locale.ROOT).takeIf { Regex("[a-z]{2}").matches(it) }

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.positiveId(key: String): Long? =
    (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }

private fun JsonObject.positiveNumber(key: String): Int? =
    (get(key) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

private fun safeStoreLink(raw: String?): String? {
    if (raw == null) return null
    return try {
        val uri = URI(raw)
        if (uri.scheme == "https" && uri.userInfo == null &&
            uri.host?.lowercase(Locale.ROOT) in setOf("music.apple.com", "itunes.apple.com")
        ) raw else null
    } catch (_: Exception) { null }
}

/**
 * Store badges must open the very Apple product whose cover they accompany.
 * Apple URLs can be /br/album/name/123, /br/album/id123, and for songs
 * /br/album/name/123?i=456. A trusted host alone is insufficient: another
 * album on that host would still make a misleading attribution.
 *
 * No URL is ever synthesized from a title or MusicBrainz ID. Unknown route
 * conventions fail closed (artwork can still be displayed without a badge).
 */
internal fun appleStoreLinkForExactProduct(
    raw: String?,
    kind: ItunesCatalogItemType,
    id: Long,
    storefront: String,
): String? {
    if (raw.isNullOrBlank() || raw.length > 2048 ||
        id <= 0L || !Regex("^[a-z]{2}$").matches(storefront)
    ) return null
    val uri = try { URI(raw) } catch (_: Exception) { return null }
    if (uri.scheme != "https" || uri.userInfo != null ||
        (uri.port != -1 && uri.port != 443) ||
        uri.host?.lowercase(Locale.ROOT) !in setOf(
            "itunes.apple.com", "music.apple.com",
        ) || uri.rawFragment != null
    ) return null
    val parts = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
    if (parts.size < 3 || !parts[0].equals(storefront, ignoreCase = true))
        return null
    val routeKind = parts[1].lowercase(Locale.ROOT)
    val numeric = parts.last().removePrefix("id").toLongOrNull()
    val trackQueries = uri.rawQuery.orEmpty().split('&')
        .filter { it.startsWith("i=") }
    if (trackQueries.size > 1) return null
    val linkedTrack = trackQueries.singleOrNull()?.removePrefix("i=")
        ?.toLongOrNull()
    return when (kind) {
        ItunesCatalogItemType.ALBUM ->
            raw.takeIf { routeKind == "album" && numeric == id &&
                linkedTrack == null && trackQueries.isEmpty()
            }
        ItunesCatalogItemType.ARTIST ->
            raw.takeIf { routeKind == "artist" && numeric == id &&
                linkedTrack == null && trackQueries.isEmpty()
            }
        ItunesCatalogItemType.TRACK ->
            raw.takeIf { (routeKind == "album" && linkedTrack == id) ||
                (routeKind == "song" && numeric == id && trackQueries.isEmpty())
            }
    }
}

internal fun safeAppleArtwork(raw: String?): String? {
    if (raw == null) return null
    return try {
        val uri = URI(raw)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (uri.scheme == "https" && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            host != null && host.endsWith(".mzstatic.com") &&
            host.length > ".mzstatic.com".length &&
            uri.path?.let { path ->
                listOf(".jpg", ".jpeg", ".png", ".webp").any {
                    path.lowercase(Locale.ROOT).endsWith(it)
                }
            } == true
        ) raw else null
    } catch (_: Exception) { null }
}

private fun JsonObject.toCatalogItem(storefront: String): ItunesCatalogItem? {
    val wrapper = text("wrapperType")
    val kind = when (wrapper) {
        "artist" -> ItunesCatalogItemType.ARTIST
        "collection" -> if (text("collectionType")?.equals("Album", true) != false)
            ItunesCatalogItemType.ALBUM else return null
        "track" -> if (text("kind") == "song") ItunesCatalogItemType.TRACK
            else return null
        else -> return null
    }
    val id = positiveId(when (kind) {
        ItunesCatalogItemType.ARTIST -> "artistId"
        ItunesCatalogItemType.ALBUM -> "collectionId"
        ItunesCatalogItemType.TRACK -> "trackId"
    }) ?: return null
    val artist = text("artistName")
    val title = when (kind) {
        ItunesCatalogItemType.ARTIST -> artist
        ItunesCatalogItemType.ALBUM -> text("collectionName")
        ItunesCatalogItemType.TRACK -> text("trackName")
    }?.takeIf { it.length <= 300 } ?: return null

    return ItunesCatalogItem(
        type = kind, id = id, storefront = storefront, title = title,
        artistName = artist, artistId = positiveId("artistId"),
        collectionId = positiveId("collectionId"),
        storeUrl = appleStoreLinkForExactProduct(
            safeStoreLink(when (kind) {
                ItunesCatalogItemType.ARTIST -> text("artistLinkUrl") ?: text("artistViewUrl")
                ItunesCatalogItemType.ALBUM -> text("collectionViewUrl")
                ItunesCatalogItemType.TRACK -> text("trackViewUrl")
            }),
            kind, id, storefront,
        ),
        releaseDate = text("releaseDate")?.takeIf { it.length <= 40 },
        genre = text("primaryGenreName")?.takeIf { it.length <= 100 },
        durationMs = if (kind == ItunesCatalogItemType.TRACK)
            positiveId("trackTimeMillis") else null,
        trackNumber = positiveNumber("trackNumber"),
        discNumber = positiveNumber("discNumber"),
        artworkUrl = safeAppleArtwork(text("artworkUrl100"))
            ?: safeAppleArtwork(text("artworkUrl60"))
            ?: safeAppleArtwork(text("artworkUrl30")),
        upc = text("upc")?.takeIf { Regex("[0-9]{8,14}").matches(it) },
        trackCount = positiveNumber("trackCount"),
        collectionTitle = if (kind == ItunesCatalogItemType.TRACK)
            text("collectionName")?.takeIf { it.length <= 300 && it.none(Char::isISOControl) }
        else null,
    )
}
