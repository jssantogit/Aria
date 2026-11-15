package com.music.bitchord.data.providers

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

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
 * When the specific (capability, provider instance) is revoked mid-flight,
 * the active child job is cancelled immediately and Stale is returned without
 * cancelling the caller's outer coroutine scope. Adapter runtime exceptions
 * are captured as Failed(cause) without poisoning parent scopes.
 */
class ProviderQueryExecutor(private val gate: ProviderPolicyGate) {
    suspend fun <T> execute(
        capability: ProviderCapability,
        key: ProviderKey,
        operation: suspend () -> T,
    ): ProviderCallResult<T> {
        val ticket = gate.open(capability, key) ?: return ProviderCallResult.Denied
        val callerJob = coroutineContext[Job]
        val operationJob = Job(callerJob)
        val revoked = AtomicBoolean(false)
        val unregister = gate.onRevoke(ticket) {
            revoked.set(true)
            operationJob.cancel(CancellationException("Provider capability revoked"))
        }
        return try {
            val value = withContext(operationJob) {
                operation()
            }
            if (!revoked.get() && gate.accept(ticket)) {
                ProviderCallResult.Success(value)
            } else {
                ProviderCallResult.Stale
            }
        } catch (cancelled: CancellationException) {
            if (revoked.get() || !gate.accept(ticket)) {
                ProviderCallResult.Stale
            } else {
                throw cancelled
            }
        } catch (failure: Exception) {
            if (!revoked.get() && gate.accept(ticket)) {
                ProviderCallResult.Failed(failure)
            } else {
                ProviderCallResult.Stale
            }
        } finally {
            unregister()
            operationJob.cancel()
        }
    }
}
