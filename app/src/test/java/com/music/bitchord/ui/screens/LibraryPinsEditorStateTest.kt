package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.app.Application
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Persistent order is cross-source; provider titles must never be identity.
 * org.json.JSONArray needs a real Android implementation, not JVM mock stubs. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LibraryPinsEditorStateTest {
    private val recording = CanonicalLibraryPin(
        CanonicalLibrarySelection(
            CanonicalLibraryEntityType.TRACK,
            "123e4567-e89b-12d3-a456-426614174000",
        ),
        "Song",
    )
    private val album = CanonicalLibraryPin(
        CanonicalLibrarySelection(
            CanonicalLibraryEntityType.ALBUM,
            "223e4567-e89b-12d3-a456-426614174000",
        ),
        "Album",
    )
    private val playlist = LibraryPlaylistPin("normal:mix-123", "Song")
    private val catalog = CatalogMetadataPin(
        "catalog:RECORDING:323e4567-e89b-12d3-a456-426614174000",
        "Song",
    )

    @Test fun sameTitleDifferentTypesRemainDistinctAndStable() {
        val ids = canonicalLibraryPinKeys(
            listOf(recording, album), listOf(playlist), listOf(catalog),
        )
        assertEquals(4, ids.size)
        assertEquals(4, ids.toSet().size)
        assertTrue(ids[0].startsWith("canonical:TRACK:"))
        assertTrue(ids[1].startsWith("canonical:ALBUM:"))
        assertTrue(ids[2].startsWith("playlist:"))
        assertTrue(ids[3].startsWith("catalog:"))
    }

    @Test fun oldUnorderedInstallMigratesWithoutLosingPins() {
        val ids = canonicalLibraryPinKeys(
            listOf(recording, album), listOf(playlist), listOf(catalog),
        )
        assertEquals(ids, orderedLibraryPinKeys(emptyList(), ids))
        val custom = listOf(ids[3], ids[1], ids[0], ids[2])
        assertEquals(custom, orderedLibraryPinKeys(custom, ids))
        assertEquals(custom, decodeLibraryPinOrder(encodeLibraryPinOrder(custom)))
    }

    @Test fun missingPinsDropAndNewPinsAppendAfterExistingOrder() {
        val live = listOf("canonical:TRACK:current", "playlist:new", "catalog:online")
        val saved = listOf("catalog:online", "canonical:TRACK:removed")
        assertEquals(
            listOf("catalog:online", "canonical:TRACK:current", "playlist:new"),
            orderedLibraryPinKeys(saved, live),
        )
        assertEquals(
            listOf("catalog:online", "canonical:TRACK:current", "playlist:new"),
            orderedLibraryPinKeys(saved + saved, live),
        )
    }

    @Test fun legacyAndMalformedOrderNeverCrashOrIntroduceForeignKeys() {
        assertTrue(decodeLibraryPinOrder(null).isEmpty())
        assertTrue(decodeLibraryPinOrder("{bad").isEmpty())
        assertEquals(
            listOf("playlist:normal:1", "canonical:ARTIST:abc"),
            decodeLibraryPinOrder(
                """["playlist:normal:1","invalid","playlist:normal:1","canonical:ARTIST:abc"]""",
            ),
        )
        assertFalse(orderedLibraryPinKeys(
            listOf("catalog:missing"), listOf("playlist:normal:1"),
        ).contains("catalog:missing"))
    }
}
