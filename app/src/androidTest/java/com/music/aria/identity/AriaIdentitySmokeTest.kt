package com.music.aria.identity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.aria.AppIcon
import com.music.aria.BuildConfig
import com.music.aria.data.settings.AppSettings
import com.music.aria.data.stats.Backup
import com.music.aria.data.stats.ListeningStats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only the explicitly opted-in clean CI emulator may run these synthetic restores. */
@RunWith(AndroidJUnit4::class)
class AriaIdentitySmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun requireSyntheticEmulator() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("ariaIdentitySynthetic"))
        assertTrue(BuildConfig.DEBUG)
        assertEquals("com.dev.aria", context.packageName)
        assertTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
    }

    @Test fun allLauncherChoicesResolveThroughRealAppIconApi() {
        assertEquals(13, AppIcon.entries.size)
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        try {
            for (icon in AppIcon.entries) {
                AppIcon.apply(context, icon)
                assertEquals(icon, AppIcon.current(context))
                val activities = pm.queryIntentActivities(intent, 0)
                assertEquals(icon.name, 1, activities.size)
                assertEquals("com.music.aria.${icon.alias}", activities.single().activityInfo.name)
                assertEquals(icon.iconRes, activities.single().activityInfo.icon)
            }
        } finally {
            AppIcon.apply(context, AppIcon.Default)
        }
        assertEquals(AppIcon.Default, AppIcon.current(context))
        assertEquals("com.music.aria.LauncherDefault", pm.queryIntentActivities(intent, 0).single().activityInfo.name)
    }

    @Test fun legacyRestoreAndAriaReexportPreserveStateAndFilterProtectedKeys() = runBlocking {
        // Synthetic state only; no real credentials or storage grants.
        val prefs = context.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
        val priorSettings = AppSettings.exportPrefs()
        val priorListening = ListeningStats.exportAll()
        val protectedKeys = listOf("lastfm_session_key", "local_music_folder_uri")
        val priorProtected = prefs.all.filterKeys { it in protectedKeys }
        val input = File(context.cacheDir, "identity-legacy-fixture.json")
        val output = File(context.cacheDir, "identity-aria-fixture.json")
        try {
            assertTrue(prefs.edit().putString("lastfm_session_key", "synthetic-local")
                .putString("local_music_folder_uri", "").commit())
            input.writeText("""
                {"app":"bitchord","version":1,"versionName":"legacy-fixture","settings":{
                  "crossfade_seconds":{"type":"int","value":"4"},
                  "playback_speed":{"type":"float","value":"1.25"},
                  "autoplay":{"type":"bool","value":"false"},
                  "lastfm_session_key":{"type":"string","value":"synthetic-incoming"},
                  "local_music_folder_uri":{"type":"string","value":"content://invalid/fixture"}
                },"listening":[{"month":"2026-01","tracks":[{"id":"fixture","ms":123456,"plays":2}],"days":{"1":123456}}]}
            """.trimIndent())
            val restored = Backup.importFrom(context, Uri.fromFile(input)).getOrThrow()
            assertEquals(1, restored.months)
            assertEquals(4, AppSettings.crossfadeSeconds.value)
            assertEquals(1.25f, AppSettings.playbackSpeed.value, 0f)
            assertFalse(AppSettings.autoplay.value)
            assertEquals("synthetic-local", prefs.getString("lastfm_session_key", null))
            assertEquals("", prefs.getString("local_music_folder_uri", null))
            val month = ListeningStats.exportAll().single()
            assertEquals("2026-01", month.month)
            assertEquals(123456L, month.tracks.single().ms)
            assertEquals(2, month.tracks.single().plays)

            assertEquals(1, Backup.exportTo(context, Uri.fromFile(output)).getOrThrow())
            val exportedText = output.readText()
            val reexport = Backup.decodeBackup(exportedText)
            assertEquals("aria", reexport.app)
            assertEquals(4, reexport.settings.getValue("crossfade_seconds").decoded())
            assertEquals(month, reexport.listening.single())
            assertFalse(reexport.settings.containsKey("lastfm_session_key"))
            assertFalse(reexport.settings.containsKey("local_music_folder_uri"))

            input.writeText("{}")
            assertTrue(Backup.importFrom(context, Uri.fromFile(input)).isFailure)
            assertEquals(4, AppSettings.crossfadeSeconds.value)
            assertEquals(month, ListeningStats.exportAll().single())
        } finally {
            prefs.edit().apply {
                protectedKeys.forEach { key ->
                    val value = priorProtected[key] as? String
                    if (value == null) remove(key) else putString(key, value)
                }
            }.commit()
            AppSettings.importPrefs(priorSettings)
            ListeningStats.importAll(priorListening)
            input.delete()
            output.delete()
        }
    }
}
