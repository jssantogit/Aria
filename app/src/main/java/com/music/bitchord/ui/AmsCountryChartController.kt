package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.music.bitchord.data.model.ShelfItem
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.screens.appleMusicWebVerifiedStoreUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface AmsCountryChartResult {
    data class Songs(val chart: AppleCountryChart): AmsCountryChartResult
    data class Albums(val chart: AppleCountryAlbumChart): AmsCountryChartResult
}

/** Preserve raw positions even when an external link is not safe to publish.
 * Shared chart DTOs retain exact AMS identity; no iTunes alias. */
internal fun projectAmsCountryChart(territory: AppleChartTerritory, albums: Boolean,
    rows: List<AppleMusicWebItem>, fetchedAt: Long): AmsCountryChartResult? {
    val kind = if (albums) AppleMusicWebKind.ALBUM else AppleMusicWebKind.SONG
    if (rows.isEmpty() || rows.size > 100 || rows.distinctBy { it.scopedId }.size != rows.size ||
        rows.any { it.kind != kind || it.storefront != territory.code }) return null
    fun art(item: AppleMusicWebItem) = com.music.bitchord.data.catalog.provider.applemusicweb.appleMusicWebArtworkUrl(item.artworkUrl,100)
    if (albums) {
        val projected = rows.mapIndexedNotNull { index,item ->
            val id = item.id.toLongOrNull()?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
            val url = appleMusicWebVerifiedStoreUrl(item) ?: return@mapIndexedNotNull null
            val credit = item.artistName?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            if (item.title.isBlank()) return@mapIndexedNotNull null
            AppleChartAlbum(index+1,id,territory.code,item.title,credit,art(item),url,item.scopedId)
        }
        return projected.takeIf { it.isNotEmpty() }?.let {
            AmsCountryChartResult.Albums(AppleCountryAlbumChart(territory,it,fetchedAt,rows.size))
        }
    }
    val projected = rows.mapIndexedNotNull { index,item ->
        val id = item.id.toLongOrNull()?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
        val url = appleMusicWebVerifiedStoreUrl(item) ?: return@mapIndexedNotNull null
        val credit = item.artistName?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
        if (item.title.isBlank()) return@mapIndexedNotNull null
        AppleChartSong(index+1,id,territory.code,item.title,credit,art(item),url,item.scopedId)
    }
    return projected.takeIf { it.isNotEmpty() }?.let {
        AmsCountryChartResult.Songs(AppleCountryChart(territory,it,fetchedAt,rows.size))
    }
}

internal data class AmsCountryChartKey(val country: String,val albums: Boolean)

internal class AmsCountryChartController(
    private val scope: CoroutineScope,
    private val gate: () -> ProviderPolicyGate?,
    private val read: suspend (String,Boolean) -> ProviderCallResult<AmsCountryChartResult>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Read(val owner: ProviderPolicyGate,val lease: ProviderReadLease,
        val started: Long,val job: Job)
    private val reads = mutableMapOf<AmsCountryChartKey,Read>()
    private val mutable = MutableStateFlow<Map<AmsCountryChartKey,UiState<AmsCountryChartResult>>>(emptyMap())
    val snapshots = mutable.asStateFlow()
    fun isCurrent(key: AmsCountryChartKey): Boolean =
        reads[key]?.let { it.owner === gate() && it.lease.isCurrent() } == true
    fun purgeRevoked() {
        val revoked = reads.keys.filterNot(::isCurrent)
        for (key in revoked) {
            reads.remove(key)?.job?.cancel()
            mutable.value = mutable.value - key
        }
    }
    fun load(country: String,albums: Boolean,force: Boolean = false) {
        purgeRevoked()
        if (knownAppleChartTerritory(country) == null) return
        val owner = gate() ?: return
        if (preferredAppleChartProvider(owner) != BuiltInProviderKeys.APPLE_MUSIC_WEB) return
        val lease = ProviderReadLease.acquire(owner,listOf(
            ProviderCapability.CHART_MOST_PLAYED to BuiltInProviderKeys.APPLE_MUSIC_WEB)) ?: return
        val key = AmsCountryChartKey(country,albums)
        val prior = reads[key]
        val started = now()
        if (prior?.job?.isActive == true || (!force && prior != null &&
            started-prior.started in 0L until 15L*60_000L)) return
        prior?.job?.cancel()
        mutable.value = mutable.value + (key to UiState.Loading)
        val job = scope.launch(start=CoroutineStart.LAZY) {
            val result = try { read(country,albums) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (reads[key]?.lease !== lease) return@launch
            if (gate() !== owner || !lease.isCurrent() || result == ProviderCallResult.Denied ||
                result == ProviderCallResult.Stale) {
                reads.remove(key)
                mutable.value = mutable.value - key
                return@launch
            }
            val chart = (result as? ProviderCallResult.Success)?.value?.takeIf {
                if (albums) it is AmsCountryChartResult.Albums && it.chart.territory.code == country
                else it is AmsCountryChartResult.Songs && it.chart.territory.code == country
            }
            mutable.value = mutable.value + (key to if (chart == null)
                UiState.Error("Não foi possível carregar este ranking.") else UiState.Success(chart))
        }
        reads[key] = Read(owner,lease,started,job)
        val unregister = lease.cancelJobOnRevoke(job)
        job.invokeOnCompletion { unregister() }
        job.start()
    }
}

/** Real daily chart playlist identity, sourced from the official chart directory. */
internal data class AmsDailyChartTile(
    val country: String, val ref: AppleMusicWebDailyChartRef, val card: ShelfItem,
)

internal fun projectAmsDailyChartDirectory(directory: List<AppleMusicWebDailyChartRef>): List<AmsDailyChartTile> =
    appleChartTerritories.mapNotNull { territory ->
        val ref = resolveAppleMusicWebDailyChart(directory,territory.code) ?: return@mapNotNull null
        val route = AppleMusicWebNativeRoute(territory.code,AppleMusicWebKind.PLAYLIST,ref.id)
        if (AppleMusicWebNativeRoute.parse(route.browseId) != route) return@mapNotNull null
        AmsDailyChartTile(territory.code,ref,ShelfItem(
            title = "Top 100 · ${territory.displayName}",subtitle = "Apple Music",
            thumbnailUrl = appleMusicWebArtworkUrl(ref.artworkUrl),
            videoId = null,browseId = route.browseId,
        ))
    }

internal fun canonicalAmsChartShelf(tiles: List<AmsDailyChartTile>): HomeShelf =
    HomeShelf("Rankings pelo mundo",appleChartTerritories.flatMap { territory ->
        listOfNotNull(
            tiles.singleOrNull { it.country == territory.code }?.card,
            ShelfItem(title="Top 100 álbuns · ${territory.displayName}",
                subtitle="Apple Music · Álbuns mais tocados",thumbnailUrl=null,videoId=null,
                browseId=canonicalAppleChartRoute(BuiltInProviderKeys.APPLE_MUSIC_WEB,territory.code,true)),
        )
    })

internal class AmsDailyChartDirectoryController(
    private val scope: CoroutineScope,
    private val gate: () -> ProviderPolicyGate?,
    private val read: suspend () -> ProviderCallResult<List<AmsDailyChartTile>>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var owner: ProviderPolicyGate? = null
    private var lease: ProviderReadLease? = null
    private var job: Job? = null
    private var started: Long? = null
    private val mutable = MutableStateFlow<List<AmsDailyChartTile>>(emptyList())
    val tiles = mutable.asStateFlow()
    fun current(): Boolean = owner === gate() && lease?.isCurrent() == true &&
        preferredAppleChartProvider(gate()) == BuiltInProviderKeys.APPLE_MUSIC_WEB
    fun tile(browseId: String?): AmsDailyChartTile? =
        if (!current()) null else mutable.value.singleOrNull { it.card.browseId == browseId }
    fun purgeRevoked() {
        if (current()) return
        job?.cancel()
        job = null
        owner = null
        lease = null
        started = null
        mutable.value = emptyList()
    }
    fun load() {
        purgeRevoked()
        val gateNow = gate() ?: return
        if (preferredAppleChartProvider(gateNow) != BuiltInProviderKeys.APPLE_MUSIC_WEB) return
        if (job?.isActive == true || started?.let { now()-it in 0L until 15*60_000L } == true) return
        val ticket = ProviderReadLease.acquire(gateNow,listOf(
            ProviderCapability.CHART_MOST_PLAYED to BuiltInProviderKeys.APPLE_MUSIC_WEB)) ?: return
        owner = gateNow
        lease = ticket
        started = now()
        val task = scope.launch(start=CoroutineStart.LAZY) {
            val result = try { read() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (lease !== ticket) return@launch
            if (!current() || result == ProviderCallResult.Denied || result == ProviderCallResult.Stale) {
                purgeRevoked()
                return@launch
            }
            mutable.value = (result as? ProviderCallResult.Success)?.value.orEmpty()
        }
        job = task
        val unregister = ticket.cancelJobOnRevoke(task)
        task.invokeOnCompletion { unregister() }
        task.start()
    }
}

internal data class CanonicalAppleChartRuntime(
    val provider: ProviderKey?,
    val shelf: HomeShelf?,
    val songs: Map<String,UiState<AppleCountryChart>>,
    val albums: Map<String,UiState<AppleCountryAlbumChart>>,
)

@Composable
internal fun rememberCanonicalAppleChartRuntime(viewModel: MainViewModel): CanonicalAppleChartRuntime {
    val provider = rememberAppleCountryChartProvider()
    val itunesSongs by viewModel.appleCountryCharts.collectAsStateWithLifecycle()
    val itunesAlbums by viewModel.appleCountryAlbumCharts.collectAsStateWithLifecycle()
    val ams by viewModel.amsCountryCharts.snapshots.collectAsStateWithLifecycle()
    val directory by viewModel.amsDailyCharts.tiles.collectAsStateWithLifecycle()
    LaunchedEffect(provider,directory) {
        if (provider == BuiltInProviderKeys.APPLE_MUSIC_WEB) viewModel.amsDailyCharts.load()
    }
    val shelf = if (provider == BuiltInProviderKeys.APPLE_MUSIC_WEB)
        canonicalAmsChartShelf(directory.takeIf { viewModel.amsDailyCharts.current() }.orEmpty())
    else provider?.let { canonicalCountryChartShelf(true,provider=it) }
    if (provider == BuiltInProviderKeys.ITUNES)
        return CanonicalAppleChartRuntime(provider,shelf,itunesSongs,itunesAlbums)
    if (provider != BuiltInProviderKeys.APPLE_MUSIC_WEB)
        return CanonicalAppleChartRuntime(null,null,emptyMap(),emptyMap())
    val current = ams.filterKeys(viewModel.amsCountryCharts::isCurrent)
    val songs = current.filterKeys { !it.albums }.mapNotNull { (key,value) ->
        val state: UiState<AppleCountryChart> = when (value) {
            UiState.Loading -> UiState.Loading
            is UiState.Error -> value
            is UiState.Success -> UiState.Success((value.data as? AmsCountryChartResult.Songs)?.chart
                ?: return@mapNotNull null)
        }
        key.country to state
    }.toMap()
    val albums = current.filterKeys { it.albums }.mapNotNull { (key,value) ->
        val state: UiState<AppleCountryAlbumChart> = when (value) {
            UiState.Loading -> UiState.Loading
            is UiState.Error -> value
            is UiState.Success -> UiState.Success((value.data as? AmsCountryChartResult.Albums)?.chart
                ?: return@mapNotNull null)
        }
        key.country to state
    }.toMap()
    return CanonicalAppleChartRuntime(provider,shelf,songs,albums)
}
