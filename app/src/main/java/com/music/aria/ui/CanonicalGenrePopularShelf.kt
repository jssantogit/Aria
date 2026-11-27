package com.music.aria.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.aria.data.catalog.metadata.ListenBrainzPopularityEvidence
import com.music.aria.data.catalog.metadata.ListenBrainzPopularityFetchResult
import com.music.aria.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.aria.data.catalog.metadata.MusicBrainzRecordingHit
import com.music.aria.data.catalog.metadata.isCuratedMusicBrainzRecording
import com.music.aria.data.model.HomeShelf
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderPolicyGate
import com.music.aria.data.providers.ProviderRequestTicket
import com.music.aria.data.providers.ProviderRuntime
import com.music.aria.ui.screens.CanonicalCatalogSearchRow
import com.music.aria.ui.screens.CanonicalSearchKind
import kotlinx.coroutines.CancellationException

/** Verified tag matches and independently observed ListenBrainz popularity. */
internal data class CanonicalGenrePopularSnapshot(
    val tag: String,
    val rows: List<CanonicalCatalogSearchRow>,
    val observations: List<ListenBrainzPopularityEvidence>,
)

internal suspend fun loadMusicBrainzGenreRecordings(
    tag: String,
    gate: ProviderPolicyGate,
    ticket: ProviderRequestTicket,
    searchRecordings: suspend (String, Int) -> MusicBrainzCatalogSearchResult<MusicBrainzRecordingHit>,
    isCurrent: () -> Boolean,
    fetchPopularity: suspend (List<String>) -> ListenBrainzPopularityFetchResult,
): CanonicalGenrePopularSnapshot? {
    val chartTicket = gate.open(
        ProviderCapability.CHART_MOST_PLAYED, BuiltInProviderKeys.LISTENBRAINZ,
    ) ?: return null
    val tagged = try {
        searchRecordings(tag, 40)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }
    if (!gate.accept(ticket) || !gate.accept(chartTicket) || !isCurrent()) return null

    val recordings = (tagged as? MusicBrainzCatalogSearchResult.Success)
        ?.page?.items.orEmpty()
        .filter(::isCuratedMusicBrainzRecording)
        .distinctBy { it.musicBrainzId }
        .map { hit ->
            CanonicalCatalogSearchRow(
                hit.musicBrainzId, CanonicalSearchKind.RECORDING,
                hit.title, hit.artistDisplay, artworkUri = null,
            )
        }
    val measured = if (recordings.isEmpty()) null else try {
        fetchPopularity(recordings.map { it.musicBrainzId })
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { ListenBrainzPopularityFetchResult.Unavailable }
    if (!gate.accept(ticket) || !gate.accept(chartTicket) || !isCurrent()) return null

    val evidence = (measured as? ListenBrainzPopularityFetchResult.Success)
        ?.observations.orEmpty()
    return CanonicalGenrePopularSnapshot(tag, recordings, evidence)
}

internal fun canonicalGenrePopularShelf(snapshot: CanonicalGenrePopularSnapshot): HomeShelf? =
    canonicalPublicPopularityShelf(
        rows = snapshot.rows,
        evidence = snapshot.observations,
        heading = "Músicas populares",
    )

/** React to capability opt-out; a cached ranking must disappear immediately. */
@Composable
internal fun rememberGenrePopularityAccess(): Boolean {
    val session = ProviderRuntime.state
    session?.collectAsStateWithLifecycle()
    return ProviderRuntime.gateOrNull()?.open(
        ProviderCapability.CHART_MOST_PLAYED,
        BuiltInProviderKeys.LISTENBRAINZ,
    ) != null
}
