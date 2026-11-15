package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.legacyProviderChoices
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyProviderChoicesTest {
    @Test fun lyricsSelectionsKeepExistingPriorityAndEveryExplicitOptOut() {
        val choices = legacyProviderChoices(
            lyricsOrder = listOf(LyricsSource.LRCLIB, LyricsSource.LRC_RED, LyricsSource.GENIUS),
            enabledLyrics = setOf(LyricsSource.LRC_RED),
            sources = emptyList(),
        )
        val rows = choices.getValue(ProviderCapability.LYRICS_LOOKUP)
        assertEquals(listOf("lrclib", "lrc_red", "genius"),
            rows.take(3).map { it.key.providerId })
        assertEquals(listOf(false, true, false), rows.take(3).map { it.enabled })
        assertEquals(listOf(0, 1, 2), rows.take(3).map { it.order })
        assertTrue(rows.none { it.key.providerId == "simp_music" })
        assertFalse(choices.containsKey(ProviderCapability.AUDIO_PLAYBACK))
    }

    @Test fun allLyricsOffRemainsAllOffEvenWithTheLastProvider() {
        val choices = legacyProviderChoices(
            lyricsOrder = LyricsSource.offered,
            enabledLyrics = emptySet(),
            sources = emptyList(),
        )
        assertTrue(choices.getValue(ProviderCapability.LYRICS_LOOKUP).isNotEmpty())
        assertTrue(choices.getValue(ProviderCapability.LYRICS_LOOKUP).none { it.enabled })
    }

    @Test fun extensionInstancesStayDistinctAndRetiredSourcesDoNotReturn() {
        val choices = legacyProviderChoices(
            lyricsOrder = emptyList(),
            enabledLyrics = emptySet(),
            sources = listOf(
                SourceConfig(id = "a", kind = SourceKind.ADDON, baseUrl = "https://private.example/index",
                    enabled = false, allowDownloads = true),
                SourceConfig(id = "b", kind = SourceKind.ADDON, baseUrl = "https://other.example/index",
                    enabled = true, allowDownloads = false),
                SourceConfig(id = "c", kind = SourceKind.CUSTOM_MODULE, baseUrl = "https://third.example/index",
                    enabled = true),
                SourceConfig(id = "retired", kind = SourceKind.JIOSAAVN, enabled = true),
                SourceConfig(id = "youtube", kind = SourceKind.YOUTUBE, enabled = true),
            ),
        )
        val rows = choices.getValue(ProviderCapability.EXTENSION_PLAYBACK)
        assertEquals(listOf(ProviderKey("addon", "a"), ProviderKey("addon", "b"),
            ProviderKey("custom_module", "c")), rows.map { it.key })
        assertEquals(listOf(false, true, true), rows.map { it.enabled })
        assertEquals(listOf(0, 1, 2), rows.map { it.order })
        assertFalse(choices.containsKey(ProviderCapability.AUDIO_PLAYBACK))
        assertFalse(choices.containsKey(ProviderCapability.DOWNLOAD))
        assertFalse(rows.toString().contains("private.example"))
    }

    @Test fun absentUserExtensionsAreNotMisrepresentedAsExplicitAudioOptOut() {
        val choices = legacyProviderChoices(
            lyricsOrder = emptyList(),
            enabledLyrics = emptySet(),
            sources = listOf(SourceConfig(kind = SourceKind.YOUTUBE)),
        )
        assertFalse(choices.containsKey(ProviderCapability.EXTENSION_PLAYBACK))
        assertTrue(choices.getValue(ProviderCapability.LYRICS_LOOKUP).none { it.enabled })
    }
}
