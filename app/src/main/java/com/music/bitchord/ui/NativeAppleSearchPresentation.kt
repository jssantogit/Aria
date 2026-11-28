package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.providers.ProviderRuntime
import com.music.bitchord.ui.screens.*

internal data class NativeAppleSearchPresentation(
    val albums: UiState<List<AppleEditorialSearchRow>>?,
    val artists: UiState<List<AppleEditorialSearchRow>>?,
    val tracks: UiState<List<AppleEditorialTrackSearchRow>>?,
    val paging: Map<AppleSearchSection,AppleSearchPageStatus>,
    val discogsVisible: Boolean,
)

@Composable
internal fun collectNativeAppleSearchPresentation(viewModel: MainViewModel, filter: SearchFilter): NativeAppleSearchPresentation {
    val ams by viewModel.appleMusicWebSearchUi.state.collectAsStateWithLifecycle()
    val policy = ProviderRuntime.state?.collectAsStateWithLifecycle()?.value
    val expanded = policy != null && filter == SearchFilter.ALL && viewModel.appleMusicWebSearchUi.expansionCurrent()
    val albums by viewModel.appleEditorialResults.collectAsStateWithLifecycle()
    val artists by viewModel.appleEditorialArtists.collectAsStateWithLifecycle()
    val tracks by viewModel.appleEditorialTracks.collectAsStateWithLifecycle()
    val paging by viewModel.appleSearchPaging.collectAsStateWithLifecycle()
    val discogsVisible by viewModel.catalogDiscogsSearchVisible.collectAsStateWithLifecycle()
    return if (ams.selected) {
        if (viewModel.appleMusicWebSearchUi.current())
            NativeAppleSearchPresentation(if (expanded) ams.mixedAlbums else ams.albums,ams.artists,
                if (expanded) ams.mixedTracks else ams.tracks,ams.paging,false)
        else NativeAppleSearchPresentation(null,null,null,emptyMap(),false)
    } else NativeAppleSearchPresentation(albums,artists,tracks,paging,discogsVisible)
}
