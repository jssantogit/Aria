package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.providers.ArtworkProbePolicyGuard
import com.music.bitchord.data.providers.BuiltInProviderDescriptors
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.LyricsCapabilityDispatcher
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderRequestDeniedException
import com.music.bitchord.data.providers.RateLimitedProviderTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogAdaptersTest {
    @Test fun rateLimitedTransportAbortsQueuedRequestWhenRevokedDuringLimiterWait(): Unit = runBlocking {
        fun policy(itunesEnabled: Boolean) = ProviderCapabilityPolicy(
            descriptors = listOf(
                BuiltInProviderDescriptors.itunes(readiness = ProviderReadiness.READY),
            ),
            choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(
                    ProviderChoice(BuiltInProviderKeys.ITUNES, itunesEnabled, 0),
                ),
            ),
        )
        val gate = ProviderPolicyGate(policy(itunesEnabled = true))
        val transport = RateLimitedProviderTransport { gate }
        val enteredLimiter = CompletableDeferred<Unit>()
        val limiterAborted = CompletableDeferred<Unit>()
        var networkContacted = false

        val call = async {
            try {
                transport.execute(
                    capability = ProviderCapability.CATALOG_SEARCH,
                    provider = BuiltInProviderKeys.ITUNES,
                    scheduleLimited = { block ->
                        enteredLimiter.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            limiterAborted.complete(Unit)
                        }
                        @Suppress("UNREACHABLE_CODE")
                        block()
                    },
                    networkCall = {
                        networkContacted = true
                        "itunes-json"
                    },
                )
                false
            } catch (_: ProviderRequestDeniedException) {
                true
            }
        }

        withTimeout(2_000) { enteredLimiter.await() }
        gate.replace(policy(itunesEnabled = false))
        withTimeout(2_000) { limiterAborted.await() }
        assertTrue(withTimeout(2_000) { call.await() })
        assertFalse(networkContacted)
    }

    @Test fun artworkProbeGuardIsolatesReleaseArtworkFromArtistArtwork(): Unit = runBlocking {
        fun policy(releaseArt: Boolean, artistArt: Boolean) = ProviderCapabilityPolicy(
            descriptors = listOf(
                BuiltInProviderDescriptors.itunes(readiness = ProviderReadiness.READY),
                BuiltInProviderDescriptors.appleArtistArt(readiness = ProviderReadiness.READY),
            ),
            choices = mapOf(
                ProviderCapability.RELEASE_ARTWORK to listOf(
                    ProviderChoice(BuiltInProviderKeys.ITUNES, releaseArt, 0),
                ),
                ProviderCapability.ARTIST_ARTWORK to listOf(
                    ProviderChoice(BuiltInProviderKeys.APPLE_ARTIST_ART, artistArt, 0),
                ),
            ),
        )
        val gate = ProviderPolicyGate(policy(releaseArt = false, artistArt = true))
        val guard = ArtworkProbePolicyGuard { gate }

        var releaseCdnProbed = false
        val releaseUrl = guard.probe(
            capability = ProviderCapability.RELEASE_ARTWORK,
            provider = BuiltInProviderKeys.ITUNES,
            fallback = "https://is1-ssl.mzstatic.com/image/thumb/100x100bb.jpg",
        ) {
            releaseCdnProbed = true
            "https://is1-ssl.mzstatic.com/image/thumb/1024x1024bb.jpg"
        }
        assertFalse(releaseCdnProbed)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/100x100bb.jpg", releaseUrl)

        var artistProbed = false
        val artistHero = guard.probe(
            capability = ProviderCapability.ARTIST_ARTWORK,
            provider = BuiltInProviderKeys.APPLE_ARTIST_ART,
            fallback = null,
        ) {
            artistProbed = true
            "https://is1-ssl.mzstatic.com/artist-hero.jpg"
        }
        assertTrue(artistProbed)
        assertEquals("https://is1-ssl.mzstatic.com/artist-hero.jpg", artistHero)
    }

    @Test fun lyricsDispatcherHonorsPriorityAndFailsClosedWhenAllDisabled(): Unit = runBlocking {
        val descriptors = BuiltInProviderDescriptors.offeredLyrics()
        val lrclib = BuiltInProviderKeys.lyrics(LyricsSource.LRCLIB)
        val lrcRed = BuiltInProviderKeys.lyrics(LyricsSource.LRC_RED)
        val genius = BuiltInProviderKeys.lyrics(LyricsSource.GENIUS)

        val gate = ProviderPolicyGate(
            ProviderCapabilityPolicy(
                descriptors = descriptors,
                choices = mapOf(
                    ProviderCapability.LYRICS_LOOKUP to listOf(
                        ProviderChoice(lrclib, enabled = false, order = 0),
                        ProviderChoice(lrcRed, enabled = true, order = 1),
                        ProviderChoice(genius, enabled = true, order = 2),
                    ),
                ),
            ),
        )
        val visited = mutableListOf<String>()
        val result = LyricsCapabilityDispatcher { gate }.fetchFirst { key ->
            visited += key.providerId
            if (key == lrcRed) "[00:01.00] Hello" else null
        }
        assertEquals("[00:01.00] Hello", result)
        assertEquals(listOf("lrc_red"), visited)

        // Turning off all lyric providers produces null without hidden fallback.
        gate.replace(
            ProviderCapabilityPolicy(
                descriptors = descriptors,
                choices = mapOf(
                    ProviderCapability.LYRICS_LOOKUP to listOf(
                        ProviderChoice(lrclib, enabled = false, order = 0),
                        ProviderChoice(lrcRed, enabled = false, order = 1),
                        ProviderChoice(genius, enabled = false, order = 2),
                    ),
                ),
            ),
        )
        var anyCalled = false
        assertNull(LyricsCapabilityDispatcher { gate }.fetchFirst {
            anyCalled = true
            "should-never-run"
        })
        assertFalse(anyCalled)
    }
}
