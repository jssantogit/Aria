package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PinnedLibraryEditorTileComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneGridKeepsThreeSlotsAndDoesNotEnlargeOnlyTwoPins() {
        // At a 360dp phone viewport, two occupied slots should each remain
        // ~112dp wide, not stretch to roughly half the Library shelf.
        val phoneWidth = 360.dp
        val phoneColumns = pinnedLibraryGridColumns(phoneWidth)
        assertEquals(3, phoneColumns)
        assertEquals(112.dp, (phoneWidth - 24.dp) / phoneColumns)
        assertEquals(3, pinnedLibraryGridColumns(599.dp))
        assertEquals(4, pinnedLibraryGridColumns(600.dp))
        assertEquals(4, pinnedLibraryGridColumns(840.dp))
    }

    @Test fun normalPinShowsTitleAndNeverShowsRemovalButton() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                PinnedLibraryEditorTile(
                    id = "canonical:ALBUM:test", title = "NJWMX",
                    editing = false, columns = 3,
                    onClick = { opens++ }, onLongPress = {}, onRemove = {},
                    onMoveBy = {},
                ) { Box(Modifier.size(80.dp)) }
            }
        }
        compose.onNodeWithText("NJWMX").assertExists()
        compose.onNodeWithTag("library-pin-editor-remove", useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithTag("pinned-library-artwork").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun editPinShowsRemovableOverlayAndKeepsItsTitle() {
        var removals = 0
        compose.setContent {
            MaterialTheme {
                PinnedLibraryEditorTile(
                    id = "playlist:normal:test", title = "NewJeans 1st EP",
                    editing = true, columns = 3,
                    onClick = {}, onLongPress = {},
                    onRemove = { removals++ }, onMoveBy = {},
                ) { Box(Modifier.size(80.dp)) }
            }
        }
        compose.onNodeWithText("NewJeans 1st EP").assertExists()
        compose.onNodeWithTag("library-pin-editor-remove", useUnmergedTree = true)
            .assertExists().performClick()
        compose.runOnIdle { assertEquals(1, removals) }
    }
}
