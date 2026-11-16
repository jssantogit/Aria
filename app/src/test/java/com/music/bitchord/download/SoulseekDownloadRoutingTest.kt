package com.music.bitchord.download

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import org.junit.Assert.*
import org.junit.Test

/** Issue #172: every acquisition intent must be explicitly manual, no YT fallback. */
class SoulseekDownloadRoutingTest {
    private val key = ProviderKey("soulseek")
    private fun gate(enabled: Boolean) = ProviderPolicyGate(ProviderCapabilityPolicy(
        descriptors = listOf(ProviderDescriptor(key, setOf(ProviderCapability.DOWNLOAD), ProviderReadiness.READY)),
        choices = mapOf(ProviderCapability.DOWNLOAD to listOf(ProviderChoice(key, enabled, 0))),
    ))

    @Test fun unauthorizedPilotNeverOffersManualSearch() {
        assertEquals(SoulseekDownloadRouting.Decision.Unavailable,
            SoulseekDownloadRouting.route("Artist", "Track", 1, true, gate(false)))
        assertEquals(SoulseekDownloadRouting.Decision.Unavailable,
            SoulseekDownloadRouting.route("Artist", "Track", 1, true, null))
        assertEquals(SoulseekDownloadRouting.Decision.ManualSearch("Artist - Track"),
            SoulseekDownloadRouting.route("Artist", "Track", 1, true, gate(true)))
    }
    @Test fun oneTrackOpensManualSoulseekSearch() {
        assertEquals(
            SoulseekDownloadRouting.Decision.ManualSearch("NewJeans - OMG"),
            SoulseekDownloadRouting.route("NewJeans", "OMG", 1, true, gate(true))
        )
    }

    @Test fun releaseAndUnavailablePilotFailClosed() {
        assertEquals(
            SoulseekDownloadRouting.Decision.Unavailable,
            SoulseekDownloadRouting.route("Artist", "Track", 1, false, gate(true))
        )
    }

    @Test fun albumAndPlaylistNeverStartBulkDownloads() {
        for (count in listOf(0, 2, 40)) {
            assertEquals(
                SoulseekDownloadRouting.Decision.BulkNotSupported,
                SoulseekDownloadRouting.route("Artist", "Track", count, true, gate(true))
            )
        }
    }

    @Test fun malformedOrEmptyIdentityDoesNotReachP2P() {
        assertEquals(
            SoulseekDownloadRouting.Decision.Unavailable,
            SoulseekDownloadRouting.route("", "", 1, true, gate(true))
        )
        assertEquals(
            SoulseekDownloadRouting.Decision.Unavailable,
            SoulseekDownloadRouting.route("Artist", "\u0000", 1, true, gate(true))
        )
    }

    @Test fun queryIsSanitizedAndBounded() {
        val query = SoulseekDownloadRouting.route(
            "A".repeat(400), "Song", 1, true, gate(true)
        ) as SoulseekDownloadRouting.Decision.ManualSearch
        assertTrue(query.query.length <= 120)
        assertTrue(query.query.contains("Song"))
        assertEquals(
            SoulseekDownloadRouting.Decision.ManualSearch("Artist - Track"),
            SoulseekDownloadRouting.route("Artist\n", "Track", 1, true, gate(true))
        )
    }
}
