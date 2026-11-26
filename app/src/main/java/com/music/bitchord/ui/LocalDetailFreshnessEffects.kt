package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.UiState
import com.music.bitchord.download.Downloads
import com.music.bitchord.ui.screens.CACHE_FOLDER_BROWSE_ID

/**
 * Existing local/downloaded detail refresh effects, isolated from the giant
 * BitChordApp Compose method. No source identity, playback or UI change.
 *
 * The key snapshots intentionally accept Any?: they are only invalidation
 * signals to LaunchedEffect, never cast or used as provider content.
 */
@Composable
internal fun LocalDetailFreshnessEffects(
    viewModel: MainViewModel,
    detail: DetailPage?,
    savedDownloads: Any?,
    savedCollections: Any?,
    localMusicFolderUri: Any?,
    filterNonMusicAudio: Any?,
    webdavUrl: Any?,
    smbHost: Any?,
) {
    LaunchedEffect(savedDownloads, savedCollections, detail?.browseId) {
        val openPage = detail ?: return@LaunchedEffect
        val open = openPage.browseId
        val folder = LocalGroups.folderOf(open) ?: open
        if (openPage.songs !is UiState.Loading &&
            (folder == "local:downloads" ||
                folder == CACHE_FOLDER_BROWSE_ID ||
                Downloads.recordIdOf(open) != null ||
                com.music.bitchord.data.spotify.LocalPlaylistStore
                    .getPlaylist(open) != null)
        ) {
            viewModel.reloadLocalDetail(open)
        }
    }
    LaunchedEffect(localMusicFolderUri, filterNonMusicAudio) {
        val open = detail?.browseId
        if (open == "local:all" || LocalGroups.folderOf(open) == "local:all") {
            viewModel.reloadLocalDetail(open!!)
        }
    }
    LaunchedEffect(webdavUrl) {
        if (detail?.browseId == com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID) {
            viewModel.reloadLocalDetail(
                com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID)
        }
    }
    LaunchedEffect(smbHost) {
        if (detail?.browseId == com.music.bitchord.data.smb.SmbConfig.BROWSE_ID) {
            viewModel.reloadLocalDetail(
                com.music.bitchord.data.smb.SmbConfig.BROWSE_ID)
        }
    }
}
