package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulseekActiveSessionPolicyTest {
    private val soulseek = ProviderKey("soulseek")
    private val other = ProviderKey("other-download")
    private fun policy(soulseekEnabled: Boolean, otherEnabled: Boolean = true) =
        ProviderCapabilityPolicy(
            descriptors = listOf(soulseek, other).map {
                ProviderDescriptor(it, setOf(ProviderCapability.DOWNLOAD), ProviderReadiness.READY)
            },
            choices = mapOf(ProviderCapability.DOWNLOAD to listOf(
                ProviderChoice(soulseek, soulseekEnabled, 0),
                ProviderChoice(other, otherEnabled, 1),
            )),
        )

    @After fun cleanUp() { SoulseekActiveSession.bindDownloadPermission(null) }

    @Test fun revokingCanonicalDownloadClosesActiveSoulseekSocketImmediately() {
        val gate = ProviderPolicyGate(policy(true))
        assertTrue(SoulseekActiveSession.bindDownloadPermission(gate))
        val socket = Socket()
        val generation = SoulseekActiveSession.generation()
        assertTrue(SoulseekActiveSession.register(socket, generation))
        gate.replace(policy(false))
        assertTrue(socket.isClosed)
        assertFalse(SoulseekActiveSession.isCurrent(generation))
        assertFalse(SoulseekActiveSession.bindDownloadPermission(gate))
    }

    @Test fun revokedCapabilityCannotRegisterNewPeerSocketOrCommitNewFile() {
        val gate = ProviderPolicyGate(policy(true))
        assertTrue(SoulseekActiveSession.bindDownloadPermission(gate))
        val former = SoulseekActiveSession.generation()
        gate.replace(policy(false))
        val lateSocket = Socket()
        assertFalse(SoulseekActiveSession.register(
            lateSocket, SoulseekActiveSession.generation()))
        assertTrue(lateSocket.isClosed)
        var published = false
        try {
            SoulseekActiveSession.commitIfCurrent(
                SoulseekActiveSession.generation()) { published = true }
        } catch (_: java.io.IOException) { }
        assertFalse(published)
        assertFalse(SoulseekActiveSession.isCurrent(former))
    }

    @Test fun revokingSiblingDownloaderDoesNotCloseSoulseekSocket() {
        val gate = ProviderPolicyGate(policy(true, true))
        assertTrue(SoulseekActiveSession.bindDownloadPermission(gate))
        val socket = Socket()
        val generation = SoulseekActiveSession.generation()
        assertTrue(SoulseekActiveSession.register(socket, generation))
        gate.replace(policy(true, false))
        assertFalse(socket.isClosed)
        assertTrue(SoulseekActiveSession.isCurrent(generation))
    }
}
