package com.systemwebstudio.publish

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a deployment step under a deadline. On timeout the caller gets a [TimeoutException] (the step is interrupted, but a call that
 * ignores interrupts may still finish later: the caller must treat the outcome as unknown, which is why a timed-out DEPLOYING step is
 * verified / restored rather than retried). Exceptions of the step itself come through unwrapped.
 */
class StepRunner(private val pool: ExecutorService = defaultPool) {
    fun <T> bounded(timeoutMs: Long, body: () -> T): T {
        if (timeoutMs <= 0) return body()
        val future = pool.submit(Callable { body() })
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw e
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw e
        }
    }

    companion object {
        private val counter = AtomicInteger()
        val defaultPool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "deploy-step-" + counter.incrementAndGet()).apply { isDaemon = true } }
    }
}

/** How often a transient failure of a step is retried: attempt n waits n × backoff. */
data class RetryPolicy(val maxAttempts: Int, val backoffMs: Long) {
    init { require(maxAttempts >= 1) { "maxAttempts must be at least 1" } }
    fun delayBefore(nextAttempt: Int): Long = if (backoffMs <= 0) 0 else backoffMs * (nextAttempt - 1).coerceAtLeast(1)
    /** `attemptsMade` includes the one that just failed */
    fun canRetry(failure: StepFailure, attemptsMade: Int): Boolean = failure.transient && !failure.ambiguous && attemptsMade < maxAttempts
}
