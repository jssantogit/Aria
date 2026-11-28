package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.safeAppleArtwork
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebOutcome
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.screens.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val amsKey = BuiltInProviderKeys.APPLE_MUSIC_WEB
private val amsId = Regex("[1-9][0-9]{0,11}")
private fun route(market: String, type: String, id: String?) =
    id?.takeIf { amsId.matches(it) }?.let { "apple_music_web:$market:$type:$it" }
private fun CatalogSearchItem.artwork() = safeAppleArtwork(
    artworkUrl?.replace("{w}", "600")?.replace("{h}", "600")?.replace("{f}", "jpg"))

internal fun amsSearchRow(item: CatalogSearchItem): AppleEditorialSearchRow? {
    val identity = item.identity
    val market = identity.storefront?.takeIf { it.matches(Regex("[a-z]{2}")) } ?: return null
    if (identity.provider != amsKey || item.title.isBlank()) return null
    val kind = when (identity.kind) {
        CatalogSearchKind.ARTIST -> AppleEditorialSearchKind.ARTIST
        CatalogSearchKind.ALBUM -> AppleEditorialSearchKind.ALBUM
        else -> return null
    }
    val id = route(market,if (kind == AppleEditorialSearchKind.ARTIST) "artist" else "album",identity.nativeId)
        ?: return null
    return AppleEditorialSearchRow(id,item.title,
        if (kind == AppleEditorialSearchKind.ARTIST) item.title else item.subtitle.orEmpty(),
        item.artwork(),item.releaseDate,kind,
        appleStoreUrl=appleMusicWebVerifiedRouteUrl(item.storeUrl,id),
        artistBrowseId=route(market,"artist",item.artistNativeId))
}

internal fun amsSearchTrack(item: CatalogSearchItem): AppleEditorialTrackSearchRow? {
    val identity = item.identity
    val market = identity.storefront?.takeIf { it.matches(Regex("[a-z]{2}")) } ?: return null
    if (identity.provider != amsKey || identity.kind != CatalogSearchKind.TRACK || item.title.isBlank()) return null
    val key = route(market,"song",identity.nativeId) ?: return null
    return AppleEditorialTrackSearchRow(key,item.title,item.subtitle.orEmpty(),item.artwork(),
        route(market,"album",item.albumNativeId),item.durationMs?.takeIf { it > 0 },
        appleStoreUrl=appleMusicWebVerifiedRouteUrl(item.storeUrl,key),
        artistBrowseId=route(market,"artist",item.artistNativeId),
        hasAudioPreview=item.hasAudioPreview)
}

internal data class AmsSearchSectionState(
    val items: List<CatalogSearchItem> = emptyList(),
    val continuation: CatalogSearchContinuation? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
) {
    val status get() = AppleSearchPageStatus(continuation != null,loading && items.isNotEmpty(),failed)
}

/** Preserve the textual hit's richer metadata when the exact native identity agrees.
 * Relationship evidence can fill a missing artist ID, not replace a conflicting one. */
internal fun amsSearchWithArtistProducts(rows: List<CatalogSearchItem>, products: List<CatalogSearchItem>, kind: CatalogSearchKind): List<CatalogSearchItem> {
    val textual = rows.associateBy { it.identity }
    val expanded = products.filter { it.identity.kind == kind }.mapNotNull { product ->
        val hit = textual[product.identity] ?: return@mapNotNull product
        if (hit.artistNativeId != null && hit.artistNativeId != product.artistNativeId) return@mapNotNull null
        hit.copy(
            artistNativeId=product.artistNativeId,
            subtitle=hit.subtitle?.takeIf { it.isNotBlank() } ?: product.subtitle,
            artworkUrl=hit.artworkUrl ?: product.artworkUrl,
            storeUrl=hit.storeUrl ?: product.storeUrl,
            albumNativeId=hit.albumNativeId ?: product.albumNativeId,
            releaseDate=hit.releaseDate ?: product.releaseDate,
            durationMs=hit.durationMs?.takeIf { it > 0 } ?: product.durationMs,
            hasAudioPreview=hit.hasAudioPreview || product.hasAudioPreview,
        )
    }
    return (expanded + rows).distinctBy { it.identity }
}

internal data class AmsSearchSnapshot(
    val query: String? = null,
    val selected: Boolean = false,
    val sections: Map<CatalogSearchKind,AmsSearchSectionState> = emptyMap(),
    val artistProducts: List<CatalogSearchItem> = emptyList(),
) {
    private fun <T> project(kind: CatalogSearchKind, map: (CatalogSearchItem) -> T?, expanded: Boolean = false): UiState<List<T>>? {
        val section = sections[kind] ?: return null
        val rows = (if (expanded) amsSearchWithArtistProducts(section.items,artistProducts,kind) else section.items).mapNotNull(map)
        return when {
            rows.isNotEmpty() -> UiState.Success(rows)
            section.loading -> UiState.Loading
            section.failed -> UiState.Error("Catálogo Apple Music indisponível.")
            else -> UiState.Success(emptyList())
        }
    }
    val artists get() = project(CatalogSearchKind.ARTIST,::amsSearchRow)
    val albums get() = project(CatalogSearchKind.ALBUM,::amsSearchRow)
    val tracks get() = project(CatalogSearchKind.TRACK,::amsSearchTrack)
    val mixedAlbums get() = project(CatalogSearchKind.ALBUM,::amsSearchRow,true)
    val mixedTracks get() = project(CatalogSearchKind.TRACK,::amsSearchTrack,true)
    val paging get() = sections.mapKeys { (kind,_) -> kind.section() }.mapValues { it.value.status }
    fun coverage(filter: SearchFilter): CatalogFilterCoverage {
        if (query == null) return CatalogFilterCoverage.NOT_QUERIED
        val kinds = when (filter) {
            SearchFilter.ARTISTS -> listOf(CatalogSearchKind.ARTIST)
            SearchFilter.ALBUMS -> listOf(CatalogSearchKind.ALBUM)
            SearchFilter.SONGS -> listOf(CatalogSearchKind.TRACK)
            SearchFilter.ALL -> CatalogSearchKind.entries
            else -> return CatalogFilterCoverage.EMPTY
        }
        if (kinds.any { kind -> when (kind) {
                CatalogSearchKind.TRACK -> sections[kind]?.items.orEmpty().any { amsSearchTrack(it) != null }
                else -> sections[kind]?.items.orEmpty().any { amsSearchRow(it) != null }
            } }) return CatalogFilterCoverage.MATCHING
        if (kinds.any { sections[it]?.loading != false }) return CatalogFilterCoverage.LOADING
        return CatalogFilterCoverage.EMPTY
    }
}

private fun CatalogSearchKind.section(): AppleSearchSection = when (this) {
    CatalogSearchKind.ARTIST -> AppleSearchSection.ARTISTS
    CatalogSearchKind.ALBUM -> AppleSearchSection.ALBUMS
    CatalogSearchKind.TRACK -> AppleSearchSection.TRACKS
}
private fun AppleSearchSection.kind() = CatalogSearchKind.entries.single { it.section() == this }

/** Independent source-owned cursor session; existing search renderer is data-only.
 * All requests and publications are bound to the current policy/order/query.
 */
internal class AppleMusicWebSearchUiSession(
    private val scope: CoroutineScope,
    private val gate: () -> ProviderPolicyGate?,
    private val order: () -> List<ProviderKey>,
    private val read: suspend (CatalogSearchRequest) -> ProviderCallResult<CatalogSearchPage>,
    private val settled: (String) -> Unit,
    private val readArtistProducts: (suspend (CatalogSearchItem) -> List<CatalogSearchItem>)? = null,
) {
    private val _state = MutableStateFlow(AmsSearchSnapshot())
    val state = _state.asStateFlow()
    private var owner: ProviderPolicyGate? = null
    private var lease: ProviderReadLease? = null
    private var originalOrder = emptyList<ProviderKey>()
    private val jobs = mutableMapOf<CatalogSearchKind,Job>()
    private var expansionJob: Job? = null
    private var expansionLease: ProviderReadLease? = null

    fun expansionCurrent(): Boolean = current() && expansionLease?.isCurrent() == true
    fun admittedItems(): List<CatalogSearchItem> {
        val snapshot = _state.value
        val textual = snapshot.sections.values.flatMap { it.items }
        if (!expansionCurrent()) return textual
        // Admit the actual mixed projection, not a conflicting or superseded
        // raw artist-view row; typed tabs retain their independently read hits.
        return textual + listOf(CatalogSearchKind.TRACK,CatalogSearchKind.ALBUM).flatMap { kind ->
            amsSearchWithArtistProducts(snapshot.sections[kind]?.items.orEmpty(),snapshot.artistProducts,kind)
        }
    }

    private fun expandExactArtist(query: String) {
        val read = readArtistProducts ?: return
        val primary = _state.value.sections[CatalogSearchKind.ARTIST]?.items.orEmpty().firstOrNull {
            amsSearchRow(it)?.let { row -> appleUnifiedExactArtistMatch(query,row.title) } == true
        } ?: return
        val ticket = ProviderReadLease.acquire(owner,listOf(
            ProviderCapability.CATALOG_SEARCH to amsKey,
            ProviderCapability.EDITORIAL_DISCOGRAPHY to amsKey)) ?: return
        expansionJob?.cancel()
        expansionLease = ticket
        val job = scope.launch(start=CoroutineStart.LAZY) {
            val products = try { read(primary) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            if (expansionLease !== ticket || !expansionCurrent() || _state.value.query != query) return@launch
            val valid = products.filter { item ->
                item.identity.provider == amsKey && item.identity.storefront == primary.identity.storefront &&
                    item.identity.kind in setOf(CatalogSearchKind.TRACK,CatalogSearchKind.ALBUM) &&
                    amsId.matches(item.identity.nativeId) && item.artistNativeId == primary.identity.nativeId
            }.distinctBy { it.identity }
            _state.value = _state.value.copy(artistProducts=valid)
        }
        expansionJob = job
        val unregister = ticket.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
        job.start()
    }

    fun current(): Boolean = owner != null && gate() === owner && lease?.isCurrent() == true &&
        order() == originalOrder && amsKey in originalOrder
    fun clear() {
        val old = jobs.values.toList()
        jobs.clear()
        expansionJob?.cancel()
        expansionJob = null
        expansionLease = null
        owner = null
        lease = null
        originalOrder = emptyList()
        _state.value = AmsSearchSnapshot()
        old.forEach { it.cancel() }
    }
    fun deselect() {
        _state.value = _state.value.copy(selected=false)
    }
    fun select(query: String) {
        if (current() && _state.value.query == query) {
            _state.value = _state.value.copy(selected=true)
            return
        }
        clear()
        val g = gate() ?: return
        val keys = order()
        if (amsKey !in keys || query.isBlank()) return
        val ticket = ProviderReadLease.acquire(g,listOf(ProviderCapability.CATALOG_SEARCH to amsKey)) ?: return
        owner = g
        lease = ticket
        originalOrder = keys
        _state.value = AmsSearchSnapshot(query,true,CatalogSearchKind.entries.associateWith { AmsSearchSectionState() })
        CatalogSearchKind.entries.forEach { load(it,false) }
    }
    fun loadMore(section: AppleSearchSection) {
        if (_state.value.selected) load(section.kind(),true)
    }
    private fun load(kind: CatalogSearchKind, more: Boolean) {
        val ticket = lease ?: return
        val snapshot = _state.value
        val q = snapshot.query ?: return
        val before = snapshot.sections[kind] ?: return
        if (!current() || jobs[kind]?.isActive == true) return
        if (more && before.continuation == null && (!before.failed || before.items.isNotEmpty())) return
        val cursor = if (more) before.continuation else null
        val request = CatalogSearchRequest(kind,q,"br",cursor?.page ?: 1,24,cursor)
        fun publish(section: AmsSearchSectionState) {
            _state.value = _state.value.copy(sections=_state.value.sections + (kind to section))
        }
        publish(before.copy(loading=true,failed=false))
        val job = scope.launch(start=CoroutineStart.LAZY) {
            val result = try { read(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (lease !== ticket || !current() || _state.value.query != q) return@launch
            val page = (result as? ProviderCallResult.Success)?.value?.takeIf { page ->
                page.provider == amsKey && page.capability == ProviderCapability.CATALOG_SEARCH &&
                    page.storefront == request.storefront && page.kind == kind && page.page == request.page &&
                    !page.partial && page.hasMore == (page.continuation != null) &&
                    page.continuation?.matches(amsKey,request.copy(page=request.page+1)) != false &&
                    page.items.all { it.identity.provider == amsKey && it.identity.storefront == request.storefront &&
                        it.identity.kind == kind && amsId.matches(it.identity.nativeId) }
            }
            if (result == ProviderCallResult.Denied || result == ProviderCallResult.Stale) {
                clear()
                return@launch
            }
            publish(if (page == null) before.copy(loading=false,failed=true)
                else AmsSearchSectionState((before.items + page.items).distinctBy { it.identity },
                    page.continuation,false,false))
            if (request.page == 1 && kind == CatalogSearchKind.ARTIST && page != null) expandExactArtist(q)
            if (!more && _state.value.selected && _state.value.sections.values.none { it.loading }) settled(q)
        }
        jobs[kind] = job
        val unregister = ticket.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
        job.start()
    }
}
