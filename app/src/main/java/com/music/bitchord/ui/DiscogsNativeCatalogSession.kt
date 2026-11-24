package com.music.bitchord.ui

import android.content.Context
import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderReadLease
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.ui.screens.DiscogsNativeArtistSearchRow
import com.music.bitchord.ui.screens.DiscogsNativeReleaseSearchRow
import com.music.bitchord.ui.screens.discogsSearchArtistRank
import com.music.bitchord.ui.screens.discogsSearchDistinctArtists
import com.music.bitchord.ui.screens.discogsArtistDisplayName
import com.music.bitchord.ui.screens.discogsSearchReleaseRank
import com.music.bitchord.ui.screens.discogsSearchNeedsNextRelevantPage
import com.music.bitchord.ui.screens.NativeMetadataTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class DiscogsNativeRoute { ARTIST, RELEASE }

/** Source attribution is derived from a validated provider ID, never a URL
 * supplied by search text or an untrusted response. */
internal fun discogsNativeAttributionUrl(browseId: String?): String? {
    val kind = when {
        discogsNativeDetailRouteKey(DiscogsNativeRoute.ARTIST, browseId) != null -> "artist"
        discogsNativeDetailRouteKey(DiscogsNativeRoute.RELEASE, browseId) != null -> "release"
        else -> return null
    }
    return "https://www.discogs.com/$kind/${browseId!!.substringAfterLast(':')}"
}

/**
 * Exact provider-scoped entity route. Rejects historical generic screen keys
 * and refuses to reinterpret an Apple/MusicBrainz ID as a Discogs artist.
 */
internal fun discogsNativeDetailRouteKey(
    route: DiscogsNativeRoute?,
    browseId: String?,
): String? {
    val prefix = when (route) {
        DiscogsNativeRoute.ARTIST -> "discogs:artist:"
        DiscogsNativeRoute.RELEASE -> "discogs:release:"
        null -> return null
    }
    val id = browseId?.takeIf { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.takeIf { it.matches(Regex("[1-9][0-9]{0,11}")) }
        ?.toLongOrNull()
        ?.takeIf { it in 1..999_999_999_999L }
        ?: return null
    return "$prefix$id"
}

internal data class DiscogsNativePage(
    val page: DetailPage,
    val tracks: List<NativeMetadataTrack> = emptyList(),
    /** Explicit non-playable Release facts shown in native album information. */
    val editionFacts: List<Pair<String, String>> = emptyList(),
    val hasMore: Boolean = false,
    val incomplete: Boolean = false,
    val attributionUrl: String? = null,
    /** Exact release artist, omitted for multi-artist or unproved credits. */
    val albumArtistBrowseId: String? = null,
    val albumArtistDisplayName: String? = null,
    /** Source-owned Master context, never an inferred MusicBrainz group. */
    val versionMasterId: Long? = null,
)

/** A version link is accepted only from a hydrated, explicitly verified
 * Master-specific carousel. Its source ID cannot be inferred from a title. */
internal fun discogsVerifiedVersionSelection(
    page: DiscogsNativePage,
    browseId: String,
): DiscogsNativeReleaseSearchRow? {
    if (page.page.type != BrowseType.ALBUM ||
        page.versionMasterId !in 1..999_999_999_999L
    ) return null
    val row = page.page.sections
        .singleOrNull { it.title == "Versões" }
        ?.items?.singleOrNull { it.browseId == browseId } ?: return null
    val id = discogsNativeDetailRouteKey(DiscogsNativeRoute.RELEASE, browseId)
        ?.removePrefix("discogs:release:")?.toLongOrNull() ?: return null
    return DiscogsNativeReleaseSearchRow(id, row.title)
}

/** Native loading skeleton, just like a MusicBrainz DetailPage pushed from Search. */
internal fun discogsNativeLoadingPage(
    browseId: String,
    title: String,
    subtitle: String,
    type: BrowseType,
): DiscogsNativePage = DiscogsNativePage(
    page = DetailPage(
        browseId = browseId, title = title, subtitle = subtitle,
        thumbnailUrl = null, songs = UiState.Loading, type = type,
    ),
)

/**
 * A revoked discography cannot keep painting its source-owned metadata.
 * If Catalog Search remains authorized, a metadata-free error page is still
 * allowed to explain which capability the listener needs to enable.
 */
internal fun discogsNativePageDisplayAllowed(
    page: DiscogsNativePage,
    discographyAuthorized: Boolean,
    catalogSearchAuthorized: Boolean,
): Boolean = discographyAuthorized || (
    catalogSearchAuthorized &&
        page.page.songs is UiState.Error &&
        page.page.sections.isEmpty() &&
        page.tracks.isEmpty() &&
        page.editionFacts.isEmpty() &&
        page.attributionUrl == null &&
        page.albumArtistBrowseId == null &&
        page.albumArtistDisplayName == null &&
        page.versionMasterId == null &&
        page.page.thumbnailUrl == null
    )

/**
 * One per Android ViewModel. Independent from MusicBrainz and Apple. Search
 * and discography use separate persisted capability choices and revoke leases.
 * The only source identities stored here are Discogs Artist/Release numbers.
 */
internal class DiscogsNativeCatalogSession(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val tokenVault by lazy { DiscogsSearchTokenVault(appContext) }
    private val searchClient by lazy {
        DiscogsNativeArtistSearchClient(
            enabled = true,
            tokenProvider = { tokenVault.readForSearch() },
        )
    }
    private val releaseSearchClient by lazy {
        DiscogsNativeReleaseSearchClient(
            enabled = true, tokenProvider = { tokenVault.readForSearch() },
        )
    }
    // Search and every source-owned hydration share the same credential,
    // source gate and process-wide limiter. Anonymous fallback remains safe
    // if the token is unavailable or its authorization is rejected.
    private val editionClient by lazy {
        DiscogsClient(
            enabled = true,
            capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
            tokenProvider = { tokenVault.readForSearch() },
        )
    }
    private val editions by lazy { DiscogsNativeEditionClient(editionClient) }
    private val artistClient by lazy {
        DiscogsNativeArtistCatalog(
            enabled = true, editions = editions,
            tokenProvider = { tokenVault.readForSearch() },
        )
    }
    private val editorialClient by lazy {
        DiscogsNativeGenreEditorial(
            enabled = true, tokenProvider = { tokenVault.readForSearch() },
            editions = editions,
        )
    }

    private val _homeEditorial = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val homeEditorial: StateFlow<UiState<List<HomeShelf>>> = _homeEditorial.asStateFlow()
    private val _genreEditorial =
        MutableStateFlow<Map<String, UiState<List<HomeShelf>>>>(emptyMap())
    val genreEditorial: StateFlow<Map<String, UiState<List<HomeShelf>>>> =
        _genreEditorial.asStateFlow()
    private var homeEditorialJob: Job? = null
    private val _homePaging = MutableStateFlow(DiscogsHomePageSnapshot())
    val homePaging: StateFlow<DiscogsHomePageSnapshot> = _homePaging.asStateFlow()
    private var homePagingJob: Job? = null
    private var homePagingEpoch = 0L
    private var genreEditorialJob: Job? = null
    private val _genrePaging = MutableStateFlow(DiscogsGenrePageSnapshot())
    val genrePaging: StateFlow<DiscogsGenrePageSnapshot> = _genrePaging.asStateFlow()
    private var genrePagingJob: Job? = null
    private var genrePagingEpoch = 0L
    private var homeEditorialAtMs = 0L
    private var genreEditorialAtMs = 0L
    private var genreEditorialTag: String? = null
    private var homeEditorialExpiryJob: Job? = null
    private var genreEditorialExpiryJob: Job? = null
    private var homeEditorialExpiryEpoch = 0L
    private var genreEditorialExpiryEpoch = 0L

    // A two-hour reuse window is not an indefinite right to display API
    // Content. Even a Home or Explore page left open overnight purges its
    // Discogs-owned metadata before the six-hour provider freshness deadline.
    private fun scheduleHomeEditorialExpiry() {
        homeEditorialExpiryJob?.cancel()
        val epoch = ++homeEditorialExpiryEpoch
        homeEditorialExpiryJob = scope.launch {
            delay(5L * 60L * 60L * 1_000L)
            if (homeEditorialExpiryEpoch == epoch) {
                homeEditorialJob?.cancel()
                homePagingJob?.cancel()
                ++homePagingEpoch
                _homePaging.value = DiscogsHomePageSnapshot()
                homeEditorialAtMs = 0L
                _homeEditorial.value = UiState.Loading
            }
        }
    }

    private fun scheduleGenreEditorialExpiry() {
        genreEditorialExpiryJob?.cancel()
        val epoch = ++genreEditorialExpiryEpoch
        genreEditorialExpiryJob = scope.launch {
            delay(5L * 60L * 60L * 1_000L)
            if (genreEditorialExpiryEpoch == epoch) {
                genreEditorialJob?.cancel()
                genrePagingJob?.cancel()
                ++genrePagingEpoch
                _genrePaging.value = DiscogsGenrePageSnapshot()
                genreEditorialAtMs = 0L
                _genreEditorial.value = emptyMap()
            }
        }
    }

    private val _artists = MutableStateFlow<UiState<List<DiscogsNativeArtistSearchRow>>?>(null)
    val artists: StateFlow<UiState<List<DiscogsNativeArtistSearchRow>>?> = _artists.asStateFlow()
    private val _releasesSearch =
        MutableStateFlow<UiState<List<DiscogsNativeReleaseSearchRow>>?>(null)
    val releaseSearch: StateFlow<UiState<List<DiscogsNativeReleaseSearchRow>>?> =
        _releasesSearch.asStateFlow()
    private val _releaseSearchHasMore = MutableStateFlow(false)
    val releaseSearchHasMore: StateFlow<Boolean> = _releaseSearchHasMore.asStateFlow()
    private val _searchHasMore = MutableStateFlow(false)
    val searchHasMore: StateFlow<Boolean> = _searchHasMore.asStateFlow()
    private val _route = MutableStateFlow<DiscogsNativeRoute?>(null)
    val route: StateFlow<DiscogsNativeRoute?> = _route.asStateFlow()
    // The root navigator uses the same exact browseId route key as a
    // MusicBrainz native DetailPage. Never reuse a generic artist/release slot
    // for two distinct Discogs identities.
    private val _browseId = MutableStateFlow<String?>(null)
    val browseId: StateFlow<String?> = _browseId.asStateFlow()
    private val _detail = MutableStateFlow<UiState<DiscogsNativePage>?>(null)
    val detail: StateFlow<UiState<DiscogsNativePage>?> = _detail.asStateFlow()

    private var query = ""
    /** Never let a still-open page display stale Discogs API Content beyond
     * the provider's six-hour maximum. This timer is not extended by paging. */
    private var expirationJob: Job? = null
    private var expirationEpoch = 0L

    private fun expireCurrentSnapshot() {
        expirationJob?.cancel()
        val epoch = ++expirationEpoch
        expirationJob = scope.launch {
            delay(4L * 60L * 60L * 1_000L)
            if (expirationEpoch == epoch) {
                clearSearch()
                backAll()
            }
        }
    }

    private fun ensureDetailSnapshotExpiry() {
        // Search starts this clock on its own. Home/Explore can open the
        // exact same DetailPage without any Search visit, so they also need
        // an absolute expiration. Never reset an already running deadline
        // while opening a related Release or switching native pages.
        if (expirationJob?.isActive != true) expireCurrentSnapshot()
    }

    private var searchPage = 0
    private var releaseSearchPage = 0
    private var searchJob: Job? = null
    private var releaseSearchJob: Job? = null
    private var detailJob: Job? = null
    // Direct Search -> Release -> related Release retains the original native
    // album on Back. Artist -> Release continues to use cachedArtistPage.
    private var directReleaseHistory: List<DiscogsNativePage> = emptyList()
    private var versionHistory: List<DiscogsNativePage> = emptyList()
    private var artist: DiscogsNativeArtistSearchRow? = null
    private var releases: List<DiscogsNativeArtistRelease> = emptyList()
    private var artistPage = 0
    private var artistHasMore = false
    private var artistIncomplete = false
    private var cachedArtistPage: DiscogsNativePage? = null

    private fun lease(capability: ProviderCapability): ProviderReadLease? =
        ProviderReadLease.acquire(ProviderRuntime.gateOrNull(),
            listOf(capability to BuiltInProviderKeys.DISCOGS))

    /** Keep the very same native artist/album header on network failure. */
    private fun editorialLease(): ProviderReadLease? =
        ProviderReadLease.acquire(
            ProviderRuntime.gateOrNull(),
            listOf(
                ProviderCapability.CATALOG_SEARCH to BuiltInProviderKeys.DISCOGS,
                ProviderCapability.EDITORIAL_DISCOGRAPHY to BuiltInProviderKeys.DISCOGS,
            ),
        )

    /**
     * Source-only Home discovery: a small bounded cross-genre sample, with
     * exact Discogs IDs and no implied chart ranking or false latest-date.
     * Providers are independently selectable; MB/iTunes need not be enabled.
     */
    fun loadHomeEditorial(force: Boolean = false) {
        val now = System.currentTimeMillis()
        // Authorization is checked BEFORE cache reuse. A capability revoked
        // while the old snapshot is still fresh must not keep its cards live.
        val lease = editorialLease()
        if (lease == null) {
            homeEditorialJob?.cancel()
            homePagingJob?.cancel()
            ++homePagingEpoch
            _homePaging.value = DiscogsHomePageSnapshot()
            homeEditorialExpiryJob?.cancel()
            ++homeEditorialExpiryEpoch
            homeEditorialAtMs = 0L
            _homeEditorial.value = UiState.Error(
                "Ative Busca e Discografia do Discogs em Provedores.",
            )
            return
        }
        if (!force && (homeEditorialJob?.isActive == true ||
            discogsEditorialSnapshotReusable(
                homeEditorialAtMs, now, _homeEditorial.value,
            )
        )) return
        homeEditorialJob?.cancel()
        homePagingJob?.cancel()
        val editorialEpoch = ++homePagingEpoch
        _homePaging.value = DiscogsHomePageSnapshot()
        homeEditorialAtMs = now
        scheduleHomeEditorialExpiry()
        _homeEditorial.value = UiState.Loading
        val job = scope.launch(Dispatchers.IO) {
            val verified = mutableListOf<DiscogsEditorialCandidate>()
            var failure: String? = null
            val landingCursors = mutableMapOf<DiscogsEditorialQuery, DiscogsEditorialCursor>()
            // Diverse verified catalog slices instead of only Pop and Rock.
            // Publish incrementally and stop at the bounded Home target; no
            // implied chart order, genre-wide completeness or fabricated art.
            for ((index, tag) in discogsHomeDiscoveryTags.withIndex()) {
                val distinctMasters = verified.distinctBy { it.masterId }.size
                if (distinctMasters >= 10) break
                val request = discogsEditorialQueriesForTag(tag).firstOrNull() ?: continue
                // Reserve one verified slot for each later genre, so success
                // in Pop/Rock cannot prevent K-pop, Metal, R&B and Folk
                // from even being queried. This is discovery, not a chart.
                val budget = discogsMultiStylePageBudget(
                    verifiedMasters = distinctMasters,
                    remainingQueries = discogsHomeDiscoveryTags.size - index - 1,
                    totalSlots = 10,
                ).coerceAtMost(2)
                val inflight = mutableListOf<DiscogsEditorialCandidate>()
                val result = try {
                    editorialClient.discoverDistinct(
                        request, maxReleases = budget,
                        knownMasterIds = verified.map { it.masterId }.toSet(),
                        onCheckpoint = { landingCursors[request] = it },
                        onVerified = { candidate ->
                            if (lease.isCurrent() && homePagingEpoch == editorialEpoch &&
                                candidate.masterId !in verified.map { it.masterId } &&
                                inflight.none { it.masterId == candidate.masterId }
                            ) {
                                inflight += candidate
                                val preview = discogsEditorialShelves(
                                    verified + inflight, home = true,
                                )
                                if (preview.isNotEmpty())
                                    _homeEditorial.value = UiState.Success(preview)
                            }
                        },
                    )
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { DiscogsEditorialResult.Unavailable }
                if (!lease.isCurrent() || homePagingEpoch != editorialEpoch) return@launch
                when (result) {
                    is DiscogsEditorialResult.Verified -> {
                        verified += result.releases
                        val shelves = discogsEditorialShelves(verified, home = true)
                        if (shelves.isNotEmpty()) _homeEditorial.value = UiState.Success(shelves)
                    }
                    DiscogsEditorialResult.Unauthorized -> {
                        failure = "Configure o token do Discogs em Conexões."
                        break
                    }
                    DiscogsEditorialResult.Disabled -> {
                        failure = "Ative Busca e Discografia do Discogs em Provedores."
                        break
                    }
                    is DiscogsEditorialResult.Throttled -> {
                        failure = "O Discogs limitou temporariamente as consultas."
                        break
                    }
                    else -> failure = "Não foi possível consultar o catálogo Discogs."
                }
            }
            if (!lease.isCurrent() || homePagingEpoch != editorialEpoch) return@launch
            val shelves = discogsEditorialShelves(verified, home = true)
            _homeEditorial.value = if (shelves.isNotEmpty()) UiState.Success(shelves)
                else UiState.Error(failure ?: "Nenhum lançamento Discogs verificado nesta amostra.")
            if (shelves.isNotEmpty()) {
                val granularQueries = discogsHomeGranularQueries()
                _homePaging.value = initialDiscogsHomePageSnapshot(
                    verified, granularQueries.size,
                    granularQueries.mapIndexedNotNull { index, predicate ->
                        landingCursors[predicate]?.let { index to it }
                    }.toMap(),
                )
            }
        }
        homeEditorialJob = job
        val unregister = lease.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
    }

    /**
     * Round-robin Home continuations query real Discogs Genre/Style predicates.
     * Each gesture checks at most twelve source-index candidates, no more than
     * three four-position chunks. Already verified Masters are never repeated.
     */
    fun loadMoreHomeEditorial() {
        val current = _homePaging.value
        if (!current.hasMore || current.loading ||
            homeEditorialJob?.isActive == true || homePagingJob?.isActive == true ||
            (_homeEditorial.value as? UiState.Success<List<HomeShelf>>)?.data.isNullOrEmpty()
        ) return
        val queries = discogsHomeGranularQueries()
        if (queries.size != current.cursors.size) return
        val lease = editorialLease() ?: return
        val epoch = ++homePagingEpoch
        _homePaging.value = current.copy(loading = true, failed = false)
        val job = scope.launch(Dispatchers.IO) {
            val gesture = scanDiscogsHomeGesture(
                current, queries, editorialClient,
                stillCurrent = { lease.isCurrent() && homePagingEpoch == epoch },
                onVerified = { candidate ->
                    val visible = _homePaging.value
                    if (candidate.masterId !in visible.knownMasterIds) {
                        _homePaging.value = visible.copy(
                            knownMasterIds = visible.knownMasterIds + candidate.masterId,
                            releases = visible.releases + candidate, loading = true,
                        )
                    }
                },
            ) ?: return@launch
            if (lease.isCurrent() && homePagingEpoch == epoch)
                _homePaging.value = gesture.state
        }
        homePagingJob = job
        val unregister = lease.cancelJobOnRevoke(job)
        job.invokeOnCompletion {
            unregister()
            if (homePagingEpoch == epoch) {
                _homePaging.value = if (!lease.isCurrent()) DiscogsHomePageSnapshot()
                else _homePaging.value.copy(loading = false)
            }
        }
    }

    /** Typed Discogs Genre or Style, verified by hydrated source Master data. */
    fun loadGenreEditorial(tag: String, force: Boolean = false) {
        if (canonicalAllExploreGenres.none { it.tag == tag }) return
        val now = System.currentTimeMillis()
        val lease = editorialLease()
        if (lease == null) {
            genreEditorialJob?.cancel()
            genrePagingJob?.cancel()
            ++genrePagingEpoch
            _genrePaging.value = DiscogsGenrePageSnapshot()
            genreEditorialExpiryJob?.cancel()
            ++genreEditorialExpiryEpoch
            genreEditorialAtMs = 0L
            _genreEditorial.value = mapOf(tag to UiState.Error(
                "Ative Busca e Discografia do Discogs em Provedores.",
            ))
            return
        }
        if (!force && genreEditorialTag == tag &&
            (genreEditorialJob?.isActive == true ||
                discogsEditorialSnapshotReusable(
                    genreEditorialAtMs, now, _genreEditorial.value[tag],
                ))
        ) return
        genreEditorialJob?.cancel()
        genrePagingJob?.cancel()
        val editorialEpoch = ++genrePagingEpoch
        _genrePaging.value = DiscogsGenrePageSnapshot(tag = tag)
        genreEditorialTag = tag
        genreEditorialAtMs = now
        val queries = discogsEditorialQueriesForTag(tag)
        if (queries.isEmpty()) {
            genreEditorialAtMs = 0L
            genreEditorialExpiryJob?.cancel()
            ++genreEditorialExpiryEpoch
            _genreEditorial.value = mapOf(tag to UiState.Error(
                "Este gênero ainda não possui correspondência verificada no Discogs.",
            ))
            return
        }
        scheduleGenreEditorialExpiry()
        _genreEditorial.value = mapOf(tag to UiState.Loading)
        val job = scope.launch(Dispatchers.IO) {
            val landing = scanDiscogsGenreLanding(
                queries, editorialClient,
                stillCurrent = { lease.isCurrent() && genrePagingEpoch == editorialEpoch &&
                    genreEditorialTag == tag },
                onProgress = { rows ->
                    val preview = discogsEditorialShelves(rows)
                    if (preview.isNotEmpty()) _genreEditorial.value =
                        mapOf(tag to UiState.Success(preview))
                },
            ) ?: return@launch
            if (!lease.isCurrent() || genrePagingEpoch != editorialEpoch ||
                genreEditorialTag != tag
            ) return@launch
            val failure = when (landing.failure) {
                DiscogsEditorialResult.Unauthorized -> "Configure o token do Discogs em Conexões."
                DiscogsEditorialResult.Disabled -> "Ative Busca e Discografia do Discogs em Provedores."
                is DiscogsEditorialResult.Throttled -> "O Discogs limitou temporariamente as consultas."
                null -> null
                else -> "Não foi possível consultar este gênero no Discogs."
            }
            val shelves = discogsEditorialShelves(landing.releases)
            _genreEditorial.value = mapOf(tag to if (shelves.isNotEmpty()) {
                UiState.Success(shelves)
            } else UiState.Error(failure ?:
                "Nenhum lançamento com classificação Discogs verificada nesta amostra."))
            if (shelves.isNotEmpty()) _genrePaging.value = landing.paging(tag)
        }
        genreEditorialJob = job
        val unregister = lease.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
    }

    /** Bounded, scroll-triggered Discogs candidate scan on the SAME genre page. */
    fun loadMoreGenreEditorial(tag: String) {
        if (genreEditorialTag != tag || genreEditorialJob?.isActive == true ||
            (_genreEditorial.value[tag] as? UiState.Success<List<HomeShelf>>)?.data.isNullOrEmpty()
        ) return
        val current = _genrePaging.value
        if (current.cursor == null) return
        val queries = discogsEditorialQueriesForTag(tag)
        if (queries.getOrNull(current.queryIndex) == null) return
        if (current.tag != tag || current.loading ||
            genrePagingJob?.isActive == true
        ) return
        val lease = editorialLease() ?: return
        val epoch = ++genrePagingEpoch
        _genrePaging.value = current.copy(loading = true, failed = false)
        val job = scope.launch(Dispatchers.IO) {
            val gesture = scanDiscogsGenreGesture(
                current, tag, queries, editorialClient,
                stillCurrent = { lease.isCurrent() && genrePagingEpoch == epoch &&
                    genreEditorialTag == tag },
                onVerified = { candidate ->
                    appendDiscogsGenreVerified(_genrePaging.value, tag, candidate)?.let {
                        _genrePaging.value = it
                    }
                },
            ) ?: return@launch
            if (lease.isCurrent() && genrePagingEpoch == epoch && genreEditorialTag == tag)
                _genrePaging.value = gesture.state
        }
        genrePagingJob = job
        val unregister = lease.cancelJobOnRevoke(job)
        job.invokeOnCompletion {
            unregister()
            if (genrePagingEpoch == epoch && _genrePaging.value.tag == tag &&
                _genrePaging.value.loading
            ) _genrePaging.value = _genrePaging.value.copy(
                loading = false, failed = true,
            )
        }
    }

    private fun failDetail(message: String) {
        val current = (_detail.value as? UiState.Success)?.data
        _detail.value = if (current == null) UiState.Error(message)
            else UiState.Success(current.copy(page = current.page.copy(
                songs = UiState.Error(message),
            )))
    }

    /** Removing consent closes even previously painted provider-owned results. */
    fun purgeRevoked() {
        if (editorialLease() == null) {
            homeEditorialJob?.cancel()
            homePagingJob?.cancel()
            ++homePagingEpoch
            _homePaging.value = DiscogsHomePageSnapshot()
            genreEditorialJob?.cancel()
            genrePagingJob?.cancel()
            ++genrePagingEpoch
            _genrePaging.value = DiscogsGenrePageSnapshot()
            homeEditorialExpiryJob?.cancel()
            genreEditorialExpiryJob?.cancel()
            ++homeEditorialExpiryEpoch
            ++genreEditorialExpiryEpoch
            homeEditorialAtMs = 0L
            genreEditorialAtMs = 0L
            _homeEditorial.value = UiState.Loading
            _genreEditorial.value = emptyMap()
        }
        if (lease(ProviderCapability.CATALOG_SEARCH) == null) clearSearch()
        if (lease(ProviderCapability.EDITORIAL_DISCOGRAPHY) == null) backAll()
    }

    fun clearSearch() {
        searchJob?.cancel()
        releaseSearchJob?.cancel()
        searchJob = null
        releaseSearchJob = null
        query = ""
        searchPage = 0
        releaseSearchPage = 0
        _searchHasMore.value = false
        _releaseSearchHasMore.value = false
        _artists.value = null
        _releasesSearch.value = null
    }

    fun search(raw: String) {
        clearSearch()
        backAll()
        query = raw.trim()
        if (query.isBlank()) return
        expireCurrentSnapshot()
        _artists.value = UiState.Loading
        _releasesSearch.value = UiState.Loading
        loadNextSearchPage()
        loadNextReleaseSearchPage()
    }

    fun loadNextSearchPage() {
        if (query.isBlank() || searchJob?.isActive == true) return
        if (searchPage != 0 && !_searchHasMore.value) return
        val subject = query
        val firstPage = searchPage + 1
        val authorization = lease(ProviderCapability.CATALOG_SEARCH)
        if (authorization == null) {
            _artists.value = UiState.Error(
                if (!tokenVault.configured()) "discogs_token_required"
                else "discogs_search_disabled",
            )
            return
        }
        val job = scope.launch(Dispatchers.IO) {
            var currentPage = firstPage
            while (true) {
                val result = try { searchClient.search(subject, currentPage) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { DiscogsNativeArtistSearchResult.Unavailable }
                if (query != subject || !authorization.isCurrent()) return@launch
                when (result) {
                    is DiscogsNativeArtistSearchResult.Success -> {
                        val rows = result.page.artists.asSequence()
                            .map { DiscogsNativeArtistSearchRow(it.artistId, it.name) }
                            .filter { discogsSearchArtistRank(subject, it.name) != null }
                            .toList()
                        val previous = (_artists.value as? UiState.Success)?.data.orEmpty()
                        searchPage = currentPage
                        _searchHasMore.value = result.page.nextPage != null
                        // Don't show "nothing found" merely because the
                        // public Discogs ranking filled page one with junk.
                        if (discogsSearchNeedsNextRelevantPage(
                                previous.size, rows.size, result.page.nextPage,
                            )
                        ) {
                            currentPage = requireNotNull(result.page.nextPage)
                            continue
                        }
                        // "(2)" suffixes are Discogs disambiguators, not
                        // visible artist names. The native Artist tab must
                        // collapse duplicate display names just like General,
                        // while a click retains the selected exact source ID.
                        // We never claim that two Discogs IDs are the same
                        // identity outside this search presentation.
                        val candidates = discogsSearchDistinctArtists(
                            subject, (previous + rows).distinctBy { it.artistId },
                        )
                        val exact = candidates.any {
                            (discogsSearchArtistRank(subject, it.name) ?: 99) <= 2
                        }
                        _artists.value = UiState.Success(
                            if (exact) candidates.filter {
                                (discogsSearchArtistRank(subject, it.name) ?: 99) <= 2
                            } else candidates.take(12),
                        )
                    }
                    DiscogsNativeArtistSearchResult.AuthenticationRequired ->
                        _artists.value = UiState.Error("discogs_token_required")
                    DiscogsNativeArtistSearchResult.Disabled ->
                        _artists.value = UiState.Error("discogs_search_disabled")
                    else -> {
                        if (currentPage > 1 && _artists.value is UiState.Success)
                            _searchHasMore.value = true
                        else _artists.value = UiState.Error("discogs_catalog_unavailable")
                    }
                }
                break
            }
        }
        searchJob = job
        val unregister = authorization.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
    }

    fun loadNextReleaseSearchPage() {
        if (query.isBlank() || releaseSearchJob?.isActive == true) return
        if (releaseSearchPage != 0 && !_releaseSearchHasMore.value) return
        val subject = query
        val firstPage = releaseSearchPage + 1
        val authorization = lease(ProviderCapability.CATALOG_SEARCH)
        if (authorization == null) {
            _releasesSearch.value = UiState.Error(
                if (!tokenVault.configured()) "discogs_token_required"
                else "discogs_search_disabled",
            )
            return
        }
        val job = scope.launch(Dispatchers.IO) {
            var currentPage = firstPage
            while (true) {
                val result = try { releaseSearchClient.search(subject, currentPage) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { DiscogsNativeReleaseSearchResult.Unavailable }
                if (query != subject || !authorization.isCurrent()) return@launch
                when (result) {
                    is DiscogsNativeReleaseSearchResult.Success -> {
                        val rows = result.page.releases.asSequence()
                            .map { DiscogsNativeReleaseSearchRow(it.releaseId, it.title) }
                            .filter { discogsSearchReleaseRank(subject, it.title) != null }
                            .toList()
                        val previous =
                            (_releasesSearch.value as? UiState.Success)?.data.orEmpty()
                        releaseSearchPage = currentPage
                        _releaseSearchHasMore.value = result.page.nextPage != null
                        if (discogsSearchNeedsNextRelevantPage(
                                previous.size, rows.size, result.page.nextPage,
                            )
                        ) {
                            currentPage = requireNotNull(result.page.nextPage)
                            continue
                        }
                        _releasesSearch.value = UiState.Success(
                            (previous + rows).distinctBy { it.releaseId }
                                .sortedWith(compareBy<DiscogsNativeReleaseSearchRow> {
                                    discogsSearchReleaseRank(subject, it.title)
                                        ?: Int.MAX_VALUE
                                }.thenBy { it.releaseId }),
                        )
                    }
                    DiscogsNativeReleaseSearchResult.AuthenticationRequired ->
                        _releasesSearch.value = UiState.Error("discogs_token_required")
                    DiscogsNativeReleaseSearchResult.Disabled ->
                        _releasesSearch.value = UiState.Error("discogs_search_disabled")
                    else -> {
                        if (currentPage > 1 && _releasesSearch.value is UiState.Success)
                            _releaseSearchHasMore.value = true
                        else _releasesSearch.value =
                            UiState.Error("discogs_catalog_unavailable")
                    }
                }
                break
            }
        }
        releaseSearchJob = job
        val unregister = authorization.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
    }

    private data class VerifiedVersionSample(
        val records: List<DiscogsRecord> = emptyList(),
        val incomplete: Boolean = false,
    )

    /**
     * A Master version index supplies candidate IDs ONLY. For a native
     * versions carousel we independently hydrate up to three editions and
     * verify their Master membership, unofficial flag and exact Release IDs.
     * The exhaustive edition cross-source matcher is intentionally separate.
     */
    private suspend fun sampleVerifiedVersions(
        source: DiscogsRecord,
    ): VerifiedVersionSample {
        val masterId = source.masterId ?: return VerifiedVersionSample()
        if (source.unofficial || source.link.kind != DiscogsKind.RELEASE)
            return VerifiedVersionSample()
        val index = try { editionClient.fetchMasterVersionPreview(masterId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DiscogsMasterVersionPreviewResult.Unavailable }
        // A Release with an explicit Master link may have other editions.
        // Failure to read its version index is uncertainty, not proof that
        // no other editions exist. Do not invent a Versões shelf.
        val page = (index as? DiscogsMasterVersionPreviewResult.Page)?.result
            ?: return VerifiedVersionSample(incomplete = true)
        val candidates = page.releaseIds.filter { it != source.link.id }
        val verified = mutableListOf<DiscogsRecord>()
        var incomplete = page.hasMore || candidates.size > 3
        for (id in candidates.take(3)) {
            val response = try { editionClient.fetchRelease(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsLookupResult.Unavailable }
            val record = (response as? DiscogsLookupResult.Found)?.record
            if (record == null || record.link.kind != DiscogsKind.RELEASE ||
                record.link.id != id || record.masterId != masterId ||
                record.unofficial
            ) {
                incomplete = true
                if (response is DiscogsLookupResult.Throttled ||
                    response is DiscogsLookupResult.Disabled ||
                    response is DiscogsLookupResult.Unauthorized
                ) break
                continue
            }
            verified += record
        }
        return VerifiedVersionSample(verified, incomplete)
    }

    /**
     * Exact typed Discogs Release search selection. Hydrate the chosen source
     * ID directly, with no artist page or MusicBrainz requirement.
     */
    fun openReleaseFromSearch(
        selected: DiscogsNativeReleaseSearchRow,
        retainDirectParent: Boolean = false,
        expectedMasterId: Long? = null,
    ) {
        if (selected.releaseId !in 1..999_999_999_999L || selected.title.isBlank()) return
        val previous = if (retainDirectParent && artist == null &&
            _route.value == DiscogsNativeRoute.RELEASE
        ) (_detail.value as? UiState.Success)?.data?.takeIf {
            it.page.type == BrowseType.ALBUM && it.page.browseId == _browseId.value
        } else null
        val history = if (previous != null)
            (directReleaseHistory + previous).takeLast(8) else emptyList()
        backAll()
        ensureDetailSnapshotExpiry()
        directReleaseHistory = history
        _browseId.value = selected.browseId
        _route.value = DiscogsNativeRoute.RELEASE
        _detail.value = UiState.Success(discogsNativeLoadingPage(
            selected.browseId, selected.title, "Lançamento", BrowseType.ALBUM,
        ))
        val authorization = lease(ProviderCapability.EDITORIAL_DISCOGRAPHY)
        if (authorization == null) {
            failDetail("Ative a Discografia do Discogs em Provedores.")
            return
        }
        val job = scope.launch(Dispatchers.IO) {
            val response = try { editionClient.fetchRelease(selected.releaseId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsLookupResult.Unavailable }
            if (!authorization.isCurrent() || artist != null ||
                _route.value != DiscogsNativeRoute.RELEASE) return@launch
            val record = (response as? DiscogsLookupResult.Found)?.record
            if (record == null || record.link.kind != DiscogsKind.RELEASE ||
                record.link.id != selected.releaseId ||
                (expectedMasterId != null &&
                    (record.masterId != expectedMasterId || record.unofficial)) ||
                _browseId.value != selected.browseId
            ) {
                failDetail("Não foi possível carregar esta edição Discogs.")
                return@launch
            }
            // Paint the canonical album immediately, then enrich the SAME
            // DetailPage with a bounded source-verified editions sample.
            val credit = record.artistNames.joinToString(", ")
            val initial = discogsReleasePage(credit, record)
            _detail.value = UiState.Success(initial)
            val variants = sampleVerifiedVersions(record)
            if (!authorization.isCurrent() || artist != null ||
                _route.value != DiscogsNativeRoute.RELEASE ||
                _browseId.value != selected.browseId
            ) return@launch
            val withVersions = discogsReleasePage(
                credit, record, versionRecords = variants.records,
                versionsIncomplete = variants.incomplete,
            )
            if (variants.records.isNotEmpty() || variants.incomplete) {
                _detail.value = UiState.Success(withVersions)
            }
            val exactArtistId = initial.albumArtistBrowseId
                ?.removePrefix("discogs:artist:")?.toLongOrNull()
                ?.takeIf { !record.unofficial } ?: return@launch
            // Only one bounded, independently verified Discogs Artist page.
            // Do not re-identify a release through a similar artist name.
            val related = try { artistClient.load(exactArtistId, 1) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsNativeArtistLoad.Unavailable }
            if (!authorization.isCurrent() || artist != null ||
                _route.value != DiscogsNativeRoute.RELEASE ||
                _browseId.value != selected.browseId
            ) return@launch
            if (related is DiscogsNativeArtistLoad.Success) {
                val enriched = discogsReleasePage(
                    record.artistNames.single(), record, related.releases,
                    versionRecords = variants.records,
                    versionsIncomplete = variants.incomplete,
                )
                _detail.value = UiState.Success(enriched.copy(
                    incomplete = enriched.incomplete ||
                        related.incomplete || related.hasMore,
                ))
            }
        }
        detailJob = job
        val unregister = authorization.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
    }

    /**
     * Home, Explore and Search route through the SAME source-owned catalog
     * session. A malformed Discogs ID is consumed (fail-closed), never
     * handed to a legacy album screen or a name-based search.
     */
    fun openFromShelf(item: ShelfItem): Boolean {
        val browseId = item.browseId ?: return false
        if (!browseId.startsWith("discogs:")) return false
        when {
            discogsNativeDetailRouteKey(DiscogsNativeRoute.RELEASE, browseId) != null ->
                openReleaseFromSearch(DiscogsNativeReleaseSearchRow(
                    browseId.substringAfterLast(':').toLong(), item.title,
                ))
            discogsNativeDetailRouteKey(DiscogsNativeRoute.ARTIST, browseId) != null ->
                openArtist(DiscogsNativeArtistSearchRow(
                    browseId.substringAfterLast(':').toLong(), item.title,
                ))
        }
        return true
    }

    fun openArtist(selected: DiscogsNativeArtistSearchRow) {
        if (selected.artistId !in 1..999_999_999_999L || selected.name.isBlank()) return
        backAll()
        ensureDetailSnapshotExpiry()
        artist = selected
        _browseId.value = selected.browseId
        _route.value = DiscogsNativeRoute.ARTIST
        _detail.value = UiState.Success(discogsNativeLoadingPage(
            selected.browseId, discogsArtistDisplayName(selected.name),
            "Artista", BrowseType.ARTIST,
        ))
        loadNextArtistPage()
    }

    fun loadNextArtistPage() {
        val current = artist ?: return
        if (_route.value != DiscogsNativeRoute.ARTIST ||
            detailJob?.isActive == true || (artistPage != 0 && !artistHasMore)) return
        val authorization = lease(ProviderCapability.EDITORIAL_DISCOGRAPHY)
        if (authorization == null) {
            failDetail("Ative a Discografia do Discogs em Provedores.")
            return
        }
        val page = artistPage + 1
        val job = scope.launch(Dispatchers.IO) {
            val window = try {
                artistClient.loadWindow(current.artistId, page, releases) { verified ->
                    if (artist == current && authorization.isCurrent() &&
                        _route.value == DiscogsNativeRoute.ARTIST &&
                        _browseId.value == current.browseId
                    ) {
                        val partial = discogsMergeArtistPageReleases(
                            releases + verified,
                        )
                        val snapshot = discogsArtistPage(
                            current, partial, hasMore = false, incomplete = true,
                        )
                        cachedArtistPage = snapshot
                        _detail.value = UiState.Success(snapshot)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) {
                  DiscogsNativeArtistWindow(DiscogsNativeArtistLoad.Unavailable)
              }
            if (artist != current || !authorization.isCurrent() ||
                _route.value != DiscogsNativeRoute.ARTIST ||
                _browseId.value != current.browseId) return@launch
            when (val response = window.result) {
                is DiscogsNativeArtistLoad.Success -> {
                    releases = discogsMergeArtistPageReleases(releases + response.releases)
                    // Only settled source pages advance; the last interrupted
                    // page stays retryable even after a productive earlier page.
                    artistPage += window.completedPages
                    artistHasMore = response.hasMore || response.retryCurrentPage
                    artistIncomplete = artistIncomplete || window.settledIncomplete
                    val result = discogsArtistPage(current, releases,
                        artistHasMore, artistIncomplete || response.retryCurrentPage)
                    cachedArtistPage = result
                    _detail.value = UiState.Success(result)
                }
                DiscogsNativeArtistLoad.Disabled ->
                    failDetail("Ative a Discografia do Discogs em Provedores.")
                else -> {
                    if (cachedArtistPage == null) {
                        failDetail("Não foi possível carregar este artista Discogs.")
                    } else {
                        _detail.value = UiState.Success(cachedArtistPage!!.copy(
                            incomplete = true,
                        ))
                    }
                }
            }
        }
        detailJob = job
        val unregister = authorization.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
    }

    private fun openVerifiedArtistVersion(
        selected: DiscogsNativeReleaseSearchRow,
        current: DiscogsNativePage,
        expectedMasterId: Long,
        parent: DiscogsNativeArtistSearchRow,
    ) {
        val authorization = lease(ProviderCapability.EDITORIAL_DISCOGRAPHY) ?: return
        detailJob?.cancel()
        versionHistory = (versionHistory + current).takeLast(8)
        _browseId.value = selected.browseId
        _route.value = DiscogsNativeRoute.RELEASE
        _detail.value = UiState.Success(discogsNativeLoadingPage(
            selected.browseId, selected.title, parent.name, BrowseType.ALBUM,
        ))
        val job = scope.launch(Dispatchers.IO) {
            val response = try { editionClient.fetchRelease(selected.releaseId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsLookupResult.Unavailable }
            if (!authorization.isCurrent() || artist != parent ||
                _route.value != DiscogsNativeRoute.RELEASE ||
                _browseId.value != selected.browseId) return@launch
            val record = (response as? DiscogsLookupResult.Found)?.record
            if (record == null || record.link.id != selected.releaseId ||
                record.link.kind != DiscogsKind.RELEASE ||
                record.masterId != expectedMasterId || record.unofficial ||
                parent.artistId !in record.artistIds
            ) {
                failDetail("Não foi possível verificar esta versão Discogs.")
                return@launch
            }
            _detail.value = UiState.Success(discogsReleasePage(parent, record, releases))
            val variants = sampleVerifiedVersions(record)
            if (!authorization.isCurrent() || artist != parent ||
                _route.value != DiscogsNativeRoute.RELEASE ||
                _browseId.value != selected.browseId) return@launch
            _detail.value = UiState.Success(discogsReleasePage(
                parent, record, releases,
                versionRecords = variants.records,
                versionsIncomplete = variants.incomplete,
            ))
        }
        detailJob = job
        val unregister = authorization.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
    }

    fun openRelease(browseId: String) {
        val parent = artist
        val current = (_detail.value as? UiState.Success)?.data
            ?.takeIf { _route.value == DiscogsNativeRoute.RELEASE &&
                it.page.browseId == _browseId.value }
        val verifiedVersion = current?.let {
            discogsVerifiedVersionSelection(it, browseId)
        }
        if (verifiedVersion != null) {
            val versionParent = current ?: return
            val masterId = versionParent.versionMasterId ?: return
            if (parent != null) {
                openVerifiedArtistVersion(verifiedVersion, versionParent, masterId, parent)
            } else {
                openReleaseFromSearch(
                    verifiedVersion, retainDirectParent = true,
                    expectedMasterId = masterId,
                )
            }
            return
        }
        if (parent == null) {
            // A directly opened source edition can navigate ONLY its verified
            // "Mais do artista" entries; return to the parent album on Back.
            val live = current ?: return
            val related = live.page.sections
                .filter { it.title.startsWith("Mais de ") ||
                    it.title == "Mais do artista" }
                .flatMap { it.items }
                .singleOrNull { it.browseId == browseId } ?: return
            val exact = discogsNativeDetailRouteKey(
                DiscogsNativeRoute.RELEASE, browseId,
            )?.removePrefix("discogs:release:")?.toLongOrNull() ?: return
            openReleaseFromSearch(
                DiscogsNativeReleaseSearchRow(exact, related.title),
                retainDirectParent = true,
            )
            return
        }
        val release = releases.singleOrNull { it.browseId == browseId } ?: return
        val authorization = lease(ProviderCapability.EDITORIAL_DISCOGRAPHY) ?: return
        detailJob?.cancel()
        // This opens another work from the artist's discography, not a
        // pressing of the current work. Discard its separate version stack.
        versionHistory = emptyList()
        _browseId.value = release.browseId
        _route.value = DiscogsNativeRoute.RELEASE
        _detail.value = UiState.Success(discogsNativeLoadingPage(
            release.browseId, release.title, parent.name, BrowseType.ALBUM,
        ))
        val job = scope.launch(Dispatchers.IO) {
            val response = try { editionClient.fetchRelease(release.releaseId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsLookupResult.Unavailable }
            if (!authorization.isCurrent() || artist != parent ||
                _route.value != DiscogsNativeRoute.RELEASE) return@launch
            val record = (response as? DiscogsLookupResult.Found)?.record
            if (record == null || record.unofficial ||
                record.link.kind != DiscogsKind.RELEASE ||
                record.link.id != release.releaseId ||
                parent.artistId !in record.artistIds ||
                _browseId.value != release.browseId
            ) {
                failDetail("Não foi possível carregar esta edição Discogs.")
                return@launch
            }
            _detail.value = UiState.Success(discogsReleasePage(parent, record, releases))
            val variants = sampleVerifiedVersions(record)
            if (!authorization.isCurrent() || artist != parent ||
                _route.value != DiscogsNativeRoute.RELEASE ||
                _browseId.value != release.browseId) return@launch
            _detail.value = UiState.Success(discogsReleasePage(
                parent, record, releases,
                versionRecords = variants.records,
                versionsIncomplete = variants.incomplete,
            ))
        }
        detailJob = job
        val unregister = authorization.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
    }

    fun back() {
        detailJob?.cancel()
        detailJob = null
        if (_route.value == DiscogsNativeRoute.RELEASE &&
            versionHistory.isNotEmpty()
        ) {
            val previous = versionHistory.last()
            versionHistory = versionHistory.dropLast(1)
            _browseId.value = previous.page.browseId
            _route.value = DiscogsNativeRoute.RELEASE
            _detail.value = UiState.Success(previous)
        } else if (_route.value == DiscogsNativeRoute.RELEASE && artist != null) {
            _browseId.value = artist?.browseId
            _route.value = DiscogsNativeRoute.ARTIST
            _detail.value = cachedArtistPage?.let { UiState.Success(it) }
                ?: UiState.Error("discogs_catalog_unavailable")
        } else if (_route.value == DiscogsNativeRoute.RELEASE &&
            directReleaseHistory.isNotEmpty()
        ) {
            val previous = directReleaseHistory.last()
            directReleaseHistory = directReleaseHistory.dropLast(1)
            _browseId.value = previous.page.browseId
            _route.value = DiscogsNativeRoute.RELEASE
            _detail.value = UiState.Success(previous)
        } else backAll()
    }

    fun backAll() {
        detailJob?.cancel()
        detailJob = null
        _route.value = null
        _browseId.value = null
        _detail.value = null
        directReleaseHistory = emptyList()
        versionHistory = emptyList()
        artist = null
        releases = emptyList()
        artistPage = 0
        artistHasMore = false
        artistIncomplete = false
        cachedArtistPage = null
        if (_artists.value == null && _releasesSearch.value == null) {
            expirationJob?.cancel()
            expirationJob = null
            expirationEpoch++
        }
    }
}

/** Exact Discogs format descriptors, never titles or MusicBrainz types. */
internal fun discogsArtistPage(
    artist: DiscogsNativeArtistSearchRow,
    releases: List<DiscogsNativeArtistRelease>,
    hasMore: Boolean,
    incomplete: Boolean,
): DiscogsNativePage {
    fun has(release: DiscogsNativeArtistRelease, label: String) =
        release.formats.any { it.equals(label, ignoreCase = true) }
    val verified = discogsMergeArtistPageReleases(releases.filter {
        it.artistId == artist.artistId && it.releaseId in 1..999_999_999_999L &&
            it.title.isNotBlank()
    })
    val buckets = verified.groupBy { release ->
        val album = has(release, "Album")
        val epOrSingle = has(release, "EP") || has(release, "Single")
        val live = has(release, "Live")
        val compilation = has(release, "Compilation")
        when {
            // Contradictory source labels cannot be arbitrarily assigned to
            // one of the confident categories. "Outros" means unconfirmed.
            (album && epOrSingle) || (live && compilation) ->
                "Outros lançamentos"
            album && live -> "Álbuns ao vivo"
            album && compilation -> "Coletâneas"
            epOrSingle -> "Singles e EPs"
            album -> "Álbuns"
            else -> "Outros lançamentos"
        }
    }
    val order = listOf("Álbuns", "Singles e EPs", "Álbuns ao vivo",
        "Coletâneas", "Outros lançamentos")
    return DiscogsNativePage(
        page = DetailPage(
            browseId = artist.browseId,
            title = discogsArtistDisplayName(artist.name),
            subtitle = "Artista",
            thumbnailUrl = null,
            type = BrowseType.ARTIST,
            songs = UiState.Success(emptyList()),
            sections = order.mapNotNull { title ->
                buckets[title]?.takeIf { it.isNotEmpty() }?.let { group ->
                    HomeShelf(title = title, items = group.map { release ->
                        ShelfItem(
                            title = release.title,
                            subtitle = listOfNotNull(
                                release.year?.toString(),
                                release.formats.joinToString(" · ").takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            thumbnailUrl = null,
                            videoId = null,
                            browseId = release.browseId,
                        )
                    })
                }
            },
        ),
        // Preserve source/position keys and the release title as context.
        // A track on an artist's Release is not necessarily solely performed
        // by that artist. Do not synthesize a recording ID or play action.
        tracks = verified.asSequence().flatMap { release ->
            release.tracklist.asSequence().take(4).mapIndexed { index, (position, title) ->
                NativeMetadataTrack(
                    key = "discogs:track:${release.releaseId}:$index",
                    title = title, credit = release.title,
                    trackNumber = position.toIntOrNull()?.takeIf { it > 0 },
                    discNumber = null,
                )
            }
        }.filter { it.title.isNotBlank() }.distinctBy { it.key }.take(16).toList(),
        hasMore = hasMore,
        incomplete = incomplete,
        attributionUrl = "https://www.discogs.com/artist/${artist.artistId}",
    )
}

/**
 * The artist's Discogs index has already proved both the Release ID and its
 * source-scoped Artist ID. "Mais de" is another set of releases from that
 * artist, not the Unclassified/Outros bucket; never join by title or MBID.
 */
internal fun discogsAlbumArtistRelatedShelf(
    record: DiscogsRecord,
    artistCredit: String,
    candidates: List<DiscogsNativeArtistRelease>,
): HomeShelf? {
    if (record.unofficial || record.link.kind != DiscogsKind.RELEASE) return null
    val artistId = record.artistIds.singleOrNull()
        ?.takeIf { it in 1..999_999_999_999L } ?: return null
    val artistName = record.artistNames.singleOrNull()?.trim()?.takeIf {
        it.isNotBlank() && it.equals(artistCredit.trim(), ignoreCase = true)
    } ?: return null
    val safe = candidates.asSequence()
        .filter { it.artistId == artistId &&
            it.releaseId in 1..999_999_999_999L &&
            it.releaseId != record.link.id &&
            (record.masterId == null || it.masterId == null ||
                it.masterId != record.masterId) &&
            it.title.isNotBlank()
        }
        .groupBy { it.releaseId }
        .values.mapNotNull { group ->
            group.firstOrNull()?.takeIf { first -> group.all { it == first } }
        }
        .sortedWith(compareByDescending<DiscogsNativeArtistRelease> { it.year ?: 0 }
            .thenBy { it.releaseId })
        .take(12)
        .map { release ->
            ShelfItem(
                title = release.title,
                subtitle = listOfNotNull(
                    release.year?.toString(),
                    release.formats.joinToString(" · ").takeIf(String::isNotBlank),
                ).joinToString(" · "),
                thumbnailUrl = null, videoId = null, browseId = release.browseId,
            )
        }
    if (safe.isEmpty()) return null
    val heading = artistName.takeIf { it.length <= 80 &&
        !it.equals("Various Artists", ignoreCase = true)
    }?.let { "Mais de $it" } ?: "Mais do artista"
    return HomeShelf(heading, safe)
}

/** Values are copied only from the exact hydrated Discogs Release, not
 * inferred from an artist name, a Master or a search snippet. No market
 * pricing, imagery or invented track playback enters these native facts. */
internal fun discogsReleaseMetadataFacts(record: DiscogsRecord): List<Pair<String, String>> {
    if (record.link.kind != DiscogsKind.RELEASE) return emptyList()
    val facts = mutableListOf<Pair<String, String>>()
    record.year?.takeIf { it in 1800..2200 }?.let {
        facts += "Ano" to it.toString()
    }
    record.country?.trim()?.takeIf { it.isNotBlank() }?.let {
        facts += "País" to it.take(80)
    }
    val formats = record.formats.map { it.trim() }
        .filter { it.isNotBlank() }.distinct().take(8)
    if (formats.isNotEmpty()) facts += "Formatos" to formats.joinToString(" · ").take(240)
    val labels = record.labels.map { it.first.trim() }
        .filter { it.isNotBlank() }.distinct().take(4)
    if (labels.isNotEmpty()) facts += "Gravadoras" to labels.joinToString(" · ").take(240)
    val catalog = record.labels.map { it.second.trim() }
        .filter { it.isNotBlank() && it != "none" }.distinct().take(4)
    if (catalog.isNotEmpty()) facts += "Nº de catálogo" to
        catalog.joinToString(" · ").take(160)
    return facts
}

/**
 * Exact Master membership is proved by the hydrated Release, not by the
 * (potentially extensive) search/versions index. This is a bounded preview,
 * never an exhaustive edition-matching or ranking claim.
 */
internal fun discogsMasterVersionsShelf(
    source: DiscogsRecord,
    editionCandidates: List<DiscogsRecord>,
): HomeShelf? {
    val masterId = source.masterId?.takeIf { it in 1..999_999_999_999L }
        ?: return null
    if (source.link.kind != DiscogsKind.RELEASE) return null
    val verified = editionCandidates
        .filter { candidate ->
            candidate.link.kind == DiscogsKind.RELEASE &&
                candidate.link.id != source.link.id &&
                candidate.link.id in 1..999_999_999_999L &&
                candidate.masterId == masterId &&
                !candidate.unofficial && candidate.title.isNotBlank()
        }
        .groupBy { it.link.id }.values
        .mapNotNull { snapshots ->
            snapshots.firstOrNull()?.takeIf { first ->
                snapshots.all { it == first }
            }
        }.take(3)
    val items = verified.mapNotNull { record ->
        val subtitle = listOfNotNull(
            record.year?.toString(),
            record.country?.trim()?.takeIf(String::isNotBlank),
            record.formats.distinct().take(3).joinToString(" · ")
                .takeIf(String::isNotBlank),
        ).joinToString(" · ").take(240)
        // Two source pressings can have the same title. With no distinguishing
        // date/country/format, a second identical-looking card is just noise.
        if (subtitle.isBlank()) null else ShelfItem(
            title = record.title,
            subtitle = subtitle,
            thumbnailUrl = null, videoId = null,
            browseId = "discogs:release:${record.link.id}",
        )
    }
    return items.takeIf { it.isNotEmpty() }?.let { HomeShelf("Versões", it) }
}

/** A Discogs release's tracklist is non-playable source metadata only. */
internal fun discogsReleasePage(
    artist: DiscogsNativeArtistSearchRow,
    record: DiscogsRecord,
    relatedReleases: List<DiscogsNativeArtistRelease> = emptyList(),
    versionRecords: List<DiscogsRecord> = emptyList(),
    versionsIncomplete: Boolean = false,
): DiscogsNativePage = discogsReleasePage(
    artist.name, record, relatedReleases, versionRecords, versionsIncomplete,
)

internal fun discogsReleasePage(
    artistCredit: String,
    record: DiscogsRecord,
    relatedReleases: List<DiscogsNativeArtistRelease> = emptyList(),
    versionRecords: List<DiscogsRecord> = emptyList(),
    versionsIncomplete: Boolean = false,
): DiscogsNativePage = DiscogsNativePage(
    page = DetailPage(
        browseId = "discogs:release:${record.link.id}",
        title = record.title,
        subtitle = listOfNotNull(
            artistCredit.takeIf(String::isNotBlank),
            record.year?.toString(),
            "Edição não oficial".takeIf { record.unofficial },
        ).joinToString(" · "),
        thumbnailUrl = null,
        type = BrowseType.ALBUM,
        songs = UiState.Success(emptyList()),
        sections = listOfNotNull(
            discogsAlbumArtistRelatedShelf(record, artistCredit, relatedReleases),
            discogsMasterVersionsShelf(record, versionRecords),
        ),
    ),
    editionFacts = discogsReleaseMetadataFacts(record),
    tracks = record.tracklist.mapIndexed { index, (position, title) ->
        NativeMetadataTrack(
            key = "discogs:track:${record.link.id}:$index",
            title = title,
            credit = artistCredit.takeIf(String::isNotBlank),
            discNumber = null,
            trackNumber = position.toIntOrNull()?.takeIf { it > 0 },
        )
    },
    attributionUrl = record.attributionUrl,
    albumArtistBrowseId = record.artistIds.singleOrNull()
        ?.takeIf { it in 1..999_999_999_999L &&
            record.artistNames.singleOrNull()?.equals(
                artistCredit.trim(), ignoreCase = true,
            ) == true
        }?.let { "discogs:artist:$it" },
    albumArtistDisplayName = record.artistNames.singleOrNull()?.takeIf { name ->
        record.artistIds.singleOrNull()?.let { it in 1..999_999_999_999L } == true &&
            name.equals(artistCredit.trim(), ignoreCase = true)
    },
    versionMasterId = record.masterId,
    // Even with zero verified cards, an interrupted Master lookup means
    // unknown coverage. The native source footer communicates this without
    // presenting a fabricated Versions carousel.
    incomplete = versionsIncomplete,
)
