package com.music.aria.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** A playlist track has no ⋮. The held plate exposes only verified actions. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalPlaylistTrackMenuComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun heldActionsExposeVerifiedAlbumWithoutGuessingTheLink() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    CanonicalPlaylistHeldTrackActions(
                        onDismiss = {},
                        onPlay = {},
                        onPlayNext = {},
                        onAddToQueue = {},
                        onOpenAlbum = { opened++ },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Ações da música").assertDoesNotExist()
        compose.onNodeWithText("Abrir álbum").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun absentCanonicalAlbumEvidenceHidesOnlyTheAlbumAction() {
        compose.setContent {
            MaterialTheme {
                Column {
                    CanonicalPlaylistHeldTrackActions(
                        onDismiss = {},
                        onPlay = {},
                        onPlayNext = {},
                        onAddToQueue = {},
                        onOpenAlbum = null,
                    )
                }
            }
        }
        compose.onNodeWithText("Reproduzir a seguir").assertExists()
        compose.onNodeWithText("Adicionar à fila").assertExists()
        compose.onNodeWithText("Abrir álbum").assertDoesNotExist()
    }

    @Test fun savedAlbumActionIsSeparateAndDismissesNativeMenu() {
        var dismissed = 0
        var trackRemovals = 0
        var albumRemovals = 0
        compose.setContent {
            MaterialTheme {
                CanonicalPlaylistHeldTrackActions(
                    onDismiss = { dismissed++ },
                    onPlay = {},
                    onPlayNext = {},
                    onAddToQueue = {},
                    onOpenAlbum = null,
                    onRemoveTrack = { trackRemovals++ },
                    onRemoveAlbum = { albumRemovals++ },
                )
            }
        }
        compose.onNodeWithText("Remover faixa").assertExists()
        compose.onNodeWithText("Remover álbum").performClick()
        compose.runOnIdle {
            assertEquals(1, albumRemovals)
            assertEquals(0, trackRemovals)
            assertEquals(1, dismissed)
        }
    }

    @Test fun metadataOnlyMemberOffersRemovalButNeverFakePlayback() {
        var removals = 0
        compose.setContent {
            MaterialTheme {
                CanonicalPlaylistHeldTrackActions(
                    onDismiss = {},
                    onPlay = null,
                    onPlayNext = null,
                    onAddToQueue = null,
                    onOpenAlbum = null,
                    onRemoveTrack = { removals++ },
                )
            }
        }
        compose.onNodeWithText("Reproduzir daqui").assertDoesNotExist()
        compose.onNodeWithText("Reproduzir a seguir").assertDoesNotExist()
        compose.onNodeWithText("Adicionar à fila").assertDoesNotExist()
        compose.onNodeWithText("Remover faixa").performClick()
        compose.runOnIdle { assertEquals(1, removals) }
    }

}
