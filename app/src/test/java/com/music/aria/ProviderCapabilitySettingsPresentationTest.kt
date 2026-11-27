package com.music.aria

import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderReadiness
import com.music.aria.ui.screens.functionSettingsText
import com.music.aria.ui.screens.functionSettingsWarning
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

    @Test fun unavailableFunctionsNeverMasqueradeAsConfigurableReadyControls() {
        assertNull(ProviderReadiness.READY.functionSettingsWarning())
        assertEquals("Configuração necessária",
            ProviderReadiness.NEEDS_CONFIGURATION.functionSettingsWarning())
        assertEquals("Ainda não implementado",
            ProviderReadiness.NOT_IMPLEMENTED.functionSettingsWarning())
        assertEquals("Bloqueado", ProviderReadiness.BLOCKED.functionSettingsWarning())
    }
}
