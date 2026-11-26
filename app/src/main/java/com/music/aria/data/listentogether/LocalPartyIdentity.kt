package com.music.aria.data.listentogether

import java.security.MessageDigest

/** Display identity only. Membership authorization remains server-issued. */
internal data class LocalPartyIdentity(
    val userId: String,
    val deviceId: String,
    val name: String,
    val avatar: String? = null,
)

internal fun localPartyIdentity(installationId: String, nickname: String): LocalPartyIdentity? {
    val name = nickname.trim().take(80).takeIf { it.isNotBlank() } ?: return null
    if (installationId.isBlank()) return null
    val id = MessageDigest.getInstance("SHA-256")
        .digest("aria-party:$installationId".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(32)
    return LocalPartyIdentity(id, installationId, name)
}
