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
        artistBrowseId=route(market,"artist",item.artistNativeId))
}

internal data class AmsSearchSectionState(
    val items: List<CatalogSearchItem> = emptyList(),
    val continuation: CatalogSearchContinuation? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
) {
    val status get() = AppleSearchPageStatus(continuation != null,loading && items.isNotEmpty(),failed)
}

internal data class AmsSearchSnapshot(
    val query: String? = null,
    val selected: Boolean = false,
    val sections: Map<CatalogSearchKind,AmsSearchSectionState> = emptyMap(),
) {
    private fun <T> project(kind: CatalogSearchKind, map: (CatalogSearchItem) -> T?): UiState<List<T>>? {
        val section = sections[kind] ?: return null
        val rows = section.items.mapNotNull(map)
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
) {
    private val _state = MutableStateFlow(AmsSearchSnapshot())
    val state = _state.asStateFlow()
    private var owner: ProviderPolicyGate? = null
    private var lease: ProviderReadLease? = null
    private var originalOrder = emptyList<ProviderKey>()
    private val jobs = mutableMapOf<CatalogSearchKind,Job>()

    fun current(): Boolean = owner != null && gate() === owner && lease?.isCurrent() == true &&
        order() == originalOrder && amsKey in originalOrder
    fun clear() {
        val old = jobs.values.toList()
        jobs.clear()
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
            if (!more && _state.value.selected && _state.value.sections.values.none { it.loading }) settled(q)
        }
        jobs[kind] = job
        val unregister = ticket.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
        job.start()
    }
}
