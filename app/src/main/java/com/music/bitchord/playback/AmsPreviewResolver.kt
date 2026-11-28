package com.music.bitchord.playback

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import java.io.IOException
import java.net.URI

/** A source-owned sample identity, never a canonical Recording or MediaAsset. */
internal data class AmsPreviewRoute private constructor(val storefront: String, val id: String) {
    val scopedId: String get() = "apple_music_web:$storefront:song:$id"
    val uri: String get() = "bitchord://preview/$storefront/$id"

    companion object {
        private val key = Regex("apple_music_web:([a-z]{2}):song:([1-9][0-9]{0,11})")
        private val logical = Regex("bitchord://preview/([a-z]{2})/([1-9][0-9]{0,11})")
        fun fromScopedId(value: String?): AmsPreviewRoute? = value?.let(key::matchEntire)
            ?.let { AmsPreviewRoute(it.groupValues[1],it.groupValues[2]) }
        fun decode(value: String?): AmsPreviewRoute? = value?.let(logical::matchEntire)
            ?.let { AmsPreviewRoute(it.groupValues[1],it.groupValues[2]) }
        /** A marked sample can never fall through to local/full-stream routing. */
        fun forQueue(scopedId: String): String =
            fromScopedId(scopedId)?.uri ?: "bitchord://preview/invalid"
        fun owns(value: String?): Boolean {
            val uri = value?.let { runCatching { URI(it) }.getOrNull() } ?: return false
            return uri.scheme.equals("bitchord",ignoreCase=true) && uri.authority == "preview"
        }
    }
}

internal data class AmsPreviewSelection(val url: String, val lease: ProviderPlaybackByteLease)

/** Re-read the exact sample on open; retain a revocable audio and editorial
 * ticket for the byte transport. No URL from UI, queue or persisted metadata
 * is trusted. Neither capability is inferred from the other. */
internal class AmsPreviewResolver(
    private val currentGate: () -> ProviderPolicyGate? = { ProviderRuntime.gateOrNull() },
    private val createGateway: (ProviderPolicyGate) -> AppleMusicWebEditorialGateway = { gate ->
        AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
            admission = RateLimitedProviderTransport { ProviderRuntime.gateOrNull() },
        )),gate)
    },
) {
    private val provider = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private var owner: ProviderPolicyGate? = null
    private var reader: AppleMusicWebEditorialGateway? = null

    @Synchronized private fun gateway(gate: ProviderPolicyGate): AppleMusicWebEditorialGateway {
        if (owner !== gate || reader == null) {
            reader = createGateway(gate)
            owner = gate
        }
        return requireNotNull(reader)
    }

    suspend fun resolve(route: AmsPreviewRoute): AmsPreviewSelection {
        val gate = currentGate() ?: throw IOException("Preview policy unavailable")
        val audio = gate.open(ProviderCapability.AUDIO_PLAYBACK,provider)
            ?: throw IOException("Preview audio capability denied")
        val editorial = gate.open(ProviderCapability.EDITORIAL_DISCOGRAPHY,provider)
            ?: throw IOException("Preview lookup capability denied")
        val lease = ProviderPlaybackByteLease(gate,audio,listOf(editorial))
        if (currentGate() !== gate || !lease.allowed())
            throw IOException("Preview permission revoked")
        val result = gateway(gate).songPreview(route.scopedId)
        val value = (result as? ProviderCallResult.Success)?.value
            ?: throw IOException("Preview lookup unavailable")
        if (currentGate() !== gate || !lease.allowed() || value.provider != provider ||
            value.capability != ProviderCapability.EDITORIAL_DISCOGRAPHY ||
            value.storefront != route.storefront)
            throw IOException("Preview authorization or provenance changed")
        val item = (value.outcome as? AppleMusicWebOutcome.Complete)?.value
            ?: throw IOException("No verified audio preview")
        val url = appleMusicWebAudioPreviewUrl(item.previewUrl)
            ?.takeIf { item.scopedId == route.scopedId && item.kind == AppleMusicWebKind.SONG }
            ?: throw IOException("Invalid preview evidence")
        return AmsPreviewSelection(url,lease)
    }
}
