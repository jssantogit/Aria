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
    ) : DiscogsNativeArtistLoad
    data object Disabled : DiscogsNativeArtistLoad
    data object InvalidArtist : DiscogsNativeArtistLoad
    data object NotFound : DiscogsNativeArtistLoad
    data object Unavailable : DiscogsNativeArtistLoad
    data class Throttled(val retryAfterSeconds: Long?) : DiscogsNativeArtistLoad
}

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
            // Artist shelves retain IDs and format evidence only; the exact
            // Release tracklist is fetched again when the user opens its page.
            tracklist = emptyList(),
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
    private val userAgent: String =
        "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)",
) {
    suspend fun load(artistId: Long, page: Int = 1): DiscogsNativeArtistLoad {
        if (artistId !in 1..999_999_999_999L || page !in 1..100)
            return DiscogsNativeArtistLoad.InvalidArtist
        if (!enabled || !admission.permitted(
            ProviderCapability.EDITORIAL_DISCOGRAPHY, BuiltInProviderKeys.DISCOGS,
        )) return DiscogsNativeArtistLoad.Disabled
        val response = try {
            admission.execute(
                capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
                provider = BuiltInProviderKeys.DISCOGS,
                scheduleLimited = { block -> limiter.runHttpLimited { block() } },
                networkCall = { transport.list(artistId, page, userAgent) },
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
        var incomplete = index.entries.size > 6 || index.totalPages > 100
        val seenReleaseIds = HashSet<Long>()
        for (entry in index.entries.take(6)) {
            val releaseId = when (entry.kind) {
                DiscogsKind.RELEASE -> entry.id
                DiscogsKind.MASTER -> {
                    val response = try { editions.master(entry.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { DiscogsLookupResult.Unavailable }
                    val master = (response as? DiscogsLookupResult.FoundMaster)?.record
                    if (response is DiscogsLookupResult.Throttled) {
                        if (hydrated.isEmpty()) return DiscogsNativeArtistLoad.Throttled(
                            response.retryAfterSeconds,
                        )
                        incomplete = true
                        break
                    }
                    if (response is DiscogsLookupResult.Disabled) {
                        if (hydrated.isEmpty()) return DiscogsNativeArtistLoad.Disabled
                        incomplete = true
                        break
                    }
                    if (response is DiscogsLookupResult.Unauthorized ||
                        response is DiscogsLookupResult.Unavailable
                    ) {
                        if (hydrated.isEmpty()) return DiscogsNativeArtistLoad.Unavailable
                        incomplete = true
                        break
                    }
                    val id = master?.takeIf {
                        it.link.kind == DiscogsKind.MASTER && it.link.id == entry.id
                    }?.mainReleaseId
                    if (id == null) incomplete = true
                    id ?: continue
                }
            }
            if (!seenReleaseIds.add(releaseId)) continue
            val lookup = try { editions.release(releaseId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsLookupResult.Unavailable }
            if (lookup is DiscogsLookupResult.Throttled) {
                if (hydrated.isEmpty()) return DiscogsNativeArtistLoad.Throttled(
                    lookup.retryAfterSeconds,
                )
                incomplete = true
                break
            }
            if (lookup is DiscogsLookupResult.Disabled) {
                if (hydrated.isEmpty()) return DiscogsNativeArtistLoad.Disabled
                incomplete = true
                break
            }
            if (lookup is DiscogsLookupResult.Unauthorized ||
                lookup is DiscogsLookupResult.Unavailable
            ) {
                if (hydrated.isEmpty()) return DiscogsNativeArtistLoad.Unavailable
                incomplete = true
                break
            }
            val record = (lookup as? DiscogsLookupResult.Found)?.record
            if (record == null) {
                incomplete = true
                continue
            }
            hydrated += entry to record
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
        )
    }
}
