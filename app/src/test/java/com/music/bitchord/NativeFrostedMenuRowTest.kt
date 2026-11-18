package com.music.bitchord

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.test.assertExists
import com.music.bitchord.ui.components.ActionRow
import com.music.bitchord.ui.components.ActionRowStyle
import com.music.bitchord.ui.components.LocalActionRowStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import com.music.bitchord.ui.components.TopBarAccountButton
import androidx.compose.ui.test.performClick
import com.music.bitchord.ui.theme.BitChordTheme
import org.junit.Assert.assertEquals
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Selected source/sort menu rows announce state and preserve callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeFrostedMenuRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedFilterIsDistinguishableAndCanStillBeClicked() {
        var clicks by mutableIntStateOf(0)
        compose.setContent {
            BitChordTheme {
                FrostedMenuRow(label = "Fonte local", checked = true, onClick = { clicks++ })
            }
        }
        compose.onNodeWithText("Fonte local").assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun homeSettingsLauncherDelegatesToArtistStyleHostWithoutOpeningAnotherPopup() {
        var opens by mutableIntStateOf(0)
        compose.setContent {
            BitChordTheme {
                TopBarAccountButton(
                    account = null,
                    onClick = {},
                    hostedExpanded = false,
                    onShowHostedMenu = { opens++ },
                )
            }
        }
        compose.onNodeWithTag("apple-global-more-options").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        compose.onNodeWithTag("native-compact-overflow").assertDoesNotExist()
    }

    @Test fun frostedArtistActionsAlwaysHaveAnOpaqueNeutralBase() {
        assertEquals(Color(0xFF29292D), frostedMenuPanelOpaquePlate(isDark = true))
        assertEquals(Color(0xFFF5F5F7), frostedMenuPanelOpaquePlate(isDark = false))
        assertEquals(1f, frostedMenuPanelOpaquePlate(isDark = true).alpha)
        assertEquals(1f, frostedMenuPanelOpaquePlate(isDark = false).alpha)
    }

    @Test fun overflowLauncherNeverPaintsASecondSelectedCircle() {
        assertEquals(Color.Transparent,
            nativeCollectionOverflowActiveFill(expanded = false, ink = Color.White))
        assertEquals(Color.Transparent,
            nativeCollectionOverflowActiveFill(expanded = true, ink = Color.White))
        assertEquals(Color.Transparent,
            nativeCollectionOverflowActiveFill(expanded = true, ink = Color.Black))
    }

    @Test fun rootSettingsAndArtistUseTheSameFrostedMenuWidth() {
        assertEquals(240.dp, FROSTED_SETTINGS_MENU_WIDTH)
    }

    @Test fun settingsRowUsesTheSameLeadingLabelColumnAsExistingTrailingIconActions() {
        val settings = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.settings)
        compose.setContent {
            BitChordTheme {
                CompositionLocalProvider(LocalActionRowStyle provides ActionRowStyle.Menu) {
                    Column {
                        ActionRow(
                            icon = Icons.Rounded.Settings, label = "Tocar a seguir",
                            onClick = {},
                        )
                        FrostedSettingsAction {}
                    }
                }
            }
        }
        val normal = compose.onNodeWithText("Tocar a seguir")
            .fetchSemanticsNode().boundsInRoot
        val settingsBounds = compose.onNodeWithText(settings)
            .fetchSemanticsNode().boundsInRoot
        val iconBounds = compose.onNodeWithTag(
            "native-frosted-trailing-icon", useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertEquals(normal.left, settingsBounds.left, 1f)
        org.junit.Assert.assertTrue(iconBounds.left > settingsBounds.right)
    }

    @Test fun sharedArtistSettingsActionIsClickableAndNeverSelected() {
        var clicks by mutableIntStateOf(0)
        val label = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.settings)
        compose.setContent {
            BitChordTheme {
                FrostedSettingsAction { clicks++ }
            }
        }
        compose.onNodeWithText(label).assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun ordinaryMenuActionIsNotIncorrectlyMarkedSelected() {
        compose.setContent {
            BitChordTheme {
                FrostedMenuRow(label = "Visualizar em grade", checked = false, onClick = {})
            }
        }
        compose.onNodeWithText("Visualizar em grade").assertIsNotSelected()
    }
}
