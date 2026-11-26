package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCachedReadAccess
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderKey

/**
 * Source metadata stored by the existing canonical provider-backed cache.
 * This package does NOT own a Room table, disk cache or a second expiry system.
 * Its caller must supply a canonical storage reader, then apply this boundary.
 */
internal data class AppleMusicWebCachedValue<T>(
    val key: String,
    val provider: ProviderKey,
    val capability: ProviderCapability,
    val storefront: String,
    val language: String,
    val fetchedAtMs: Long,
    val expiresAtMs: Long,
    val outcome: AppleMusicWebOutcome<T>,
)

/**
 * A gated bridge between the canonical provider cache and Apple metadata.
 * The caller-supplied loader is never invoked while a capability is denied.
 * ProviderQueryExecutor enforces invalidation/cancellation during suspended
 * Room/cache reads, so a revoked response cannot be published.
 *
 * Only a proven Complete hit from this exact capability+market+locale+key is
 * reusable. Partial/unavailable, stale or mismatched cache entries are misses.
 * No alternate HTTP provider/fallback is called from this bridge.
 */
internal class AppleMusicWebCacheBridge(
    private val access: ProviderCachedReadAccess = ProviderCachedReadAccess(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun <T> read(
        capability: ProviderCapability,
        storefront: String,
        language: String,
        path: String,
        params: Map<String, String>,
        cached: suspend (key: String) -> AppleMusicWebCachedValue<T>?,
    ): AppleMusicWebOutcome<T>? {
        val market = storefront.lowercase(java.util.Locale.ROOT)
        val key = AppleMusicWebCacheContract.key(
            capability, market, language, path, params,
        ) ?: return null
        val maxAge = AppleMusicWebCacheContract.maxAgeMs(capability, path, params) ?: return null
        return access.read(
            capability = capability,
            provider = BuiltInProviderKeys.APPLE_MUSIC_WEB,
            unavailable = null as AppleMusicWebOutcome<T>?,
        ) {
            val entry = cached(key) ?: return@read null
            val now = clockMs()
            if (entry.provider != BuiltInProviderKeys.APPLE_MUSIC_WEB ||
                entry.capability != capability ||
                entry.storefront != market ||
                entry.language != language ||
                entry.key != key ||
                entry.fetchedAtMs < 0 || entry.fetchedAtMs > now ||
                entry.expiresAtMs <= now || entry.expiresAtMs <= entry.fetchedAtMs ||
                entry.expiresAtMs - entry.fetchedAtMs > maxAge
            ) return@read null
            entry.outcome as? AppleMusicWebOutcome.Complete<T>
        }
    }

    /** A canonical storage writer is supplied by the caller. Only completed
     * public metadata may be written, with expiry determined here. Revocation
     * suppresses the result of a suspended write; subsequent reads recheck
     * admission regardless of whether storage committed before revocation. */
    suspend fun <T> writeComplete(
        capability: ProviderCapability, storefront: String, language: String,
        path: String, params: Map<String, String>, outcome: AppleMusicWebOutcome<T>,
        persist: suspend (AppleMusicWebCachedValue<T>) -> Unit,
    ): Boolean {
        if (outcome !is AppleMusicWebOutcome.Complete) return false
        val market = storefront.lowercase(java.util.Locale.ROOT)
        val key = AppleMusicWebCacheContract.key(capability, market, language, path, params) ?: return false
        val maxAge = AppleMusicWebCacheContract.maxAgeMs(capability, path, params) ?: return false
        return access.read(capability, BuiltInProviderKeys.APPLE_MUSIC_WEB, false) {
            val now = clockMs()
            if (now < 0 || now > Long.MAX_VALUE - maxAge) return@read false
            persist(AppleMusicWebCachedValue(key, BuiltInProviderKeys.APPLE_MUSIC_WEB,
                capability, market, language, now, now + maxAge, outcome))
            true
        }
    }
}
