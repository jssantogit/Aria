package com.music.bitchord.data.providers

import java.security.MessageDigest

/**
 * Keys adaptive HTTP segments by the exact provider instance and entire
 * signed URL, so two providers (or two different renditions) cannot read
 * each other's cached bytes. Credentials and URL signatures are never placed
 * in plaintext cache keys. HTTP Range is encoded by the cache span position,
 * not by a second URL identity.
 */
internal fun providerSegmentCacheKey(
    owner: ProviderKey,
    url: String,
    requestKey: String? = null,
): String {
    val input = (url + "\u0000" + requestKey.orEmpty())
        .toByteArray(Charsets.UTF_8)
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(input).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    input.fill(0)
    return "source-segment:${owner.providerId}:${owner.instanceId}:$hash"
}
