package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.*

internal data class NativeAppleSearchPresentation(
    val albums: UiState<List<AppleEditorialSearchRow>>?,
    val artists: UiState<List<AppleEditorialSearchRow>>?,
    val tracks: UiState<List<AppleEditorialTrackSearchRow>>?,
    val paging: Map<AppleSearchSection,AppleSearchPageStatus>,
)

@Composable
internal fun collectNativeAppleSearchPresentation(viewModel: MainViewModel): NativeAppleSearchPresentation {
    val ams by viewModel.appleMusicWebSearchUi.state.collectAsStateWithLifecycle()
    val albums by viewModel.appleEditorialResults.collectAsStateWithLifecycle()
    val artists by viewModel.appleEditorialArtists.collectAsStateWithLifecycle()
    val tracks by viewModel.appleEditorialTracks.collectAsStateWithLifecycle()
    val paging by viewModel.appleSearchPaging.collectAsStateWithLifecycle()
    return if (ams.selected) {
        if (viewModel.appleMusicWebSearchUi.current())
            NativeAppleSearchPresentation(ams.albums,ams.artists,ams.tracks,ams.paging)
        else NativeAppleSearchPresentation(null,null,null,emptyMap())
    } else NativeAppleSearchPresentation(albums,artists,tracks,paging)
}
