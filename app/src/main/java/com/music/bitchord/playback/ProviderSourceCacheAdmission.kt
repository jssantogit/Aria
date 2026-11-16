package com.music.bitchord.playback

import com.music.bitchord.data.providers.ProviderCapability
import java.io.IOException
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate

/**
 * Cache hits skip both SourceResolver and the provider HTTP byte DataSource.
 * Never serve source-owned cached spans without the exact winning provider's
 * current EXTENSION_PLAYBACK authorization. Unknown/old provenance and partial
 * cache entries are unsafe to reuse and must be discarded before resolving.
 *
 * Returns an in-memory revocable lease, not a persisted grant.
 */
internal fun sourceCacheReadLease(
    providerId: String?,
    instanceId: String?,
    completelyCached: Boolean,
    gate: ProviderPolicyGate?,
): ProviderPlaybackByteLease? {
    if (!completelyCached || gate == null) return null
    if (providerId != "addon" && providerId != "custom_module") return null
    if (instanceId.isNullOrBlank() || instanceId.length > 128) return null
    val ticket = gate.open(
        ProviderCapability.EXTENSION_PLAYBACK,
        ProviderKey(providerId, instanceId),
    ) ?: return null
    return ProviderPlaybackByteLease(gate, ticket)
}

/**
 * Media3 bypasses the HTTP datasource entirely on an HLS/DASH segment cache
 * hit. The per-media-item ticket is mandatory even when every byte is local.
 * One provider's revocation must not affect an authorized sibling session.
 */
internal fun admitAdaptiveSegmentCacheRead(
    lease: ProviderPlaybackByteLease?,
): ProviderPlaybackByteLease =
    lease?.takeIf(ProviderPlaybackByteLease::allowed)
        ?: throw IOException("Adaptive segment cache requires current provider authorization")
