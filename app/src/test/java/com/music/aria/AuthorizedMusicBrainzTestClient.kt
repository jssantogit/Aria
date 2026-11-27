package com.music.aria

import com.music.aria.data.catalog.metadata.MusicBrainzClient
import com.music.aria.data.catalog.metadata.MusicBrainzRateLimiter
import com.music.aria.data.catalog.metadata.MusicBrainzRequestBudget
import com.music.aria.data.catalog.metadata.MusicBrainzTransport
import com.music.aria.data.catalog.metadata.defaultMusicBrainzUserAgent
import com.music.aria.data.providers.ProviderCachedReadAccess
import com.music.aria.data.providers.ProviderCapability
import kotlinx.coroutines.delay

/**
 * Explicit test-only MusicBrainz policy for mocked network or bounded XML
 * transports under Robolectric. Production's missing-policy fail-closed
 * behavior remains unchanged, and gate/admission tests use their own policies.
 */
private val musicBrainzFixtureGate by lazy {
    ProviderCachedReadAccess.permissiveForUnitTests(
        capabilities = setOf(
            ProviderCapability.IDENTITY,
            ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
        ),
    ).currentGateOrNull()
}

internal fun authorizedMusicBrainzClient(
    transport: MusicBrainzTransport,
    rateLimiter: MusicBrainzRateLimiter = MusicBrainzRequestBudget.limiter,
    userAgent: String = defaultMusicBrainzUserAgent(),
    retryDelayMillis: suspend (Long) -> Unit = { delay(it) },
): MusicBrainzClient = MusicBrainzClient(
    transport = transport,
    rateLimiter = rateLimiter,
    userAgent = userAgent,
    retryDelayMillis = retryDelayMillis,
    admission = { musicBrainzFixtureGate },
)
