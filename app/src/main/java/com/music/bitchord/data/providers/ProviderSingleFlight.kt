package com.music.bitchord.data.providers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coalesces *concurrent, identical* provider operations. Not a result cache:
 * completed entries disappear as soon as the last caller has consumed them.
 *
 * Only the first observer launches work. Later observers share that exact
 * in-flight response. Cancelling one observer preserves work needed by others;
 * cancelling the final observer cancels the underlying operation.
 *
 * Keys MUST include provider instance, capability, authorization generation
 * and the complete normalized request identity. Never put credentials in a key.
 * Failure is not remembered and unrelated operations never share a slot.
 *
 * This lightweight coordinator owns no background polling or recurring work.
 */
internal class ProviderSingleFlight(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class Entry(
        val deferred: Deferred<Any?>,
        var subscribers: Int,
    )

    private val mutex = Mutex()
    private val ongoing = mutableMapOf<Any, Entry>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> run(
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
                    operation() as Any?
                }
                Entry(deferred, subscribers = 1).also { ongoing[key] = it }
            }
        }

        try {
            entry.deferred.start()
            return entry.deferred.await() as T
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

    /** Diagnostics/test visibility only; no result data is exposed. */
    suspend fun activeCount(): Int = mutex.withLock { ongoing.size }

    /** Diagnostics/test visibility only; no request bodies are exposed. */
    suspend fun activeWaiters(key: Any): Int =
        mutex.withLock { ongoing[key]?.subscribers ?: 0 }
}
