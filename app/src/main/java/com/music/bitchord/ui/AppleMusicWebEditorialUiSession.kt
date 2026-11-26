package com.music.bitchord.ui

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebOutcome
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

internal data class AppleMusicWebEditorialUiState(
    val home: UiState<List<HomeShelf>> = UiState.Loading,
    val genres: Map<String, UiState<List<HomeShelf>>> = emptyMap(),
)

/** ViewModel-owned state for existing Home/Explore. The BR storefront matches
 * the existing native editorial default; cache reads still require admission.
 */
internal class AppleMusicWebEditorialUiSession(
    private val scope: CoroutineScope,
    private val gate: () -> ProviderPolicyGate?,
    private val readHome: suspend (String) -> ProviderCallResult<AppleMusicWebOutcome<List<HomeShelf>>>,
    private val readGenre: suspend (String, String, Boolean) -> ProviderCallResult<AppleMusicWebOutcome<List<HomeShelf>>>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Read(
        val owner: ProviderPolicyGate, val lease: ProviderReadLease,
        val started: Long, val job: Job,
    )
    private val reads = mutableMapOf<String?, Read>()
    private val _state = MutableStateFlow(AppleMusicWebEditorialUiState())
    val state = _state.asStateFlow()

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
        }
    }

    private fun publish(tag: String?, value: UiState<List<HomeShelf>>?) {
        val previous = _state.value
        _state.value = if (tag == null) previous.copy(home = value ?: UiState.Loading)
        else previous.copy(genres = if (value == null) previous.genres - tag
            else previous.genres + (tag to value))
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
            val shelves = when (val outcome = (result as? ProviderCallResult.Success)?.value) {
                is AppleMusicWebOutcome.Complete -> outcome.value
                is AppleMusicWebOutcome.Partial -> outcome.value.takeIf { it.isNotEmpty() }
                else -> null
            }
            publish(tag, if (shelves == null) UiState.Error("Catálogo editorial indisponível.")
                else UiState.Success(shelves))
        }
        reads[tag] = Read(owner, lease, started, job)
        val unregister = lease.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
        job.start()
    }
}
