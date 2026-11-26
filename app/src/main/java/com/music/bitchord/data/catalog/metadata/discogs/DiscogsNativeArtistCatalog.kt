package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderRequestDeniedException
import com.music.bitchord.data.providers.RateLimitedProviderTransport
import kotlinx.coroutines.CancellationException

/**
 * A Discogs-native artist catalog. Its root is the exact Discogs Artist ID,
 * not a MusicBrainz relation, a stage-name match, or a successful MB browse.
 * IDs remain Discogs IDs and no release is exposed as a playable asset.
 */
internal data class DiscogsNativeArtistRelease(
    val artistId: Long,
    val releaseId: Long,
    val masterId: Long?,
    val title: String,
    val year: Int?,
    val formats: List<String>,
    val tracklist: List<Pair<String, String>>,
) {
    val browseId: String get() = "discogs:release:$releaseId"
    val attributionUrl: String get() = "https://www.discogs.com/release/$releaseId"
}

internal sealed interface DiscogsNativeArtistLoad {
    data class Success(
        val releases: List<DiscogsNativeArtistRelease>,
        val hasMore: Boolean,
        /** A bounded read or incomplete hydration cannot prove catalog completeness. */
        val incomplete: Boolean,
        /** A transient interruption left some entries on this index page unread. */
        val retryCurrentPage: Boolean = false,
    ) : DiscogsNativeArtistLoad
    data object Disabled : DiscogsNativeArtistLoad
    data object InvalidArtist : DiscogsNativeArtistLoad
    data object NotFound : DiscogsNativeArtistLoad
    data object Unavailable : DiscogsNativeArtistLoad
    data class Throttled(val retryAfterSeconds: Long?) : DiscogsNativeArtistLoad
}

internal data class DiscogsNativeArtistWindow(
    val result: DiscogsNativeArtistLoad,
    /** Only settled source pages advance the session's persistent offset. */
    val completedPages: Int = 0,
    val settledIncomplete: Boolean = false,
)

/**
 * Source-scoped identity is proved twice: by an Artist/Release index with
 * role=Main and by the hydrated Release's explicit Discogs artist ID. Masters
 * are edition groups, not MusicBrainz ReleaseGroups.
 */
internal fun discogsNativeArtistReleases(
    artistId: Long,
    candidates: List<Pair<DiscogsArtistIndexEntry, DiscogsRecord>>,
): List<DiscogsNativeArtistRelease> {
    if (artistId !in 1..999_999_999_999L) return emptyList()
    return candidates.asSequence().mapNotNull { (entry, release) ->
        val id = release.link.id
        if (release.link.kind != DiscogsKind.RELEASE ||
            id !in 1..999_999_999_999L ||
            artistId !in release.artistIds ||
            release.title.isBlank() || release.unofficial ||
            when (entry.kind) {
                DiscogsKind.RELEASE -> entry.id != id
                DiscogsKind.MASTER -> entry.id != release.masterId
            }
        ) null else DiscogsNativeArtistRelease(
            artistId = artistId,
            releaseId = id,
            masterId = release.masterId,
            title = release.title,
            year = release.year,
            formats = release.formats,
            // Reuse a bounded sample from the already hydrated Release.
            // Only single-artist credits qualify; this proves album
            // membership, NOT individual track authorship or popularity.
            // Album navigation still loads the complete exact tracklist.
            tracklist = if (release.artistIds.singleOrNull() == artistId)
                release.displayTracks.take(4).map { it.position.orEmpty() to it.title }
                else emptyList(),
        )
    }.toList().let(::discogsMergeArtistPageReleases)
}

/**
 * Deduplication is a source-scoped *display* rule, applied both within a
 * batch and across artist pagination. A Master confirms several pressings
 * are editions of one work; unrelated standalone Release IDs never merge.
 * The retained first item always keeps its original, independently verified
 * Discogs Release ID for navigation.
 */
internal fun discogsMergeArtistPageReleases(
    releases: List<DiscogsNativeArtistRelease>,
): List<DiscogsNativeArtistRelease> = releases.distinctBy { release ->
    release.masterId?.let { "master:$it" } ?: "release:${release.releaseId}"
}

internal interface DiscogsNativeEditionReader {
    suspend fun release(id: Long): DiscogsLookupResult
    suspend fun master(id: Long): DiscogsLookupResult
}

internal class DiscogsNativeEditionClient(
    private val source: DiscogsClient = DiscogsClient(
        enabled = true, capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
    ),
) : DiscogsNativeEditionReader {
    override suspend fun release(id: Long): DiscogsLookupResult = source.fetchRelease(id)
    override suspend fun master(id: Long): DiscogsLookupResult = source.fetchMaster(id)
}

/**
 * Pagination and admission are independent of MusicBrainz. The bound on
 * hydration is deliberate: avoid 12+ serial requests per artist page while
 * never representing this first slice as a complete discography.
 */
internal class DiscogsNativeArtistCatalog(
    private val transport: DiscogsPublicArtistTransport =
        KtorDiscogsPublicArtistTransport(perPage = 6),
    private val editions: DiscogsNativeEditionReader = DiscogsNativeEditionClient(),
    private val enabled: Boolean = false,
    private val limiter: DiscogsRateLimiter = DiscogsRequestBudget.limiter,
    private val admission: RateLimitedProviderTransport = RateLimitedProviderTransport(),
    private val tokenProvider: () -> String? = { null },
    private val userAgent: String =
        "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)",
) {
    /**
     * One app gesture fills a small verified target across at most three
     * six-position source pages. Finish each page before advancing; preserve
     * earlier accepted rows if a later request is interrupted.
     */
    suspend fun loadWindow(
        artistId: Long,
        startPage: Int,
        knownVerified: List<DiscogsNativeArtistRelease>,
        maxPages: Int = 3,
        onProgress: (List<DiscogsNativeArtistRelease>) -> Unit = {},
    ): DiscogsNativeArtistWindow {
        require(maxPages in 1..3)
        val collected = mutableListOf<DiscogsNativeArtistRelease>()
        var completed = 0
        var settledIncomplete = false
        for (offset in 0 until maxPages) {
            val page = startPage + offset
            val result = loadDistinct(artistId, page, knownVerified + collected) { partial ->
                onProgress(discogsMergeArtistPageReleases(collected + partial))
            }
            if (result !is DiscogsNativeArtistLoad.Success) {
                if (completed == 0 && collected.isEmpty())
                    return DiscogsNativeArtistWindow(result)
                return DiscogsNativeArtistWindow(
                    DiscogsNativeArtistLoad.Success(
                        collected.toList(), hasMore = true, incomplete = true,
                        retryCurrentPage = true,
                    ), completed, settledIncomplete,
                )
            }
            collected += result.releases
            if (result.retryCurrentPage) return DiscogsNativeArtistWindow(
                result.copy(releases = collected.toList()), completed, settledIncomplete,
            )
            completed++
            settledIncomplete = settledIncomplete || result.incomplete
            if (!result.hasMore || collected.size >= 6 || offset == maxPages - 1)
                return DiscogsNativeArtistWindow(
                    result.copy(releases = collected.toList(), incomplete = settledIncomplete),
                    completed, settledIncomplete,
                )
        }
        error("Bounded artist window must return after its final page")
    }

    suspend fun load(
        artistId: Long,
        page: Int = 1,
        onProgress: (List<DiscogsNativeArtistRelease>) -> Unit = {},
    ): DiscogsNativeArtistLoad = loadDistinct(artistId, page, emptyList(), onProgress)

    /** The caller supplies only already verified rows from this artist snapshot.
     * Skip exact known Release/Master identities, never title or index hints. */
    suspend fun loadDistinct(
        artistId: Long,
        page: Int,
        knownVerified: List<DiscogsNativeArtistRelease>,
        onProgress: (List<DiscogsNativeArtistRelease>) -> Unit = {},
    ): DiscogsNativeArtistLoad {
        if (artistId !in 1..999_999_999_999L || page !in 1..100)
            return DiscogsNativeArtistLoad.InvalidArtist
        if (!enabled || !admission.permitted(
            ProviderCapability.EDITORIAL_DISCOGRAPHY, BuiltInProviderKeys.DISCOGS,
        )) return DiscogsNativeArtistLoad.Disabled
        // A source-owned Discogs token allows the typed Artist index to
        // share the authenticated budget with Master/Release hydration.
        // No token or unsupported transport remains strictly anonymous.
        val token = tokenProvider()?.trim()?.takeIf(::isValidDiscogsSearchToken)
            ?.takeIf { transport.supportsTokenAuthorization }
        val response = try {
            admission.execute(
                capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
                provider = BuiltInProviderKeys.DISCOGS,
                scheduleLimited = { block ->
                    limiter.runHttpLimited(authenticated = token != null) { block() }
                },
                networkCall = {
                    if (token != null) transport.listAuthenticated(
                        artistId, page, userAgent, token,
                    ) else transport.list(artistId, page, userAgent)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ProviderRequestDeniedException) {
            return DiscogsNativeArtistLoad.Disabled
        } catch (_: Exception) {
            return DiscogsNativeArtistLoad.Unavailable
        }
        if (response.status == 404) return DiscogsNativeArtistLoad.NotFound
        if (response.status == 429)
            return DiscogsNativeArtistLoad.Throttled(response.retryAfterSeconds)
        if (response.status != 200) return DiscogsNativeArtistLoad.Unavailable
        val index = DiscogsPublicArtistParsing.page(response.body, page)
            ?: return DiscogsNativeArtistLoad.Unavailable
        val hydrated = mutableListOf<Pair<DiscogsArtistIndexEntry, DiscogsRecord>>()
        var emitted = emptyList<DiscogsNativeArtistRelease>()
        var incomplete = index.entries.size > 6 || index.totalPages > 100
        var retryCurrentPage = false
        val known = knownVerified.filter { it.artistId == artistId }
        val knownMasters = known.mapNotNull { it.masterId }.toHashSet()
        val seenReleaseIds = known.map { it.releaseId }.toHashSet()
        for (entry in index.entries.take(6)) {
            if (entry.kind == DiscogsKind.MASTER && entry.id in knownMasters ||
                entry.kind == DiscogsKind.RELEASE && entry.id in seenReleaseIds
            ) continue
            // The official Artist index can nominate the Master main Release.
            // A successful exact Release read must independently establish both
            // the artist and Master IDs before we can omit the redundant Master GET.
            // Missing or mismatched hints fall back to the normal Master route.
            var prefetched: DiscogsLookupResult? = null
            val hintedId = entry.mainReleaseId?.takeIf {
                entry.kind == DiscogsKind.MASTER && it !in seenReleaseIds
            }
            if (hintedId != null) {
                val hinted = try { editions.release(hintedId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { DiscogsLookupResult.Unavailable }
                val record = (hinted as? DiscogsLookupResult.Found)?.record
                if ((record != null && record.link.id == hintedId &&
                        discogsNativeArtistReleases(artistId, listOf(entry to record)).isNotEmpty()) ||
                    hinted is DiscogsLookupResult.Throttled ||
                    hinted is DiscogsLookupResult.Disabled ||
                    hinted is DiscogsLookupResult.Unauthorized ||
                    hinted is DiscogsLookupResult.Unavailable ||
                    hinted is DiscogsLookupResult.Malformed
                ) prefetched = hinted
            }
            val releaseId = when {
                prefetched != null -> checkNotNull(hintedId)
                entry.kind == DiscogsKind.RELEASE -> entry.id
                else -> {
                    val response = try { editions.master(entry.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { DiscogsLookupResult.Unavailable }
                    val master = (response as? DiscogsLookupResult.FoundMaster)?.record
                    if (response is DiscogsLookupResult.Throttled) {
                        if (hydrated.isEmpty()) return DiscogsNativeArtistLoad.Throttled(
                            response.retryAfterSeconds,
                        )
                        incomplete = true
                        retryCurrentPage = true
                        break
                    }
                    if (response is DiscogsLookupResult.Disabled) {
                        if (hydrated.isEmpty()) return DiscogsNativeArtistLoad.Disabled
                        incomplete = true
                        retryCurrentPage = true
                        break
                    }
                    if (response is DiscogsLookupResult.Unauthorized ||
                        response is DiscogsLookupResult.Unavailable ||
                        response is DiscogsLookupResult.Malformed
                    ) {
                        if (hydrated.isEmpty()) return DiscogsNativeArtistLoad.Unavailable
                        incomplete = true
                        retryCurrentPage = true
                        break
                    }
                    val id = master?.takeIf {
                        it.link.kind == DiscogsKind.MASTER && it.link.id == entry.id
                    }?.mainReleaseId
                    if (id == null) incomplete = true
                    id ?: continue
                }
            }
            // A Master pointing to a known Release but not a known Master
            // is a conflicting relation, not a settled duplicate.
            if (releaseId in seenReleaseIds) {
                if (entry.kind == DiscogsKind.MASTER) incomplete = true
                continue
            }
            val lookup = prefetched ?: try { editions.release(releaseId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsLookupResult.Unavailable }
            if (lookup is DiscogsLookupResult.Throttled) {
                if (hydrated.isEmpty()) return DiscogsNativeArtistLoad.Throttled(
                    lookup.retryAfterSeconds,
                )
                incomplete = true
                retryCurrentPage = true
                break
            }
            if (lookup is DiscogsLookupResult.Disabled) {
                if (hydrated.isEmpty()) return DiscogsNativeArtistLoad.Disabled
                incomplete = true
                retryCurrentPage = true
                break
            }
            if (lookup is DiscogsLookupResult.Unauthorized ||
                lookup is DiscogsLookupResult.Unavailable ||
                lookup is DiscogsLookupResult.Malformed
            ) {
                if (hydrated.isEmpty()) return DiscogsNativeArtistLoad.Unavailable
                incomplete = true
                retryCurrentPage = true
                break
            }
            val record = (lookup as? DiscogsLookupResult.Found)?.record
            if (record == null || record.link.id != releaseId) {
                incomplete = true
                continue
            }
            val verified = discogsNativeArtistReleases(artistId, listOf(entry to record))
                .singleOrNull()
            if (verified == null) {
                incomplete = true
                continue
            }
            seenReleaseIds.add(verified.releaseId)
            if (verified.masterId != null && !knownMasters.add(verified.masterId))
                continue
            hydrated += entry to record
            // Publish as soon as a genuine source-scoped Release is verified.
            // Do not wait for all six (potentially rate-limited) hydrations.
            // A disputed/unofficial/mismatched candidate is never emitted.
            val partial = discogsNativeArtistReleases(artistId, hydrated)
            if (partial.isNotEmpty() && partial != emitted) {
                emitted = partial
                onProgress(partial)
            }
        }
        val verified = discogsNativeArtistReleases(artistId, hydrated)
        // A well-formed HTTP response can still contain unverified artist
        // credits, unofficial editions or inconsistent master relations.
        // Missing evidence is partial coverage, never proof of completeness.
        if (verified.size < hydrated.map { it.second.link.id }.distinct().size)
            incomplete = true
        return DiscogsNativeArtistLoad.Success(
            releases = verified,
            hasMore = page < index.totalPages && page < 100,
            incomplete = incomplete,
            retryCurrentPage = retryCurrentPage,
        )
    }
}
