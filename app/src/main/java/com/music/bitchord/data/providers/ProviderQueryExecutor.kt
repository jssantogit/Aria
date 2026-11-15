package com.music.bitchord.data.providers

import kotlinx.coroutines.CancellationException

/** A request was denied before dispatch, or invalidated before publication. */
sealed interface ProviderCallResult<out T> {
    data object Denied : ProviderCallResult<Nothing>
    data object Stale : ProviderCallResult<Nothing>
    data class Success<T>(val value: T) : ProviderCallResult<T>
    data class Failed(val cause: Exception) : ProviderCallResult<Nothing>
}

/**
 * One capability-safe dispatch boundary, independent from any provider SDK.
 *
 * The adapter lambda is not called unless the current gate permits it.
 * The result cannot escape once that capability's policy has changed.
 * Cancellation must propagate so screen/viewmodel lifecycles still work.
 *
 * This is intentionally not a rate-limiter or a blanket global fallback.
 * Every actual network entrypoint still needs to be migrated through this
 * boundary, and cancellation of running HTTP requests remains adapter-owned.
 */
class ProviderQueryExecutor(private val gate: ProviderPolicyGate) {
    suspend fun <T> execute(
        capability: ProviderCapability,
        key: ProviderKey,
        operation: suspend () -> T,
    ): ProviderCallResult<T> {
        val ticket = gate.open(capability, key) ?: return ProviderCallResult.Denied
        return try {
            val value = operation()
            if (gate.accept(ticket)) ProviderCallResult.Success(value)
            else ProviderCallResult.Stale
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (gate.accept(ticket)) ProviderCallResult.Failed(failure)
            else ProviderCallResult.Stale
        }
    }
}
