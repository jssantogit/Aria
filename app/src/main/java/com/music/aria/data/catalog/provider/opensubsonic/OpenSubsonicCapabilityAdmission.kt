package com.music.aria.data.catalog.provider.opensubsonic

import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderKey
import com.music.aria.data.providers.ProviderPolicyGate
import com.music.aria.data.providers.ProviderRequestTicket
import com.music.aria.data.providers.ProviderRuntime

/** An authenticated account ID, not its mutable server or catalog realm. */
internal fun openSubsonicCapabilityKey(accountId: String): ProviderKey =
    ProviderKey("opensubsonic", accountId)

/**
 * Exact capability+account ticket. Its revision is invalidated on opt-out,
 * descriptor removal, and operational unavailability. A separate catalog
 * realm still fences assets when the server or login changes under the ID.
 */
internal class OpenSubsonicCapabilityAdmission private constructor(
    private val gate: ProviderPolicyGate,
    private val ticket: ProviderRequestTicket,
) {
    fun isCurrent(): Boolean = gate.accept(ticket)

    fun onRevoke(callback: () -> Unit): () -> Unit = gate.onRevoke(ticket, callback)

    companion object {
        fun open(
            capability: ProviderCapability,
            accountId: String,
            gateProvider: () -> ProviderPolicyGate? = ProviderRuntime::gateOrNull,
        ): OpenSubsonicCapabilityAdmission? {
            val gate = gateProvider() ?: return null
            val ticket = gate.open(capability, openSubsonicCapabilityKey(accountId)) ?: return null
            return OpenSubsonicCapabilityAdmission(gate, ticket)
        }
    }
}
