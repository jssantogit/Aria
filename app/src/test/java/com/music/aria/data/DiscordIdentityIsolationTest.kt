package com.music.aria.data

import com.music.aria.data.discord.DiscordApplication
import com.music.aria.data.discord.DiscordRPC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiscordIdentityIsolationTest {
    @Test fun inheritedIdentityIsNotConfigured() {
        assertFalse(DiscordApplication.isConfigured)
        assertEquals("", DiscordApplication.ID)
        assertEquals("https://github.com/jssantogit/Aria", DiscordRPC.PROJECT_URL)
    }
}
