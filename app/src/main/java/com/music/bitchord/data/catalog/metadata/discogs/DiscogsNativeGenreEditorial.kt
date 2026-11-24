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
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Discogs tags are typed, verified source predicates. Genre and Style are NOT
 * interchangeable and neither is a music asset or a MusicBrainz identity.
 */
internal data class DiscogsEditorialQuery(
    val sourceName: String,
    val isStyle: Boolean,
    /** Optional exact Discogs Genre required in addition to a Style. */
    val requiredGenre: String? = null,
) {
    fun isValid(): Boolean = sourceName.length in 2..80 &&
        sourceName.none(Character::isISOControl) &&
        sourceName.none { it == '\\' || it == '/' } &&
        (requiredGenre == null || (isStyle &&
            requiredGenre.length in 2..80 &&
            requiredGenre.none(Character::isISOControl) &&
            requiredGenre.none { it == '\\' || it == '/' }))
}

internal data class DiscogsEditorialCandidate(
    val masterId: Long,
    val releaseId: Long,
    val title: String,
    val artist: String,
    val year: Int?,
    val formats: List<String>,
    /** Exact artist IDs from the same verified release, never a name match. */
    val creditedArtists: List<Pair<Long, String>> = emptyList(),
) {
    val browseId: String get() = "discogs:release:$releaseId"
}

/**
 * Source index position, independent of the number of releases that passed
 * verification. The signature prevents a changing Discogs index page from
 * silently shifting already-consumed candidates between scroll requests.
 */
internal data class DiscogsEditorialCursor(
    val page: Int = 1,
    val candidatePosition: Int = 0,
    val pageSignature: Int? = null,
) {
    fun isValid(): Boolean = page in 1..1000 &&
        candidatePosition in 0..12 &&
        (candidatePosition == 0 || pageSignature != null)
}

internal sealed interface DiscogsGranularRead {
    data class Page(
        val releases: List<DiscogsEditorialCandidate>,
        val nextCursor: DiscogsEditorialCursor?,
        val consumedCandidates: Int,
        val hydratedCandidates: Int,
        /** A safety ceiling is not proof of source exhaustion. */
        val stoppedByPageLimit: Boolean = false,
    ) : DiscogsGranularRead

    data class Interrupted(
        val releases: List<DiscogsEditorialCandidate>,
        val retryCursor: DiscogsEditorialCursor,
        val failure: DiscogsEditorialResult,
    ) : DiscogsGranularRead
}

internal sealed interface DiscogsEditorialResult {
    data class Verified(val releases: List<DiscogsEditorialCandidate>) : DiscogsEditorialResult
    data object Unauthorized : DiscogsEditorialResult
    data object Disabled : DiscogsEditorialResult
    data object Unavailable : DiscogsEditorialResult
    data object InvalidQuery : DiscogsEditorialResult
    data class Throttled(val retryAfterSeconds: Long?) : DiscogsEditorialResult
}

internal interface DiscogsGenreSearchTransport {
    suspend fun search(
        query: DiscogsEditorialQuery,
        token: String,
        userAgent: String,
        page: Int,
    ): DiscogsHttpResponse
}

/** No redirects: a Discogs token is sent only to the fixed API endpoint. */
internal class KtorDiscogsGenreSearchTransport(
    private val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000L
            connectTimeoutMillis = 7_000L
            socketTimeoutMillis = 12_000L
        }
    },
) : DiscogsGenreSearchTransport {
    override suspend fun search(
        query: DiscogsEditorialQuery,
        token: String,
        userAgent: String,
        page: Int,
    ): DiscogsHttpResponse {
        require(query.isValid() && page in 1..1000)
        val response = client.get("https://api.discogs.com/database/search") {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/vnd.discogs.v2.discogs+json")
            header(HttpHeaders.Authorization, "Discogs token=$token")
            parameter("type", "master")
            parameter(if (query.isStyle) "style" else "genre", query.sourceName)
            query.requiredGenre?.let { parameter("genre", it) }
            parameter("per_page", 12)
            parameter("page", page)
        }
        if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) > 131_072L) {
            response.bodyAsChannel().cancel()
            return DiscogsHttpResponse(413, "")
        }
        val bytes = java.io.ByteArrayOutputStream()
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8_192)
        while (true) {
            val n = channel.readAvailable(buffer)
            if (n < 0) break
            if (n == 0) continue
            if (bytes.size() + n > 131_072) {
                channel.cancel()
                return DiscogsHttpResponse(413, "")
            }
            bytes.write(buffer, 0, n)
        }
        return DiscogsHttpResponse(
            response.status.value,
            bytes.toByteArray().toString(Charsets.UTF_8),
            response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
                ?.takeIf { it in 0..86_400L },
        )
    }
}

/** A structured search row is only a candidate until the Master and its
 * main Release independently corroborate its tag and source-owned linkage. */
internal data class DiscogsEditorialIndexPage(
    val masterIds: List<Long>,
    val hasNext: Boolean,
)

internal object DiscogsEditorialMasterParsing {
    private val json = Json { ignoreUnknownKeys = true }

    fun page(body: String, expectedPage: Int): DiscogsEditorialIndexPage? {
        if (expectedPage !in 1..1000 || body.length > 131_072) return null
        val root = try { json.parseToJsonElement(body) as? JsonObject }
            catch (_: Exception) { null } ?: return null
        val paging = root["pagination"] as? JsonObject ?: return null
        if ((paging["page"] as? JsonPrimitive)?.intOrNull != expectedPage)
            return null
        val pages = (paging["pages"] as? JsonPrimitive)?.intOrNull
            ?: return null
        if (pages !in expectedPage..100_000) return null
        // The fixed transport always asks for 12; reject a larger surprise
        // page before accepting a truncated list as the whole candidate set.
        val perPage = (paging["per_page"] as? JsonPrimitive)?.intOrNull
        if (perPage != null && perPage !in 1..12) return null
        val rows = root["results"] as? JsonArray ?: return null
        if (rows.size > 12) return null
        val ids = rows.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "master") {
                return@mapNotNull null
            }
            (obj["id"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { it in 1..999_999_999_999L }
        }.distinct()
        return DiscogsEditorialIndexPage(ids, expectedPage < pages)
    }

    /** Kept for the original first-page contract and callers. */
    fun candidates(body: String): List<Long>? = page(body, 1)?.masterIds
}

internal fun discogsEditorialMasterMatches(
    master: DiscogsMasterRecord,
    query: DiscogsEditorialQuery,
): Boolean {
    if (!query.isValid()) return false
    val entries = if (query.isStyle) master.styles else master.genres
    return entries.any { it.trim().equals(query.sourceName, ignoreCase = true) } &&
        (query.requiredGenre == null || master.genres.any {
            it.trim().equals(query.requiredGenre, ignoreCase = true)
        })
}

/** Independent of MusicBrainz and iTunes. Never uses image/search snippets as
 * identity evidence and never interprets result order as popularity. */
internal class DiscogsNativeGenreEditorial(
    private val transport: DiscogsGenreSearchTransport = KtorDiscogsGenreSearchTransport(),
    private val editions: DiscogsNativeEditionReader = DiscogsNativeEditionClient(),
    private val tokenProvider: () -> String? = { null },
    private val enabled: Boolean = false,
    private val limiter: DiscogsRateLimiter = DiscogsRequestBudget.limiter,
    private val admission: RateLimitedProviderTransport = RateLimitedProviderTransport(),
    private val userAgent: String =
        "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)",
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    // One source-owned, bounded, in-memory index snapshot per recent predicate.
    // Only a new cursor (position=0, no signature) can use it. Partial
    // cursors MUST reread the index to detect shifted source positions.
    // Every call still checks capability and token before cache access, and
    // every candidate still requires independently verified Master + Release.
    private data class IndexKey(val query: DiscogsEditorialQuery, val page: Int)
    private data class IndexedAt(
        val page: DiscogsEditorialIndexPage,
        val instantMs: Long,
        /** One-way in-memory binding; the access token is never cached. */
        val tokenDigest: ByteArray,
    )
    private val recentIndexes = linkedMapOf<IndexKey, IndexedAt>()
    private val indexTtlMs = 120_000L
    private val maxCachedIndexes = 20

    private fun digestForCache(token: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

    private fun cachedIndex(query: DiscogsEditorialQuery, page: Int,
                            tokenDigest: ByteArray): DiscogsEditorialIndexPage? =
        synchronized(recentIndexes) {
            val key = IndexKey(query, page)
            val value = recentIndexes[key] ?: return@synchronized null
            if (clockMs() - value.instantMs !in 0L..indexTtlMs ||
                !value.tokenDigest.contentEquals(tokenDigest)
            ) {
                recentIndexes.remove(key)
                null
            } else value.page
        }

    private fun recordIndex(query: DiscogsEditorialQuery, page: Int,
                            parsed: DiscogsEditorialIndexPage, tokenDigest: ByteArray) {
        synchronized(recentIndexes) {
            val key = IndexKey(query, page)
            recentIndexes.remove(key)
            recentIndexes[key] = IndexedAt(parsed, clockMs(), tokenDigest)
            if (recentIndexes.size > maxCachedIndexes) {
                recentIndexes.remove(recentIndexes.keys.first())
            }
        }
    }

    // Master evidence can be reused even if its Genre/Style did not match the
    // first query. It is a positive, exact source identity, not a negative
    // result or an assertion that an unrelated Release has been checked.
    private data class MasterEvidence(
        val master: DiscogsMasterRecord,
        val instantMs: Long,
        val tokenDigest: ByteArray,
    )
    private val verifiedMasters = linkedMapOf<Long, MasterEvidence>()
    private val maxVerifiedMasters = 80

    private fun cachedMaster(masterId: Long, tokenDigest: ByteArray):
        DiscogsMasterRecord? = synchronized(verifiedMasters) {
        val evidence = verifiedMasters[masterId] ?: return@synchronized null
        if (clockMs() - evidence.instantMs !in 0L..indexTtlMs ||
            !evidence.tokenDigest.contentEquals(tokenDigest)
        ) {
            verifiedMasters.remove(masterId)
            null
        } else evidence.master
    }

    private fun rememberMaster(master: DiscogsMasterRecord, tokenDigest: ByteArray) {
        if (master.link.kind != DiscogsKind.MASTER ||
            master.link.id !in 1..999_999_999_999L
        ) return
        synchronized(verifiedMasters) {
            verifiedMasters.remove(master.link.id)
            verifiedMasters[master.link.id] =
                MasterEvidence(master, clockMs(), tokenDigest)
            if (verifiedMasters.size > maxVerifiedMasters)
                verifiedMasters.remove(verifiedMasters.keys.first())
        }
    }

    // Reusing positive evidence avoids repeated Master/Release HTTP reads
    // when the same source Master appears in another Genre/Style index.
    // No negative result, token, search thumbnail or unverified row is cached.
    private data class VerifiedPair(
        val master: DiscogsMasterRecord,
        val release: DiscogsRecord,
        val instantMs: Long,
        val tokenDigest: ByteArray,
    )
    private val verifiedPairs = linkedMapOf<Long, VerifiedPair>()
    private val maxVerifiedPairs = 80

    private fun cachedPair(masterId: Long, tokenDigest: ByteArray): VerifiedPair? =
        synchronized(verifiedPairs) {
            val pair = verifiedPairs[masterId] ?: return@synchronized null
            if (clockMs() - pair.instantMs !in 0L..indexTtlMs ||
                !pair.tokenDigest.contentEquals(tokenDigest)
            ) {
                verifiedPairs.remove(masterId)
                null
            } else pair
        }

    private fun rememberVerifiedPair(
        masterId: Long, master: DiscogsMasterRecord,
        release: DiscogsRecord, tokenDigest: ByteArray,
    ) {
        synchronized(verifiedPairs) {
            verifiedPairs.remove(masterId)
            verifiedPairs[masterId] =
                VerifiedPair(master, release, clockMs(), tokenDigest)
            if (verifiedPairs.size > maxVerifiedPairs)
                verifiedPairs.remove(verifiedPairs.keys.first())
        }
    }

    private fun verifiedCandidate(
        masterId: Long, master: DiscogsMasterRecord,
        release: DiscogsRecord, query: DiscogsEditorialQuery,
    ): DiscogsEditorialCandidate? {
        if (master.link.kind != DiscogsKind.MASTER || master.link.id != masterId ||
            !discogsEditorialMasterMatches(master, query)
        ) return null
        val releaseId = master.mainReleaseId ?: return null
        if (release.link.kind != DiscogsKind.RELEASE ||
            release.link.id != releaseId || release.masterId != masterId ||
            release.unofficial || release.title.isBlank() ||
            release.artistNames.isEmpty()
        ) return null
        return DiscogsEditorialCandidate(
            masterId = masterId, releaseId = releaseId,
            title = release.title, artist = release.artistNames.joinToString(", "),
            year = release.year ?: master.year, formats = release.formats,
            creditedArtists = if (release.artistIds.size == release.artistNames.size)
                release.artistIds.zip(release.artistNames) else emptyList(),
        )
    }

    private fun cachedCandidate(
        masterId: Long, query: DiscogsEditorialQuery, tokenDigest: ByteArray,
    ): DiscogsEditorialCandidate? = cachedPair(masterId, tokenDigest)?.let {
        verifiedCandidate(masterId, it.master, it.release, query)
    }

    /**
     * One bounded scroll gesture examines at most [maxCandidates] index
     * positions, regardless of how many verified releases those positions
     * yield. No candidate after the visual target is discarded.
     *
     * Existing discover() remains the compatible, bounded landing sampler;
     * this cursor API is independently testable before wiring UI continuation.
     */
    suspend fun discoverGranular(
        query: DiscogsEditorialQuery,
        cursor: DiscogsEditorialCursor = DiscogsEditorialCursor(),
        knownMasterIds: Set<Long> = emptySet(),
        maxCandidates: Int = 4,
        /** Fully verified source identity, emitted before the bounded batch ends. */
        onVerified: suspend (DiscogsEditorialCandidate) -> Unit = {},
    ): DiscogsGranularRead {
        fun interrupted(reason: DiscogsEditorialResult,
                        retry: DiscogsEditorialCursor = cursor,
                        releases: List<DiscogsEditorialCandidate> = emptyList()) =
            DiscogsGranularRead.Interrupted(releases, retry, reason)
        if (!query.isValid() || !cursor.isValid() || maxCandidates !in 1..6)
            return interrupted(DiscogsEditorialResult.InvalidQuery)
        if (!enabled || !admission.permitted(
                ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.DISCOGS,
            ) || !admission.permitted(
                ProviderCapability.EDITORIAL_DISCOGRAPHY, BuiltInProviderKeys.DISCOGS,
            )) return interrupted(DiscogsEditorialResult.Disabled)
        val token = tokenProvider()?.trim()?.takeIf(::isValidDiscogsSearchToken)
            ?: return interrupted(DiscogsEditorialResult.Unauthorized)
        val tokenDigest = digestForCache(token)
        val reused = if (cursor.candidatePosition == 0 &&
            cursor.pageSignature == null
        ) cachedIndex(query, cursor.page, tokenDigest) else null
        val index = if (reused != null) reused else {
            val response = try {
                admission.execute(
                    capability = ProviderCapability.CATALOG_SEARCH,
                    provider = BuiltInProviderKeys.DISCOGS,
                    scheduleLimited = { block -> limiter.runHttpLimited { block() } },
                    networkCall = { transport.search(query, token, userAgent, cursor.page) },
                )
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: ProviderRequestDeniedException) {
                  return interrupted(DiscogsEditorialResult.Disabled)
              } catch (_: Exception) {
                  return interrupted(DiscogsEditorialResult.Unavailable)
              }
            when (response.status) {
                200 -> DiscogsEditorialMasterParsing.page(response.body, cursor.page)
                    ?.also { recordIndex(query, cursor.page, it, tokenDigest) }
                    ?: return interrupted(DiscogsEditorialResult.Unavailable)
                401, 403 -> return interrupted(DiscogsEditorialResult.Unauthorized)
                429 -> return interrupted(
                    DiscogsEditorialResult.Throttled(response.retryAfterSeconds))
                else -> return interrupted(DiscogsEditorialResult.Unavailable)
            }
        }
        val signature = 31 * index.masterIds.hashCode() +
            if (index.hasNext) 1 else 0
        if (cursor.candidatePosition > index.masterIds.size ||
            (cursor.pageSignature != null && cursor.pageSignature != signature)
        ) return interrupted(DiscogsEditorialResult.Unavailable)
        var position = cursor.candidatePosition
        var hydrated = 0
        val releases = mutableListOf<DiscogsEditorialCandidate>()
        fun retryAtCurrent() = DiscogsEditorialCursor(cursor.page, position, signature)

        while (position < index.masterIds.size &&
            position - cursor.candidatePosition < maxCandidates
        ) {
            val masterId = index.masterIds[position]
            if (masterId in knownMasterIds) {
                position++
                continue
            }
            val previouslyVerified = cachedCandidate(masterId, query, tokenDigest)
            if (previouslyVerified != null) {
                releases += previouslyVerified
                onVerified(previouslyVerified)
                position++
                continue
            }
            val cached = cachedMaster(masterId, tokenDigest)
            val masterResult = if (cached != null) {
                DiscogsLookupResult.FoundMaster(cached)
            } else try { editions.master(masterId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsLookupResult.Unavailable }
            if (cached == null) hydrated++
            when (masterResult) {
                is DiscogsLookupResult.Throttled ->
                    return interrupted(
                        DiscogsEditorialResult.Throttled(masterResult.retryAfterSeconds),
                        retryAtCurrent(), releases)
                DiscogsLookupResult.Unauthorized ->
                    return interrupted(DiscogsEditorialResult.Unauthorized, retryAtCurrent(), releases)
                DiscogsLookupResult.Disabled ->
                    return interrupted(DiscogsEditorialResult.Disabled, retryAtCurrent(), releases)
                DiscogsLookupResult.Unavailable, DiscogsLookupResult.Malformed ->
                    return interrupted(DiscogsEditorialResult.Unavailable, retryAtCurrent(), releases)
                else -> Unit
            }
            val master = (masterResult as? DiscogsLookupResult.FoundMaster)?.record
            if (master != null && master.link.kind == DiscogsKind.MASTER &&
                master.link.id == masterId
            ) rememberMaster(master, tokenDigest)
            if (master == null || master.link.id != masterId ||
                master.link.kind != DiscogsKind.MASTER ||
                !discogsEditorialMasterMatches(master, query)
            ) {
                position++
                continue
            }
            val releaseId = master.mainReleaseId
            if (releaseId == null) {
                position++
                continue
            }
            val releaseResult = try { editions.release(releaseId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsLookupResult.Unavailable }
            when (releaseResult) {
                is DiscogsLookupResult.Throttled ->
                    return interrupted(
                        DiscogsEditorialResult.Throttled(releaseResult.retryAfterSeconds),
                        retryAtCurrent(), releases)
                DiscogsLookupResult.Unauthorized ->
                    return interrupted(DiscogsEditorialResult.Unauthorized, retryAtCurrent(), releases)
                DiscogsLookupResult.Disabled ->
                    return interrupted(DiscogsEditorialResult.Disabled, retryAtCurrent(), releases)
                DiscogsLookupResult.Unavailable, DiscogsLookupResult.Malformed ->
                    return interrupted(DiscogsEditorialResult.Unavailable, retryAtCurrent(), releases)
                else -> Unit
            }
            val release = (releaseResult as? DiscogsLookupResult.Found)?.record
            val verified = release?.let { verifiedCandidate(masterId, master, it, query) }
            if (verified != null && release != null) {
                rememberVerifiedPair(masterId, master, release, tokenDigest)
                releases += verified
                // In-place streaming changes presentation latency, not source
                // authorization, cursor advancement or HTTP request budget.
                onVerified(verified)
            }
            position++
        }
        val next = if (position < index.masterIds.size)
            DiscogsEditorialCursor(cursor.page, position, signature)
        else if (index.hasNext && cursor.page < 1000)
            DiscogsEditorialCursor(cursor.page + 1)
        else null
        return DiscogsGranularRead.Page(
            releases, next, position - cursor.candidatePosition, hydrated,
            stoppedByPageLimit = index.hasNext && cursor.page == 1000 &&
                position == index.masterIds.size,
        )
    }

    suspend fun discover(
        query: DiscogsEditorialQuery,
        maxReleases: Int = 4,
        /** Publish each fully verified source Release before the sample ends. */
        onVerified: suspend (DiscogsEditorialCandidate) -> Unit = {},
    ): DiscogsEditorialResult {
        if (!query.isValid() || maxReleases !in 1..6)
            return DiscogsEditorialResult.InvalidQuery
        if (!enabled || !admission.permitted(
            ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.DISCOGS,
        ) || !admission.permitted(
            ProviderCapability.EDITORIAL_DISCOGRAPHY, BuiltInProviderKeys.DISCOGS,
        )) return DiscogsEditorialResult.Disabled
        val token = tokenProvider()?.trim()?.takeIf(::isValidDiscogsSearchToken)
            ?: return DiscogsEditorialResult.Unauthorized
        val tokenDigest = digestForCache(token)
        val verified = mutableListOf<DiscogsEditorialCandidate>()
        val hydratedMasterIds = HashSet<Long>()
        // Transport failures are not negative evidence. Overlapping Discogs
        // index pages can legitimately repeat a Master; allow the next page
        // to retry an unresolved Master instead of permanently blacklisting it.
        val unresolvedMasterIds = HashSet<Long>()
        // Fill the caller's bounded sample with verified releases, not just
        // the first non-empty index page. Check all 12 advertised candidates
        // within each source page: an arbitrary first-five cut hides valid
        // releases in positions 6-12 even when no other page exists. Stop
        // once maxReleases is met; every hydration remains rate-limited and
        // only the existing two index pages may be requested.
        for (pageNumber in 1..2) {
            // Initial Home/genre discovery may reuse a recent, source-verified
            // index page from another canonical view. Permissions and the
            // credential are validated above on every entry, while partial
            // granular cursors still always re-read for signature drift.
            val index = cachedIndex(query, pageNumber, tokenDigest) ?: run {
                val response = try {
                    admission.execute(
                        capability = ProviderCapability.CATALOG_SEARCH,
                        provider = BuiltInProviderKeys.DISCOGS,
                        scheduleLimited = { block -> limiter.runHttpLimited { block() } },
                        networkCall = {
                            transport.search(query, token, userAgent, pageNumber)
                        },
                    )
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: ProviderRequestDeniedException) {
                      return if (verified.isNotEmpty()) DiscogsEditorialResult.Verified(verified)
                          else DiscogsEditorialResult.Disabled
                  } catch (_: Exception) {
                      return if (verified.isNotEmpty()) DiscogsEditorialResult.Verified(verified)
                          else DiscogsEditorialResult.Unavailable
                  }
                when (response.status) {
                    200 -> DiscogsEditorialMasterParsing.page(response.body, pageNumber)
                        ?.also { recordIndex(query, pageNumber, it, tokenDigest) }
                        ?: return if (verified.isNotEmpty()) DiscogsEditorialResult.Verified(verified)
                            else DiscogsEditorialResult.Unavailable
                    401, 403 -> return if (verified.isNotEmpty())
                        DiscogsEditorialResult.Verified(verified) else DiscogsEditorialResult.Unauthorized
                    429 -> return if (verified.isNotEmpty())
                        DiscogsEditorialResult.Verified(verified)
                        else DiscogsEditorialResult.Throttled(response.retryAfterSeconds)
                    else -> return if (verified.isNotEmpty())
                        DiscogsEditorialResult.Verified(verified) else DiscogsEditorialResult.Unavailable
                }
            }
            for (masterId in index.masterIds) {
                if (verified.size >= maxReleases) break
                // A successfully checked Master is deduplicated across pages.
                // An unavailable/malformed transport response is NOT a check.
                if (!hydratedMasterIds.add(masterId)) continue
                val previouslyVerified = cachedCandidate(masterId, query, tokenDigest)
                if (previouslyVerified != null) {
                    verified += previouslyVerified
                    onVerified(previouslyVerified)
                    continue
                }
                val cached = cachedMaster(masterId, tokenDigest)
                val masterResult = if (cached != null) {
                    DiscogsLookupResult.FoundMaster(cached)
                } else try { editions.master(masterId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { DiscogsLookupResult.Unavailable }
                if (masterResult is DiscogsLookupResult.Throttled)
                    return if (verified.isNotEmpty()) DiscogsEditorialResult.Verified(verified)
                        else DiscogsEditorialResult.Throttled(masterResult.retryAfterSeconds)
                if (masterResult is DiscogsLookupResult.Unauthorized)
                    return if (verified.isNotEmpty()) DiscogsEditorialResult.Verified(verified)
                        else DiscogsEditorialResult.Unauthorized
                if (masterResult is DiscogsLookupResult.Disabled)
                    return if (verified.isNotEmpty()) DiscogsEditorialResult.Verified(verified)
                        else DiscogsEditorialResult.Disabled
                if (masterResult is DiscogsLookupResult.Unavailable ||
                    masterResult is DiscogsLookupResult.Malformed
                ) {
                    hydratedMasterIds.remove(masterId)
                    unresolvedMasterIds.add(masterId)
                    continue
                }
                // A definitive Master response (even Missing or a mismatched
                // source ID) settles this index candidate without guessing.
                unresolvedMasterIds.remove(masterId)
                val master = (masterResult as? DiscogsLookupResult.FoundMaster)?.record
                    ?: continue
                if (master.link.id != masterId ||
                    master.link.kind != DiscogsKind.MASTER) continue
                rememberMaster(master, tokenDigest)
                if (!discogsEditorialMasterMatches(master, query)) continue
                val releaseId = master.mainReleaseId ?: continue
                val releaseResult = try { editions.release(releaseId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { DiscogsLookupResult.Unavailable }
                if (releaseResult is DiscogsLookupResult.Throttled)
                    return if (verified.isNotEmpty()) DiscogsEditorialResult.Verified(verified)
                        else DiscogsEditorialResult.Throttled(releaseResult.retryAfterSeconds)
                if (releaseResult is DiscogsLookupResult.Unauthorized)
                    return if (verified.isNotEmpty()) DiscogsEditorialResult.Verified(verified)
                        else DiscogsEditorialResult.Unauthorized
                if (releaseResult is DiscogsLookupResult.Disabled)
                    return if (verified.isNotEmpty()) DiscogsEditorialResult.Verified(verified)
                        else DiscogsEditorialResult.Disabled
                if (releaseResult is DiscogsLookupResult.Unavailable ||
                    releaseResult is DiscogsLookupResult.Malformed
                ) {
                    // A second index page can safely retry this source-owned
                    // Master and its main Release. Never cache a failed read.
                    hydratedMasterIds.remove(masterId)
                    unresolvedMasterIds.add(masterId)
                    continue
                }
                unresolvedMasterIds.remove(masterId)
                val release = (releaseResult as? DiscogsLookupResult.Found)?.record
                    ?: continue
                // Never admit an unverified edition or a source ID that changed.
                val candidate = verifiedCandidate(masterId, master, release, query)
                    ?: continue
                rememberVerifiedPair(masterId, master, release, tokenDigest)
                verified += candidate
                onVerified(candidate)
            }
            if (!index.hasNext || verified.size >= maxReleases) break
        }
        return if (verified.isEmpty() && unresolvedMasterIds.isNotEmpty())
            DiscogsEditorialResult.Unavailable
        else DiscogsEditorialResult.Verified(verified.distinctBy { it.masterId })
    }
}
