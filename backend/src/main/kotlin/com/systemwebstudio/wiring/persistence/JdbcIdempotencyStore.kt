package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.IdempotencyDecision
import com.systemwebstudio.data.gateway.IdempotencyStore
import com.systemwebstudio.data.gateway.StoredMutation
import com.systemwebstudio.wiring.persistence.JdbcSupport.instant
import com.systemwebstudio.wiring.persistence.JdbcSupport.instantOrNull
import com.systemwebstudio.wiring.persistence.JdbcSupport.longOrNull
import com.systemwebstudio.wiring.persistence.JdbcSupport.ts
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `data_idempotency` (V28): at-most-once execution of a mutation per (tenant, data source, mutation, key), the durable twin of
 * `InMemoryIdempotencyStore` with the same decision table:
 *
 *  - absent or expired -> the call owns the key (RESERVED + lease) = Run;  - different fingerprint -> Conflict;  - DONE -> Replay;
 *  - UNKNOWN -> OutcomeUnknown;  - RESERVED whose lease ran out -> becomes UNKNOWN + OutcomeUnknown (never runnable again);  - else InProgress.
 *
 * `begin` is atomic without a transaction: the takeover of an absent/expired row is one `INSERT .. ON CONFLICT DO UPDATE .. WHERE expires_at <= now`
 * statement, so two concurrent callers cannot both get Run; the lease expiry is one guarded UPDATE.
 *
 * Retention (D-C0-21 / D4): a row lives at least [minRetention] (default 30 days, configurable, never below 7 days) whatever TTL the caller
 * asks for. The gateway still passes its historical 24 h constant; this store raises it, so a replay window can never be shorter than the
 * action-run retention. Rows are removed only by [purgeExpired], never while unexpired.
 * Only the derived key, a fingerprint, counts and a bounded result document are stored: no client key, no request parameters.
 */
class JdbcIdempotencyStore(
    private val jdbc: JdbcTemplate,
    private val clock: Clock = Clock.systemUTC(),
    val minRetention: Duration = DEFAULT_RETENTION,
    private val leaseSeconds: Long = DEFAULT_LEASE_SECONDS
) : IdempotencyStore {
    init {
        require(minRetention >= MIN_RETENTION) { "idempotency retention must be at least $MIN_RETENTION" }
        require(leaseSeconds in 1..3_600) { "invalid idempotency lease" }
    }

    override fun begin(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String, fingerprint: String, ttlSeconds: Long): IdempotencyDecision {
        requireKey(key)
        val ttl = maxOf(ttlSeconds, minRetention.seconds)
        repeat(ATTEMPTS) {
            val now = clock.instant()
            val taken = jdbc.update(
                """INSERT INTO data_idempotency (tenant_id, data_source_id, mutation_id, idem_key, fingerprint, state, lease_until, expires_at, created_at)
                   VALUES (?, ?, ?, ?, ?, 'RESERVED', ?, ?, ?)
                   ON CONFLICT (tenant_id, data_source_id, mutation_id, idem_key) DO UPDATE SET fingerprint = EXCLUDED.fingerprint, state = 'RESERVED',
                       affected = NULL, output_json = NULL, completed_at = NULL, lease_until = EXCLUDED.lease_until, expires_at = EXCLUDED.expires_at,
                       created_at = EXCLUDED.created_at
                   WHERE data_idempotency.expires_at <= ?""",
                tenantId, dataSourceId, mutationId, key, fingerprint, ts(now.plusSeconds(leaseSeconds)), ts(now.plusSeconds(ttl)), ts(now), ts(now)
            )
            if (taken == 1) return IdempotencyDecision.Run

            // the lease of a RESERVED row with the same fingerprint ran out: the holder vanished or hung, the outcome is ambiguous -> UNKNOWN
            jdbc.update(
                """UPDATE data_idempotency SET state = 'UNKNOWN' WHERE tenant_id = ? AND data_source_id = ? AND mutation_id = ? AND idem_key = ?
                   AND state = 'RESERVED' AND lease_until <= ? AND fingerprint = ? AND expires_at > ?""",
                tenantId, dataSourceId, mutationId, key, ts(now), fingerprint, ts(now)
            )
            val row = jdbc.query(
                """SELECT fingerprint, state, affected, output_json, completed_at, expires_at FROM data_idempotency
                   WHERE tenant_id = ? AND data_source_id = ? AND mutation_id = ? AND idem_key = ?""",
                { rs, _ -> Row(rs.getString("fingerprint"), rs.getString("state"), rs.longOrNull("affected"), rs.getString("output_json"), rs.instantOrNull("completed_at"), rs.instant("expires_at")) },
                tenantId, dataSourceId, mutationId, key
            ).firstOrNull()
            // the row vanished (released) or expired between the statements: take another turn
            if (row == null || row.expiresAt <= now) return@repeat
            return when {
                row.fingerprint != fingerprint -> IdempotencyDecision.Conflict
                row.state == "DONE" -> IdempotencyDecision.Replay(StoredMutation(row.affected, row.outputJson, row.completedAt ?: row.expiresAt))
                row.state == "UNKNOWN" -> IdempotencyDecision.OutcomeUnknown
                else -> IdempotencyDecision.InProgress
            }
        }
        // could not settle after several turns (heavy contention on one key): never guess Run
        return IdempotencyDecision.InProgress
    }

    override fun complete(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String, result: StoredMutation) {
        // an oversized result document is dropped (the count is kept) rather than failing the completion: a replay then answers without the body
        val output = result.outputJson?.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_OUTPUT_BYTES }
        jdbc.update(
            """UPDATE data_idempotency SET state = 'DONE', affected = ?, output_json = ?, completed_at = ?
               WHERE tenant_id = ? AND data_source_id = ? AND mutation_id = ? AND idem_key = ?""",
            result.affected, output, ts(result.completedAt), tenantId, dataSourceId, mutationId, key
        )
    }

    /** only a row that is not DONE can be freed: a completed mutation is never forgotten before it expires */
    override fun release(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String) {
        jdbc.update(
            "DELETE FROM data_idempotency WHERE tenant_id = ? AND data_source_id = ? AND mutation_id = ? AND idem_key = ? AND state <> 'DONE'",
            tenantId, dataSourceId, mutationId, key
        )
    }

    override fun markUnknown(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String) {
        jdbc.update(
            "UPDATE data_idempotency SET state = 'UNKNOWN' WHERE tenant_id = ? AND data_source_id = ? AND mutation_id = ? AND idem_key = ? AND state = 'RESERVED'",
            tenantId, dataSourceId, mutationId, key
        )
    }

    /** removes at most [limit] rows whose retention ended; @return how many */
    fun purgeExpired(now: Instant = clock.instant(), limit: Int = 1_000): Int =
        jdbc.update("DELETE FROM data_idempotency WHERE ctid IN (SELECT ctid FROM data_idempotency WHERE expires_at <= ? LIMIT ?)", ts(now), limit.coerceIn(1, 100_000))

    private class Row(val fingerprint: String, val state: String, val affected: Long?, val outputJson: String?, val completedAt: Instant?, val expiresAt: Instant)

    private fun requireKey(key: String) {
        if (!KEY.matches(key)) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "invalid idempotency key")
    }

    companion object {
        val DEFAULT_RETENTION: Duration = Duration.ofDays(30)
        val MIN_RETENTION: Duration = Duration.ofDays(7)
        const val DEFAULT_LEASE_SECONDS = 300L
        private const val ATTEMPTS = 3
        private const val MAX_OUTPUT_BYTES = 1_048_576
        private val KEY = Regex("^[A-Za-z0-9_-]{8,128}$")
    }
}
