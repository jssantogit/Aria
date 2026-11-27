package com.music.aria.data.catalog.metadata.discogs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.music.aria.data.providers.maskedProviderCredentialHint

/**
 * Optional personal Discogs search credential, device-bound and excluded from
 * Android backup/device transfer. Unlike the app's legacy AuthStore this vault
 * NEVER falls back to plaintext if Keystore is unavailable.
 *
 * No read method returns the token to Compose or diagnostics. Only the
 * dedicated search adapter receives it in memory for the authorized request.
 */
internal class DiscogsSearchTokenVault(context: Context) {
    private val application = context.applicationContext

    private val preferences: SharedPreferences? by lazy {
        try {
            EncryptedSharedPreferences.create(
                application,
                PREFS_NAME,
                MasterKey.Builder(application)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            null // fail closed: never send credentials to plain preferences
        }
    }

    fun configured(): Boolean = validatedToken() != null

    /**
     * Only a short, non-editable indicator leaves the vault for Settings.
     * No raw token enters Compose state, saved state, logs or navigation.
     */
    fun maskedHint(): String? = validatedToken()?.let(::maskedProviderCredentialHint)

    /**
     * Called by the authorized search adapter for network requests only.
     * Never put its return value in UI, diagnostics or exported backup.
     */
    fun readForSearch(): String? = validatedToken()

    private fun validatedToken(): String? = try {
        preferences?.getString(TOKEN_KEY, null)?.takeIf(::isValidDiscogsSearchToken)
    } catch (_: Exception) {
        null
    }

    fun store(value: String): Boolean {
        val token = value.trim()
        if (!isValidDiscogsSearchToken(token)) return false
        return try {
            preferences?.edit()?.putString(TOKEN_KEY, token)?.commit() == true
        } catch (_: Exception) {
            false
        }
    }

    fun clear(): Boolean = try {
        preferences?.edit()?.remove(TOKEN_KEY)?.commit() == true
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val PREFS_NAME = "bitchord_discogs_credentials"
        const val TOKEN_KEY = "discogs_search_token"
    }
}

internal fun isValidDiscogsSearchToken(raw: String): Boolean {
    val token = raw.trim()
    return token.length in 24..128 &&
        token.all { it.code in 33..126 && (it.isLetterOrDigit() || it == '_' || it == '-') }
}
