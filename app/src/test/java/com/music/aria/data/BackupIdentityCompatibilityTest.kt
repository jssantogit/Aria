package com.music.aria.data

import com.music.aria.data.stats.Backup
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupIdentityCompatibilityTest {
    @Test fun legacyBackupStillDecodesAfterNamespaceMigration() {
        val old = """
            {
              "app": "bitchord",
              "version": 1,
              "versionName": "1.8",
              "exportedAt": "2026-01-01T00:00:00Z",
              "settings": {"volume": {"type": "float", "value": "0.5"}},
              "listening": []
            }
        """.trimIndent()
        val backup = Backup.decodeBackup(old)
        assertEquals("bitchord", backup.app)
        assertEquals(0.5f, backup.settings.getValue("volume").decoded())
        assertTrue(backup.listening.isEmpty())
    }

    @Test fun newBackupWritesAriaTagWithoutQualifiedClassNames() {
        val encoded = Json { encodeDefaults = true }
            .encodeToString(Backup.BackupFile.serializer(), Backup.BackupFile())
        val decoded = Backup.decodeBackup(encoded)
        assertEquals("aria", decoded.app)
        assertFalse(encoded.contains("com.music.aria"))
    }

    @Test fun foreignAndFutureBackupRejectedBeforeStateMutation() {
        assertTrue(runCatching { Backup.decodeBackup("""{"app":"other","version":1}""") }.isFailure)
        assertTrue(runCatching { Backup.decodeBackup("""{"app":"aria","version":2}""") }.isFailure)
    }
}
