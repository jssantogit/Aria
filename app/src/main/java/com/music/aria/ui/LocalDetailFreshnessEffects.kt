package com.music.aria.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.music.aria.data.LocalGroups
import com.music.aria.data.model.DetailPage
import com.music.aria.data.model.UiState
import com.music.aria.download.Downloads
import com.music.aria.ui.screens.CACHE_FOLDER_BROWSE_ID

/**
 * Existing local/downloaded detail refresh effects, isolated from the giant
 * AriaApp Compose method. No source identity, playback or UI change.
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
                com.music.aria.data.spotify.LocalPlaylistStore
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
        if (detail?.browseId == com.music.aria.data.webdav.WebDavConfig.BROWSE_ID) {
            viewModel.reloadLocalDetail(
                com.music.aria.data.webdav.WebDavConfig.BROWSE_ID)
        }
    }
    LaunchedEffect(smbHost) {
        if (detail?.browseId == com.music.aria.data.smb.SmbConfig.BROWSE_ID) {
            viewModel.reloadLocalDetail(
                com.music.aria.data.smb.SmbConfig.BROWSE_ID)
        }
    }
}
