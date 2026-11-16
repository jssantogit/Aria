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
    private val provider = ProviderKey("soulseek")
    private fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
        descriptors = listOf(ProviderDescriptor(
            provider, setOf(ProviderCapability.DOWNLOAD), ProviderReadiness.READY,
        )),
        choices = mapOf(ProviderCapability.DOWNLOAD to listOf(
            ProviderChoice(provider, enabled, 0),
        )),
    )

    @After fun cleanup() {
        SoulseekActiveSession.revoke()
        SoulseekActiveSession.policyGateForTests = null
    }

    @Test fun disabledSoulseekCannotRegisterNetworkSocket() {
        val gate = ProviderPolicyGate(policy(false))
        SoulseekActiveSession.policyGateForTests = { gate }
        val socket = Socket()
        assertFalse(SoulseekActiveSession.register(socket, SoulseekActiveSession.generation()))
        assertTrue(socket.isClosed)
    }

    @Test fun revocationClosesRegisteredSocketAndInvalidatesSessionGeneration() {
        val gate = ProviderPolicyGate(policy(true))
        SoulseekActiveSession.policyGateForTests = { gate }
        val socket = Socket()
        val generation = SoulseekActiveSession.generation()
        assertTrue(SoulseekActiveSession.register(socket, generation))
        assertFalse(socket.isClosed)
        gate.replace(policy(false))
        assertTrue(socket.isClosed)
        assertFalse(SoulseekActiveSession.isCurrent(generation))
    }
}
