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
        assertTrue(runCatching { Backup.decodeBackup("""{"app":"other","version":1,"settings":{},"listening":[]}""") }.isFailure)
        assertTrue(runCatching { Backup.decodeBackup("""{"app":"aria","version":2,"settings":{},"listening":[]}""") }.isFailure)
    }
    @Test fun unrelatedOrTruncatedObjectsCannotBecomeEmptyRestores() {
        for (input in listOf(
            "{}",
            """{"settings":{},"listening":[]}""",
            """{"app":"aria","settings":{},"listening":[]}""",
            """{"app":"aria","version":1,"settings":{}}""",
            """{"app":"bitchord","version":1,"listening":[]}""",
            """{"app":"aria","version":"1","settings":{},"listening":[]}""",
            """{"app":"aria","version":0,"settings":{},"listening":[]}""",
            """{"app":"aria","version":1,"settings":[],"listening":[]}""",
        )) {
            assertTrue(input, runCatching { Backup.decodeBackup(input) }.isFailure)
        }
        val empty = Backup.decodeBackup("""{"app":"aria","version":1,"settings":{},"listening":[]}""")
        assertTrue(empty.settings.isEmpty())
        assertTrue(empty.listening.isEmpty())
    }

    @Test fun invalidDuplicateOrFutureListeningBucketsAreRejectedAsAWhole() {
        for (listening in listOf(
            """[{"month":"2026-13"}]""",
            """[{"month":"2026-01"},{"month":"2026-01"}]""",
            """[{"month":"2026-01","version":2}]""",
        )) {
            val input = """{"app":"bitchord","version":1,"settings":{},"listening":$listening}"""
            assertTrue(input, runCatching { Backup.decodeBackup(input) }.isFailure)
        }
    }

    @Test fun populatedLegacyBackupRetainsTypedSettingsAndListeningOnDecode() {
        val legacy = """
            {"app":"bitchord","version":1,"settings":{
              "crossfade_seconds":{"type":"int","value":"4"},
              "playback_speed":{"type":"float","value":"1.25"},
              "autoplay":{"type":"bool","value":"false"},
              "theme_mode":{"type":"string","value":"DARK"}
            },"listening":[{"month":"2026-01","tracks":[{"id":"fixture","ms":123456,"plays":2}],
              "days":{"1":123456}}]}
        """.trimIndent()
        val decoded = Backup.decodeBackup(legacy)
        assertEquals(4, decoded.settings.getValue("crossfade_seconds").decoded())
        assertEquals(1.25f, decoded.settings.getValue("playback_speed").decoded())
        assertEquals(false, decoded.settings.getValue("autoplay").decoded())
        assertEquals("DARK", decoded.settings.getValue("theme_mode").decoded())
        val bucket = decoded.listening.single()
        assertEquals("2026-01", bucket.month)
        assertEquals(123456L, bucket.tracks.single().ms)
        assertEquals(2, bucket.tracks.single().plays)
        assertEquals(123456L, bucket.days.getValue(1))
    }
}
