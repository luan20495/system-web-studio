package com.systemwebstudio.data.org

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.organization.DuplicateOrganizationKey
import com.systemwebstudio.organization.TenantStructuralLock
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal fun ResultSet.uuid(c: String): UUID = getObject(c, UUID::class.java)
internal fun ResultSet.uuidOrNull(c: String): UUID? = getObject(c, UUID::class.java)
internal fun ResultSet.instant(c: String): Instant = getTimestamp(c).toInstant()
internal fun ResultSet.instantOrNull(c: String): Instant? = getTimestamp(c)?.toInstant()
internal fun ts(i: Instant): Timestamp = Timestamp.from(i)

/** A statement and its arguments. `internal`: the benchmark runs EXPLAIN on exactly the SQL the repositories execute. */
internal class Q(val sql: String, val args: List<Any>)

/**
 * Shared plumbing of the Dynamic Organization repositories (C3). Plain `JdbcTemplate` on the platform's own DataSource, so every statement takes part in the AMBIENT Spring
 * transaction: [write] and [tx] are `PROPAGATION_REQUIRED` (join an open transaction, otherwise open one) - never REQUIRES_NEW, never a second connection - which is what lets
 * C1's services write the account, the memberships, the positions and the audit row as one unit and roll all of it back together.
 *
 * Every write first sets a transaction-local `lock_timeout`: a writer that has to wait for a row lock or for the tenant structural lock waits a BOUNDED time and then fails with a
 * retryable `503 ORG_STRUCTURE_BUSY` (SQLSTATE 55P03 is the only failure translated), so a hot tenant can never hold pooled connections indefinitely.
 */
class OrgDb(val jdbc: JdbcTemplate, txManager: PlatformTransactionManager, val json: JsonMapper, val lockTimeoutMs: Int = DEFAULT_LOCK_TIMEOUT_MS) {
    private val template = TransactionTemplate(txManager)

    companion object {
        const val DEFAULT_LOCK_TIMEOUT_MS = 5_000
        const val MAX_DEPTH = 1000                // recursion guard of every recursive CTE: a legal tree never reaches it; a cycle written around the repositories cannot loop forever
        const val LOCK_NAMESPACE = 0x4F524754     // "ORGT": first key of pg_advisory_xact_lock(int4, int4)
        const val MAX_PAGE_SIZE = 100             // frozen C0 / C1: size <= 100
        const val MAX_OFFSET = 10_000             // frozen C0: offset <= 10000 (a deeper page answers empty + the true total)
        const val BUSY_CODE = "ORG_STRUCTURE_BUSY"
        private val CONSTRAINT = Regex("constraint \"([^\"]+)\"")
        fun likeLiteral(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> tx(block: () -> T): T = template.execute { try { block() } catch (e: DataAccessException) { throw translateBusy(e) } } as T

    /** what this ambient transaction has already paid for: the bounded `lock_timeout` is set ONCE, the structural advisory lock is taken ONCE per tenant (both live until the transaction ends) */
    private class TxState { var timeoutSet = false; val locked = HashSet<UUID>() }
    private val stateKey = Any()
    private fun state(): TxState {
        (TransactionSynchronizationManager.getResource(stateKey) as TxState?)?.let { return it }
        val st = TxState(); TransactionSynchronizationManager.bindResource(stateKey, st)
        TransactionSynchronizationManager.registerSynchronization(object : org.springframework.transaction.support.TransactionSynchronization {
            override fun afterCompletion(status: Int) { TransactionSynchronizationManager.unbindResourceIfPossible(stateKey) }
        })
        return st
    }

    /** `SET LOCAL lock_timeout` once per transaction: every later lock wait of the transaction (row locks, the structural lock) is bounded */
    fun boundLockWaits() { val st = state(); if (!st.timeoutSet) { jdbc.execute("SET LOCAL lock_timeout = '${lockTimeoutMs}ms'"); st.timeoutSet = true } }

    /** a write: one REQUIRED transaction, bounded lock waits */
    fun <T> write(block: () -> T): T = tx { boundLockWaits(); block() }

    fun sqlState(e: Throwable): String? { var c: Throwable? = e; while (c != null) { if (c is SQLException) return c.sqlState; c = c.cause }; return null }

    private fun translateBusy(e: DataAccessException): RuntimeException =
        if (sqlState(e) == "55P03") ApiException(HttpStatus.SERVICE_UNAVAILABLE, BUSY_CODE, "The organization structure is being changed by someone else; retry in a moment", emptyMap(), mapOf("Retry-After" to "1")) else e

    fun constraint(e: Throwable): String? = CONSTRAINT.find(generateSequence(e) { it.cause }.map { it.message.orEmpty() }.firstOrNull { CONSTRAINT.containsMatchIn(it) } ?: "")?.groupValues?.get(1)

    /** the uniqueness violation of [keys] (constraint name -> the contract's key) as the contract's typed refusal; anything else is rethrown unchanged */
    fun duplicate(e: DataIntegrityViolationException, keys: Map<String, String>): RuntimeException = constraint(e)?.let { keys[it] }?.let { DuplicateOrganizationKey(it) } ?: e

    /** the tenant structural lock: pg_advisory_xact_lock on (namespace, tenant). Held until the AMBIENT transaction ends; re-entrant inside it. Needs [write] / [tx] around it. */
    fun lockTenantStructure(tenantId: UUID) {
        val st = state(); if (!st.locked.add(tenantId)) return                                                              // already held by this transaction (re-entrant: nothing more to ask PostgreSQL)
        try { jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(?, hashtext(?))) l", Int::class.java, LOCK_NAMESPACE, tenantId.toString()) } catch (e: RuntimeException) { st.locked.remove(tenantId); throw e }
    }

    fun jsonText(n: JsonNode): String = json.writeValueAsString(n)
}

/**
 * The tenant STRUCTURAL lock seam of C1 ([TenantStructuralLock]) on PostgreSQL: `pg_advisory_xact_lock(namespace, hashtext(tenant))`. Taken ONLY by the subtree move (C1 contract CF-4):
 * it serialises the moves of ONE tenant (different tenants never wait for each other, a hash collision only serialises), it is released by the ambient transaction's commit OR rollback,
 * it is re-entrant inside one transaction, it needs an active transaction (`IllegalStateException` otherwise) and its wait is bounded (`lock_timeout`; failure = retryable
 * `503 ORG_STRUCTURE_BUSY`). Nothing else of the organization persistence takes it: memberships, positions, catalogs, directory reads and unit create / update / archive / restore use
 * constraints, row locks and optimistic versions only.
 */
class PostgresTenantStructuralLock(private val db: OrgDb) : TenantStructuralLock {
    override fun acquire(tenantId: UUID) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "the structural lock needs an active transaction" }
        db.tx { db.boundLockWaits(); db.lockTenantStructure(tenantId) }
    }
}
