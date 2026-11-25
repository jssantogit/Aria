package com.music.bitchord.data

import com.music.bitchord.data.discord.DiscordApplication
import com.music.bitchord.data.discord.DiscordRPC
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
