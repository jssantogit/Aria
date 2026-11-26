package com.music.aria

import com.music.aria.data.listentogether.localPartyIdentity
import org.junit.Assert.*
import org.junit.Test

class LocalPartyIdentityTest {
    @Test fun identityIsStableAcrossNameChangesWithoutAccountData() {
        val first = localPartyIdentity("installation-a", " First ")!!
        val second = localPartyIdentity("installation-a", "Second")!!
        assertEquals(first.userId, second.userId)
        assertEquals("installation-a", first.deviceId)
        assertEquals("First", first.name)
        assertNull(first.avatar)
        assertTrue(first.userId.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(first.userId, localPartyIdentity("installation-b", "First")!!.userId)
    }

    @Test fun blankIdentityIsRejectedAndNamesAreBounded() {
        assertNull(localPartyIdentity("installation-a", "  "))
        assertNull(localPartyIdentity("", "First"))
        assertEquals(80, localPartyIdentity("installation-a", "a".repeat(100))!!.name.length)
    }
}
