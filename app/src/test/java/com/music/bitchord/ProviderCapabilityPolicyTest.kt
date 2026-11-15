package com.music.bitchord

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderOperationalHealth
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCapabilityPolicyTest {
    private val apple = ProviderKey("itunes")
    private val musicBrainz = ProviderKey("musicbrainz")
    private val nasA = ProviderKey("opensubsonic", "nas-a")
    private val nasB = ProviderKey("opensubsonic", "nas-b")

    private fun ready(
        key: ProviderKey,
        capabilities: Set<ProviderCapability>,
    ): ProviderDescriptor = ProviderDescriptor(key, capabilities,
        readiness = ProviderReadiness.READY)

    @Test fun unimplementedDescriptorsAreExcludedByDefault() {
        val notImplemented = ProviderDescriptor(apple, setOf(ProviderCapability.CATALOG_SEARCH))
        val policy = ProviderCapabilityPolicy(listOf(notImplemented), mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(apple, true, 0)),
        ))
        assertTrue(policy.eligible(ProviderCapability.CATALOG_SEARCH).isEmpty())
    }

    @Test fun everyProviderIncludingTheLastCanBeDisabledWithoutFallback() {
        val policy = ProviderCapabilityPolicy(
            descriptors = listOf(
                ready(apple, setOf(ProviderCapability.CATALOG_SEARCH)),
                ready(musicBrainz, setOf(ProviderCapability.CATALOG_SEARCH)),
            ),
            choices = mapOf(ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(apple, enabled = false, order = 0),
                ProviderChoice(musicBrainz, enabled = false, order = 1),
            )),
        )
        assertTrue(policy.eligible(ProviderCapability.CATALOG_SEARCH).isEmpty())
        assertTrue(policy.eligible(ProviderCapability.RELEASE_ARTWORK).isEmpty())
    }

    @Test fun enablingArtworkDoesNotAutomaticallyEnableCatalogOfTheSameProvider() {
        val policy = ProviderCapabilityPolicy(
            descriptors = listOf(ready(apple, setOf(
                ProviderCapability.CATALOG_SEARCH, ProviderCapability.RELEASE_ARTWORK,
            ))),
            choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(apple, false, 0)),
                ProviderChoice.RELEASE_ARTWORK to listOf(ProviderChoice(apple, true, 0)),
            ),
        )
        assertTrue(policy.eligible(ProviderCapability.CATALOG_SEARCH).isEmpty())
        assertEquals(listOf(apple), policy.eligible(ProviderCapability.RELEASE_ARTWORK).map { it.key })
    }

    @Test fun preservesPriorityAndIndependenceOfConfiguredServerInstances() {
        val policy = ProviderCapabilityPolicy(
            descriptors = listOf(
                ready(nasA, setOf(ProviderCapability.AUDIO_PLAYBACK)),
                ready(nasB, setOf(ProviderCapability.AUDIO_PLAYBACK)),
            ),
            choices = mapOf(ProviderCapability.AUDIO_PLAYBACK to listOf(
                ProviderChoice(nasA, true, order = 20),
                ProviderChoice(nasB, true, order = 10),
            )),
        )
        assertEquals(listOf(nasB, nasA),
            policy.eligible(ProviderCapability.AUDIO_PLAYBACK).map { it.key })
    }

    @Test fun unavailableUnknownOrUnsupportedSourceIsNeverInventedAsFallback() {
        val absent = ProviderKey("not-installed")
        val policy = ProviderCapabilityPolicy(
            descriptors = listOf(
                ProviderDescriptor(apple, setOf(ProviderCapability.CATALOG_SEARCH),
                    readiness = ProviderReadiness.NEEDS_CONFIGURATION),
                ready(musicBrainz, setOf(ProviderCapability.IDENTITY)),
            ),
            choices = mapOf(ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(apple, true, 0),
                ProviderChoice(absent, true, 1),
                ProviderChoice(musicBrainz, true, 2),
            )),
        )
        assertTrue(policy.eligible(ProviderCapability.CATALOG_SEARCH).isEmpty())
    }

    @Test fun switchingOffProviderRejectsResultsFromInflightRequests() {
        val descriptor = ready(apple, setOf(ProviderCapability.RELEASE_ARTWORK))
        fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
            descriptors = listOf(descriptor),
            choices = mapOf(ProviderCapability.RELEASE_ARTWORK to listOf(
                ProviderChoice(apple, enabled, 0),
            )),
        )
        val gate = ProviderPolicyGate(policy(true))
        val ticket = gate.open(ProviderCapability.RELEASE_ARTWORK, apple)
        assertNotNull(ticket)
        assertTrue(gate.accept(ticket!!))

        gate.replace(policy(false))
        assertFalse(gate.accept(ticket))
        assertNull(gate.open(ProviderCapability.RELEASE_ARTWORK, apple))
        assertTrue(gate.ordered(ProviderCapability.RELEASE_ARTWORK).isEmpty())
    }

    @Test fun changingArtworkPolicyDoesNotCancelUnrelatedAudioRequests() {
        val descriptor = ready(apple, setOf(
            ProviderCapability.RELEASE_ARTWORK, ProviderCapability.AUDIO_PLAYBACK,
        ))
        fun policy(artwork: Boolean) = ProviderCapabilityPolicy(
            descriptors = listOf(descriptor),
            choices = mapOf(
                ProviderCapability.RELEASE_ARTWORK to listOf(ProviderChoice(apple, artwork, 0)),
                ProviderCapability.AUDIO_PLAYBACK to listOf(ProviderChoice(apple, true, 0)),
            ),
        )
        val gate = ProviderPolicyGate(policy(true))
        val audio = gate.open(ProviderCapability.AUDIO_PLAYBACK, apple)
        val artwork = gate.open(ProviderCapability.RELEASE_ARTWORK, apple)
        assertNotNull(audio)
        assertNotNull(artwork)

        gate.replace(policy(false))
        assertTrue(gate.accept(audio!!))
        assertFalse(gate.accept(artwork!!))
    }

    @Test fun distinguishesImplementationUserAuthorizationAndOperationalHealth() {
        val policy = ProviderCapabilityPolicy(
            descriptors = listOf(
                ready(musicBrainz, setOf(ProviderCapability.CATALOG_SEARCH)),
                ready(nasA, setOf(ProviderCapability.AUDIO_PLAYBACK)),
                ProviderDescriptor(
                    ProviderKey("discogs"),
                    setOf(ProviderCapability.EDITION_METADATA),
                    readiness = ProviderReadiness.NEEDS_CONFIGURATION,
                ),
            ),
            choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(musicBrainz, true, 0)),
                ProviderCapability.AUDIO_PLAYBACK to listOf(ProviderChoice(nasA, true, 0)),
                ProviderCapability.EDITION_METADATA to listOf(
                    ProviderChoice(ProviderKey("discogs"), true, 0),
                ),
            ),
            health = mapOf(
                musicBrainz to ProviderOperationalHealth.UNKNOWN,
                nasA to ProviderOperationalHealth.UNAVAILABLE,
            ),
        )

        val mbStatus = policy.status(ProviderCapability.CATALOG_SEARCH, musicBrainz)
        assertTrue(mbStatus.implemented)
        assertTrue(mbStatus.prerequisitesMet)
        assertTrue(mbStatus.userAuthorized)
        assertEquals(ProviderOperationalHealth.UNKNOWN, mbStatus.operationalHealth)
        assertTrue(mbStatus.effectiveEligible)

        val nasStatus = policy.status(ProviderCapability.AUDIO_PLAYBACK, nasA)
        assertTrue(nasStatus.implemented)
        assertTrue(nasStatus.prerequisitesMet)
        assertTrue(nasStatus.userAuthorized)
        assertEquals(ProviderOperationalHealth.UNAVAILABLE, nasStatus.operationalHealth)
        assertFalse(nasStatus.effectiveEligible)
        assertTrue(policy.eligible(ProviderCapability.AUDIO_PLAYBACK).isEmpty())

        val discogsStatus = policy.status(
            ProviderCapability.EDITION_METADATA, ProviderKey("discogs"),
        )
        assertTrue(discogsStatus.implemented)
        assertFalse(discogsStatus.prerequisitesMet)
        assertTrue(discogsStatus.userAuthorized)
        assertFalse(discogsStatus.effectiveEligible)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateProviderInstanceRegistrationIsRejected() {
        ProviderCapabilityPolicy(
            descriptors = listOf(
                ready(nasA, setOf(ProviderCapability.AUDIO_PLAYBACK)),
                ready(nasA, setOf(ProviderCapability.AUDIO_PLAYBACK)),
            ),
        )
    }
}
