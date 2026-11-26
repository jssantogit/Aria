package com.music.aria.auth

import android.content.Context
import android.content.SharedPreferences

/** Credential storage for independent Aria integrations. Legacy file names are
 * retained so existing Discord and local-server credentials remain readable.
 */
class AuthStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        EncryptedPrefs.open(context, "bitchord_auth", "bitchord_auth_plain"),
    )

    init {
        // Remove only the retired account credentials and profile selectors.
        // Never clear the shared store, music data, or other service logins.
        val obsolete = RETIRED_ACCOUNT_KEYS.filter(prefs::contains)
        if (obsolete.isNotEmpty()) {
            val edit = prefs.edit()
            obsolete.forEach { edit.remove(it) }
            edit.apply()
        }
    }

    /** Compatibility gate for historic account-dependent presentation. */
    val isSignedIn: Boolean get() = false

    var discordToken: String?
        get() = prefs.getString(KEY_DISCORD_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DISCORD_TOKEN, value).apply()

    var webdavPassword: String?
        get() = prefs.getString(KEY_WEBDAV_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_WEBDAV_PASSWORD, value).apply()

    var smbPassword: String?
        get() = prefs.getString(KEY_SMB_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_SMB_PASSWORD, value).apply()

    companion object {
        private val RETIRED_ACCOUNT_KEYS = listOf(
            "cookie", "google_account_sessions_v2", "active_google_account_id_v2",
            "active_youtube_profile_id_v2", "channel_page_id", "channel_datasync_id",
            "channel_name", "channel_auth_user",
        )
        private const val KEY_DISCORD_TOKEN = "discord_token"
        private const val KEY_WEBDAV_PASSWORD = "webdav_password"
        private const val KEY_SMB_PASSWORD = "smb_password"
    }
}
