package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.providers.ProviderCapability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

/** Storefront-scoped catalog resources, not MBIDs, personal media, or streams. */
@kotlinx.serialization.Serializable
internal enum class AppleMusicWebKind(val apiType: String, val identityType: String) {
    SONG("songs", "song"),
    ALBUM("albums", "album"),
    ARTIST("artists", "artist"),
    PLAYLIST("playlists", "playlist"),
    MUSIC_VIDEO("music-videos", "music_video"),
    STATION("stations", "station"),
    CURATOR("curators", "curator"),
    LABEL("record-labels", "label"),
}

@kotlinx.serialization.Serializable
internal data class AppleMusicWebItem(
    val id: String,
    val kind: AppleMusicWebKind,
    val storefront: String,
    val title: String,
    val artistName: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val artworkUrl: String? = null,
    val storeUrl: String? = null,
    val isrc: String? = null,
    val upc: String? = null,
    val releaseDate: String? = null,
    val trackCount: Int? = null,
    val isSingle: Boolean? = null,
    val isCompilation: Boolean? = null,
    val durationMillis: Long? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val genreNames: List<String> = emptyList(),
    val albumName: String? = null,
    val editorialTallHeroUrl: String? = null,
    val editorialMotionUrl: String? = null,
    val editorialMotionFallbackUrl: String? = null,
    val playlistType: String? = null,
) {
    val scopedId: String get() = "apple_music_web:${storefront}:${kind.identityType}:$id"
    val playable: Boolean get() = false
    val musicBrainzId: String? get() = null
}

internal data class AppleMusicWebGenre(val id: String, val storefront: String, val title: String)
internal data class AppleMusicWebChartPage(
    val name: String?, val storefront: String, val genreId: String?,
    val kind: AppleMusicWebKind, val items: List<AppleMusicWebItem>,
    val nextCursor: String?,
)

internal data class AppleMusicWebPage(
    val items: List<AppleMusicWebItem>, val page: Int, val limit: Int, val hasMore: Boolean,
    val nextCursor: String? = null,
    val unresolvedReferences: Int = 0,
)

internal enum class AppleMusicWebArtistShelf(val endpoint: String) {
    ALL_ALBUMS("albums"),
    STUDIO_ALBUMS("view/full-albums"),
    SINGLES_AND_EPS("view/singles"),
    LIVE_ALBUMS("view/live-albums"),
    COMPILATIONS("view/compilation-albums"),
    APPEARS_ON("view/appears-on-albums"),
    ESSENTIAL_ALBUMS("view/featured-albums"),
    LATEST_RELEASE("view/latest-release"),
    TOP_SONGS("view/top-songs"),
    MUSIC_VIDEOS("view/music-videos"),
    ARTIST_PLAYLISTS("view/playlists"),
    FEATURED_PLAYLISTS("view/featured-playlists"),
    SIMILAR_ARTISTS("view/similar-artists"),
}

internal data class AppleMusicWebArtist(
    val identity: AppleMusicWebItem,
    val topSongs: List<AppleMusicWebItem>,
    val albums: List<AppleMusicWebItem>,
    val singlesAndEps: List<AppleMusicWebItem>,
    val similarArtists: List<AppleMusicWebItem>,
    val featuredAlbums: List<AppleMusicWebItem> = emptyList(),
    val artistPlaylists: List<AppleMusicWebItem> = emptyList(),
    val unresolvedShelves: Map<AppleMusicWebArtistShelf, Int> = emptyMap(),
    val latestRelease: AppleMusicWebItem? = null,
    val nextCursors: Map<AppleMusicWebArtistShelf,String> = emptyMap(),
)

/** Exact song resource plus native album linkage; linkage alone is not an album. */
internal data class AppleMusicWebSongCollections(
    val song: AppleMusicWebItem,
    val albumIds: List<String>,
    val hasMore: Boolean,
)

internal data class AppleMusicWebPlaylist(val identity: AppleMusicWebItem, val tracks: List<AppleMusicWebItem>)

internal data class AppleMusicWebAlbum(
    val identity: AppleMusicWebItem,
    val tracks: List<AppleMusicWebItem>,
    val artists: List<AppleMusicWebItem>,
    val possiblyTruncated: Boolean,
)

internal class AppleMusicWebProtocolException(message: String) : IllegalStateException(message)

internal fun interface AppleMusicWebTransport {
    suspend fun get(capability: ProviderCapability, path: String, params: Map<String, String>): String
}

internal data class AppleMusicWebToken(val value: String, val expiresAtEpochSeconds: Long)

/**
 * Parse the public web bundle's AMPWebPlay JWT by claims; this is NOT
 * signature validation or a claim that the API is guaranteed for third parties.
 */
internal object AppleMusicWebTokenParser {
    private val candidates = Regex("""eyJ[A-Za-z0-9_-]{12,}\.eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{16,}""")
    fun pick(bundle: String, nowEpochSeconds: Long): AppleMusicWebToken? =
        candidates.findAll(bundle).mapNotNull { match ->
            val jwt = match.value
            val payload = runCatching {
                Json.parseToJsonElement(String(
                    Base64.getUrlDecoder().decode(jwt.split('.')[1]), Charsets.UTF_8,
                )) as? JsonObject
            }.getOrNull() ?: return@mapNotNull null
            val issuer = (payload["iss"] as? JsonPrimitive)?.contentOrNull
            val expiry = (payload["exp"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: return@mapNotNull null
            if (issuer == "AMPWebPlay" && expiry > nowEpochSeconds + 86400L)
                AppleMusicWebToken(jwt, expiry) else null
        }.firstOrNull()
}

/** Native, no-python catalog client. Upstream network is a separate gated boundary. */
internal class AppleMusicWebCatalog(private val transport: AppleMusicWebTransport) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun storefront(value: String): String =
        value.trim().lowercase(Locale.ROOT).also {
            require(Regex("[a-z]{2}").matches(it)) { "Invalid storefront" }
        }

    private fun resourceId(value: String, kind: AppleMusicWebKind): String {
        val valid = when (kind) {
            AppleMusicWebKind.PLAYLIST -> Regex("pl\\.[A-Za-z0-9-]{6,}").matches(value)
            AppleMusicWebKind.STATION -> Regex("ra\\.[A-Za-z0-9.-]{3,}").matches(value)
            else -> Regex("[0-9]{1,12}").matches(value)
        }
        require(valid) { "Invalid Apple ID" }
        return value
    }

    private fun parse(body: String): JsonObject {
        if (body.length > 2_000_000) throw AppleMusicWebProtocolException("Oversized response")
        return runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: throw AppleMusicWebProtocolException("Invalid catalog JSON")
    }

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.objects(name: String): List<JsonObject> {
        val el = this[name] ?: return emptyList()
        if (el.toString() == "null") return emptyList()
        val values = el as? JsonArray ?: throw AppleMusicWebProtocolException("Invalid array")
        return values.map {
            it as? JsonObject ?: throw AppleMusicWebProtocolException("Invalid catalog item")
        }
    }

    private fun convert(raw: JsonObject, sf: String): AppleMusicWebItem? {
        val kind = AppleMusicWebKind.entries.firstOrNull { it.apiType == raw.str("type") }
            ?: return null
        val id = raw.str("id") ?: throw AppleMusicWebProtocolException("Missing ID")
        val attr = raw.obj("attributes")
            ?: throw AppleMusicWebProtocolException("Missing attributes")
        val name = attr.str("name") ?: attr.str("title")
            ?: throw AppleMusicWebProtocolException("Missing title")
        val editorial = attr.obj("editorialArtwork")
        val tallHero = if (kind == AppleMusicWebKind.PLAYLIST || kind == AppleMusicWebKind.ALBUM)
            listOf("superHeroTall", "staticDetailTall").firstNotNullOfOrNull { key ->
                val art = editorial?.obj(key) ?: return@firstNotNullOfOrNull null
                appleMusicWebTallHeroUrl(art.str("url"),
                    (art["width"] as? JsonPrimitive)?.intOrNull ?: 0,
                    (art["height"] as? JsonPrimitive)?.intOrNull ?: 0)
            } else null
        val motion = if (kind == AppleMusicWebKind.ALBUM || kind == AppleMusicWebKind.PLAYLIST)
            appleMusicWebAlbumMotion(attr.obj("editorialVideo"),tallHero != null) else null
        return AppleMusicWebItem(
            id = id, kind = kind, storefront = sf, title = name,
            editorialTallHeroUrl = tallHero,
            editorialMotionUrl = motion?.first,
            editorialMotionFallbackUrl = motion?.second,
            playlistType = if (kind == AppleMusicWebKind.PLAYLIST) attr.str("playlistType") else null,
            artistName = if (kind == AppleMusicWebKind.PLAYLIST) attr.str("curatorName") else attr.str("artistName"), artistId = attr.str("artistId"),
            albumId = attr.str("albumId"),
            albumName = attr.str("albumName"),
            artworkUrl = attr.obj("artwork")?.str("url")?.takeIf { it.startsWith("https://") },
            storeUrl = attr.str("url")?.takeIf { it.startsWith("https://music.apple.com/") },
            isrc = attr.str("isrc"), upc = attr.str("upc"),
            releaseDate = attr.str("releaseDate"),
            trackCount = (attr["trackCount"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 },
            isSingle = (attr["isSingle"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
            isCompilation = (attr["isCompilation"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
            durationMillis = (attr["durationInMillis"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
            discNumber = (attr["discNumber"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
            trackNumber = (attr["trackNumber"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
            genreNames = (attr["genreNames"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull?.takeIf(String::isNotBlank)
            }.distinct(),
        )
    }

    private fun items(block: JsonObject?, sf: String): List<AppleMusicWebItem> =
        block?.objects("data").orEmpty().mapNotNull { convert(it, sf) }

    private data class ArtistShelfItems(
        val items: List<AppleMusicWebItem>,
        val unresolved: Set<String>,
    )

    private fun artistShelfKinds(shelf: AppleMusicWebArtistShelf): Set<AppleMusicWebKind> =
        when (shelf) {
            AppleMusicWebArtistShelf.TOP_SONGS -> setOf(AppleMusicWebKind.SONG)
            AppleMusicWebArtistShelf.SIMILAR_ARTISTS -> setOf(AppleMusicWebKind.ARTIST)
            AppleMusicWebArtistShelf.MUSIC_VIDEOS -> setOf(AppleMusicWebKind.MUSIC_VIDEO)
            AppleMusicWebArtistShelf.ARTIST_PLAYLISTS,
            AppleMusicWebArtistShelf.FEATURED_PLAYLISTS -> setOf(AppleMusicWebKind.PLAYLIST)
            else -> setOf(AppleMusicWebKind.ALBUM)
        }

    // Relationship linkage is not a materialized catalog row. Validate the
    // source type and native ID even for unresolved references. Never create
    // a label or publish an incomplete resource as a real item.
    private fun artistShelfItems(
        block: JsonObject?, sf: String, shelf: AppleMusicWebArtistShelf,
    ): ArtistShelfItems {
        val rows = mutableListOf<AppleMusicWebItem>()
        val unresolved = linkedSetOf<String>()
        val seen = hashSetOf<String>()
        for (raw in block?.objects("data").orEmpty()) {
            val kind = artistShelfKinds(shelf).firstOrNull { it.apiType == raw.str("type") }
                ?: throw AppleMusicWebProtocolException("Wrong artist shelf resource type")
            val id = raw.str("id") ?: throw AppleMusicWebProtocolException("Missing ID")
            resourceId(id, kind)
            if (!seen.add(id)) throw AppleMusicWebProtocolException("Duplicate artist shelf resource")
            val attributes = raw["attributes"]
            if (attributes == null || attributes.toString() == "null") {
                unresolved += id
            } else {
                // A malformed attributes object is a protocol error, not linkage.
                rows += convert(raw, sf) ?: throw AppleMusicWebProtocolException("Invalid artist shelf resource")
            }
        }
        return ArtistShelfItems(rows, unresolved)
    }

    /** Only Apple's same-route, same-storefront next links are allowed. */
    private fun follow(cursor: String, path: String,
                       term: String? = null, kind: AppleMusicWebKind? = null): Map<String, String> {
        if (cursor.length > 4096) throw AppleMusicWebProtocolException("Oversized cursor")
        val uri = runCatching { URI(cursor) }.getOrNull()
            ?: throw AppleMusicWebProtocolException("Malformed cursor")
        if (uri.path != path || (uri.scheme != null && uri.scheme != "https") ||
            (uri.host != null && uri.host != "amp-api.music.apple.com") ||
            (uri.isAbsolute && uri.host == null) || uri.userInfo != null ||
            uri.fragment != null || (uri.port != -1 && uri.port != 443)
        ) throw AppleMusicWebProtocolException("Untrusted Apple cursor")
        val pairs = linkedMapOf<String, String>()
        for (part in (uri.rawQuery ?: "").split('&')) {
            if (part.isBlank()) continue
            val tokens = part.split('=', limit = 2)
            val key = URLDecoder.decode(tokens[0], "UTF-8")
            val value = URLDecoder.decode(tokens.getOrElse(1) { "" }, "UTF-8")
            if (key in pairs) throw AppleMusicWebProtocolException("Duplicate cursor field")
            pairs[key] = value
        }
        if (pairs.isEmpty() || (term != null && pairs["term"]?.let { it != term } == true) ||
            (kind != null && pairs["types"]?.let { it != kind.apiType } == true)
        ) throw AppleMusicWebProtocolException("Changed cursor query")
        return pairs
    }

    /** The typed results envelope may explicitly contain no matches. */
    private fun searchBlock(body: String, kind: AppleMusicWebKind): JsonObject {
        val response = parse(body)
        if ("errors" in response) throw AppleMusicWebProtocolException("Search error envelope")
        val results = response.obj("results")
            ?: throw AppleMusicWebProtocolException("Missing search results envelope")
        if (results.isEmpty()) return JsonObject(mapOf("data" to JsonArray(emptyList())))
        val block = results.obj(kind.apiType)
            ?: throw AppleMusicWebProtocolException("Missing requested search result type")
        if (block["data"] !is JsonArray)
            throw AppleMusicWebProtocolException("Invalid search data")
        return block
    }

    /** Next offsets are opaque: NEVER calculate page*limit for Apple's web search. */
    suspend fun search(
        kind: AppleMusicWebKind, query: String, country: String,
        page: Int = 1, limit: Int = 25, continuation: String? = null,
    ): AppleMusicWebPage {
        require(page in 1..20 && limit in 1..100)
        require(query.isNotBlank() && query.length <= 120 && query.none(Char::isISOControl))
        require(continuation == null || page > 1)
        val sf = storefront(country)
        val path = "/v1/catalog/$sf/search"
        val base = mapOf("term" to query, "types" to kind.apiType, "limit" to limit.toString())
        suspend fun load(args: Map<String, String>): JsonObject =
            searchBlock(transport.get(ProviderCapability.CATALOG_SEARCH, path, args), kind)
        // Cursor-issued pagination arguments are authoritative. The base fills
        // missing fields only; follow() rejects changes to query/type/origin.
        fun nextArgs(cursor: String) = base + follow(cursor, path, query, kind)
        var block = if (continuation == null) load(base) else load(nextArgs(continuation))
        val seen = mutableSetOf<String>()
        if (continuation != null) seen += continuation
        if (continuation == null) for (index in 1 until page) {
            val cursor = block.str("next") ?: return AppleMusicWebPage(
                emptyList(), page, limit, false,
            )
            if (!seen.add(cursor)) throw AppleMusicWebProtocolException("Cyclic cursor")
            block = load(nextArgs(cursor))
        }
        val cursor = block.str("next")
        if (cursor != null && cursor in seen)
            throw AppleMusicWebProtocolException("Cyclic cursor")
        return AppleMusicWebPage(items(block, sf), page, limit, cursor != null, cursor)
    }

    /** Market-native taxonomy. IDs are not Aria's local genre slugs. */
    suspend fun genres(country: String): List<AppleMusicWebGenre> {
        val sf = storefront(country)
        val body = parse(transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY,
            "/v1/catalog/$sf/genres", emptyMap()))
        if ("errors" in body || body["data"] !is JsonArray)
            throw AppleMusicWebProtocolException("Invalid genre envelope")
        val seen = hashSetOf<String>()
        return body.objects("data").map { row ->
            val id = row.str("id")?.takeIf { it.matches(Regex("[0-9]{1,12}")) }
                ?: throw AppleMusicWebProtocolException("Invalid genre identity")
            if (row.str("type") != "genres" || !seen.add(id))
                throw AppleMusicWebProtocolException("Wrong or duplicate genre")
            val title = row.obj("attributes")?.str("name")
                ?: throw AppleMusicWebProtocolException("Missing genre title")
            AppleMusicWebGenre(id, sf, title)
        }
    }

    /** Only materialized genre relationships of an exact album, not guessed IDs.
     * Bare linkage is insufficient to bind a name to an identifier. */
    suspend fun albumGenres(id: String, country: String): List<AppleMusicWebGenre> {
        val sf = storefront(country)
        val albumId = resourceId(id, AppleMusicWebKind.ALBUM)
        val raw = detail(sf, AppleMusicWebKind.ALBUM, albumId, mapOf("include" to "genres"))
        val block = raw.obj("relationships")?.obj("genres") ?: return emptyList()
        if (block["data"] !is JsonArray)
            throw AppleMusicWebProtocolException("Invalid album genre linkage")
        val genres = block.objects("data").mapNotNull { row ->
            val genreId = row.str("id")?.takeIf { it.matches(Regex("[0-9]{1,12}")) }
                ?: throw AppleMusicWebProtocolException("Invalid genre identity")
            if (row.str("type") != "genres")
                throw AppleMusicWebProtocolException("Wrong album genre linkage type")
            val name = row.obj("attributes")?.str("name") ?: return@mapNotNull null
            AppleMusicWebGenre(genreId, sf, name)
        }
        if (genres.groupBy { it.id }.values.any { rows -> rows.distinct().size > 1 })
            throw AppleMusicWebProtocolException("Conflicting album genre identity")
        return genres.distinct()
    }

    /** Public most-played rank, not a fabricated stream count or daily playlist. */
    suspend fun mostPlayedChart(
        kind: AppleMusicWebKind, country: String, genreId: String? = null,
        limit: Int = 25, cursor: String? = null,
    ): AppleMusicWebChartPage {
        require(kind in setOf(AppleMusicWebKind.SONG, AppleMusicWebKind.ALBUM))
        require(limit in 1..100 && (genreId == null || genreId.matches(Regex("[0-9]{1,12}"))))
        val sf = storefront(country)
        val path = "/v1/catalog/$sf/charts"
        val base = mapOf("types" to kind.apiType, "chart" to "most-played", "limit" to limit.toString()) +
            (genreId?.let { mapOf("genre" to it) } ?: emptyMap())
        fun cursorArgs(next: String): Map<String, String> {
            val args = follow(next, path, kind = kind)
            if (args["genre"]?.let { it != genreId } == true ||
                args["chart"]?.let { it != "most-played" } == true ||
                args.keys.any { it !in setOf("types", "chart", "genre", "limit", "offset", "l") })
                throw AppleMusicWebProtocolException("Changed chart query")
            return base + args
        }
        val response = parse(transport.get(ProviderCapability.CHART_MOST_PLAYED, path,
            cursor?.let(::cursorArgs) ?: base))
        if ("errors" in response) throw AppleMusicWebProtocolException("Chart error envelope")
        val results = response.obj("results") ?: throw AppleMusicWebProtocolException("Missing chart results")
        val blocks = results.objects(kind.apiType)
        val block = blocks.singleOrNull()
            ?: throw AppleMusicWebProtocolException("Missing or ambiguous most-played chart")
        if (block.str("chart")?.let { it != "most-played" } == true || block["data"] !is JsonArray)
            throw AppleMusicWebProtocolException("Invalid chart envelope")
        val seen = hashSetOf<String>()
        val rows = block.objects("data").map { raw ->
            if (raw.str("type") != kind.apiType)
                throw AppleMusicWebProtocolException("Wrong chart resource type")
            val item = convert(raw, sf) ?: throw AppleMusicWebProtocolException("Invalid chart resource")
            resourceId(item.id, kind)
            if (!seen.add(item.scopedId)) throw AppleMusicWebProtocolException("Duplicate chart resource")
            item
        }
        val next = block.str("next")
        if (next != null) {
            cursorArgs(next)
            if (next == cursor) throw AppleMusicWebProtocolException("Cyclic chart cursor")
        }
        return AppleMusicWebChartPage(block.str("name"), sf, genreId, kind, rows, next)
    }

    /** Apple's public chart directory, distinct from the most-played endpoint. */
    suspend fun dailyChartDirectory(): List<AppleMusicWebDailyChartRef> {
        val body = parse(transport.get(ProviderCapability.CHART_MOST_PLAYED,
            "/v1/catalog/us/charts", mapOf("chartId" to "119", "limit" to "200", "l" to "en-US")))
        if ("errors" in body) throw AppleMusicWebProtocolException("Daily chart error envelope")
        val block = body.obj("results")?.objects("dailyGlobalTopCharts")?.singleOrNull()
            ?: throw AppleMusicWebProtocolException("Missing daily chart directory")
        if (block["data"] !is JsonArray) throw AppleMusicWebProtocolException("Invalid daily chart directory")
        val rows = block.objects("data").map { raw ->
            if (raw.str("type") != "playlists") throw AppleMusicWebProtocolException("Wrong daily chart resource")
            val id = resourceId(raw.str("id") ?: throw AppleMusicWebProtocolException("Missing ID"), AppleMusicWebKind.PLAYLIST)
            val name = raw.obj("attributes")?.str("name")
                ?: throw AppleMusicWebProtocolException("Missing daily chart title")
            AppleMusicWebDailyChartRef(id, name,
                raw.obj("attributes")?.obj("artwork")?.str("url"))
        }
        if (rows.distinctBy { it.id }.size != rows.size)
            throw AppleMusicWebProtocolException("Duplicate daily chart resource")
        return rows
    }

    /** Exact chart playlist ID observed in the directory; no foreign-market fallback.
     * Do not silently renumber missing songs or publish a partial chart as Top 100. */
    suspend fun dailyChart(
        region: String, entry: AppleMusicWebDailyChartRef,
    ): AppleMusicWebOutcome<AppleMusicWebDailyChart> {
        require(resolveAppleMusicWebDailyChart(listOf(entry), region) == entry)
        val sf = if (region == "global") "us" else storefront(region)
        val id = resourceId(entry.id, AppleMusicWebKind.PLAYLIST)
        val path = "/v1/catalog/$sf/playlists/$id"
        val body = parse(transport.get(ProviderCapability.CHART_MOST_PLAYED, path,
            mapOf("include" to "tracks", "extend" to "editorialArtwork,editorialVideo")))
        if ("errors" in body) throw AppleMusicWebProtocolException("Daily chart error envelope")
        val root = body.objects("data").singleOrNull()
            ?: throw AppleMusicWebProtocolException("Missing chart playlist")
        if (root.str("id") != id || root.str("type") != "playlists")
            throw AppleMusicWebProtocolException("Wrong chart playlist identity")
        val identity = convert(root, sf) ?: throw AppleMusicWebProtocolException("Invalid chart playlist")
        var block = root.obj("relationships")?.obj("tracks")
            ?: throw AppleMusicWebProtocolException("Missing chart tracklist")
        val rows = mutableListOf<AppleMusicWebItem>()
        val seen = hashSetOf<String>()
        val visited = hashSetOf<String>()
        val allowed = setOf("$path/tracks", "$path/relationships/tracks")
        var incomplete = false
        for (page in 1..5) {
            if (block["data"] !is JsonArray) throw AppleMusicWebProtocolException("Invalid chart tracklist")
            for (raw in block.objects("data")) {
                if (raw.str("type") != "songs") throw AppleMusicWebProtocolException("Wrong daily chart track type")
                val song = convert(raw, sf) ?: throw AppleMusicWebProtocolException("Invalid chart song")
                resourceId(song.id, AppleMusicWebKind.SONG)
                if (!seen.add(song.scopedId)) throw AppleMusicWebProtocolException("Duplicate daily chart song")
                rows += song
                if (rows.size > 100) throw AppleMusicWebProtocolException("Oversized daily chart")
            }
            val next = block.str("next")
            if (next == null) break
            if (rows.size == 100 || page == 5 || block.objects("data").isEmpty()) {
                incomplete = true
                break
            }
            if (!visited.add(next)) throw AppleMusicWebProtocolException("Cyclic daily chart cursor")
            val nextPath = runCatching { URI(next).path }.getOrNull()
                ?: throw AppleMusicWebProtocolException("Malformed daily chart cursor")
            if (nextPath !in allowed) throw AppleMusicWebProtocolException("Foreign daily chart cursor")
            val args = follow(next, nextPath)
            if (args.keys.any { it !in setOf("offset", "limit", "l") })
                throw AppleMusicWebProtocolException("Changed daily chart cursor")
            block = parse(transport.get(ProviderCapability.CHART_MOST_PLAYED, nextPath,
                mapOf("limit" to (100 - rows.size).toString()) + args))
            if ("errors" in block) throw AppleMusicWebProtocolException("Daily chart error envelope")
        }
        val result = AppleMusicWebDailyChart(region, identity, rows)
        return if (incomplete || rows.size != 100)
            AppleMusicWebOutcome.Partial(result, AppleMusicWebCompleteness.PAGE_LIMIT)
        else AppleMusicWebOutcome.Complete(result)
    }

    /** Resolve actual linked editorial rooms, including empty previews. */
    suspend fun browseRooms(country: String, genreId: String? = null): List<AppleMusicWebRoomRef> {
        val sf = storefront(country)
        require(genreId == null || genreId.matches(Regex("[0-9]{1,12}")))
        val args = mapOf("platform" to "web") +
            (if (genreId == null) mapOf("name" to "music", "l" to "en-US") else mapOf("genre" to genreId))
        val body = parse(transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY,
            "/v1/editorial/$sf/groupings", args))
        if ("errors" in body || body["data"] !is JsonArray)
            throw AppleMusicWebProtocolException("Invalid grouping envelope")
        val grouping = body.objects("data").singleOrNull()
            ?: throw AppleMusicWebProtocolException("Missing or ambiguous grouping")
        return groupingRooms(grouping, sf, genreId)
    }

    private fun groupingRooms(grouping: JsonObject, sf: String, genreId: String?,
        curatorId: String? = null): List<AppleMusicWebRoomRef> {
        val tabs = grouping.obj("relationships")?.obj("tabs")
            ?: throw AppleMusicWebProtocolException("Missing grouping tabs")
        if (tabs["data"] !is JsonArray) throw AppleMusicWebProtocolException("Invalid grouping tabs")
        val first = tabs.objects("data").firstOrNull()
            ?: throw AppleMusicWebProtocolException("Missing grouping tab")
        val children = first.obj("relationships")?.obj("children")
            ?: throw AppleMusicWebProtocolException("Missing grouping children")
        if (children["data"] !is JsonArray) throw AppleMusicWebProtocolException("Invalid grouping children")
        return children.objects("data").flatMap { shelf ->
            val attrs = shelf.obj("attributes")
            val title = attrs?.str("name") ?: attrs?.str("title")
            val room = shelf.obj("relationships")?.obj("room")
            if (room != null && room["data"] !is JsonArray)
                throw AppleMusicWebProtocolException("Invalid room linkage")
            if (title == null) emptyList() else room?.objects("data").orEmpty().map { link ->
                val id = link.str("id")?.takeIf { it.matches(Regex("[0-9]{1,12}")) }
                    ?: throw AppleMusicWebProtocolException("Invalid room identity")
                if (link.str("type")?.let { it != "rooms" } == true)
                    throw AppleMusicWebProtocolException("Wrong room linkage type")
                AppleMusicWebRoomRef(sf, genreId, id, title, curatorId)
            }
        }.distinct()
    }

    /** Official editorial brands are a separate resource type from genres and
     * external curators. Never coerce an external curator into an Apple brand. */
    suspend fun searchAppleCurators(query: String, country: String, limit: Int = 25): List<AppleMusicWebCuratorRef> {
        val sf = storefront(country)
        require(query.isNotBlank() && query.length <= 120 && query.none(Char::isISOControl))
        require(limit in 1..25)
        val body = parse(transport.get(ProviderCapability.CATALOG_SEARCH,
            "/v1/catalog/$sf/search", mapOf("term" to query, "types" to "apple-curators", "limit" to limit.toString())))
        if ("errors" in body) throw AppleMusicWebProtocolException("Curator search error")
        val results = body.obj("results") ?: throw AppleMusicWebProtocolException("Missing curator results")
        val block = results.obj("apple-curators")
            ?: if (results.isEmpty()) return emptyList() else throw AppleMusicWebProtocolException("Missing curator result type")
        if (block["data"] !is JsonArray) throw AppleMusicWebProtocolException("Invalid curator results")
        return block.objects("data").map { curatorRef(it, sf) }.distinctBy { it.id }
    }

    private fun curatorRef(raw: JsonObject, sf: String): AppleMusicWebCuratorRef {
        if (raw.str("type") != "apple-curators") throw AppleMusicWebProtocolException("Wrong curator type")
        val id = raw.str("id")?.takeIf { it.matches(Regex("[0-9]{1,12}")) }
            ?: throw AppleMusicWebProtocolException("Invalid curator identity")
        val attr = raw.obj("attributes") ?: throw AppleMusicWebProtocolException("Missing curator attributes")
        val name = attr.str("name") ?: throw AppleMusicWebProtocolException("Missing curator name")
        return AppleMusicWebCuratorRef(id, sf, name, attr.str("shortName"))
    }

    suspend fun appleCurator(id: String, country: String): AppleMusicWebOutcome<AppleMusicWebCurator> {
        val sf = storefront(country)
        require(id.matches(Regex("[0-9]{1,12}")))
        val path = "/v1/catalog/$sf/apple-curators/$id"
        val body = parse(transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY, path,
            mapOf("platform" to "web", "include" to "grouping,playlists")))
        if ("errors" in body || body["data"] !is JsonArray)
            throw AppleMusicWebProtocolException("Invalid curator envelope")
        val raw = body.objects("data").singleOrNull()
            ?: throw AppleMusicWebProtocolException("Missing or ambiguous curator")
        val identity = curatorRef(raw, sf)
        if (identity.id != id) throw AppleMusicWebProtocolException("Wrong curator identity")
        val grouping = raw.obj("relationships")?.obj("grouping")
        if (grouping == null)
            return AppleMusicWebOutcome.Unavailable("Curator has no grouping relationship")
        if (grouping["data"] !is JsonArray) throw AppleMusicWebProtocolException("Invalid curator grouping")
        val groups = grouping.objects("data")
        if (groups.isEmpty()) return AppleMusicWebOutcome.Unavailable("Curator grouping absent")
        val rooms = groups.flatMap { groupingRooms(it, sf, null, id) }.distinct()
        return AppleMusicWebOutcome.Complete(AppleMusicWebCurator(identity, rooms))
    }

    /** Room memberships sometimes omit a playlist's artwork. Only hydrate
     * exact already-linked public playlist IDs, in one bounded metadata read.
     * Never replace membership, order, genre evidence or curator attribution. */
    private suspend fun playlistRoomArtwork(
        sf: String, items: List<AppleMusicWebItem>,
    ): List<AppleMusicWebItem> {
        val missing = items.filter { appleMusicWebArtworkUrl(it.artworkUrl) == null }
        if (missing.isEmpty()) return items
        val covers = linkedMapOf<String,String>()
        for (chunk in missing.chunked(25)) {
            val ids = chunk.map { resourceId(it.id,AppleMusicWebKind.PLAYLIST) }
            val body = try {
                parse(transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY,
                    "/v1/catalog/$sf/playlists",mapOf("ids" to ids.joinToString(","))))
            } catch (failure: AppleMusicWebHttpException) {
                if (failure.status in setOf(401,403,429)) throw failure
                continue
            }
            if ("errors" in body || body["data"] !is JsonArray)
                throw AppleMusicWebProtocolException("Invalid playlist artwork envelope")
            val seen = hashSetOf<String>()
            for (raw in body.objects("data")) {
                val id = raw.str("id")
                if (id == null || id !in ids || raw.str("type") != "playlists" || !seen.add(id))
                    throw AppleMusicWebProtocolException("Wrong playlist artwork identity")
                val artwork = raw.obj("attributes")?.obj("artwork")?.str("url")
                if (appleMusicWebArtworkUrl(artwork) != null) covers[id] = requireNotNull(artwork)
            }
        }
        return items.map { item ->
            covers[item.id]?.let { item.copy(artworkUrl=it) } ?: item
        }
    }

    suspend fun roomContents(
        room: AppleMusicWebRoomRef, kind: AppleMusicWebKind, limit: Int = 25,
        cursor: String? = null,
    ): AppleMusicWebRoomPage {
        require(kind in setOf(AppleMusicWebKind.SONG, AppleMusicWebKind.ALBUM, AppleMusicWebKind.PLAYLIST))
        require(limit in 1..100 && room.id.matches(Regex("[0-9]{1,12}")))
        val sf = storefront(room.storefront)
        val path = "/v1/editorial/$sf/rooms/" + room.id + "/contents"
        // The grouping index requests English for semantic matching, but
        // content uses the storefront's default locale (as in the Python oracle).
        // The source-issued cursor can carry that market's own language.
        val base = mapOf("limit" to limit.toString())
        fun cursorArgs(next: String): Map<String, String> {
            val args = follow(next, path)
            if (args.keys.any { it !in setOf("limit", "offset", "l", "sort") } ||
                args["l"]?.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}")) == false)
                throw AppleMusicWebProtocolException("Changed room cursor")
            return base + args
        }
        val body = parse(transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY, path,
            cursor?.let(::cursorArgs) ?: base))
        if ("errors" in body || body["data"] !is JsonArray)
            throw AppleMusicWebProtocolException("Invalid room contents envelope")
        val raw = body.objects("data")
        var excluded = 0
        var unresolved = 0
        val seen = hashSetOf<String>()
        val rows = raw.mapNotNull { row ->
            if (row.str("type") != kind.apiType) {
                excluded++
                null
            } else {
                val id = resourceId(row.str("id") ?: throw AppleMusicWebProtocolException("Missing ID"), kind)
                if (!seen.add(id)) throw AppleMusicWebProtocolException("Duplicate room resource")
                if (row["attributes"] == null || row["attributes"].toString() == "null") {
                    unresolved++
                    null
                } else convert(row, sf) ?: throw AppleMusicWebProtocolException("Invalid room resource")
            }
        }
        val next = body.str("next")
        if (next != null) {
            cursorArgs(next)
            if (next == cursor) throw AppleMusicWebProtocolException("Cyclic room cursor")
        }
        val projected = if (kind == AppleMusicWebKind.PLAYLIST) playlistRoomArtwork(sf,rows) else rows
        return AppleMusicWebRoomPage(room, kind, projected, raw.size, excluded, unresolved, next)
    }

    private suspend fun detail(
        sf: String, kind: AppleMusicWebKind, id: String, args: Map<String, String>,
    ): JsonObject {
        val path = "/v1/catalog/$sf/${kind.apiType}/$id"
        val results = parse(transport.get(
            ProviderCapability.EDITORIAL_DISCOGRAPHY, path, args,
        )).objects("data")
        val resource = results.singleOrNull()
            ?: throw AppleMusicWebProtocolException("Missing or ambiguous Apple detail")
        if (resource.str("id") != id || resource.str("type") != kind.apiType)
            throw AppleMusicWebProtocolException("Wrong Apple resource identity")
        return resource
    }

    suspend fun artist(id: String, country: String): AppleMusicWebArtist {
        val sf = storefront(country)
        val artistId = resourceId(id, AppleMusicWebKind.ARTIST)
        val raw = detail(sf, AppleMusicWebKind.ARTIST, artistId, mapOf(
            "views" to listOf("latest-release", "top-songs", "featured-albums",
                "full-albums", "singles", "similar-artists", "playlists").joinToString(","),
            "extend" to "artistBio,editorialArtwork,plainEditorialNotes",
            "include" to "genres,station",
        ))
        // Identity is mandatory. If an expanded view only returns root
        // linkage, obtain the same artist directly without expanded views.
        val root = if (raw["attributes"] == null || raw["attributes"].toString() == "null")
            detail(sf, AppleMusicWebKind.ARTIST, artistId, emptyMap()) else raw
        val identity = convert(root, sf) ?: throw AppleMusicWebProtocolException("Invalid artist")
        val views = raw.obj("views")
        val unresolved = linkedMapOf<AppleMusicWebArtistShelf, Int>()
        val cursors = linkedMapOf<AppleMusicWebArtistShelf,String>()
        suspend fun shelf(shelf: AppleMusicWebArtistShelf): List<AppleMusicWebItem> {
            val block = views?.obj(shelf.endpoint.removePrefix("view/"))
            val initial = artistShelfItems(block, sf, shelf)
            if (initial.unresolved.isEmpty()) {
                if (shelf in setOf(AppleMusicWebArtistShelf.STUDIO_ALBUMS,
                    AppleMusicWebArtistShelf.SINGLES_AND_EPS,AppleMusicWebArtistShelf.ESSENTIAL_ALBUMS,
                    AppleMusicWebArtistShelf.ARTIST_PLAYLISTS)) {
                    val path = "/v1/catalog/$sf/artists/$artistId/" + shelf.endpoint
                    block?.str("next")?.let { next ->
                        // Never expose a cursor belonging to another artist/view.
                        if (runCatching { follow(next,path) }.isSuccess) cursors[shelf] = next
                        else unresolved[shelf] = 1
                    }
                }
                return initial.items
            }
            // One bounded, same-artist, same-view recovery. Only hydrate IDs
            // explicitly present in the original shelf; preserve its order.
            val recovered = try {
                val path = "/v1/catalog/$sf/artists/$artistId/" + shelf.endpoint
                val body = transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY, path,
                    mapOf("limit" to minOf(100, maxOf(20, initial.items.size + initial.unresolved.size)).toString()))
                artistShelfItems(parse(body), sf, shelf).items.associateBy { it.id }
            } catch (failure: AppleMusicWebHttpException) {
                if (failure.status in listOf(401, 403, 429)) throw failure
                emptyMap()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                emptyMap()
            }
            val resolved = recovered.toMutableMap()
            // Some song linkage is not expanded even by its own top-songs
            // view. Resolve only exact IDs attested by that view, not a text
            // search or a different market. Bound the extra reads per artist.
            if (shelf == AppleMusicWebArtistShelf.TOP_SONGS) {
                for (id in initial.unresolved.filter { it !in resolved }.take(4)) {
                    val song = try {
                        val record = detail(sf, AppleMusicWebKind.SONG, id, emptyMap())
                        if (record["attributes"] == null || record["attributes"].toString() == "null")
                            null
                        else convert(record, sf)
                    } catch (failure: AppleMusicWebHttpException) {
                        if (failure.status in listOf(401, 403, 429)) throw failure
                        null
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: java.io.IOException) {
                        null
                    }
                    if (song != null) resolved[id] = song
                }
            }
            val original = initial.items.associateBy { it.id }
            val missing = initial.unresolved.count { it !in resolved }
            if (missing > 0) unresolved[shelf] = missing
            return block?.objects("data").orEmpty().mapNotNull { item ->
                val id = item.str("id")
                original[id] ?: if (id in initial.unresolved) resolved[id] else null
            }
        }
        return AppleMusicWebArtist(
            identity, shelf(AppleMusicWebArtistShelf.TOP_SONGS),
            shelf(AppleMusicWebArtistShelf.STUDIO_ALBUMS), shelf(AppleMusicWebArtistShelf.SINGLES_AND_EPS),
            shelf(AppleMusicWebArtistShelf.SIMILAR_ARTISTS), shelf(AppleMusicWebArtistShelf.ESSENTIAL_ALBUMS),
            shelf(AppleMusicWebArtistShelf.ARTIST_PLAYLISTS), unresolved,
            latestRelease = shelf(AppleMusicWebArtistShelf.LATEST_RELEASE).singleOrNull(),
            nextCursors = cursors.toMap(),
        )
    }

    /**
     * Direct Apple artist relationship/view pagination (including Singles/EPs).
     * Reusing the search offset would be incorrect: follow the upstream cursor.
     */
    suspend fun artistCollection(
        id: String, country: String, shelf: AppleMusicWebArtistShelf,
        page: Int = 1, limit: Int = 20, continuation: String? = null,
    ): AppleMusicWebPage {
        require(page in 1..20 && limit in 1..100 && (continuation == null || page == 1))
        val sf = storefront(country)
        val artistId = resourceId(id, AppleMusicWebKind.ARTIST)
        val path = "/v1/catalog/$sf/artists/$artistId/${shelf.endpoint}"
        val firstParams = mapOf("limit" to limit.toString())
        var block = parse(transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY,
            path, firstParams + (continuation?.let { follow(it,path) } ?: emptyMap())))
        val visited = mutableSetOf<String>()
        for (index in 1 until page) {
            val cursor = block.str("next")
                ?: return AppleMusicWebPage(emptyList(), page, limit, false)
            if (!visited.add(cursor)) throw AppleMusicWebProtocolException("Cyclic artist cursor")
            val params = firstParams + follow(cursor, path)
            block = parse(transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY, path, params))
        }
        if ("errors" in block || block["data"] !is JsonArray)
            throw AppleMusicWebProtocolException("Invalid artist collection envelope")
        val parsed = artistShelfItems(block, sf, shelf)
        val next = block.str("next")
        if (next != null) {
            follow(next,path)
            if (next == continuation || next in visited)
                throw AppleMusicWebProtocolException("Cyclic artist cursor")
        }
        return AppleMusicWebPage(parsed.items, page, limit, next != null,
            nextCursor=next,unresolvedReferences = parsed.unresolved.size)
    }

    suspend fun songCollections(id: String, country: String): AppleMusicWebSongCollections {
        val sf = storefront(country)
        val songId = resourceId(id, AppleMusicWebKind.SONG)
        val raw = detail(sf, AppleMusicWebKind.SONG, songId, mapOf("include" to "albums"))
        val song = convert(raw, sf) ?: throw AppleMusicWebProtocolException("Invalid song")
        val albums = raw.obj("relationships")?.obj("albums")
            ?: throw AppleMusicWebProtocolException("Missing song albums relationship")
        if (albums["data"] !is JsonArray)
            throw AppleMusicWebProtocolException("Missing song album linkage")
        val ids = albums.objects("data").map { row ->
            if (row.str("type") != AppleMusicWebKind.ALBUM.apiType)
                throw AppleMusicWebProtocolException("Wrong song album linkage type")
            resourceId(row.str("id") ?: throw AppleMusicWebProtocolException("Missing ID"), AppleMusicWebKind.ALBUM)
        }
        if (ids.size != ids.distinct().size)
            throw AppleMusicWebProtocolException("Duplicate song album linkage")
        return AppleMusicWebSongCollections(song, ids, albums.str("next") != null)
    }

    /** Public editorial playlist: preserve order and intentional repeated songs.
     * No video, library account or playback access. Bound reads and disclose gaps. */
    suspend fun playlist(id: String, country: String): AppleMusicWebOutcome<AppleMusicWebPlaylist> {
        val sf = storefront(country)
        val playlistId = resourceId(id,AppleMusicWebKind.PLAYLIST)
        val raw = detail(sf,AppleMusicWebKind.PLAYLIST,playlistId,mapOf("include" to "tracks", "extend" to "editorialArtwork,editorialVideo"))
        val identity = convert(raw,sf) ?: throw AppleMusicWebProtocolException("Invalid playlist")
        val path = "/v1/catalog/$sf/playlists/$playlistId"
        var block = raw.obj("relationships")?.obj("tracks")
        val rows = mutableListOf<AppleMusicWebItem>()
        val visited = hashSetOf<String>()
        var partial: AppleMusicWebCompleteness? = null
        for (page in 1..5) {
            val current = block
            if (current == null || current["data"] !is JsonArray || "errors" in current) {
                partial = AppleMusicWebCompleteness.MISSING_TRACKLIST
                break
            }
            for (entry in current.objects("data")) {
                val type = entry.str("type")
                if (type == AppleMusicWebKind.MUSIC_VIDEO.apiType) continue
                if (type != AppleMusicWebKind.SONG.apiType)
                    throw AppleMusicWebProtocolException("Wrong playlist track type")
                resourceId(entry.str("id") ?: throw AppleMusicWebProtocolException("Missing ID"),AppleMusicWebKind.SONG)
                if (entry["attributes"] == null || entry["attributes"].toString() == "null") {
                    partial = AppleMusicWebCompleteness.UNRESOLVED_EDITORIAL_REFERENCES
                    continue
                }
                if (rows.size == 500) {
                    partial = AppleMusicWebCompleteness.PAGE_LIMIT
                    break
                }
                rows += convert(entry,sf) ?: throw AppleMusicWebProtocolException("Invalid playlist song")
            }
            val next = current.str("next") ?: break
            if (page == 5 || rows.size == 500) {
                partial = AppleMusicWebCompleteness.PAGE_LIMIT
                break
            }
            if (!visited.add(next)) throw AppleMusicWebProtocolException("Cyclic playlist cursor")
            val nextPath = runCatching { URI(next).path }.getOrNull()
                ?: throw AppleMusicWebProtocolException("Malformed playlist cursor")
            if (nextPath !in setOf("$path/tracks","$path/relationships/tracks"))
                throw AppleMusicWebProtocolException("Cross-playlist cursor")
            val args = follow(next,nextPath)
            if (args.keys.any { it !in setOf("limit","offset","l") })
                throw AppleMusicWebProtocolException("Changed playlist cursor")
            block = parse(transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY,nextPath,args))
        }
        val result = AppleMusicWebPlaylist(identity,rows.toList())
        return partial?.let { AppleMusicWebOutcome.Partial(result,it) } ?: AppleMusicWebOutcome.Complete(result)
    }

    /** One bounded public metadata batch for already linked albums. */
    suspend fun linkedAlbumMetadata(ids: List<String>, country: String): List<AppleMusicWebItem> {
        require(ids.size in 1..25 && ids.distinct().size == ids.size)
        val sf = storefront(country)
        ids.forEach { resourceId(it,AppleMusicWebKind.ALBUM) }
        val body = parse(transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY,
            "/v1/catalog/$sf/albums",mapOf("ids" to ids.joinToString(","))))
        if ("errors" in body || body["data"] !is JsonArray)
            throw AppleMusicWebProtocolException("Invalid linked album metadata envelope")
        val seen = hashSetOf<String>()
        return body.objects("data").map { raw ->
            val id = raw.str("id") ?: throw AppleMusicWebProtocolException("Missing linked album ID")
            if (id !in ids || raw.str("type") != "albums" || !seen.add(id))
                throw AppleMusicWebProtocolException("Wrong linked album identity")
            convert(raw,sf) ?: throw AppleMusicWebProtocolException("Invalid linked album")
        }
    }

    suspend fun album(id: String, country: String): AppleMusicWebAlbum =
        when (val outcome = albumOutcome(id, country)) {
            is AppleMusicWebOutcome.Complete -> outcome.value
            is AppleMusicWebOutcome.Partial ->
                throw AppleMusicWebProtocolException("Incomplete Apple album relationship")
            is AppleMusicWebOutcome.Unavailable ->
                throw AppleMusicWebProtocolException("Album unavailable")
        }

    /** Exposes truncated/partial albums explicitly to future canonical adapters. */
    suspend fun albumOutcome(
        id: String,
        country: String,
    ): AppleMusicWebOutcome<AppleMusicWebAlbum> {
        val sf = storefront(country)
        val albumId = resourceId(id, AppleMusicWebKind.ALBUM)
        val raw = detail(sf, AppleMusicWebKind.ALBUM, albumId, mapOf(
            "include" to "tracks,artists,genres,record-labels",
            "extend" to "editorialArtwork,plainEditorialNotes,editorialVideo",
        ))
        val identity = convert(raw, sf) ?: throw AppleMusicWebProtocolException("Invalid album")
        val rel = raw.obj("relationships")
        val first = rel?.obj("tracks")
        // A missing or unexpanded relationship is not a proven empty album.
        val missingTracklist = first == null ||
            first["data"] == null || first["data"].toString() == "null"
        val expectedTracks = (raw.obj("attributes")?.get("trackCount")
            as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
        val tracks = ArrayList(items(first, sf))
        val allowedTrackPaths = setOf(
            "/v1/catalog/$sf/albums/$albumId/tracks",
            "/v1/catalog/$sf/albums/$albumId/relationships/tracks",
        )
        val seen = mutableSetOf<String>()
        var cursor = first?.str("next")
        var interrupted = false
        val itemIds = tracks.mapTo(mutableSetOf()) { it.scopedId }
        while (cursor != null && tracks.size < 500) {
            if (!seen.add(cursor)) throw AppleMusicWebProtocolException("Cyclic album cursor")
            val path = runCatching { URI(cursor).path }.getOrNull()
                ?: throw AppleMusicWebProtocolException("Malformed album cursor")
            if (path !in allowedTrackPaths)
                throw AppleMusicWebProtocolException("Cross-album track cursor")
            val args = follow(cursor, path) +
                ("limit" to minOf(100, 500 - tracks.size).toString())
            val block = try {
                parse(transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY, path, args))
            } catch (blocked: AppleMusicWebHttpException) {
                // A denied or throttled capability must not publish cached
                // metadata as if the request succeeded.
                if (blocked.status in listOf(401, 403, 429)) throw blocked
                interrupted = true
                break
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                interrupted = true
                break
            }
            val additional = items(block, sf)
            if (additional.isEmpty()) {
                interrupted = true
                break
            }
            if (additional.any { !itemIds.add(it.scopedId) }) {
                throw AppleMusicWebProtocolException("Duplicate Apple album track across pages")
            }
            tracks.addAll(additional)
            cursor = block.str("next")
        }
        val countMismatch = expectedTracks != null && tracks.size != expectedTracks
        val truncated = cursor != null || interrupted || missingTracklist || countMismatch
        val result = AppleMusicWebAlbum(
            identity, tracks.take(500), items(rel?.obj("artists"), sf),
            possiblyTruncated = truncated,
        )
        return when {
            missingTracklist -> AppleMusicWebOutcome.Partial(
                result, AppleMusicWebCompleteness.MISSING_TRACKLIST,
            )
            interrupted -> AppleMusicWebOutcome.Partial(
                result, AppleMusicWebCompleteness.UPSTREAM_INTERRUPTED,
            )
            countMismatch -> AppleMusicWebOutcome.Partial(
                result, AppleMusicWebCompleteness.TRACK_COUNT_MISMATCH,
            )
            cursor != null -> AppleMusicWebOutcome.Partial(
                result, AppleMusicWebCompleteness.PAGE_LIMIT,
            )
            else -> AppleMusicWebOutcome.Complete(result)
        }
    }
}
