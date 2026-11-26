package com.music.aria

import android.app.Application
import android.content.Context
import com.music.aria.auth.AuthStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class RetiredGoogleCredentialsTest {
    @Test fun retirementOnlyRemovesLegacyAccountEntries() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("retired_account_fixture", Context.MODE_PRIVATE)
        val keys = listOf(
            "cookie", "google_account_sessions_v2", "active_google_account_id_v2",
            "active_youtube_profile_id_v2", "channel_page_id", "channel_datasync_id",
            "channel_name", "channel_auth_user",
        )
        val edit = prefs.edit().clear()
        keys.forEach { edit.putString(it, "synthetic") }
        edit.putString("discord_token", "discord-fixture")
            .putString("webdav_password", "webdav-fixture")
            .putString("smb_password", "smb-fixture")
            .putString("unrelated", "keep")
            .commit()
        repeat(2) {
            val store = AuthStore(prefs)
            assertFalse(store.isSignedIn)
            keys.forEach { assertFalse(prefs.contains(it)) }
            assertEquals("discord-fixture", store.discordToken)
            assertEquals("webdav-fixture", store.webdavPassword)
            assertEquals("smb-fixture", store.smbPassword)
            assertEquals("keep", prefs.getString("unrelated", null))
        }
    }
}
