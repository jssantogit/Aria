package com.music.bitchord.ui

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface AmsEditorialRequest {
    data class Room(
        val position: AppleMusicWebRoomContinuation,
        val homeKind: AppleMusicWebHomeShelf? = null,
        val curator: AppleMusicWebCuratorRef? = null,
        val tag: String? = null,
        val resourceKind: AppleMusicWebKind = AppleMusicWebKind.ALBUM,
    ) : AmsEditorialRequest
    data class RefreshGenre(val tag: String,val curator: AppleMusicWebCuratorRef) : AmsEditorialRequest
}

internal data class AmsEditorialPage(
    val shelves: List<HomeShelf>,
    val pending: List<AmsEditorialRequest> = emptyList(),
    val curator: AppleMusicWebCuratorRef? = null,
)

internal data class AmsEditorialPaging(
    val pending: List<AmsEditorialRequest> = emptyList(),
    val additional: List<ShelfItem> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val pages: Int = 0,
    /** Preserve source rail provenance for song continuations. */
    val additionalByHeading: Map<String,List<ShelfItem>> = emptyMap(),
) {
    val hasMore: Boolean get() = pending.isNotEmpty() && pages < 40
}

/** Keep original rails and their scroll keys intact, as in the MB feed. */
internal fun amsEditorialContinuationShelf(
    original: List<HomeShelf>, paging: AmsEditorialPaging?, genre: Boolean,
): HomeShelf? {
    val seen = original.flatMap { it.items }.mapNotNull { it.browseId }.toHashSet()
    val cards = paging?.additional.orEmpty().filter { card ->
        AppleMusicWebNativeRoute.parse(card.browseId)?.kind == AppleMusicWebKind.ALBUM &&
            card.browseId?.let(seen::add) == true
    }
    return cards.takeIf { it.isNotEmpty() }?.let {
        HomeShelf(if (genre) "Mais do gênero" else "Mais do catálogo",it)
    }
}

internal fun amsHomeWithTrendingContinuation(
    initial: List<HomeShelf>, paging: AmsEditorialPaging?,
): List<HomeShelf> {
    val headings = listOf("Músicas em alta","Novas músicas em destaque","Playlists atualizadas","Mais tocadas por cidade","Agora em turnê","Em breve")
    val explicit = paging?.additionalByHeading.orEmpty()
    val extras = headings.associateWith { heading ->
        explicit[heading].orEmpty().filter { card ->
            val route = AppleMusicWebNativeRoute.parse(card.browseId)
            when (heading) {
                "Músicas em alta","Novas músicas em destaque" -> amsSongStorefront(card.browseId) == "br"
                "Em breve" -> route?.storefront == "br" && route.kind == AppleMusicWebKind.ALBUM
                else -> route?.storefront == "br" && route.kind == AppleMusicWebKind.PLAYLIST
            }
        }
    }
    var result = initial
    for ((heading,extra) in extras) {
        if (extra.isEmpty()) continue
        val index = result.indexOfFirst { it.title == heading }
        result = if (index < 0) result + HomeShelf(heading,extra.distinctBy { it.browseId })
        else result.mapIndexed { i,shelf ->
            if (i == index) shelf.copy(items=(shelf.items + extra).distinctBy { it.browseId }) else shelf
        }
    }
    return result
}

internal fun amsGenreWithPlaylistContinuation(
    initial: List<HomeShelf>,paging: AmsEditorialPaging?,
): List<HomeShelf> {
    var result = initial
    val explicit = paging?.additionalByHeading.orEmpty()
    val named = explicit.filterKeys { it != "Lançamentos" }
    val alreadyNamed = named.values.flatten().mapNotNull { it.browseId }.toSet()
    val generic = paging?.additional.orEmpty().filter {
        AppleMusicWebNativeRoute.parse(it.browseId)?.kind == AppleMusicWebKind.PLAYLIST &&
            it.browseId !in alreadyNamed
    }
    val extras = named + ("Playlists" to (named["Playlists"].orEmpty() + generic))
    for ((heading,items) in extras) {
        val valid = items.filter { AppleMusicWebNativeRoute.parse(it.browseId)?.storefront == "br" }
        if (valid.isEmpty()) continue
        val index = result.indexOfFirst { it.title == heading }
        result = if (index < 0) result + HomeShelf(heading,valid.distinctBy { it.browseId })
            else result.mapIndexed { i,shelf ->
                if (i == index) shelf.copy(items=(shelf.items + valid).distinctBy { it.browseId }) else shelf
            }
    }
    return result
}

internal fun canonicalGenreShowsAmsStylePlaylist(state: UiState<List<HomeShelf>>,tag: String?): Boolean =
    tag in amsEditorialStyleParents && (state as? UiState.Success)?.data.orEmpty().any { shelf ->
        shelf.items.any { AppleMusicWebNativeRoute.parse(it.browseId)?.kind == AppleMusicWebKind.PLAYLIST }
    }

internal fun canonicalGenreShowsAmsReleases(state: UiState<List<HomeShelf>>): Boolean =
    (state as? UiState.Success)?.data.orEmpty().any { shelf ->
        shelf.title == "Lançamentos" && shelf.items.any {
            AppleMusicWebNativeRoute.parse(it.browseId)?.kind == AppleMusicWebKind.ALBUM
        }
    }

internal data class AppleMusicWebEditorialUiState(
    val home: UiState<List<HomeShelf>> = UiState.Loading,
    val genres: Map<String, UiState<List<HomeShelf>>> = emptyMap(),
    val paging: Map<String?,AmsEditorialPaging> = emptyMap(),
)

/** ViewModel-owned state for existing Home/Explore. The BR storefront matches
 * the existing native editorial default; cache reads still require admission.
 */
internal class AppleMusicWebEditorialUiSession(
    private val scope: CoroutineScope,
    private val gate: () -> ProviderPolicyGate?,
    private val readHome: suspend (String) -> ProviderCallResult<AppleMusicWebOutcome<AmsEditorialPage>>,
    private val readGenre: suspend (String, String, Boolean) -> ProviderCallResult<AppleMusicWebOutcome<AmsEditorialPage>>,
    private val now: () -> Long = System::currentTimeMillis,
    private val readNext: suspend (AmsEditorialRequest) -> ProviderCallResult<AppleMusicWebOutcome<AmsEditorialPage>> =
        { ProviderCallResult.Denied },
) {
    private data class Read(
        val owner: ProviderPolicyGate, val lease: ProviderReadLease,
        val started: Long, val job: Job,
    )
    private val reads = mutableMapOf<String?, Read>()
    private val _state = MutableStateFlow(AppleMusicWebEditorialUiState())
    val state = _state.asStateFlow()

    /** Held actions must still belong to the exact currently admitted feed. */
    fun containsCard(tag: String?, item: com.music.bitchord.data.model.ShelfItem): Boolean {
        val read = reads[tag] ?: return false
        if (read.owner !== gate() || !read.lease.isCurrent()) return false
        val state = if (tag == null) _state.value.home else _state.value.genres[tag]
        return (state as? UiState.Success)?.data.orEmpty().any { item in it.items } ||
            item in _state.value.paging[tag]?.additional.orEmpty()
    }

    fun loadHome(force: Boolean = false) = load(null, force)
    fun loadGenre(tag: String, force: Boolean = false) {
        if (appleMusicWebGenreSupported(tag)) load(tag, force)
    }

    fun clear() {
        val old = reads.values.toList()
        reads.clear()
        _state.value = AppleMusicWebEditorialUiState()
        old.forEach { it.job.cancel() }
    }

    fun purgeRevoked() {
        val current = gate()
        val revoked = reads.filterValues { it.owner !== current || !it.lease.isCurrent() }.keys.toList()
        for (key in revoked) {
            reads.remove(key)?.job?.cancel()
            publish(key, null)
            publishPaging(key,null)
        }
    }

    private fun publish(tag: String?, value: UiState<List<HomeShelf>>?) {
        val previous = _state.value
        _state.value = if (tag == null) previous.copy(home = value ?: UiState.Loading)
        else previous.copy(genres = if (value == null) previous.genres - tag
            else previous.genres + (tag to value))
    }

    private fun publishPaging(tag: String?, paging: AmsEditorialPaging?) {
        val previous = _state.value
        _state.value = previous.copy(paging = if (paging == null) previous.paging - tag
            else previous.paging + (tag to paging))
    }

    /** One request per user scroll edge. A failed page leaves the cursor and
     * visible rows unchanged; refresh or revoked permission cancels it. */
    fun loadNext(tag: String?) {
        purgeRevoked()
        val prior = reads[tag] ?: return
        val paging = _state.value.paging[tag] ?: return
        if (prior.job.isActive || paging.loading || !paging.hasMore) return
        val request = paging.pending.first()
        val initial = if (tag == null) _state.value.home else _state.value.genres[tag]
        val shown = (initial as? UiState.Success)?.data ?: return
        publishPaging(tag,paging.copy(loading=true,failed=false))
        val job = scope.launch(start=CoroutineStart.LAZY) {
            val result = try { readNext(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (reads[tag]?.lease !== prior.lease) return@launch
            if (gate() !== prior.owner || !prior.lease.isCurrent() ||
                result == ProviderCallResult.Denied || result == ProviderCallResult.Stale) {
                reads.remove(tag)
                publish(tag,null)
                publishPaging(tag,null)
                return@launch
            }
            val page = when (val outcome = (result as? ProviderCallResult.Success)?.value) {
                is AppleMusicWebOutcome.Complete -> outcome.value
                // A partial refresh cannot prove the continuation was consumed.
                is AppleMusicWebOutcome.Partial -> null
                else -> null
            }
            if (page == null) {
                publishPaging(tag,paging.copy(loading=false,failed=true))
                return@launch
            }
            val seen = (shown.flatMap { it.items } + paging.additional)
                .mapNotNull { it.browseId }.toHashSet()
            val additional = page.shelves.flatMap { it.items }.filter { card ->
                val route = AppleMusicWebNativeRoute.parse(card.browseId)
                val song = tag == null && amsSongStorefront(card.browseId) == "br" &&
                    com.music.bitchord.ui.screens.appleMusicWebVerifiedRouteUrl(card.appleStoreUrl,card.browseId) != null
                val collection = route != null && route.kind in setOf(AppleMusicWebKind.ALBUM,
                    AppleMusicWebKind.PLAYLIST,AppleMusicWebKind.ARTIST) && route.storefront == "br"
                (collection || song) && card.videoId == null && card.title.isNotBlank() &&
                    card.browseId?.let(seen::add) == true
            }
            val byHeading = paging.additionalByHeading.toMutableMap()
            val acceptedIds = additional.mapNotNull { it.browseId }.toSet()
            for (shelf in page.shelves) {
                val named = shelf.items.filter { it.browseId in acceptedIds }
                if (named.isNotEmpty()) byHeading[shelf.title] =
                    (byHeading[shelf.title].orEmpty() + named).distinctBy { it.browseId }
            }
            publishPaging(tag,paging.copy(
                pending=(paging.pending.drop(1) + page.pending).distinct(),
                additional=paging.additional + additional,
                additionalByHeading=byHeading,
                loading=false,failed=false,pages=paging.pages+1,
            ))
        }
        reads[tag] = prior.copy(job=job)
        val unregister = prior.lease.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
        job.start()
    }

    private fun load(tag: String?, force: Boolean) {
        purgeRevoked()
        val owner = gate() ?: return
        val lease = ProviderReadLease.acquire(owner, listOf(
            ProviderCapability.EDITORIAL_DISCOGRAPHY to BuiltInProviderKeys.APPLE_MUSIC_WEB,
        )) ?: return
        val started = now()
        val prior = reads[tag]
        if (!force && prior != null &&
            (prior.job.isActive || started - prior.started in 0L until 15L * 60L * 1000L)) return
        reads.remove(tag)?.job?.cancel()
        publish(tag, UiState.Loading)
        publishPaging(tag,null)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                if (tag == null) readHome("br") else readGenre(tag, "br", force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            if (reads[tag]?.lease !== lease) return@launch
            if (gate() !== owner || !lease.isCurrent() ||
                result == ProviderCallResult.Denied || result == ProviderCallResult.Stale) {
                reads.remove(tag)
                publish(tag, null)
                return@launch
            }
            val page = when (val outcome = (result as? ProviderCallResult.Success)?.value) {
                is AppleMusicWebOutcome.Complete -> outcome.value
                is AppleMusicWebOutcome.Partial -> outcome.value.takeIf { it.shelves.isNotEmpty() }
                else -> null
            }
            publish(tag, if (page == null) UiState.Error("Catálogo editorial indisponível.")
                else UiState.Success(page.shelves))
            publishPaging(tag,page?.let { AmsEditorialPaging(pending=it.pending) })
        }
        reads[tag] = Read(owner, lease, started, job)
        val unregister = lease.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
        job.start()
    }
}
