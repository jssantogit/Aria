package com.music.bitchord

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.ui.screens.functionSettingsText
import com.music.bitchord.ui.screens.functionSettingsWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCapabilitySettingsPresentationTest {
    @Test fun everyEngineCapabilityHasReadableTitleAndExplanation() {
        val all = ProviderCapability.entries.map { it.functionSettingsText() }
        assertEquals(ProviderCapability.entries.size, all.size)
        assertTrue(all.all { it.title.isNotBlank() && it.description.isNotBlank() })
        assertTrue(all.none { it.title.contains("_") || it.description.contains("_") })
        assertEquals("Busca", ProviderCapability.CATALOG_SEARCH.functionSettingsText().title)
        assertEquals("Discografia",
            ProviderCapability.EDITORIAL_DISCOGRAPHY.functionSettingsText().title)
        assertEquals("Identidade e ISRC",
            ProviderCapability.IDENTITY.functionSettingsText().title)
        assertEquals(ProviderCapability.entries.size, all.map { it.title }.distinct().size)
    }

    @Test fun amsAudioDescribesOnlyPreviewsWithoutRelabelingFullPlayback() {
        val preview = com.music.bitchord.data.providers.ProviderCapabilityRegistry.inventory()
            .single { it.key == com.music.bitchord.data.providers.BuiltInProviderKeys.APPLE_MUSIC_WEB &&
                it.capability == ProviderCapability.AUDIO_PLAYBACK }
        assertEquals("Prévias de 30 s",preview.functionSettingsText().title)
        assertEquals("Reprodução",ProviderCapability.AUDIO_PLAYBACK.functionSettingsText().title)
    }

    @Test fun unavailableFunctionsNeverMasqueradeAsConfigurableReadyControls() {
        assertNull(ProviderReadiness.READY.functionSettingsWarning())
        assertEquals("Configuração necessária",
            ProviderReadiness.NEEDS_CONFIGURATION.functionSettingsWarning())
        assertEquals("Ainda não implementado",
            ProviderReadiness.NOT_IMPLEMENTED.functionSettingsWarning())
        assertEquals("Bloqueado", ProviderReadiness.BLOCKED.functionSettingsWarning())
    }
}
