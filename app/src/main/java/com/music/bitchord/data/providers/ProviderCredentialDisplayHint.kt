package com.music.bitchord.data.providers

/**
 * Display-only indication that a credential is saved. Never display or
 * persist the full secret as a Compose input value. The string returned here
 * is a non-editable hint, NOT a credential and must never be submitted.
 *
 * Short secrets reveal no suffix; longer ones reveal at most four characters.
 */
internal fun maskedProviderCredentialHint(secret: String): String? {
    if (secret.isBlank()) return null
    if (secret.length < 8) return "••••••••"
    val suffix = secret.takeLast(4)
        .map { if (it.isLetterOrDigit()) it else '•' }
        .joinToString("")
    return "••••••••$suffix"
}
