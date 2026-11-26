package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderRequestDeniedException
import com.music.bitchord.data.providers.RateLimitedProviderTransport
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
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
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** A public Discogs Artist reference, never inferred from an artist name. */
internal data class DiscogsArtistIndexEntry(
    val kind: DiscogsKind,
    val id: Long,
    val title: String = "",
    /** First-party index hint; the exact Release must still prove its Master and artist IDs. */
    val mainReleaseId: Long? = null,
)

internal data class DiscogsArtistIndexPage(
    val entries: List<DiscogsArtistIndexEntry>,
    val totalPages: Int,
)

/**
 * MusicBrainz Artist -> Discogs Artist URLs are identity anchors. Public
 * index rows are only hints, NEVER evidence of specific edition identity.
 */
internal object DiscogsPublicArtistParsing {
    private val json = Json { ignoreUnknownKeys = true }
    private val mbid = Regex("^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")
    private val path = Regex("^/artist/([1-9][0-9]{0,11})(?:-[A-Za-z0-9_-]+)?/?$")

    private fun artistLink(raw: String): Long? {
        if (raw.length !in 1..512) return null
        val url = try { URI(raw) } catch (_: Exception) { return null }
        if (url.scheme !in setOf("https", "http") ||
            url.rawUserInfo != null || url.port != -1 ||
            url.rawQuery != null || url.rawFragment != null
        ) return null
        val host = url.host?.lowercase(Locale.ROOT) ?: return null
        if (host !in setOf("www.discogs.com", "discogs.com")) return null
        return path.matchEntire(url.rawPath ?: return null)
            ?.groupValues?.get(1)?.toLongOrNull()
            ?.takeIf { it in 1..999_999_999_999L }
    }

    fun artistId(musicBrainzArtistId: String, raw: String): Long? {
        if (!mbid.matches(musicBrainzArtistId) || raw.length > 131_072) return null
        val obj = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) { null } ?: return null
        if (!(obj["id"] as? JsonPrimitive)?.contentOrNull
                .equals(musicBrainzArtistId, ignoreCase = true)
        ) return null
        val relations = obj["relations"] as? JsonArray ?: return null
        if (relations.size > 250) return null
        val candidates = HashSet<Long>()
        for (element in relations) {
            val relation = element as? JsonObject ?: return null
            if (!(relation["type"] as? JsonPrimitive)?.contentOrNull
                    .equals("discogs", ignoreCase = true)
                || (relation["ended"] as? JsonPrimitive)?.contentOrNull == "true"
            ) continue
            val resource = ((relation["url"] as? JsonObject)
                ?.get("resource") as? JsonPrimitive)?.contentOrNull ?: return null
            val found = artistLink(resource) ?: return null
            candidates += found
        }
        return candidates.singleOrNull()
    }

    fun page(raw: String, requestedPage: Int): DiscogsArtistIndexPage? {
        if (raw.length > 131_072 || requestedPage !in 1..100) return null
        val obj = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) { null } ?: return null
        val paging = obj["pagination"] as? JsonObject ?: return null
        val page = (paging["page"] as? JsonPrimitive)?.intOrNull
        val pages = (paging["pages"] as? JsonPrimitive)?.intOrNull
        val rows = obj["releases"] as? JsonArray ?: return null
        if (page != requestedPage || pages == null || pages !in 1..10_000 ||
            requestedPage > pages || rows.size > 20
        ) return null
        val results = LinkedHashMap<Pair<DiscogsKind, Long>, DiscogsArtistIndexEntry>()
        for (element in rows) {
            val row = element as? JsonObject ?: return null
            if ((row["role"] as? JsonPrimitive)?.contentOrNull != "Main") continue
            val kind = when ((row["type"] as? JsonPrimitive)?.contentOrNull) {
                "master" -> DiscogsKind.MASTER
                "release" -> DiscogsKind.RELEASE
                else -> continue
            }
            val id = (row["id"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { it in 1..999_999_999_999L } ?: continue
            val title = (row["title"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.length in 1..240 }.orEmpty()
            val mainReleaseId = if (kind == DiscogsKind.MASTER)
                (row["main_release"] as? JsonPrimitive)?.longOrNull
                    ?.takeIf { it in 1..999_999_999_999L }
                else null
            val identity = kind to id
            // Preserve the first usable title. Conflicting duplicate hints
            // must not nominate an edition; the caller will read the Master.
            val existing = results[identity]
            if (existing == null) {
                results[identity] = DiscogsArtistIndexEntry(kind, id, title, mainReleaseId)
            } else {
                results[identity] = existing.copy(
                    title = existing.title.ifBlank { title },
                    mainReleaseId = existing.mainReleaseId.takeIf { it == mainReleaseId },
                )
            }
        }
        return DiscogsArtistIndexPage(results.values.take(12), pages)
    }
}

internal interface DiscogsPublicArtistTransport {
    suspend fun list(artistId: Long, page: Int, userAgent: String): DiscogsHttpResponse

    /** Source-selected authorization; legacy public supplements stay anonymous. */
    val supportsTokenAuthorization: Boolean get() = false

    suspend fun listAuthenticated(
        artistId: Long, page: Int, userAgent: String, token: String,
    ): DiscogsHttpResponse = throw UnsupportedOperationException(
        "Artist transport does not support authenticated reads",
    )
}

/** Fixed official endpoint, no images/redirects; optional bearer-free Discogs token. */
internal class KtorDiscogsPublicArtistTransport(
    private val perPage: Int = 12,
    private val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000L
            connectTimeoutMillis = 7_000L
            socketTimeoutMillis = 12_000L
        }
    },
) : DiscogsPublicArtistTransport {
    override val supportsTokenAuthorization: Boolean get() = true

    override suspend fun list(
        artistId: Long, page: Int, userAgent: String,
    ): DiscogsHttpResponse = read(artistId, page, userAgent, null)

    override suspend fun listAuthenticated(
        artistId: Long, page: Int, userAgent: String, token: String,
    ): DiscogsHttpResponse = read(artistId, page, userAgent, token)

    private suspend fun read(
        artistId: Long, page: Int, userAgent: String, token: String?,
    ): DiscogsHttpResponse {
        require(artistId in 1..999_999_999_999L && page in 1..100 && perPage in 1..12)
        require(token == null || isValidDiscogsSearchToken(token))
        val response = client.get("https://api.discogs.com/artists/$artistId/releases") {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/vnd.discogs.v2.discogs+json")
            if (token != null) header(HttpHeaders.Authorization, "Discogs token=$token")
            parameter("page", page)
            parameter("per_page", perPage)
            parameter("sort", "year")
            parameter("sort_order", "desc")
        }
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > 131_072L) {
            response.bodyAsChannel().cancel()
            return DiscogsHttpResponse(413, "")
        }
        val channel = response.bodyAsChannel()
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (bytes.size() + count > 131_072) {
                channel.cancel()
                return DiscogsHttpResponse(413, "")
            }
            bytes.write(buffer, 0, count)
        }
        val retry = response.headers[HttpHeaders.RetryAfter]
            ?.toLongOrNull()?.takeIf { it in 0..86_400L }
        return DiscogsHttpResponse(response.status.value,
            bytes.toByteArray().toString(Charsets.UTF_8), retry)
    }
}

/**
 * A token-free source-only supplement, strictly limited to artists with an
 * exact MB Artist -> Discogs Artist relationship and complete MB discography.
 * Source rows are hydrated through the same guarded Release/Master client.
 */
internal class DiscogsPublicArtistDiscovery(
    private val musicBrainz: MusicBrainzClient = MusicBrainzClient(),
    private val transport: DiscogsPublicArtistTransport = KtorDiscogsPublicArtistTransport(),
    private val editions: DiscogsClient = DiscogsClient(),
    private val enabled: Boolean = false,
    private val limiter: DiscogsRateLimiter = DiscogsRequestBudget.limiter,
    private val admission: RateLimitedProviderTransport = RateLimitedProviderTransport(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val userAgent: String =
        "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)",
) {
    suspend fun findForArtist(
        musicBrainzArtistMbid: String,
        artistName: String,
        completedMusicBrainzTitles: Set<String>,
    ): List<DiscogsShadowRelease> {
        if (!enabled || artistName.isBlank() ||
            !admission.permitted(ProviderCapability.EDITION_METADATA, BuiltInProviderKeys.DISCOGS)
        ) return emptyList()
        val relation = try {
            musicBrainz.get("artist/$musicBrainzArtistMbid",
                mapOf("fmt" to "json", "inc" to "url-rels"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return emptyList()
        }
        val artistId = DiscogsPublicArtistParsing.artistId(
            musicBrainzArtistMbid, relation,
        ) ?: return emptyList()
        suspend fun checkedPage(number: Int): DiscogsArtistIndexPage? {
            val response = try {
                admission.execute(
                    capability = ProviderCapability.EDITION_METADATA,
                    provider = BuiltInProviderKeys.DISCOGS,
                    scheduleLimited = { block -> limiter.runHttpLimited { block() } },
                    networkCall = { transport.list(artistId, number, userAgent) },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ProviderRequestDeniedException) {
                return null
            } catch (_: Exception) {
                return null
            }
            if (response.status != 200) return null
            return DiscogsPublicArtistParsing.page(response.body, number)
        }
        val first = checkedPage(1) ?: return emptyList()
        // Public Artist release index titles are hints only. Hydrate at most
        // four release candidates per page and never fabricate an MBID.
        val knownTitle = DiscogsShadowCatalog.knownTitlePredicate(
            artistName, completedMusicBrainzTitles,
        )
        suspend fun select(page: DiscogsArtistIndexPage): List<DiscogsShadowRelease> {
            val candidates = page.entries.filter { entry ->
                entry.title.isBlank() || !knownTitle(entry.title)
            }.take(4)
            val observations = mutableListOf<DiscogsPublicEditionSnapshot>()
            val used = HashSet<Long>()
            for (entry in candidates) {
                val releaseId = when (entry.kind) {
                    DiscogsKind.RELEASE -> entry.id
                    DiscogsKind.MASTER -> {
                        val result = editions.fetchMaster(entry.id)
                        (result as? DiscogsLookupResult.FoundMaster)?.record
                            ?.mainReleaseId ?: continue
                    }
                }
                if (!used.add(releaseId)) continue
                val record = (editions.fetchRelease(releaseId) as?
                    DiscogsLookupResult.Found)?.record ?: continue
                if (entry.kind == DiscogsKind.MASTER &&
                    record.masterId != entry.id
                ) continue
                observations += DiscogsPublicEditionSnapshot(record, nowMillis())
            }
            return DiscogsShadowCatalog.select(
                observations = observations,
                expectedArtist = artistName,
                mbCatalogChecked = true,
                nowMillis = nowMillis(),
                knownMusicBrainzTitles = completedMusicBrainzTitles,
                expectedDiscogsArtistId = artistId,
            )
        }
        val firstResult = select(first)
        if (firstResult.isNotEmpty() || first.totalPages < 2) return firstResult
        // Page 2 is optional and only used when page 1 provided no new
        // eligible release. Never scan an artist's entire provider catalog.
        return checkedPage(2)?.let { select(it) } ?: emptyList()
    }
}
