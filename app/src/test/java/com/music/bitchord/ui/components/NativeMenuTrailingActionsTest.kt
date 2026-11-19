package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.music.bitchord.FrostedMenuRow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The shared contextual menu contract applies to MusicBrainz Search, Library,
 * Navidrome, artist/album detail, source/filter popups and action menus.
 * Avatar/track rows in ordinary lists are intentionally a different pattern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeMenuTrailingActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactPopupPutsActionIconAfterItsLabel() {
        compose.setContent {
            MaterialTheme {
                NativeCompactMenuItem(
                    label = "Ver detalhes",
                    icon = Icons.Rounded.Info,
                    onClick = {},
                )
            }
        }
        val text = compose.onNodeWithText("Ver detalhes", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag(
            "native-compact-menu-trailing-icon", useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertTrue("Compact menu action icon must be right of the text", icon.left > text.left)
    }

    @Test fun compactSelectedFilterKeepsItsCheckAtTheFarRight() {
        compose.setContent {
            MaterialTheme {
                NativeCompactMenuItem(
                    label = "Fonte local",
                    icon = Icons.Rounded.Info,
                    selected = true,
                    onClick = {},
                )
            }
        }
        val label = compose.onNodeWithText("Fonte local", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag(
            "native-compact-menu-trailing-icon", useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val check = compose.onNodeWithTag(
            "native-compact-menu-selected-check", useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertTrue(icon.center.x > label.center.x)
        assertTrue(check.center.x > icon.center.x)
    }

    @Test fun collectionMenusAndTrackMenusUseTheSameTrailingDirection() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalActionRowStyle provides ActionRowStyle.Menu) {
                    ActionRow(
                        icon = Icons.Rounded.Info,
                        label = "Tocar a seguir",
                        onClick = {},
                    )
                }
            }
        }
        val label = compose.onNodeWithText("Tocar a seguir", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag(
            "native-action-menu-trailing-icon", useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertTrue(icon.center.x > label.center.x)
    }

    @Test fun fullActionSheetsAlsoKeepTheirIconsOnTheRight() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalActionRowStyle provides ActionRowStyle.AppleSolid) {
                    ActionRow(
                        icon = Icons.Rounded.Info,
                        label = "Abrir artista",
                        onClick = {},
                    )
                }
            }
        }
        val label = compose.onNodeWithText("Abrir artista", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag(
            "native-action-sheet-trailing-icon", useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertTrue(icon.center.x > label.center.x)
    }

    @Test fun alreadyCanonicalFrostedMenuUsesTheSameDirection() {
        compose.setContent {
            MaterialTheme {
                FrostedMenuRow(
                    label = "Configurações",
                    checked = false,
                    icon = Icons.Rounded.Settings,
                    onClick = {},
                )
            }
        }
        val label = compose.onNodeWithText("Configurações", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag(
            "native-frosted-trailing-icon", useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertTrue(icon.center.x > label.center.x)
    }
}
