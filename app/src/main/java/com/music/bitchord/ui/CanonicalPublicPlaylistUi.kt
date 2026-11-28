package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.music.bitchord.ui.screens.CanonicalPublicPlaylistSearchRow
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistDetail
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState

/** Small state adapter: expensive Compose flows and effects live outside
 * BitChordApp, whose JVM method has a hard 64KB bytecode limit. */
internal data class CanonicalPublicPlaylistUi(
    val playlists: Map<String, List<ListenBrainzPublicPlaylist>>,
    val detail: UiState<ListenBrainzPublicPlaylistDetail>,
    val homeShelf: HomeShelf?,
)

@Composable
internal fun rememberCanonicalPublicPlaylistUi(
    viewModel: MainViewModel,
    @Suppress("UNUSED_PARAMETER") homeSelected: Boolean,
    exploreSelected: Boolean,
    genreTag: String?,
): CanonicalPublicPlaylistUi {
    val playlists by viewModel.canonicalPublicPlaylists.collectAsStateWithLifecycle()
    val detail by viewModel.canonicalPublicPlaylistDetail.collectAsStateWithLifecycle()

    // Home no longer promotes unverified user-created playlist searches.
    // Do not spend ListenBrainz requests on a hidden, uncurated shelf.
    // Search and contextual genre playlists remain available independently.
    LaunchedEffect(exploreSelected, genreTag) {
        when {
            exploreSelected && genreTag == null -> viewModel.loadCanonicalExploreGenreCovers()
            exploreSelected -> viewModel.loadCanonicalPublicPlaylists(genreTag)
        }
    }

    val homeShelf: HomeShelf? = null
    return CanonicalPublicPlaylistUi(playlists, detail, homeShelf)
}


/** Keep the public playlist search boundary outside the large main Compose method. */
@Composable
internal fun rememberCanonicalPublicPlaylistSearchUi(
    viewModel: MainViewModel,
    query: String,
    active: Boolean,
): UiState<List<CanonicalPublicPlaylistSearchRow>>? {
    val state by viewModel.canonicalPublicPlaylistQuery.collectAsStateWithLifecycle()
    val typed = query.trim()
    LaunchedEffect(active, typed) {
        if (active && typed.length in 2..80) {
            delay(650L)
            viewModel.searchCanonicalPublicPlaylists(typed)
        }
    }
    if (!active || typed.isEmpty()) return null
    if (typed.length < 2) return UiState.Success(emptyList())
    val queryState = state?.takeIf { it.first == typed }?.second ?: return UiState.Loading
    return when (queryState) {
        UiState.Loading -> UiState.Loading
        is UiState.Error -> UiState.Error(queryState.message)
        is UiState.Success -> UiState.Success(queryState.data.map { item ->
            CanonicalPublicPlaylistSearchRow(
                mbid = item.mbid,
                title = item.title,
                creator = item.creator,
                artworkUri = item.artworkUrl,
            )
        })
    }
}
