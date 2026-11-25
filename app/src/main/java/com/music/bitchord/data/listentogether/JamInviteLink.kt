package com.music.bitchord.data.listentogether

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class ParsedJamInvite(
    val code: String,
    val serverUrl: String? = null,
)

/** Relays an Aria scheme invite from [com.music.bitchord.MainActivity] to Compose. */
object JamInviteLink {

    const val ORIGIN = "aria://party"

    private const val EXTRA_CONSUMED = "aria.jamInviteConsumed"
    private const val CUSTOM_SCHEME = "aria"
    private const val CUSTOM_HOST = "party"

    private val _pending = MutableStateFlow<ParsedJamInvite?>(null)
    val pending: StateFlow<ParsedJamInvite?> = _pending.asStateFlow()

    /** Reads an Aria invite from a cold launch or a new intent on the existing task. */
    fun consume(intent: Intent?): Boolean {
        if (
            intent == null ||
            intent.action != Intent.ACTION_VIEW ||
            intent.getBooleanExtra(EXTRA_CONSUMED, false)
        ) return false

        val invite = parseInvite(intent.dataString) ?: return false
        intent.putExtra(EXTRA_CONSUMED, true)
        _pending.value = invite
        return true
    }

    fun handled() {
        _pending.value = null
    }

    /** Returns the normalized party code only for the Aria invite URL shape. */
    fun parse(value: String?): String? = parseInvite(value)?.code

    /**
     * Parses an incoming invite:
     * aria://party/<CODE>?server=<SERVER>
     * No upstream web domain is claimed or used as a fallback.
     */
    fun parseInvite(value: String?): ParsedJamInvite? {
        val uri = runCatching { URI(value ?: return null) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val query = uri.rawQuery
        val rawServer = extractQueryParam(query, "server")
        val server = rawServer?.let { sanitizeServerUrl(it) ?: return null }

        // Only the Aria scheme is accepted.
        if (scheme == CUSTOM_SCHEME && host == CUSTOM_HOST) {
            if (uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return null
            val path = uri.path.orEmpty()
            val candidate = if (path.isEmpty() || path == "/") {
                extractQueryParam(query, "code") ?: return null
            } else {
                if (!path.startsWith("/")) return null
                path.removePrefix("/")
            }
            val code = cleanCode(candidate) ?: return null
            return ParsedJamInvite(code = code, serverUrl = server)
        }

        return null
    }

    fun url(code: String, customServer: String? = null): String = schemeUrl(code, customServer)

    fun schemeUrl(code: String, customServer: String? = null): String {
        val normalizedCode = code.uppercase()
        val base = customServer?.trim()?.trimEnd('/')
        return if (!base.isNullOrBlank()) {
            val encoded = runCatching { URLEncoder.encode(base, "UTF-8") }.getOrDefault(base)
            "aria://party/$normalizedCode?server=$encoded"
        } else {
            "aria://party/$normalizedCode"
        }
    }

    private fun cleanCode(raw: String): String? {
        return raw.takeIf { it.length == ListenTogether.CODE_LENGTH && it.all { ch -> ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' } }?.uppercase()
    }

    private fun extractQueryParam(query: String?, paramName: String): String? {
        if (query.isNullOrBlank()) return null
        return query.split('&').asSequence()
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.isNotEmpty() && it[0].equals(paramName, ignoreCase = true) }
            ?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }

    private fun sanitizeServerUrl(raw: String): String? =
        when (val result = ListenTogether.parseAndNormalizeServerUrl(raw)) {
            is ServerUrlValidationResult.Valid -> result.normalizedUrl.takeIf { it.isNotBlank() }
            is ServerUrlValidationResult.Invalid -> null
        }

}

