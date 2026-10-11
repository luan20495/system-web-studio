package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.logic.action.FakePrincipals
import com.systemwebstudio.logic.action.FakeTenants
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.action.RecordingLogicAudit
import com.systemwebstudio.logic.approval.Approval
import com.systemwebstudio.logic.approval.ApprovalDecision
import com.systemwebstudio.logic.approval.ApprovalRequest
import com.systemwebstudio.logic.approval.ApprovalResult
import com.systemwebstudio.logic.approval.ApprovalService
import com.systemwebstudio.logic.approval.ApprovalSource
import com.systemwebstudio.logic.approval.ApprovalStatus
import com.systemwebstudio.logic.approval.DecisionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The durable `ApprovalStore`: same contract as `InMemoryApprovalStore`, plus persistence across a "restart" (a new store over the same database), isolation and a real race. */
class JdbcApprovalStoreTests : DataRuntimeJdbcTestBase() {
    private lateinit var tenant: UUID
    private lateinit var app: UUID
    private lateinit var store: JdbcApprovalStore
    private lateinit var requester: UUID
    private lateinit var approverA: UUID
    private lateinit var approverB: UUID

    @BeforeEach
    fun setUp() {
        ApprovalTestSchema.ensure(jdbc)
        tenant = newTenant(); app = projectIn(workspaceOf(tenant), tenant)
        store = JdbcApprovalStore(jdbc, json)
        requester = fx.user().id; approverA = UUID.randomUUID(); approverB = UUID.randomUUID()
    }

    private fun approval(
        tenantId: UUID = tenant, key: String? = "k-" + UUID.randomUUID(), status: ApprovalStatus = ApprovalStatus.PENDING, expires: Instant = at.plus(Duration.ofHours(1)),
        approvers: Set<UUID> = setOf(approverA, approverB), run: UUID? = UUID.randomUUID(), finishedAt: Instant? = null
    ) = Approval(
        id = UUID.randomUUID(), tenantId = tenantId, appId = app, title = "Release order", requestedBy = requester, requestedAt = at, expiresAt = expires,
        approverSpecs = listOf(PrincipalSpec.User(approverA, null), PrincipalSpec.Group("finance"), PrincipalSpec.Role("approvers"), PrincipalSpec.DepartmentManager("d1")),
        approvers = approvers, requiredApprovals = 1, allowSelfApproval = false, status = status, source = ApprovalSource(run, "okay"), idempotencyKey = key, finishedAt = finishedAt
    )

    @Test
    fun `an approval round-trips with its snapshot, specs and source, and survives a restart`() {
        val a = store.create(approval())
        val again = JdbcApprovalStore(jdbc, json).get(tenant, a.id)!!                       // a new store instance = a restarted process
        assertThat(again).isEqualTo(a)
        assertThat(again.approvers).isEqualTo(setOf(approverA, approverB))
        assertThat(again.source.stepId).isEqualTo("okay")
    }

    @Test
    fun `create is idempotent on the key and returns the stored approval`() {
        val first = store.create(approval(key = "same"))
        val second = store.create(approval(key = "same"))
        assertThat(second.id).isEqualTo(first.id)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approvals WHERE tenant_id = ? AND idempotency_key = 'same'", Int::class.java, tenant)).isEqualTo(1)
        assertThat(store.create(approval(key = null)).id).isNotEqualTo(store.create(approval(key = null)).id)         // no key = no de-duplication
    }

    @Test
    fun `compare-and-set stores status and decision together and a stale writer loses`() {
        val a = store.create(approval())
        val decided = a.copy(status = ApprovalStatus.APPROVED, finishedAt = at.plusSeconds(5), decisions = listOf(ApprovalDecision(approverA, DecisionKind.APPROVE, "ok", at.plusSeconds(5))))
        assertThat(store.compareAndSet(a, decided)).isTrue()
        val stored = store.get(tenant, a.id)!!
        assertThat(stored.status).isEqualTo(ApprovalStatus.APPROVED); assertThat(stored.version).isEqualTo(1); assertThat(stored.decisions).isEqualTo(decided.decisions)
        val stale = a.copy(status = ApprovalStatus.REJECTED, finishedAt = at.plusSeconds(6))
        assertThat(store.compareAndSet(a, stale)).describedAs("the version moved on").isFalse()
        assertThat(store.get(tenant, a.id)!!.status).isEqualTo(ApprovalStatus.APPROVED)
    }

    @Test
    fun `another tenant never sees, lists or changes an approval`() {
        val a = store.create(approval())
        val other = newTenant()
        assertThat(store.get(other, a.id)).isNull()
        assertThat(store.pendingFor(other, approverA, 10)).isEmpty()
        assertThat(store.compareAndSet(a.copy(tenantId = other), a.copy(tenantId = other, status = ApprovalStatus.CANCELLED, finishedAt = at))).isFalse()
        assertThat(store.get(tenant, a.id)!!.status).isEqualTo(ApprovalStatus.PENDING)
    }

    @Test
    fun `the inbox lists only pending approvals addressed to the user`() {
        val mine = store.create(approval(approvers = setOf(approverA)))
        store.create(approval(approvers = setOf(approverB)))
        store.create(approval(approvers = setOf(approverA), status = ApprovalStatus.CANCELLED, finishedAt = at))
        assertThat(store.pendingFor(tenant, approverA, 10).map { it.id }).containsExactly(mine.id)
    }

    @Test
    fun `the sweeper finds due approvals oldest first and never a decided one`() {
        val late = store.create(approval(expires = at.plusSeconds(20))); val early = store.create(approval(expires = at.plusSeconds(10)))
        store.create(approval(expires = at.plusSeconds(5), status = ApprovalStatus.APPROVED, finishedAt = at))
        store.create(approval(expires = at.plusSeconds(500)))
        assertThat(store.dueForExpiry(at.plusSeconds(30), 10).map { it.id }).containsExactly(early.id, late.id)
    }

    @Test
    fun `retention deletes only final approvals older than the cut-off and honours keep`() {
        val old = store.create(approval(status = ApprovalStatus.REJECTED, finishedAt = at))
        val kept = store.create(approval(status = ApprovalStatus.APPROVED, finishedAt = at))
        val fresh = store.create(approval(status = ApprovalStatus.APPROVED, finishedAt = at.plus(Duration.ofDays(2))))
        val pending = store.create(approval())
        assertThat(store.purgeFinal(at.plus(Duration.ofDays(1)), 500) { it.tenantId != tenant || it.id == kept.id }).describedAs("purge is global: every other test's rows are kept").isEqualTo(1)
        assertThat(store.get(tenant, old.id)).isNull()
        assertThat(listOf(kept, fresh, pending).map { store.get(tenant, it.id) }).allMatch { it != null }
    }

    @Test
    fun `the check constraints refuse a final approval without an end and a pending one with an end`() {
        org.junit.jupiter.api.Assertions.assertThrows(Exception::class.java) { store.create(approval(status = ApprovalStatus.APPROVED, finishedAt = null)) }
        org.junit.jupiter.api.Assertions.assertThrows(Exception::class.java) { store.create(approval(status = ApprovalStatus.PENDING, finishedAt = at)) }
    }

    @Test
    fun `two approvers racing with opposite decisions - exactly one wins, the other gets a deterministic answer`() {
        val principals = FakePrincipals(directory = setOf(requester, approverA, approverB))
        val svc = ApprovalService(json, store, principals, FakeTenants(), RecordingLogicAudit())
        val created = svc.request(Fx.ctx(tenant = tenant, userId = requester), ApprovalRequest("Release", listOf(PrincipalSpec.User(approverA, null), PrincipalSpec.User(approverB, null)), 1, Duration.ofHours(1), source = ApprovalSource(UUID.randomUUID(), "s"), appId = app))
        val approval = (created as ApprovalResult.Ok).value
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val a = pool.submit<ApprovalResult<Approval>> { start.await(); svc.decide(Fx.ctx(tenant = tenant, userId = approverA), approval.id, DecisionKind.APPROVE, null) }
            val b = pool.submit<ApprovalResult<Approval>> { start.await(); svc.decide(Fx.ctx(tenant = tenant, userId = approverB), approval.id, DecisionKind.REJECT, null) }
            start.countDown()
            val results = listOf(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))
            assertThat(results.count { it is ApprovalResult.Ok }).describedAs("exactly one decision wins").isEqualTo(1)
            val loser = results.filterIsInstance<ApprovalResult.Failed>().single()
            assertThat(loser.code).isIn("APPROVAL_ALREADY_DECIDED", "APPROVAL_CONFLICT")
            val stored = store.get(tenant, approval.id)!!
            assertThat(stored.status.terminal).isTrue(); assertThat(stored.decisions).hasSize(1)
        } finally { pool.shutdownNow() }
    }
}
