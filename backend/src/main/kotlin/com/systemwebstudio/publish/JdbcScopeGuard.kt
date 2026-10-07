package com.systemwebstudio.publish

import com.systemwebstudio.integration.deploy.PointerFence
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The release scope guard on PostgreSQL (C2_DEPLOY_CONTRACT.md §2): a lease row on `sites`, a monotonic fencing token, an optimistic pointer
 * version. Not a global lock, not an advisory lock, no transaction held across provider calls: every statement here is its own short transaction
 * (REQUIRES_NEW), so a caller that is inside a web transaction neither holds the row lock nor starves the heartbeat.
 *
 * [leaseTtl] 90 s, [heartbeat] 30 s, [leaseMax] 900 s by default (`app.deploy.scope-lease-seconds`, minimum 30 s once configured, `app.deploy.scope-lease-max-seconds`).
 */
@Component
class JdbcScopeGuard(
    private val jdbc: JdbcTemplate, txManager: PlatformTransactionManager,
    private val leaseTtl: Duration, private val leaseMax: Duration, private val heartbeat: Duration,
    private val worker: String
) : ReleaseScopeGuard {
    @Autowired constructor(
        jdbc: JdbcTemplate, txManager: PlatformTransactionManager,
        @Value("\${app.deploy.scope-lease-seconds:90}") ttlSeconds: Long,
        @Value("\${app.deploy.scope-lease-max-seconds:900}") maxSeconds: Long
    ) : this(jdbc, txManager, Duration.ofSeconds(ttlSeconds.coerceAtLeast(MIN_TTL_SECONDS)), Duration.ofSeconds(maxSeconds.coerceAtLeast(ttlSeconds.coerceAtLeast(MIN_TTL_SECONDS))),
        Duration.ofSeconds(ttlSeconds.coerceAtLeast(MIN_TTL_SECONDS) / 3), workerName())

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    private fun secs(d: Duration) = d.toMillis() / 1000.0

    override fun acquire(request: ScopeRequest): ScopeAcquisition {
        val project = request.scope.appId
        val taken = tx.execute {
            jdbc.query(ACQUIRE, { rs, _ -> Taken(rs.getLong(1), rs.getObject(2, UUID::class.java), rs.getLong(3), rs.getObject(4, UUID::class.java), rs.getLong(5), rs.getLong(6), rs.getBoolean(7)) },
                request.operationId, request.operation.name, request.deploymentId, request.operationId, request.seq, worker, request.operationId, secs(leaseTtl),
                project, request.scope.tenantId, request.operationId, request.allowReentry, request.operationId, secs(leaseMax)).firstOrNull()
        } ?: return holder(project).let { h ->
            log.info("release scope busy app={} op={} kind={} heldBy={} heldByOp={}", project, request.operationId, request.operation, h?.kind, h?.operationId)
            ScopeAcquisition.Busy(h)
        }
        val fence = LeaseFence(project, request.operationId, taken.fence, taken.seq, taken.pointerVersion)
        val order = ScopeOrdering.of(taken.seq, request.operationId, taken.activeSeq, taken.activeOperationId)
        if (order == ScopeOrder.STALE || order == ScopeOrder.CONFLICT) {
            // we took the scope only to learn that this intent is overtaken: give it back at once
            fence.releaseNow()
            log.info("release scope refused app={} op={} kind={} seq={} activeSeq={} order={}", project, request.operationId, request.operation, taken.seq, taken.activeSeq, order)
            val why = if (order == ScopeOrder.STALE) "a newer release operation already moved the active release" else "another operation holds the same activation number"
            return ScopeAcquisition.Stale(why, taken.activeSeq, taken.activeDeployment)
        }
        val lease = ScopeLease(request.scope, request.operation, request.operationId, request.deploymentId, taken.seq, taken.fence, taken.pointerVersion,
            taken.activeDeployment, taken.activeSeq, taken.activeOperationId, resumed = taken.resumed || order == ScopeOrder.RESUME, fence = fence, onRelease = { fence.stopAndRelease() })
        fence.lease = lease
        fence.startHeartbeat()
        log.info("release scope acquired app={} op={} kind={} seq={} fence={} pointerVersion={} active={} resumed={}", project, request.operationId, request.operation, taken.seq,
            taken.fence, taken.pointerVersion, taken.activeDeployment, lease.resumed)
        return ScopeAcquisition.Acquired(lease)
    }

    private class Taken(val pointerVersion: Long, val activeDeployment: UUID?, val activeSeq: Long, val activeOperationId: UUID?, val seq: Long, val fence: Long, val resumed: Boolean)

    override fun holder(scope: ReleaseScope): ScopeHolder? = holder(scope.appId)

    private fun holder(project: UUID): ScopeHolder? = jdbc.query(
        "SELECT lease_kind, lease_operation_id, lease_deployment_id, lease_started_at, lease_until FROM sites WHERE project_id = ? AND lease_operation_id IS NOT NULL AND lease_until > now()",
        { rs, _ -> ScopeHolder(ReleaseOperation.valueOf(rs.getString(1)), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()) }, project).firstOrNull()

    /** the pointer fence of one held lease; also owns its heartbeat */
    private inner class LeaseFence(val project: UUID, override val operationId: UUID, val token: Long, val seq: Long, version: Long) : PointerFence {
        @Volatile var lease: ScopeLease? = null
        @Volatile private var version = version
        @Volatile private var out = false
        @Volatile private var step: (() -> Unit)? = null
        private var beat: ScheduledFuture<*>? = null
        private val stopped = AtomicBoolean(false)
        override val fencedOut get() = out

        fun startHeartbeat() {
            beat = scheduler.scheduleAtFixedRate({ if (!stopped.get()) beatOnce() }, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS)
        }

        private fun beatOnce() {
            try {
                val n = tx.execute { jdbc.update(HEARTBEAT, secs(leaseTtl), project, operationId, token, secs(leaseMax)) } ?: 0
                if (n == 0) { out = true; log.warn("Scope lease of {} (operation {}) was lost: the holder is fenced out", project, operationId) }
            } catch (e: Exception) { log.warn("Scope heartbeat for {} failed (will retry): {}", project, e.message) }
        }

        fun stopAndRelease() { stopped.set(true); beat?.cancel(false); releaseNow() }

        fun releaseNow() { runCatching { tx.execute { jdbc.update(RELEASE, project, operationId, token) }.also { n -> log.debug("release scope released app={} op={} fence={} released={}", project, operationId, token, n) } }.onFailure { log.warn("Could not release the scope of {}: {}", project, it.message) } }

        override fun commit(deploymentId: UUID?, inSameTransaction: (() -> Unit)?): Boolean {
            if (out) return false
            val pending = step; step = null
            val ok = tx.execute { status ->
                val n = jdbc.update(CAS, deploymentId, seq, operationId, project, operationId, token, version, seq, seq, operationId)
                if (n == 1) { pending?.invoke(); inSameTransaction?.invoke() } else status.setRollbackOnly()
                n == 1
            } ?: false
            if (ok) version += 1 else out = true
            lease?.pointerVersion = version
            if (ok) log.info("active release moved app={} op={} to={} pointerVersion={} fence={}", project, operationId, deploymentId, version, token)
            else log.warn("active release NOT moved (fenced out) app={} op={} wanted={} expectedVersion={} fence={}", project, operationId, deploymentId, version, token)
            return ok
        }

        override fun withNextCommit(step: () -> Unit) { this.step = step }
    }

    companion object {
        const val MIN_TTL_SECONDS = 30L
        private val scheduler = Executors.newScheduledThreadPool(1) { r -> Thread(r, "release-scope-heartbeat").apply { isDaemon = true } }
        fun workerName(): String = (runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("host") + "-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().take(6)).take(64)

        /**
         * Take the scope (or take it again, same operation). Free, expired, or ours; and, for the same operation, not past the maximum lifetime.
         * The fencing token is the counter's next value on EVERY acquisition. lease_seq is kept by the same operation, otherwise the given number or
         * a fresh one from the sequence. Tenant ownership is checked through projects.tenant_id.
         */
        private const val ACQUIRE = """
            UPDATE sites s SET lease_operation_id = ?, lease_kind = ?, lease_deployment_id = CAST(? AS UUID),
                   lease_seq = COALESCE(CASE WHEN s.lease_operation_id = ? THEN s.lease_seq END, CAST(? AS BIGINT), nextval('deployment_activation_seq')),
                   lease_fence = s.fence_counter + 1, fence_counter = s.fence_counter + 1, lease_holder = ?,
                   lease_started_at = CASE WHEN s.lease_operation_id = ? THEN s.lease_started_at ELSE now() END,
                   lease_until = now() + make_interval(secs => CAST(? AS DOUBLE PRECISION))
             WHERE s.project_id = ?
               AND EXISTS (SELECT 1 FROM projects p WHERE p.id = s.project_id AND p.tenant_id = ?)
               AND (s.lease_operation_id IS NULL OR s.lease_until <= now() OR (s.lease_operation_id = ? AND CAST(? AS BOOLEAN)))
               AND (s.lease_operation_id IS DISTINCT FROM ? OR now() < s.lease_started_at + make_interval(secs => CAST(? AS DOUBLE PRECISION)))
            RETURNING s.pointer_version, s.current_deployment_id, s.active_seq, s.active_operation_id, s.lease_seq, s.lease_fence,
                      (s.lease_started_at < now()) AS resumed"""

        /** extends the lease; 0 rows = the lease is lost (another token, expired, or past the maximum) */
        private const val HEARTBEAT = """
            UPDATE sites SET lease_until = now() + make_interval(secs => CAST(? AS DOUBLE PRECISION))
             WHERE project_id = ? AND lease_operation_id = ? AND lease_fence = ? AND lease_until > now() AND now() < lease_started_at + make_interval(secs => CAST(? AS DOUBLE PRECISION))"""

        private const val RELEASE = """
            UPDATE sites SET lease_operation_id = NULL, lease_kind = NULL, lease_deployment_id = NULL, lease_seq = NULL, lease_fence = NULL, lease_holder = NULL,
                   lease_started_at = NULL, lease_until = NULL
             WHERE project_id = ? AND lease_operation_id = ? AND lease_fence = ?"""

        /**
         * The only way the active pointer moves: compare-and-set on the version, valid only while the writer holds the lease with ITS token, and only
         * for an intent that is newer than the last one, or the same operation continuing.
         */
        private const val CAS = """
            UPDATE sites SET current_deployment_id = ?, pointer_version = pointer_version + 1, active_seq = ?, active_operation_id = ?, updated_at = now()
             WHERE project_id = ? AND lease_operation_id = ? AND lease_fence = ? AND lease_until > now() AND pointer_version = ?
               AND (active_seq < ? OR (active_seq = ? AND active_operation_id = ?))"""
    }
}
