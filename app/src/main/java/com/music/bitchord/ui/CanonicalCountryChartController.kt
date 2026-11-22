package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.AppleCountryChart
import com.music.bitchord.data.catalog.metadata.AppleCountryChartRead
import com.music.bitchord.data.catalog.metadata.AppleCountryChartRepository
import com.music.bitchord.data.catalog.metadata.knownAppleChartTerritory
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Storefront charts are fetched on navigation only, not on every Home recomposition.
 * No token, player source, MBID mapping or per-country background polling.
 */
internal class CanonicalCountryChartController(
    private val scope: CoroutineScope,
    private val repository: AppleCountryChartRepository = AppleCountryChartRepository(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableSnapshots =
        MutableStateFlow<Map<String, UiState<AppleCountryChart>>>(emptyMap())
    val snapshots = mutableSnapshots.asStateFlow()
    private val inFlight = mutableMapOf<String, Job>()
    private val retryAfterMs = mutableMapOf<String, Long>()

    fun load(code: String, force: Boolean = false) {
        if (knownAppleChartTerritory(code) == null) return
        val now = clock()
        val cached = (mutableSnapshots.value[code] as? UiState.Success)?.data
        if (!force && cached != null && now >= cached.fetchedAtMs &&
            now - cached.fetchedAtMs < 2L * 60 * 60 * 1_000L
        ) return
        if (!force && (retryAfterMs[code] ?: 0L) > now) return
        if (inFlight[code]?.isActive == true) return
        mutableSnapshots.update { it + (code to UiState.Loading) }
        val job = scope.launch(Dispatchers.IO) {
            val result = try {
                repository.read(code)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) {
                AppleCountryChartRead.Failure(AppleCountryChartRead.Reason.UNAVAILABLE)
            }
            val updated: UiState<AppleCountryChart> = when(result) {
                is AppleCountryChartRead.Success -> UiState.Success(result.chart)
                is AppleCountryChartRead.Failure ->
                    UiState.Error(if (result.reason == AppleCountryChartRead.Reason.NOT_AUTHORIZED)
                        "Ative os rankings da Apple Music em Provedores."
                    else "Não foi possível carregar este ranking.")
            }
            // Never reuse old content on denial, unavailable or expired sources.
            mutableSnapshots.update { it + (code to updated) }
            retryAfterMs[code] = if (result is AppleCountryChartRead.Failure)
                clock() + 15L * 60_000L else 0L
        }
        inFlight[code] = job
        job.invokeOnCompletion { if (inFlight[code] === job) inFlight.remove(code) }
    }
}
