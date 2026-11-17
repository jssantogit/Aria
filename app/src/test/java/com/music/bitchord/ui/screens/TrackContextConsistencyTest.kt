package com.music.bitchord.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard against source-specific track menus resurrecting inline ellipses or
 * the opaque compact popup used by the playlist screenshot regression.
 * Collection toolbar overflows are deliberately outside this contract.
 */
class TrackContextConsistencyTest {
    private val root: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "app/src/main/java/com/music/bitchord/MainActivity.kt").exists() &&
                     File(it, "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/LegacySongRow.kt").exists() }
    }
    private fun read(path: String) = File(root, path).readText()

    @Test fun canonicalAndPublicPlaylistTracksHaveNoTrailingEllipsisOrCompactPopup() {
        val canonical = read("app/src/main/java/com/music/bitchord/ui/screens/CanonicalPlaylistDetailScreen.kt")
        val public = read("app/src/main/java/com/music/bitchord/ui/screens/CanonicalPublicPlaylistDetailScreen.kt")
        assertFalse(canonical.contains("RowMoreButton("))
        assertFalse(canonical.contains("NativeCompactOverflowMenu("))
        assertFalse(public.contains("CanonicalMetadataTrackMore("))
        assertTrue(canonical.contains("HeldContextMenu("))
        assertTrue(public.contains("HeldContextMenu("))
    }

    @Test fun bothPlaylistSourcesShareRealHazeBackedHeldMenuViewport() {
        val frame = read("app/src/main/java/com/music/bitchord/ui/screens/NativePlaylistDetailFrame.kt")
        assertTrue(frame.contains("heldActions:"))
        assertTrue(frame.contains(".hazeSource("))
        assertTrue(frame.contains("heldActions(hazeState)"))
    }

    @Test fun sharedTrackRowsDefaultToNoVisibleMoreButton() {
        val song = read("sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/LegacySongRow.kt")
        val remote = read("app/src/main/java/com/music/bitchord/ui/screens/CanonicalRemoteLibraryScreen.kt")
        assertTrue(song.contains("showMoreButton: Boolean = false"))
        assertTrue(remote.contains("showInlineOptions: Boolean = false"))
    }

    @Test fun homeCompactTrackUsesHoldInsteadOfInlineOverflow() {
        val screen = read("sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/HomeScreen.kt")
        val compact = screen.substringAfter("fun CompactTrackRow(")
            .substringBefore("/**\n * Same 40dp native circular surface")
        assertTrue(compact.contains(".longPressMenuClickable("))
        assertFalse(compact.contains("RowMoreButton("))
    }
}
