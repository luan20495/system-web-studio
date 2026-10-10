package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.approval.Approval
import com.systemwebstudio.logic.approval.ApprovalComment
import com.systemwebstudio.logic.approval.ApprovalDecision
import com.systemwebstudio.logic.approval.ApprovalSource
import com.systemwebstudio.logic.approval.ApprovalStatus
import com.systemwebstudio.logic.approval.ApprovalStore
import com.systemwebstudio.logic.approval.DecisionKind
import com.systemwebstudio.logic.arrayItems
import com.systemwebstudio.logic.limits.FairSelection
import com.systemwebstudio.wiring.persistence.JdbcSupport.instant
import com.systemwebstudio.wiring.persistence.JdbcSupport.instantOrNull
import com.systemwebstudio.wiring.persistence.JdbcSupport.ts
import com.systemwebstudio.wiring.persistence.JdbcSupport.uuid
import com.systemwebstudio.wiring.persistence.JdbcSupport.uuidOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * `approvals` (proposal `db/proposed/approvals.sql`, number to be allocated by C0): the durable twin of `InMemoryApprovalStore`, same contract.
 *
 *  - **compare-and-set**: `UPDATE .. WHERE tenant_id AND id AND version = expected.version` (+1); 0 rows = the CAS lost and nothing was written. Status, decisions and comments are
 *    one row, so a decision is never visible without its status, and two concurrent decisions cannot both win: the loser re-reads (ApprovalService retries) and sees the winner.
 *  - **create** is idempotent on (tenant, idempotency key): the same key returns the stored approval, never a second one.
 *  - every statement carries `tenant_id`: another tenant's approval is simply not found.
 */
class JdbcApprovalStore(
    private val jdbc: JdbcTemplate,
    private val json: JsonMapper,
    private val tx: TransactionOperations = RunStoreSupport.transactions(jdbc)
) : ApprovalStore {

    override fun create(approval: Approval): Approval = tx.execute {
        val inserted = jdbc.update(
            """INSERT INTO approvals (id, tenant_id, app_id, title, requested_by, requested_at, expires_at, approver_specs, approvers, required_approvals, allow_self_approval, status,
                   decisions, comments, source_run_id, source_step_id, idempotency_key, finished_at, version)
               VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS uuid[]), ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?)
               ON CONFLICT (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING""",
            approval.id, approval.tenantId, approval.appId, approval.title, approval.requestedBy, ts(approval.requestedAt), ts(approval.expiresAt),
            json.writeValueAsString(specsNode(approval.approverSpecs)), uuidArray(approval.approvers), approval.requiredApprovals, approval.allowSelfApproval, approval.status.name,
            json.writeValueAsString(decisionsNode(approval.decisions)), json.writeValueAsString(commentsNode(approval.comments)),
            approval.source.workflowRunId, approval.source.stepId, approval.idempotencyKey, approval.finishedAt?.let { ts(it) }, approval.version
        )
        if (inserted == 1) approval
        else jdbc.query("$SELECT WHERE tenant_id = ? AND idempotency_key = ?", { rs, _ -> map(rs) }, approval.tenantId, approval.idempotencyKey).firstOrNull()
            ?: throw IllegalStateException("the approval could not be created (identifier collision)")
    }!!

    override fun get(tenantId: UUID, id: UUID): Approval? = jdbc.query("$SELECT WHERE tenant_id = ? AND id = ?", { rs, _ -> map(rs) }, tenantId, id).firstOrNull()

    override fun compareAndSet(expected: Approval, next: Approval): Boolean {
        require(next.id == expected.id && next.tenantId == expected.tenantId) { "a compare-and-set never changes the identity of an approval" }
        return jdbc.update(
            """UPDATE approvals SET status = ?, decisions = CAST(? AS jsonb), comments = CAST(? AS jsonb), finished_at = ?, version = version + 1
               WHERE tenant_id = ? AND id = ? AND version = ?""",
            next.status.name, json.writeValueAsString(decisionsNode(next.decisions)), json.writeValueAsString(commentsNode(next.comments)), next.finishedAt?.let { ts(it) },
            expected.tenantId, expected.id, expected.version
        ) == 1
    }

    override fun pendingFor(tenantId: UUID, userId: UUID, limit: Int): List<Approval> =
        if (limit <= 0) emptyList()
        else jdbc.query("$SELECT WHERE tenant_id = ? AND status = 'PENDING' AND ? = ANY (approvers) ORDER BY requested_at, id LIMIT ?", { rs, _ -> map(rs) }, tenantId, userId, limit.coerceAtMost(MAX_LIST))

    override fun dueForExpiry(now: Instant, limit: Int, perTenant: Int): List<Approval> {
        if (limit <= 0 || perTenant <= 0) return emptyList()
        // oldest expiry first; the per-tenant fairness is applied on a bounded candidate set exactly like the in-memory store (FairSelection)
        val candidates = jdbc.query("$SELECT WHERE status = 'PENDING' AND expires_at <= ? ORDER BY expires_at, id LIMIT ?", { rs, _ -> map(rs) }, ts(now), (limit.toLong() * 4).coerceIn(1, MAX_LIST.toLong()).toInt())
        return FairSelection.pick(candidates, limit, perTenant) { it.tenantId }
    }

    override fun purgeFinal(olderThan: Instant, limit: Int, keep: (Approval) -> Boolean): Int {
        if (limit <= 0) return 0
        val victims = jdbc.query(
            "$SELECT WHERE status <> 'PENDING' AND COALESCE(finished_at, requested_at) < ? ORDER BY COALESCE(finished_at, requested_at), id LIMIT ?",
            { rs, _ -> map(rs) }, ts(olderThan), limit.coerceAtMost(MAX_LIST)
        ).filter { !keep(it) }
        var n = 0
        for (v in victims) n += jdbc.update("DELETE FROM approvals WHERE tenant_id = ? AND id = ? AND version = ? AND status <> 'PENDING'", v.tenantId, v.id, v.version)
        return n
    }

    // ------------------------------------------------------------------------------------------------ mapping

    private fun map(rs: ResultSet): Approval = Approval(
        id = rs.uuid("id"), tenantId = rs.uuid("tenant_id"), appId = rs.uuidOrNull("app_id"), title = rs.getString("title"), requestedBy = rs.uuid("requested_by"),
        requestedAt = rs.instant("requested_at"), expiresAt = rs.instant("expires_at"),
        approverSpecs = json.readTree(rs.getString("approver_specs")).arrayItems().map { spec(it) },
        approvers = (rs.getArray("approvers").array as Array<*>).map { it as UUID }.toCollection(linkedSetOf()),
        requiredApprovals = rs.getInt("required_approvals"), allowSelfApproval = rs.getBoolean("allow_self_approval"), status = ApprovalStatus.valueOf(rs.getString("status")),
        decisions = json.readTree(rs.getString("decisions")).arrayItems().map {
            ApprovalDecision(UUID.fromString(it.get("userId").asString()), DecisionKind.valueOf(it.get("kind").asString()), it.get("comment")?.takeUnless { c -> c.isNull }?.asString(), Instant.parse(it.get("at").asString()))
        },
        comments = json.readTree(rs.getString("comments")).arrayItems().map { ApprovalComment(UUID.fromString(it.get("userId").asString()), it.get("text").asString(), Instant.parse(it.get("at").asString())) },
        source = ApprovalSource(rs.uuidOrNull("source_run_id"), rs.getString("source_step_id")),
        idempotencyKey = rs.getString("idempotency_key"), finishedAt = rs.instantOrNull("finished_at"), version = rs.getLong("version")
    )

    private fun uuidArray(ids: Set<UUID>) = ids.joinToString(",", "{", "}")

    private fun decisionsNode(decisions: List<ApprovalDecision>): JsonNode = json.createArrayNode().also { a ->
        decisions.forEach { d -> a.addObject().also { o -> o.put("userId", d.userId.toString()); o.put("kind", d.kind.name); if (d.comment != null) o.put("comment", d.comment); o.put("at", d.at.toString()) } }
    }

    private fun commentsNode(comments: List<ApprovalComment>): JsonNode = json.createArrayNode().also { a ->
        comments.forEach { c -> a.addObject().also { o -> o.put("userId", c.userId.toString()); o.put("text", c.text); o.put("at", c.at.toString()) } }
    }

    private fun specsNode(specs: List<PrincipalSpec>): JsonNode = json.createArrayNode().also { a ->
        specs.forEach { p ->
            val o = a.addObject()
            when (p) {
                is PrincipalSpec.User -> { o.put("k", "user"); o.put("userId", p.userId.toString()); p.tenantId?.let { o.put("tenantId", it.toString()) } }
                is PrincipalSpec.Group -> { o.put("k", "group"); o.put("groupId", p.groupId) }
                is PrincipalSpec.DepartmentManager -> { o.put("k", "departmentManager"); p.departmentId?.let { o.put("departmentId", it) } }
                is PrincipalSpec.Role -> { o.put("k", "role"); o.put("role", p.role) }
            }
        }
    }

    private fun spec(n: JsonNode): PrincipalSpec = when (val k = n.get("k").asString()) {
        "user" -> PrincipalSpec.User(UUID.fromString(n.get("userId").asString()), n.get("tenantId")?.takeUnless { it.isNull }?.asString()?.let { UUID.fromString(it) })
        "group" -> PrincipalSpec.Group(n.get("groupId").asString())
        "departmentManager" -> PrincipalSpec.DepartmentManager(n.get("departmentId")?.takeUnless { it.isNull }?.asString())
        "role" -> PrincipalSpec.Role(n.get("role").asString())
        else -> throw IllegalStateException("unknown stored principal kind: $k")
    }

    private companion object {
        const val MAX_LIST = 500
        const val SELECT = """SELECT id, tenant_id, app_id, title, requested_by, requested_at, expires_at, approver_specs::text AS approver_specs, approvers, required_approvals, allow_self_approval,
               status, decisions::text AS decisions, comments::text AS comments, source_run_id, source_step_id, idempotency_key, finished_at, version FROM approvals"""
    }
}
