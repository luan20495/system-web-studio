package com.systemwebstudio.wiring.persistence

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/**
 * Removes `data_idempotency` rows whose retention ended. Only created with the data platform on; runs hourly by default
 * (`app.data-platform.idempotency-purge-delay-ms`). Bounded per run, never touches an unexpired row, and a failure only logs.
 */
class IdempotencyPurgeRunner(private val store: JdbcIdempotencyStore) {
    private val log = LoggerFactory.getLogger(IdempotencyPurgeRunner::class.java)

    @Scheduled(fixedDelayString = "\${app.data-platform.idempotency-purge-delay-ms:3600000}", initialDelayString = "\${app.data-platform.idempotency-purge-delay-ms:3600000}")
    fun purge() {
        try {
            val removed = store.purgeExpired()
            if (removed > 0) log.info("data idempotency purge removed {} expired rows", removed)
        } catch (e: Exception) {
            log.warn("data idempotency purge failed: {}", e.javaClass.simpleName)
        }
    }
}
