package com.music.bitchord.acquisition.a0

import java.net.Socket
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderRuntime

/**
 * Process-local P2P socket generation. Saving or forgetting an account revokes
 * the previous authenticated transport. Never persist sockets or password data.
 */
internal object SoulseekActiveSession {
    private var epoch = 0L
    private val sockets = mutableSetOf<Socket>()
    private val activeTransfers = mutableSetOf<A0ObservedCandidateHandoff>()
    private val socketUnregister = mutableMapOf<Socket, () -> Unit>()
    private val transferUnregister = mutableMapOf<A0ObservedCandidateHandoff, () -> Unit>()
    @Volatile internal var policyGateForTests: (() -> ProviderPolicyGate?)? = null

    private fun gate(): ProviderPolicyGate? =
        policyGateForTests?.invoke() ?: ProviderRuntime.sessionOrNull()?.gate

    /** JVM-only tests have no installed runtime. Android always has one. */
    private fun permission(): (() -> Unit)? {
        val current = gate() ?: return if (ProviderRuntime.sessionOrNull() == null &&
            policyGateForTests == null) ({}) else null
        val ticket = current.open(ProviderCapability.DOWNLOAD, ProviderKey("soulseek"))
            ?: return null
        val unregister = current.onRevoke(ticket) { revoke() }
        if (!current.accept(ticket)) {
            unregister()
            return null
        }
        return unregister
    }

    @Synchronized fun generation(): Long = epoch

    @Synchronized fun isCurrent(expected: Long): Boolean = epoch == expected

    /**
     * Hold the generation gate only around A3's durable AVAILABLE commit.
     * A2 copying stays outside the lock and remains cancellable on logout.
     */
    @Synchronized fun commitIfCurrent(expected: Long, commit: () -> Unit) {
        if (epoch != expected) throw java.io.IOException("Soulseek account revoked")
        commit()
    }

    @Synchronized fun registerTransfer(
        transfer: A0ObservedCandidateHandoff, expected: Long
    ): Boolean {
        if (epoch != expected) {
            transfer.cancel()
            return false
        }
        val unregister = permission()
        if (unregister == null) {
            transfer.cancel()
            return false
        }
        activeTransfers.add(transfer)
        transferUnregister[transfer] = unregister
        return true
    }

    @Synchronized fun detachTransfer(transfer: A0ObservedCandidateHandoff) {
        activeTransfers.remove(transfer)
        transferUnregister.remove(transfer)?.invoke()
    }

    @Synchronized fun register(socket: Socket, expectedGeneration: Long): Boolean {
        if (expectedGeneration != epoch) {
            runCatching { socket.close() }
            return false
        }
        val unregister = permission()
        if (unregister == null) {
            runCatching { socket.close() }
            return false
        }
        sockets.add(socket)
        socketUnregister[socket] = unregister
        return true
    }

    @Synchronized fun detach(socket: Socket) {
        sockets.remove(socket)
        socketUnregister.remove(socket)?.invoke()
    }

    @Synchronized fun revoke() {
        epoch++
        socketUnregister.values.toList().forEach { it() }
        socketUnregister.clear()
        transferUnregister.values.toList().forEach { it() }
        transferUnregister.clear()
        // P/F transport can stay alive after the authenticated server socket
        // is no longer in use. Its own cancel closes both peer channels.
        activeTransfers.forEach { runCatching { it.cancel() } }
        activeTransfers.clear()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }
}
