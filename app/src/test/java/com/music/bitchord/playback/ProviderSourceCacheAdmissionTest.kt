package com.music.bitchord.playback

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSourceCacheAdmissionTest {
    private val addon = ProviderKey("addon", "one")
    private val sibling = ProviderKey("custom_module", "two")

    private fun gate(addonOn: Boolean, siblingOn: Boolean = true) =
        ProviderPolicyGate(ProviderCapabilityPolicy(
            listOf(addon, sibling).map {
                ProviderDescriptor(it, setOf(ProviderCapability.EXTENSION_PLAYBACK),
                    ProviderReadiness.READY)
            },
            mapOf(ProviderCapability.EXTENSION_PLAYBACK to listOf(
                ProviderChoice(addon, addonOn, 0),
                ProviderChoice(sibling, siblingOn, 1),
            )),
        ))

    @Test fun remoteManifestCannotOpenFileContentOrSmbSegmentsOnThePhone() {
        val denied = listOf(
            "file" to null,
            "content" to "media",
            "smb" to "private-server",
            "ftp" to "remote",
            "data" to null,
            "bitchord" to "canonical",
            "bitchord" to "watch",
            null to null,
        )
        denied.forEach { (scheme, authority) ->
            try {
                requireAdaptiveSourceResourceScheme(scheme, authority)
                org.junit.Assert.fail("Unsafe adaptive segment scheme passed: $scheme")
            } catch (_: java.io.IOException) {
                // A manifest is untrusted input; it cannot gain device access.
            }
        }
    }

    @Test fun logicalSourceManifestAndHttpSegmentsRemainAvailable() {
        requireAdaptiveSourceResourceScheme("bitchord", "source")
        requireAdaptiveSourceResourceScheme("https", "cdn.example")
        requireAdaptiveSourceResourceScheme("http", "cdn.example")
    }

    @Test fun adaptiveSegmentsUseProviderScopedHashedKeysInsteadOfSharedVideoQuery() {
        val urlA = "https://cdn.example/track.ts?v=same&token=private-A"
        val urlB = "https://cdn.example/other.ts?v=same&token=private-B"
        val keyA = providerAdaptiveSegmentCacheKey(addon, urlA)
        val keyB = providerAdaptiveSegmentCacheKey(addon, urlB)
        val siblingKey = providerAdaptiveSegmentCacheKey(sibling, urlA)
        assertTrue(keyA.startsWith("source-segment:"))
        assertTrue(keyA != keyB)
        assertTrue(keyA != siblingKey)
        assertTrue(!keyA.contains("private-A"))
        assertTrue(!keyA.contains(urlA))
        assertTrue(keyA == providerAdaptiveSegmentCacheKey(addon, urlA))
        assertTrue(keyA != providerAdaptiveSegmentCacheKey(addon, urlA, "variant"))
        // A provider may use the same static CDN URL for two accounts, with
        // identity carried only by Authorization/Referer headers. Never return
        // the previous account's cached audio after credential rotation.
        val ownerOne = providerAdaptiveSegmentCacheKey(
            addon, urlA, null, mapOf("Authorization" to "Bearer account-one"))
        val ownerTwo = providerAdaptiveSegmentCacheKey(
            addon, urlA, null, mapOf("Authorization" to "Bearer account-two"))
        assertTrue(ownerOne != ownerTwo)
        assertTrue(ownerOne != keyA)
        assertTrue(!ownerOne.contains("account-one"))
        assertTrue(ownerOne == providerAdaptiveSegmentCacheKey(
            addon, urlA, null, mapOf("authorization" to "Bearer account-one")))
    }

    @Test fun adaptiveSegmentCacheHitRequiresCurrentMediaItemLease() {
        val gate = gate(addonOn = true, siblingOn = true)
        val addonTicket = requireNotNull(gate.open(
            ProviderCapability.EXTENSION_PLAYBACK, addon))
        val siblingTicket = requireNotNull(gate.open(
            ProviderCapability.EXTENSION_PLAYBACK, sibling))
        val addonLease = ProviderPlaybackByteLease(gate, addonTicket)
        val siblingLease = ProviderPlaybackByteLease(gate, siblingTicket)

        assertTrue(admitAdaptiveSegmentCacheRead(addonLease) === addonLease)
        try {
            admitAdaptiveSegmentCacheRead(null)
            org.junit.Assert.fail("An unknown segment must not read cached HTTP bytes")
        } catch (_: java.io.IOException) {}

        gate.replace(ProviderCapabilityPolicy(
            listOf(addon, sibling).map {
                ProviderDescriptor(it, setOf(ProviderCapability.EXTENSION_PLAYBACK),
                    ProviderReadiness.READY)
            },
            mapOf(ProviderCapability.EXTENSION_PLAYBACK to listOf(
                ProviderChoice(addon, false, 0),
                ProviderChoice(sibling, true, 1),
            )),
        ))
        try {
            admitAdaptiveSegmentCacheRead(addonLease)
            org.junit.Assert.fail("Cached segment bypassed disabled addon")
        } catch (_: java.io.IOException) {}
        assertTrue(admitAdaptiveSegmentCacheRead(siblingLease) === siblingLease)
    }

    @Test fun fullyCachedBytesNeedAnExplicitEnabledWinningProvider() {
        val gate = gate(addonOn = true)
        assertNotNull(sourceCacheReadLease("addon", "one", true, gate))
        assertNull(sourceCacheReadLease("addon", "one", false, gate))
        assertNull(sourceCacheReadLease(null, "one", true, gate))
        assertNull(sourceCacheReadLease("addon", null, true, gate))
        assertNull(sourceCacheReadLease("musicbrainz", "one", true, gate))
        assertNull(sourceCacheReadLease("addon", "one", true, null))
    }

    @Test fun disabledWinningProviderCannotReplayCachedBytesEvenWhenSiblingEnabled() {
        val gate = gate(addonOn = false, siblingOn = true)
        assertNull(sourceCacheReadLease("addon", "one", true, gate))
        val siblingLease = sourceCacheReadLease("custom_module", "two", true, gate)
        assertNotNull(siblingLease)
        assertTrue(requireNotNull(siblingLease).allowed())
        gate.replace(ProviderCapabilityPolicy(
            listOf(addon, sibling).map {
                ProviderDescriptor(it, setOf(ProviderCapability.EXTENSION_PLAYBACK),
                    ProviderReadiness.READY)
            },
            mapOf(ProviderCapability.EXTENSION_PLAYBACK to listOf(
                ProviderChoice(addon, false, 0),
                ProviderChoice(sibling, false, 1),
            )),
        ))
        assertFalse(siblingLease.allowed())
    }

    @Test fun malformedOrForgedPersistedProviderIdentityFailsClosed() {
        val gate = gate(addonOn = true)
        assertNull(sourceCacheReadLease("addon", "", true, gate))
        assertNull(sourceCacheReadLease("addon", "missing", true, gate))
        assertNull(sourceCacheReadLease("custom_module", "one", true, gate))
    }
}
