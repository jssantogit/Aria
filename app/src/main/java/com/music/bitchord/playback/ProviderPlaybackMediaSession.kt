package com.music.bitchord.playback

import java.io.IOException

/**
 * One Media3 media item may generate a manifest request plus hundreds of
 * independent HLS/DASH segment DataSpecs. Media3 does not copy the initial
 * customData into each segment; all of them must inherit the exact provider
 * authority of their *own* media item, not a process-global latest stream.
 *
 * This session exists only as long as the enclosing MediaSource's factory.
 * Active segment DataSources subscribe individually to the ticket's revocation
 * and physically cancel their own OkHttp Call. No global URL map is involved.
 */
internal class ProviderPlaybackMediaSession {
    @Volatile private var lease: ProviderPlaybackByteLease? = null

    @Synchronized fun bind(incoming: ProviderPlaybackByteLease) {
        if (!incoming.allowed())
            throw IOException("Resolved media provider no longer authorized")
        val previous = lease
        if (previous != null && previous.providerKey != incoming.providerKey)
            throw IOException("Adaptive media item cannot switch provider instances mid-stream")
        lease = incoming
    }

    fun currentLease(): ProviderPlaybackByteLease? = lease
}
