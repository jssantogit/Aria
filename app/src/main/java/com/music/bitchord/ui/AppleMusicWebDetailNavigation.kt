package com.music.bitchord.ui

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebOutcome
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderReadLease
import com.music.bitchord.ui.screens.AppleSharedDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Source-owned navigation snapshots for the existing canonical DetailScreen. */
internal data class AppleMusicWebDetailEntry(
    val route: AppleMusicWebNativeRoute,
    val title: String,
    val subtitle: String,
    val artwork: String?,
    val lease: ProviderReadLease,
    val state: UiState<AppleSharedDetail> = UiState.Loading,
) {
    fun visible(): AppleSharedDetail? {
        if (!lease.isCurrent()) return null
        return when (val value = state) {
            is UiState.Success -> value.data.takeIf { it.page.browseId == route.browseId }
            else -> AppleSharedDetail(
                page = DetailPage(
                    browseId = route.browseId, title = title, subtitle = subtitle,
                    thumbnailUrl = artwork, type = if (route.kind == AppleMusicWebKind.ARTIST)
                        BrowseType.ARTIST else BrowseType.ALBUM,
                    songs = if (value is UiState.Error) UiState.Error(value.message) else UiState.Loading,
                ),
            )
        }
    }
}

/** Never reinterpret IDs or open a shelf card that is not in the current source page. */
internal fun appleMusicWebRelatedTarget(
    page: AppleSharedDetail, targetId: String?,
): ShelfItem? {
    val parent = AppleMusicWebNativeRoute.parse(page.page.browseId) ?: return null
    val child = AppleMusicWebNativeRoute.parse(targetId) ?: return null
    if (parent.storefront != child.storefront || parent == child) return null
    if (parent.kind == AppleMusicWebKind.ALBUM && child.kind == AppleMusicWebKind.ARTIST &&
        targetId == page.albumArtistBrowseId && !page.albumArtistDisplayName.isNullOrBlank()) {
        return ShelfItem(title = page.albumArtistDisplayName, subtitle = "",
            thumbnailUrl = null, videoId = null, browseId = child.browseId)
    }
    val matches = page.page.sections.flatMap { it.items }.filter {
        it.browseId == child.browseId && it.videoId == null && it.title.isNotBlank()
    }
    return matches.firstOrNull()?.takeIf { first -> matches.all { it == first } }
}

/** ViewModel scope only. Loading/error retains the exact native route.
 * Back restores an admitted snapshot, not a new query or an iTunes alias.
 */
internal class AppleMusicWebDetailNavigation(
    private val scope: CoroutineScope,
    private val acquire: () -> ProviderReadLease?,
    private val read: suspend (String) -> ProviderCallResult<AppleMusicWebOutcome<AppleSharedDetail>>,
) {
    private val _stack = MutableStateFlow<List<AppleMusicWebDetailEntry>>(emptyList())
    val stack = _stack.asStateFlow()
    private var request: Job? = null
    private var epoch = 0L

    fun openRoot(item: ShelfItem): Boolean {
        val route = AppleMusicWebNativeRoute.parse(item.browseId) ?: return false
        val lease = acquire() ?: return false
        if (item.title.isBlank() || item.videoId != null || !lease.isCurrent()) return false
        clear()
        load(AppleMusicWebDetailEntry(route,item.title,item.subtitle,item.thumbnailUrl,lease))
        return true
    }

    fun openRelated(targetId: String?): Boolean {
        val current = _stack.value.lastOrNull() ?: return false
        if (!current.lease.isCurrent()) { clear(); return false }
        val page = (current.state as? UiState.Success)?.data ?: return false
        val target = appleMusicWebRelatedTarget(page,targetId) ?: return false
        val route = AppleMusicWebNativeRoute.parse(target.browseId) ?: return false
        val previous = _stack.value.indexOfLast { it.route == route && it.lease.isCurrent() }
        if (previous >= 0) {
            cancelRequest()
            _stack.value = _stack.value.take(previous + 1)
            return true
        }
        if (_stack.value.size >= 32) return false
        val lease = acquire() ?: return false
        if (!lease.isCurrent()) return false
        load(AppleMusicWebDetailEntry(route,target.title,target.subtitle,target.thumbnailUrl,lease))
        return true
    }

    fun back(): Boolean {
        if (_stack.value.isEmpty()) return false
        cancelRequest()
        _stack.value = _stack.value.dropLast(1).filter { it.lease.isCurrent() }
        return true
    }

    fun clear() {
        cancelRequest()
        _stack.value = emptyList()
    }

    private fun cancelRequest() {
        ++epoch
        request?.cancel()
        request = null
    }

    private fun load(entry: AppleMusicWebDetailEntry) {
        cancelRequest()
        val generation = epoch
        _stack.value = _stack.value + entry
        val job = scope.launch {
            val result = try {
                read(entry.route.browseId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (generation != epoch || _stack.value.lastOrNull() !== entry) return@launch
            if (!entry.lease.isCurrent() || result == ProviderCallResult.Stale ||
                result == ProviderCallResult.Denied) {
                _stack.value = _stack.value.dropLast(1).filter { it.lease.isCurrent() }
                return@launch
            }
            val detail = when (val outcome = (result as? ProviderCallResult.Success)?.value) {
                is AppleMusicWebOutcome.Complete -> outcome.value
                is AppleMusicWebOutcome.Partial -> outcome.value
                else -> null
            }?.takeIf { it.page.browseId == entry.route.browseId }
            val updated = if (detail != null) UiState.Success(detail)
                else UiState.Error("Não foi possível carregar os detalhes.")
            _stack.value = _stack.value.dropLast(1) + entry.copy(state = updated)
        }
        request = job
        val unregister = entry.lease.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
    }
}
