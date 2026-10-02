package com.systemwebstudio.identity

import com.systemwebstudio.common.ApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Argon2id is deliberately expensive (64 MiB and ~100 ms per verification). Unbounded concurrency lets a burst of logins
 * (or an attack) exhaust the heap and freeze the whole API; load testing 100 simultaneous logins did exactly that.
 * At most N verifications run at once; the rest wait briefly and are then told to retry (503 + Retry-After).
 */
@Component
class PasswordHashGate(
    @Value("\${app.login.max-concurrent-hashes:4}") permits: Int,
    @Value("\${app.login.queue-wait-ms:5000}") private val waitMs: Long
) {
    private val semaphore = Semaphore(permits, true)

    fun <T> run(block: () -> T): T {
        if (!semaphore.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LOGIN_BUSY", "Sign-in is busy right now. Please retry in a moment.", headers = mapOf("Retry-After" to "2"))
        }
        try { return block() } finally { semaphore.release() }
    }
}
