package com.music.bitchord.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.appleSettingsSwitchColors

/**
 * The three real Local Music settings previously under Settings > Música local.
 * Keep the same persisted SAF grant, AppSettings keys and MediaStore filter.
 * This content is shown INSIDE the local provider's existing capability page,
 * so its native Back returns to Provedores > Áudio.
 */
@Composable
internal fun ProviderLocalLibrarySettings() {
    val context = LocalContext.current
    val folderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { folder ->
        if (folder != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    folder,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            AppSettings.setLocalMusicFolderUri(folder.toString())
        }
    }

    SettingsGroupSurface(modifier = Modifier.padding(horizontal = 16.dp)) {
        SettingsRow(
            icon = Icons.Rounded.Folder,
            title = stringResource(R.string.local_music_folder),
            subtitle = LocalMediaRepository.selectedFolderLabel(folderUri)
                ?: stringResource(R.string.all_audio_folders),
            onClick = { folderPicker.launch(null) },
            modifier = Modifier.testTag("providers-local-folder-picker"),
        )
        if (folderUri.isNotBlank()) {
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.LibraryMusic,
                title = stringResource(R.string.use_all_audio_folders),
                subtitle = stringResource(R.string.use_all_audio_folders_subtitle),
                onClick = { AppSettings.setLocalMusicFolderUri("") },
                modifier = Modifier.testTag("providers-local-use-all-folders"),
            )
        }
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.FilterAlt,
            title = stringResource(R.string.filter_non_music_audio),
            subtitle = stringResource(R.string.filter_non_music_audio_subtitle),
            trailing = {
                Switch(
                    checked = filterNonMusicAudio,
                    onCheckedChange = AppSettings::setFilterNonMusicAudio,
                    colors = appleSettingsSwitchColors(),
                )
            },
            onClick = { AppSettings.setFilterNonMusicAudio(!filterNonMusicAudio) },
            modifier = Modifier.testTag("providers-local-filter-nonmusic"),
        )
    }
}
