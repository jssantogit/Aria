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

internal data class DiscogsNativePage(
    val page: DetailPage,
    val tracks: List<NativeMetadataTrack> = emptyList(),
    val hasMore: Boolean = false,
    val incomplete: Boolean = false,
    val attributionUrl: String? = null,
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
    private val artistClient by lazy { DiscogsNativeArtistCatalog(enabled = true) }
    private val editionClient by lazy {
        DiscogsClient(
            enabled = true,
            capability = ProviderCapability.EDITORIAL_DISCOGRAPHY,
        )
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

    private var searchPage = 0
    private var releaseSearchPage = 0
    private var searchJob: Job? = null
    private var releaseSearchJob: Job? = null
    private var detailJob: Job? = null
    private var artist: DiscogsNativeArtistSearchRow? = null
    private var releases: List<DiscogsNativeArtistRelease> = emptyList()
    private var artistPage = 0
    private var artistHasMore = false
    private var artistIncomplete = false
    private var cachedArtistPage: DiscogsNativePage? = null

    private fun lease(capability: ProviderCapability): ProviderReadLease? =
        ProviderReadLease.acquire(ProviderRuntime.gateOrNull(),
            listOf(capability to BuiltInProviderKeys.DISCOGS))

    /** Removing consent closes even previously painted provider-owned results. */
    fun purgeRevoked() {
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
        val page = searchPage + 1
        val authorization = lease(ProviderCapability.CATALOG_SEARCH)
        if (authorization == null) {
            _artists.value = UiState.Error(
                if (!tokenVault.configured()) "discogs_token_required"
                else "discogs_search_disabled",
            )
            return
        }
        val job = scope.launch(Dispatchers.IO) {
            val result = try { searchClient.search(subject, page) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsNativeArtistSearchResult.Unavailable }
            if (query != subject || !authorization.isCurrent()) return@launch
            when (result) {
                is DiscogsNativeArtistSearchResult.Success -> {
                    val rows = result.page.artists.map {
                        DiscogsNativeArtistSearchRow(it.artistId, it.name)
                    }
                    val previous = (_artists.value as? UiState.Success)?.data.orEmpty()
                    _artists.value = UiState.Success(
                        (previous + rows).distinctBy { it.artistId },
                    )
                    searchPage = page
                    _searchHasMore.value = result.page.nextPage != null
                }
                DiscogsNativeArtistSearchResult.AuthenticationRequired ->
                    _artists.value = UiState.Error("discogs_token_required")
                DiscogsNativeArtistSearchResult.Disabled ->
                    _artists.value = UiState.Error("discogs_search_disabled")
                else -> {
                    // A page-two outage cannot erase successful page-one IDs;
                    // keep the next-page affordance available for manual retry.
                    if (page > 1 && _artists.value is UiState.Success) {
                        _searchHasMore.value = true
                    } else _artists.value = UiState.Error("discogs_catalog_unavailable")
                }
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
        val page = releaseSearchPage + 1
        val authorization = lease(ProviderCapability.CATALOG_SEARCH)
        if (authorization == null) {
            _releasesSearch.value = UiState.Error(
                if (!tokenVault.configured()) "discogs_token_required"
                else "discogs_search_disabled",
            )
            return
        }
        val job = scope.launch(Dispatchers.IO) {
            val result = try { releaseSearchClient.search(subject, page) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsNativeReleaseSearchResult.Unavailable }
            if (query != subject || !authorization.isCurrent()) return@launch
            when (result) {
                is DiscogsNativeReleaseSearchResult.Success -> {
                    val rows = result.page.releases.map {
                        DiscogsNativeReleaseSearchRow(it.releaseId, it.title)
                    }
                    val previous = (_releasesSearch.value as? UiState.Success)?.data.orEmpty()
                    _releasesSearch.value = UiState.Success(
                        (previous + rows).distinctBy { it.releaseId },
                    )
                    releaseSearchPage = page
                    _releaseSearchHasMore.value = result.page.nextPage != null
                }
                DiscogsNativeReleaseSearchResult.AuthenticationRequired ->
                    _releasesSearch.value = UiState.Error("discogs_token_required")
                DiscogsNativeReleaseSearchResult.Disabled ->
                    _releasesSearch.value = UiState.Error("discogs_search_disabled")
                else -> {
                    if (page > 1 && _releasesSearch.value is UiState.Success) {
                        _releaseSearchHasMore.value = true
                    } else _releasesSearch.value =
                        UiState.Error("discogs_catalog_unavailable")
                }
            }
        }
        releaseSearchJob = job
        val unregister = authorization.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
    }

    /**
     * Exact typed Discogs Release search selection. Hydrate the chosen source
     * ID directly, with no artist page or MusicBrainz requirement.
     */
    fun openReleaseFromSearch(selected: DiscogsNativeReleaseSearchRow) {
        if (selected.releaseId !in 1..999_999_999_999L || selected.title.isBlank()) return
        val authorization = lease(ProviderCapability.EDITORIAL_DISCOGRAPHY) ?: return
        backAll()
        _browseId.value = selected.browseId
        _route.value = DiscogsNativeRoute.RELEASE
        _detail.value = UiState.Loading
        val job = scope.launch(Dispatchers.IO) {
            val response = try { editionClient.fetchRelease(selected.releaseId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsLookupResult.Unavailable }
            if (!authorization.isCurrent() || artist != null ||
                _route.value != DiscogsNativeRoute.RELEASE) return@launch
            val record = (response as? DiscogsLookupResult.Found)?.record
            _detail.value = if (record != null &&
                record.link.kind == DiscogsKind.RELEASE &&
                record.link.id == selected.releaseId
            ) UiState.Success(discogsReleasePage(
                record.artistNames.joinToString(", "), record,
            )) else UiState.Error("discogs_release_unavailable")
        }
        detailJob = job
        val unregister = authorization.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
    }

    fun openArtist(selected: DiscogsNativeArtistSearchRow) {
        if (selected.artistId !in 1..999_999_999_999L || selected.name.isBlank()) return
        backAll()
        artist = selected
        _browseId.value = selected.browseId
        _route.value = DiscogsNativeRoute.ARTIST
        _detail.value = UiState.Loading
        loadNextArtistPage()
    }

    fun loadNextArtistPage() {
        val current = artist ?: return
        if (_route.value != DiscogsNativeRoute.ARTIST ||
            detailJob?.isActive == true || (artistPage != 0 && !artistHasMore)) return
        val authorization = lease(ProviderCapability.EDITORIAL_DISCOGRAPHY)
        if (authorization == null) {
            _detail.value = UiState.Error("discogs_discography_disabled")
            return
        }
        val page = artistPage + 1
        val job = scope.launch(Dispatchers.IO) {
            val response = try { artistClient.load(current.artistId, page) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsNativeArtistLoad.Unavailable }
            if (artist != current || !authorization.isCurrent() ||
                _route.value != DiscogsNativeRoute.ARTIST) return@launch
            when (response) {
                is DiscogsNativeArtistLoad.Success -> {
                    releases = (releases + response.releases).distinctBy { it.releaseId }
                    artistPage = page
                    artistHasMore = response.hasMore
                    artistIncomplete = artistIncomplete || response.incomplete
                    val result = discogsArtistPage(current, releases,
                        artistHasMore, artistIncomplete)
                    cachedArtistPage = result
                    _detail.value = UiState.Success(result)
                }
                DiscogsNativeArtistLoad.Disabled ->
                    _detail.value = UiState.Error("discogs_discography_disabled")
                else -> {
                    if (cachedArtistPage == null) {
                        _detail.value = UiState.Error("discogs_catalog_unavailable")
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

    fun openRelease(browseId: String) {
        val parent = artist ?: return
        val release = releases.singleOrNull { it.browseId == browseId } ?: return
        val authorization = lease(ProviderCapability.EDITORIAL_DISCOGRAPHY) ?: return
        detailJob?.cancel()
        _browseId.value = release.browseId
        _route.value = DiscogsNativeRoute.RELEASE
        _detail.value = UiState.Loading
        val job = scope.launch(Dispatchers.IO) {
            val response = try { editionClient.fetchRelease(release.releaseId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DiscogsLookupResult.Unavailable }
            if (!authorization.isCurrent() || artist != parent ||
                _route.value != DiscogsNativeRoute.RELEASE) return@launch
            val record = (response as? DiscogsLookupResult.Found)?.record
            _detail.value = if (record != null && !record.unofficial &&
                record.link.id == release.releaseId &&
                parent.artistId in record.artistIds
            ) UiState.Success(discogsReleasePage(parent, record))
            else UiState.Error("discogs_release_unavailable")
        }
        detailJob = job
        val unregister = authorization.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
    }

    fun back() {
        detailJob?.cancel()
        detailJob = null
        if (_route.value == DiscogsNativeRoute.RELEASE && artist != null) {
            _browseId.value = artist?.browseId
            _route.value = DiscogsNativeRoute.ARTIST
            _detail.value = cachedArtistPage?.let { UiState.Success(it) }
                ?: UiState.Error("discogs_catalog_unavailable")
        } else backAll()
    }

    fun backAll() {
        detailJob?.cancel()
        detailJob = null
        _route.value = null
        _browseId.value = null
        _detail.value = null
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
    val buckets = releases.groupBy { release ->
        when {
            has(release, "Album") && has(release, "Live") &&
                !has(release, "Compilation") -> "Álbuns ao vivo"
            has(release, "Album") && has(release, "Compilation") &&
                !has(release, "Live") -> "Coletâneas"
            has(release, "EP") || has(release, "Single") -> "Singles e EPs"
            has(release, "Album") -> "Álbuns"
            else -> "Outros lançamentos"
        }
    }
    val order = listOf("Álbuns", "Singles e EPs", "Álbuns ao vivo",
        "Coletâneas", "Outros lançamentos")
    return DiscogsNativePage(
        page = DetailPage(
            browseId = artist.browseId,
            title = artist.name,
            subtitle = "Artista · Discogs",
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
        hasMore = hasMore,
        incomplete = incomplete,
        attributionUrl = "https://www.discogs.com/artist/${artist.artistId}",
    )
}

/** A Discogs release's tracklist is non-playable source metadata only. */
internal fun discogsReleasePage(
    artist: DiscogsNativeArtistSearchRow,
    record: DiscogsRecord,
): DiscogsNativePage = discogsReleasePage(artist.name, record)

internal fun discogsReleasePage(
    artistCredit: String,
    record: DiscogsRecord,
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
        sections = emptyList(),
    ),
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
)
