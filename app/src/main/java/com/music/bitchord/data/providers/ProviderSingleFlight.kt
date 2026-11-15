package com.music.bitchord.data.providers

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Optional marker for in-flight keys carrying capability admission metadata. */
internal interface PolicyBoundFlightKey {
    val gate: ProviderPolicyGate?
    val capability: ProviderCapability
    val provider: ProviderKey
}

/**
 * Coalesces *concurrent, identical* provider operations. Not a result cache:
 * completed entries disappear as soon as the last caller has consumed them.
 *
 * Only the first observer launches work. Later observers share that exact
 * in-flight response. Cancelling one observer preserves work needed by others;
 * cancelling the final observer cancels the underlying operation.
 *
 * Each coordinator has ONE result type at compile time: a text HTTP response
 * cannot be accidentally conflated with Unit, a parsed DTO or another type.
 * Keys MUST include provider instance, capability, authorization generation
 * and the complete normalized request identity. Never put credentials in a key.
 * Failure is not remembered and unrelated operations never share a slot.
 *
 * This lightweight coordinator owns no background polling or recurring work.
 */
internal class ProviderSingleFlight<T>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class Entry<T>(
        val deferred: Deferred<T>,
        var subscribers: Int,
    )

    private val mutex = Mutex()
    private val ongoing = mutableMapOf<Any, Entry<T>>()

    suspend fun run(
        key: Any,
        operation: suspend () -> T,
    ): T {
        val bound = extractPolicyBinding(key)
        return if (bound != null) {
            runGuarded(
                gate = bound.first,
                capability = bound.second,
                provider = bound.third,
                key = key,
                operation = operation,
            )
        } else {
            runShared(key, operation)
        }
    }

    private suspend fun runShared(
        key: Any,
        operation: suspend () -> T,
    ): T {
        val entry = mutex.withLock {
            val existing = ongoing[key]
            if (existing != null) {
                existing.subscribers++
                existing
            } else {
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    operation()
                }
                Entry(deferred, subscribers = 1).also { ongoing[key] = it }
            }
        }

        try {
            entry.deferred.start()
            return entry.deferred.await()
        } finally {
            // A cancelled observer still has to remove its subscription.
            // Never run this cleanup in the cancelled caller's context.
            withContext(NonCancellable) {
                mutex.withLock {
                    entry.subscribers--
                    if (entry.subscribers == 0) {
                        if (ongoing[key] === entry) ongoing.remove(key)
                        if (!entry.deferred.isCompleted) {
                            entry.deferred.cancel()
                        }
                    }
                }
            }
        }
    }

    /**
     * Policy-bound single-flight: denies immediately if [gate] does not permit
     * (capability, provider) and cancels a child waiter job (never the caller's
     * own coroutine scope) as soon as the policy revokes that specific
     * (capability, provider) pair. If no authorized waiter remains, the
     * underlying shared operation is cancelled immediately.
     */
    suspend fun runGuarded(
        gate: ProviderPolicyGate?,
        capability: ProviderCapability,
        provider: ProviderKey,
        key: Any,
        operation: suspend () -> T,
    ): T {
        val ticket = gate?.open(capability, provider)
        if (gate != null && ticket == null) {
            throw ProviderRequestDeniedException(capability, provider)
        }
        val revoked = AtomicBoolean(false)
        val waiterJob = Job(coroutineContext[Job])
        val unregister = if (gate != null && ticket != null) {
            gate.onRevoke(ticket) {
                revoked.set(true)
                waiterJob.cancel(CancellationException("Provider capability revoked"))
            }
        } else {
            {}
        }
        return try {
            val value = withContext(waiterJob) {
                runShared(key, operation)
            }
            if (revoked.get() || (gate != null && ticket != null && !gate.accept(ticket))) {
                throw ProviderRequestDeniedException(capability, provider)
            }
            value
        } catch (cancelled: CancellationException) {
            if (revoked.get() || (gate != null && ticket != null && !gate.accept(ticket))) {
                throw ProviderRequestDeniedException(capability, provider)
            }
            throw cancelled
        } finally {
            unregister()
            waiterJob.cancel()
        }
    }

    private fun extractPolicyBinding(
        key: Any,
    ): Triple<ProviderPolicyGate, ProviderCapability, ProviderKey>? {
        if (key is PolicyBoundFlightKey) {
            val gate = key.gate ?: return null
            return Triple(gate, key.capability, key.provider)
        }
        return runCatching {
            val clazz = key.javaClass
            val gateField = clazz.getDeclaredField("gate").apply { isAccessible = true }
            val gate = gateField.get(key) as? ProviderPolicyGate ?: return null
            val capField = clazz.getDeclaredField("capability").apply { isAccessible = true }
            val capability = capField.get(key) as? ProviderCapability ?: return null
            Triple(gate, capability, BuiltInProviderKeys.MUSICBRAINZ)
        }.getOrNull()
    }

    /** Diagnostics/test visibility only; no result data is exposed. */
    suspend fun activeCount(): Int = mutex.withLock { ongoing.size }

    /** Diagnostics/test visibility only; no request bodies are exposed. */
    suspend fun activeWaiters(key: Any): Int =
        mutex.withLock { ongoing[key]?.subscribers ?: 0 }
}
