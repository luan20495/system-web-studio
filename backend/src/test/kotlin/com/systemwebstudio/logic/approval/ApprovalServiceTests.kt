package com.systemwebstudio.logic.approval

import com.systemwebstudio.logic.action.AuditDomains
import com.systemwebstudio.logic.action.FakeNotifyPort
import com.systemwebstudio.logic.action.FakePrincipals
import com.systemwebstudio.logic.action.FakeTenants
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.action.RecordingLogicAudit
import com.systemwebstudio.logic.action.TestClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class ApprovalServiceTests {
    private val u3: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
    private val u4: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a4")
    private val clock = TestClock()
    private val tenants = FakeTenants()
    private val audit = RecordingLogicAudit()
    private val notify = FakeNotifyPort()
    private val finals = CopyOnWriteArrayList<Approval>()
    private val store = InMemoryApprovalStore()
    private val principals = FakePrincipals(
        groups = mapOf("finance" to setOf(Fx.user2, u3, u4)), roles = mapOf("approvers" to setOf(Fx.user2)), managers = mapOf("own" to u3),
        directory = setOf(Fx.user, Fx.user2, u3, u4)
    )
    private fun service(policy: CrossTenantApprovalPolicy = CrossTenantApprovalPolicy.DENY_ALL, listener: ApprovalListener? = ApprovalListener { finals += it }, withNotify: Boolean = true) =
        ApprovalService(Fx.json, store, principals, tenants, audit, if (withNotify) notify else null, listener, policy, clock)
    private val svc = service()

    private fun requester() = Fx.ctx(userId = Fx.user)
    private fun by(u: UUID, tenant: UUID = Fx.tenantA) = Fx.ctx(tenant = tenant, userId = u)
    private fun ok(r: ApprovalResult<Approval>): Approval {
        assertInstanceOf(ApprovalResult.Ok::class.java, r)
        return (r as ApprovalResult.Ok<Approval>).value
    }
    private fun code(r: ApprovalResult<*>) = assertInstanceOf(ApprovalResult.Failed::class.java, r).code

    private fun ask(approvers: List<PrincipalSpec> = listOf(PrincipalSpec.User(Fx.user2, null)), required: Int = 1, self: Boolean = false, key: String? = null, notifyRef: String? = null,
                    expires: Duration = Duration.ofHours(1), source: ApprovalSource = ApprovalSource()) =
        ok(svc.request(requester(), ApprovalRequest("Release", approvers, required, expires, self, notifyRef, source, key)))

    // ---- request ---------------------------------------------------------------------------------------------------

    @Test fun `a request snapshots its approvers from every principal kind`() {
        val a = ask(listOf(PrincipalSpec.User(Fx.user2, null), PrincipalSpec.Group("finance"), PrincipalSpec.Role("approvers"), PrincipalSpec.DepartmentManager(null)))
        assertEquals(setOf(Fx.user2, u3, u4), a.approvers)
        assertEquals(ApprovalStatus.PENDING, a.status)
        assertEquals(Fx.user, a.requestedBy)
        assertEquals(clock.instant().plus(Duration.ofHours(1)), a.expiresAt)
    }

    @Test fun `the requester is removed from the approvers unless self approval is allowed`() {
        val a = ask(listOf(PrincipalSpec.User(Fx.user, null), PrincipalSpec.User(Fx.user2, null)))
        assertEquals(setOf(Fx.user2), a.approvers)
        val s = ask(listOf(PrincipalSpec.User(Fx.user, null)), self = true)
        assertEquals(setOf(Fx.user), s.approvers)
        assertEquals(ApprovalStatus.APPROVED, ok(svc.decide(requester(), s.id, DecisionKind.APPROVE, null)).status)
    }

    @Test fun `a request is validated`() {
        fun req(title: String = "t", approvers: List<PrincipalSpec> = listOf(PrincipalSpec.User(Fx.user2, null)), required: Int = 1, expires: Duration = Duration.ofHours(1)) =
            svc.request(requester(), ApprovalRequest(title, approvers, required, expires))
        assertEquals(ApprovalErrorCodes.INVALID, code(req(title = " ")))
        assertEquals(ApprovalErrorCodes.INVALID, code(req(title = "x".repeat(201))))
        assertEquals(ApprovalErrorCodes.INVALID, code(req(approvers = emptyList())))
        assertEquals(ApprovalErrorCodes.INVALID, code(req(required = 0)))
        assertEquals(ApprovalErrorCodes.INVALID, code(req(required = 2)))                      // only one eligible approver
        assertEquals(ApprovalErrorCodes.INVALID, code(req(expires = Duration.ofSeconds(5))))
        assertEquals(ApprovalErrorCodes.INVALID, code(req(expires = Duration.ofDays(400))))
    }

    @Test fun `an approver that cannot be addressed, such as one in another tenant, is refused and audited`() {
        val r = svc.request(requester(), ApprovalRequest("t", listOf(PrincipalSpec.User(UUID.randomUUID(), Fx.tenantB)), 1, Duration.ofHours(1)))
        assertEquals(ApprovalErrorCodes.FORBIDDEN, code(r))
        assertTrue(audit.events(AuditDomains.APPROVAL).contains("REQUEST_DENIED"))
        assertEquals(ApprovalErrorCodes.FORBIDDEN, code(svc.request(requester(), ApprovalRequest("t", listOf(PrincipalSpec.Group("ghost")), 1, Duration.ofHours(1)))))
    }

    @Test fun `request is idempotent on its key and notifies only once`() {
        val a = ask(key = "wf:1:step", notifyRef = "approval.requested")
        val b = ask(key = "wf:1:step", notifyRef = "approval.requested")
        assertEquals(a.id, b.id)
        assertEquals(1, notify.sent.size)
        assertEquals(listOf(PrincipalSpec.User(Fx.user2)), notify.sent.single().recipients)
    }

    @Test fun `a disabled tenant cannot request or decide`() {
        val a = ask()
        tenants.disabled = setOf(Fx.tenantA)
        assertEquals(ApprovalErrorCodes.TENANT_DISABLED, code(svc.request(requester(), ApprovalRequest("t", listOf(PrincipalSpec.User(Fx.user2, null))))))
        assertEquals(ApprovalErrorCodes.TENANT_DISABLED, code(svc.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, null)))
        tenants.disabled = emptySet(); tenants.throwing = true
        assertEquals(ApprovalErrorCodes.TENANT_DISABLED, code(svc.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, null)))   // unknown state fails closed
    }

    // ---- decisions -------------------------------------------------------------------------------------------------

    @Test fun `one approval is enough when one is required, and the listener hears about it`() {
        val a = ask()
        val d = ok(svc.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, " fine "))
        assertEquals(ApprovalStatus.APPROVED, d.status)
        assertEquals("fine", d.decisions.single().comment)
        assertEquals(listOf(ApprovalStatus.APPROVED), finals.map { it.status })
        assertEquals("approval.approved", notify.sent.last().templateRef)
        assertEquals(listOf(PrincipalSpec.User(Fx.user)), notify.sent.last().recipients)
    }

    @Test fun `a quorum needs the required number of distinct approvers`() {
        val a = ask(listOf(PrincipalSpec.Group("finance")), required = 2)
        assertEquals(ApprovalStatus.PENDING, ok(svc.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, null)).status)
        assertEquals(ApprovalStatus.PENDING, ok(svc.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, null)).status)     // same user twice counts once
        assertTrue(finals.isEmpty())
        assertEquals(ApprovalStatus.APPROVED, ok(svc.decide(by(u3), a.id, DecisionKind.APPROVE, null)).status)
        assertEquals(1, finals.size)
    }

    @Test fun `any rejection rejects the request`() {
        val a = ask(listOf(PrincipalSpec.Group("finance")), required = 2)
        ok(svc.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, null))
        assertEquals(ApprovalStatus.REJECTED, ok(svc.decide(by(u3), a.id, DecisionKind.REJECT, "no")).status)
        assertEquals(ApprovalErrorCodes.ALREADY_DECIDED, code(svc.decide(by(u4), a.id, DecisionKind.APPROVE, null)))
    }

    @Test fun `changing your mind is a conflict and repeating yourself is harmless`() {
        val a = ask(listOf(PrincipalSpec.Group("finance")), required = 2)
        ok(svc.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, null))
        assertEquals(ApprovalErrorCodes.CONFLICT, code(svc.decide(by(Fx.user2), a.id, DecisionKind.REJECT, null)))
        assertEquals(ApprovalStatus.PENDING, ok(svc.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, null)).status)
    }

    @Test fun `only snapshotted approvers decide and the requester cannot decide their own request`() {
        val a = ask(listOf(PrincipalSpec.User(Fx.user2, null)))
        assertEquals(ApprovalErrorCodes.FORBIDDEN, code(svc.decide(by(UUID.randomUUID()), a.id, DecisionKind.APPROVE, null)))
        assertEquals(ApprovalErrorCodes.FORBIDDEN, code(svc.decide(by(Fx.user), a.id, DecisionKind.APPROVE, null)))
        assertTrue(audit.events(AuditDomains.APPROVAL).count { it == "DECISION_DENIED" } == 2)
    }

    @Test fun `later changes to a group do not change a pending request`() {
        val a = ask(listOf(PrincipalSpec.Group("finance")))
        val newcomer = UUID.randomUUID()
        // the group now has a different membership; the pending request keeps the approvers it was created with
        val changed = ApprovalService(Fx.json, store, FakePrincipals(groups = mapOf("finance" to setOf(newcomer)), directory = setOf(newcomer)), tenants, audit, null, null, CrossTenantApprovalPolicy.DENY_ALL, clock)
        assertEquals(ApprovalErrorCodes.FORBIDDEN, code(changed.decide(by(newcomer), a.id, DecisionKind.APPROVE, null)))
        assertEquals(ApprovalStatus.APPROVED, ok(changed.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, null)).status)
    }

    @Test fun `comments are limited`() {
        val a = ask()
        assertEquals(ApprovalErrorCodes.INVALID, code(svc.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, "x".repeat(1001))))
        assertEquals(ApprovalStatus.PENDING, svc.get(by(Fx.user2), a.id).let { ok(it).status })
    }

    // ---- expiry / cancel ---------------------------------------------------------------------------------------------

    @Test fun `a late decision cannot revive an expired request`() {
        val a = ask()
        clock.advance(Duration.ofHours(2))
        assertEquals(ApprovalErrorCodes.ALREADY_DECIDED, code(svc.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, null)))
        assertEquals(ApprovalStatus.EXPIRED, store.get(Fx.tenantA, a.id)!!.status)
        assertEquals(listOf(ApprovalStatus.EXPIRED), finals.map { it.status })
    }

    @Test fun `the sweeper expires every due request once`() {
        ask(); ask(expires = Duration.ofDays(1))
        clock.advance(Duration.ofHours(2))
        assertEquals(1, svc.expireDue())
        assertEquals(0, svc.expireDue())
        assertTrue(audit.events(AuditDomains.APPROVAL).contains("EXPIRED"))
    }

    @Test fun `cancel is idempotent and a cancelled request cannot be decided`() {
        val a = ask()
        assertEquals(ApprovalStatus.CANCELLED, ok(svc.cancel(requester(), a.id)).status)
        assertEquals(ApprovalStatus.CANCELLED, ok(svc.cancel(requester(), a.id)).status)
        assertEquals(ApprovalErrorCodes.ALREADY_DECIDED, code(svc.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, null)))
        assertEquals(ApprovalErrorCodes.NOT_FOUND, code(svc.cancel(by(Fx.user, Fx.tenantB), a.id)))
    }

    // ---- visibility / comments / inbox ------------------------------------------------------------------------------

    @Test fun `only the requester and approvers can read or comment`() {
        val a = ask()
        assertInstanceOf(ApprovalResult.Ok::class.java, svc.get(requester(), a.id))
        assertInstanceOf(ApprovalResult.Ok::class.java, svc.get(by(Fx.user2), a.id))
        assertEquals(ApprovalErrorCodes.NOT_FOUND, code(svc.get(by(u3), a.id)))
        assertEquals(ApprovalErrorCodes.FORBIDDEN, code(svc.comment(by(u3), a.id, "hi")))
        assertEquals("looks fine", ok(svc.comment(by(Fx.user2), a.id, " looks fine ")).comments.single().text)
        assertEquals(ApprovalErrorCodes.INVALID, code(svc.comment(by(Fx.user2), a.id, " ")))
    }

    @Test fun `the inbox lists only pending requests addressed to the user in their own tenant`() {
        val a = ask(); ask(listOf(PrincipalSpec.User(u3, null)))
        assertEquals(listOf(a.id), svc.inbox(by(Fx.user2)).map { it.id })
        assertTrue(svc.inbox(by(Fx.user2, Fx.tenantB)).isEmpty())
        ok(svc.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, null))
        assertTrue(svc.inbox(by(Fx.user2)).isEmpty())
    }

    // ---- cross tenant ------------------------------------------------------------------------------------------------

    @Test fun `deciding an approval of another tenant is denied by default and looks like not found`() {
        val a = ask()
        val foreign = by(Fx.user2, Fx.tenantB)
        assertEquals(ApprovalErrorCodes.NOT_FOUND, code(svc.decide(foreign, a.id, DecisionKind.APPROVE, null)))
        assertEquals(ApprovalErrorCodes.NOT_FOUND, code(svc.decide(foreign, a.id, DecisionKind.APPROVE, null, approvalTenantId = Fx.tenantA)))
        assertEquals(ApprovalStatus.PENDING, store.get(Fx.tenantA, a.id)!!.status)
        assertTrue(audit.records.any { it.event == "DECISION_DENIED" && it.tenantId == Fx.tenantB })
    }

    @Test fun `cross tenant decisions need C1's policy and still need to be an approver`() {
        val a = ask(listOf(PrincipalSpec.User(Fx.user2, null)))
        val permissive = service(CrossTenantApprovalPolicy { _, t -> t == Fx.tenantA })
        val foreign = by(Fx.user2, Fx.tenantB)
        assertEquals(ApprovalStatus.APPROVED, ok(permissive.decide(foreign, a.id, DecisionKind.APPROVE, null, approvalTenantId = Fx.tenantA)).status)
        val b = ask(listOf(PrincipalSpec.User(Fx.user2, null)))
        assertEquals(ApprovalErrorCodes.FORBIDDEN, code(permissive.decide(by(u3, Fx.tenantB), b.id, DecisionKind.APPROVE, null, approvalTenantId = Fx.tenantA)))
    }

    // ---- resilience --------------------------------------------------------------------------------------------------

    @Test fun `a failing listener, notifier or audit never undoes a decision`() {
        val s = ApprovalService(Fx.json, store, principals, tenants, audit, notify, ApprovalListener { error("listener down") }, CrossTenantApprovalPolicy.DENY_ALL, clock)
        notify.outcome = com.systemwebstudio.logic.action.PortOutcome.Failure("DOWN", true)
        audit.failAll = true
        val a = ok(s.request(requester(), ApprovalRequest("t", listOf(PrincipalSpec.User(Fx.user2, null)), 1, Duration.ofHours(1), notifyTemplateRef = "approval.requested")))
        assertEquals(ApprovalStatus.APPROVED, ok(s.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, null)).status)
        assertEquals(ApprovalStatus.APPROVED, store.get(Fx.tenantA, a.id)!!.status)
        assertFalse(finals.isNotEmpty())
    }

    @Test fun `the workflow source travels with the approval for audit and resume`() {
        val run = UUID.randomUUID()
        val a = ask(source = ApprovalSource(run, "step1"))
        ok(svc.decide(by(Fx.user2), a.id, DecisionKind.APPROVE, null))
        assertEquals(run, finals.single().source.workflowRunId)
        assertTrue(audit.records.any { it.runId == run.toString() })
    }
}
