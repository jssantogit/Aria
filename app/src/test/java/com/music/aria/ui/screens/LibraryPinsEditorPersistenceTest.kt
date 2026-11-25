package com.music.aria.ui.screens

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The visible edit draft only becomes real pins after the blue check mark. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LibraryPinsEditorPersistenceTest {
    @Test fun mixedSourceMoveAndRemovalCommitTogetherAndSurviveReopen() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
        val keys = listOf(
            CanonicalLibraryPinRepository.KEY,
            LibraryPlaylistPinRepository.KEY,
            CatalogMetadataPinRepository.KEY,
            LibraryPinsEditorState.ORDER_KEY,
        )
        val backup = keys.associateWith { prefs.getString(it, null) }
        // Avoid process-global repository singletons shared by other Robolectric
        // tests, which may be bound to a different Application sandbox.
        val canonicalRepo = CanonicalLibraryPinRepository(prefs)
        val playlistRepo = LibraryPlaylistPinRepository(prefs)
        val catalogRepo = CatalogMetadataPinRepository(prefs)
        try {
            val album = CanonicalLibraryPin(
                CanonicalLibrarySelection(CanonicalLibraryEntityType.ALBUM, "album-test"),
                "Album",
            )
            val playlist = LibraryPlaylistPin("normal:test", "Mix")
            val catalog = CatalogMetadataPin(
                "catalog:RECORDING:123e4567-e89b-12d3-a456-426614174000",
                "Catalog song",
            )
            val albumKey = libraryCanonicalPinKey(album)
            val playlistKey = libraryPlaylistPinKey(playlist)
            val catalogKey = libraryCatalogPinKey(catalog)

            assertTrue(prefs.edit()
                .putString(CanonicalLibraryPinRepository.KEY,
                    encodeCanonicalLibraryPins(listOf(album)))
                .putString(LibraryPlaylistPinRepository.KEY,
                    encodeLibraryPlaylistPins(listOf(playlist)))
                .putString(CatalogMetadataPinRepository.KEY,
                    encodeCatalogMetadataPins(listOf(catalog)))
                .remove(LibraryPinsEditorState.ORDER_KEY)
                .commit())
            canonicalRepo.reloadFromDisk()
            playlistRepo.reloadFromDisk()
            catalogRepo.reloadFromDisk()

            val editor = LibraryPinsEditorState(app, canonicalRepo, playlistRepo, catalogRepo)
            assertEquals(listOf(albumKey, playlistKey, catalogKey),
                editor.displayOrder(editor.allPins()))
            editor.start()
            editor.move(catalogKey, 0)
            editor.remove(playlistKey)
            // Editing is a draft: storage and other screens see old pins.
            assertTrue(playlistRepo.pins.value.any { it.id == playlist.id })
            assertEquals(listOf(catalogKey, albumKey), editor.draft.toList())
            assertEquals(LibraryPinEditResult.SAVED, editor.save())
            assertFalse(editor.editing)
            assertTrue(playlistRepo.pins.value.isEmpty())
            assertEquals(listOf(catalogKey, albumKey), decodeLibraryPinOrder(
                prefs.getString(LibraryPinsEditorState.ORDER_KEY, null)))
            val reopened = LibraryPinsEditorState(
                app,
                CanonicalLibraryPinRepository(prefs),
                LibraryPlaylistPinRepository(prefs),
                CatalogMetadataPinRepository(prefs),
            )
            assertEquals(listOf(catalogKey, albumKey),
                reopened.displayOrder(reopened.allPins()))

            reopened.start()
            reopened.remove(catalogKey)
            reopened.cancel()
            assertEquals(1, catalogRepo.pins.value.size)
            assertFalse(reopened.editing)
        } finally {
            val restore = prefs.edit()
            backup.forEach { (key, value) -> restore.putString(key, value) }
            restore.commit()
            canonicalRepo.reloadFromDisk()
            playlistRepo.reloadFromDisk()
            catalogRepo.reloadFromDisk()
        }
    }
}
