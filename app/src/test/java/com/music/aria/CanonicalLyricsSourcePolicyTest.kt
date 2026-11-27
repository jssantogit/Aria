package com.music.aria

import com.music.aria.data.catalog.allowedCanonicalLyricsSources
import com.music.aria.data.lyrics.LyricsSource
import com.music.aria.data.sources.SourceConfig
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderChoice
import com.music.aria.data.providers.ProviderSessionSnapshot
import com.music.aria.data.providers.ProviderSessionStatus
import com.music.aria.data.sources.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalLyricsSourcePolicyTest {
    private val all = setOf(
        LyricsSource.YOUTUBE_MUSIC,
        LyricsSource.YOUTUBE_TRANSCRIPT,
        LyricsSource.LRCLIB,
        LyricsSource.LRC_RED,
    )
    private val enabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = true))
    private val disabled = listOf(SourceConfig(kind = SourceKind.YOUTUBE, enabled = false))
    private val nonYoutube = setOf(LyricsSource.LRCLIB, LyricsSource.LRC_RED)

    @Test fun explicitOptOutExcludesBothDirectYoutubeLyricsProviders() {
        assertEquals(nonYoutube, allowedCanonicalLyricsSources(all, false, disabled, snapshot = null))
    }

    @Test fun canonicalDiscoveryOptInExcludesYoutubeEvenIfAudioSourceRemainsEnabled() {
        assertEquals(nonYoutube, allowedCanonicalLyricsSources(all, true, enabled, snapshot = null))
    }

    @Test fun historicalPreferencesCannotReenableRetiredProviders() {
        assertEquals(nonYoutube, allowedCanonicalLyricsSources(all, false, enabled, snapshot = null))
        assertEquals(nonYoutube, allowedCanonicalLyricsSources(all, false, emptyList(), snapshot = null))
    }

    @Test fun nonYoutubeProvidersAndExplicitEmptySetRemainUnchanged() {
        assertEquals(nonYoutube, allowedCanonicalLyricsSources(nonYoutube, true, disabled, snapshot = null))
        assertEquals(emptySet<LyricsSource>(), allowedCanonicalLyricsSources(emptySet(), true, disabled, snapshot = null))
    }
    @Test fun canonicalReadyPolicyHonorsEnabledLyricsAndExplicitLastSourceOptOut() {
        val lrclib = BuiltInProviderKeys.lyrics(LyricsSource.LRCLIB)
        val lrcRed = BuiltInProviderKeys.lyrics(LyricsSource.LRC_RED)
        fun snapshot(allowed: List<com.music.aria.data.providers.ProviderKey>) =
            ProviderSessionSnapshot(
                status = ProviderSessionStatus.READY,
                configured = mapOf(ProviderCapability.LYRICS_LOOKUP to listOf(
                    ProviderChoice(lrclib, true, 0),
                    ProviderChoice(lrcRed, lrcRed in allowed, 1),
                )),
                eligible = mapOf(ProviderCapability.LYRICS_LOOKUP to allowed),
            )
        assertEquals(setOf(LyricsSource.LRCLIB), allowedCanonicalLyricsSources(
            nonYoutube, true, emptyList(), snapshot(listOf(lrclib)),
        ))
        assertEquals(emptySet<LyricsSource>(), allowedCanonicalLyricsSources(
            nonYoutube, true, emptyList(), snapshot(emptyList()),
        ))
    }

    @Test fun canonicalStorageFailureAndInstanceQuarantineFailClosed() {
        val lrclib = BuiltInProviderKeys.lyrics(LyricsSource.LRCLIB)
        val selected = mapOf(
            ProviderCapability.LYRICS_LOOKUP to listOf(ProviderChoice(lrclib, true, 0)),
        )
        assertEquals(emptySet<LyricsSource>(), allowedCanonicalLyricsSources(
            nonYoutube, true, emptyList(),
            ProviderSessionSnapshot(status = ProviderSessionStatus.STORAGE_ERROR),
        ))
        assertEquals(emptySet<LyricsSource>(), allowedCanonicalLyricsSources(
            nonYoutube, true, emptyList(),
            ProviderSessionSnapshot(
                status = ProviderSessionStatus.PERSISTING,
                configured = selected,
                eligible = mapOf(ProviderCapability.LYRICS_LOOKUP to listOf(lrclib)),
                quarantinedProviders = setOf(ProviderCapability.LYRICS_LOOKUP to lrclib),
            ),
        ))
    }

    @Test fun readySessionMissingLyricsChoicesMustNotReenableLegacyProviders() {
        assertEquals(emptySet<LyricsSource>(), allowedCanonicalLyricsSources(
            nonYoutube, true, emptyList(),
            ProviderSessionSnapshot(status = ProviderSessionStatus.READY),
        ))
    }

}
