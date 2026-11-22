package com.music.bitchord

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.music.bitchord.ui.CanonicalExploreGenre

/**
 * Pushed Explore categories are navigation, never assets or playback handles.
 * Keep this stack out of the main Compose root to avoid JVM 64 KiB bytecode
 * overflow when a new route is introduced.
 */
internal class CanonicalGenreNavigation(
    private val showExplore: () -> Unit,
    private val returnToSearch: () -> Unit,
    private val requestMusicBrainz: (String) -> Unit,
) {
    var current by mutableStateOf<CanonicalExploreGenre?>(null)
        private set

    private var history by mutableStateOf<List<CanonicalExploreGenre>>(emptyList())
    private var originatedInSearch by mutableStateOf(false)

    fun open(genre: CanonicalExploreGenre, fromSearch: Boolean) {
        if (fromSearch) {
            history = emptyList()
            originatedInSearch = true
            showExplore()
        } else {
            val previous = current
            if (previous != null && previous.tag != genre.tag) {
                history = history + previous
            } else if (previous == null) {
                history = emptyList()
                originatedInSearch = false
            }
        }
        current = genre
        requestMusicBrainz(genre.tag)
    }

    fun back() {
        val previous = history.lastOrNull()
        if (previous != null) {
            history = history.dropLast(1)
            current = previous
            requestMusicBrainz(previous.tag)
        } else {
            current = null
            history = emptyList()
            if (originatedInSearch) returnToSearch()
            originatedInSearch = false
        }
    }

    /** Tab changes dismiss Explore without accidentally switching back to Search. */
    fun clear() {
        current = null
        history = emptyList()
        originatedInSearch = false
    }
}

/**
 * Stable navigation holder with up-to-date callbacks after provider order or
 * selected tab changes. No stored token, source identity or implicit fallback.
 */
@Composable
internal fun rememberCanonicalGenreNavigation(
    onShowExplore: () -> Unit,
    onReturnSearch: () -> Unit,
    loadMusicBrainz: (String) -> Unit,
): CanonicalGenreNavigation {
    val explore = rememberUpdatedState(onShowExplore)
    val search = rememberUpdatedState(onReturnSearch)
    val load = rememberUpdatedState(loadMusicBrainz)
    return remember {
        CanonicalGenreNavigation(
            showExplore = { explore.value() },
            returnToSearch = { search.value() },
            requestMusicBrainz = { tag -> load.value(tag) },
        )
    }
}
