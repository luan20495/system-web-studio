package com.systemwebstudio.member

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

data class MemberDto(val userId: UUID, val username: String, val displayName: String?, val email: String?, val role: String, val joinedAt: Instant)
data class AddMemberRequest(@field:Size(max = 120) val username: String? = null, @field:Size(max = 254) val email: String? = null, @field:NotBlank val role: String)
data class ChangeRoleRequest(@field:NotBlank val role: String)

private val WORKSPACE_ROLES = setOf("WORKSPACE_ADMIN", "EDITOR", "PUBLISHER", "VIEWER")
private val PROJECT_ROLES = setOf("OWNER", "EDITOR", "PUBLISHER", "VIEWER")

/**
 * Membership management. Rules enforced here (and covered by tests):
 *  - only holders of MEMBER_MANAGE (workspace admin / system admin) manage workspace members; only PROJECT_MEMBERS holders
 *    (project owner, workspace admin, system admin) manage project members; viewer/editor/publisher can do neither;
 *  - nobody grants themselves anything: adding yourself to a workspace/project and changing your own role are rejected for EVERY caller
 *    (including a SYSTEM_ADMIN holding MEMBER_MANAGE on the platform scope) with 403 SELF_GRANT_FORBIDDEN; leaving is allowed;
 *  - a workspace never loses its last WORKSPACE_ADMIN and a project never loses its last OWNER (rows are locked while counting);
 *  - the system-admin flag is not reachable through this API;
 *  - project members must already be active workspace members; removing a workspace member also removes their project access;
 *  - every change is written to the audit log in the same transaction.
 */
@RestController
class MemberController(private val access: AccessService, private val jdbc: JdbcTemplate, private val audit: AuditService) {

    /**
     * Resolves the person to add. When [tenantId] is given (every workspace route) the person must be an ACTIVE member of THAT tenant, otherwise the answer is the same
     * `404 USER_NOT_FOUND` as for a name nobody has: a workspace administrator cannot probe or pull in accounts of other tenants (no global directory, F-5).
     * Only an eligible account can be reported as disabled.
     */
    private fun resolveUser(username: String?, email: String?, tenantId: UUID? = null, selfId: UUID? = null): Triple<UUID, String, String?> {
        val u = username?.trim()?.takeIf { it.isNotEmpty() }
        val e = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if ((u == null) == (e == null)) throw ApiException.badRequest("VALIDATION_FAILED", "Provide exactly one of username or email")
        val rows = if (u != null) jdbc.queryForList("SELECT id, username, enabled FROM users WHERE username = ?", u)
        else jdbc.queryForList("SELECT id, username, enabled FROM users WHERE lower(email) = ?", e)
        val notFound = ApiException.notFound("USER_NOT_FOUND", "No such user in this organisation. The person must be created by an administrator of the tenant first.")
        val row = rows.firstOrNull() ?: throw notFound
        // yourself is never a stranger (and not an oracle): the self-grant rule answers that case (403 SELF_GRANT_FORBIDDEN), not the tenant filter
        if (tenantId != null && row["id"] != selfId && jdbc.queryForObject("SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND user_id = ? AND active", Long::class.java, tenantId, row["id"])!! == 0L) throw notFound
        if (row["enabled"] != true) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "USER_DISABLED", "That account is disabled")
        return Triple(row["id"] as UUID, row["username"] as String, e)
    }


    /** No principal may create or raise its own membership: someone else must grant it (separation of duties). */
    private fun rejectSelfGrant(me: StudioUserDetails, target: UUID) {
        if (target == me.userId) throw ApiException.forbidden("You cannot grant yourself access or change your own role", "SELF_GRANT_FORBIDDEN")
    }

    private val memberSql = """SELECT u.id, u.username, u.display_name, u.email, m.role, m.created_at FROM %s m JOIN users u ON u.id = m.user_id WHERE %s AND m.active ORDER BY u.username"""
    private fun mapper(rs: java.sql.ResultSet) = MemberDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant())

    // ---------------------------------------------------------------- workspace
    @GetMapping("/api/v1/workspaces/{w}/members")
    @Transactional(readOnly = true)
    fun listWorkspace(@PathVariable w: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<MemberDto> {
        access.forWorkspace(me.userId, w).require(Permission.MEMBER_MANAGE)
        return jdbc.query(memberSql.format("workspace_members", "m.workspace_id = ?"), { rs, _ -> mapper(rs) }, w)
    }

    @PostMapping("/api/v1/workspaces/{w}/members")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    fun addWorkspace(@PathVariable w: UUID, @Valid @RequestBody body: AddMemberRequest, @AuthenticationPrincipal me: StudioUserDetails): MemberDto {
        val wctx = access.forWorkspace(me.userId, w)
        wctx.require(Permission.MEMBER_MANAGE)
        if (body.role !in WORKSPACE_ROLES) throw ApiException.badRequest("INVALID_ROLE", "Role must be one of $WORKSPACE_ROLES")
        val (userId, _, _) = resolveUser(body.username, body.email, wctx.tenantId, me.userId)
        rejectSelfGrant(me, userId)
        val existing = jdbc.queryForList("SELECT active, role FROM workspace_members WHERE workspace_id = ? AND user_id = ? FOR UPDATE", w, userId).firstOrNull()
        if (existing?.get("active") == true) throw ApiException.conflict("ALREADY_MEMBER", "User is already a member of this workspace")
        if (existing == null) jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role, active) VALUES (?,?,?,TRUE)", w, userId, body.role)
        else jdbc.update("UPDATE workspace_members SET active = TRUE, role = ? WHERE workspace_id = ? AND user_id = ?", body.role, w, userId)
        audit.record("ADD_MEMBER", "WORKSPACE_MEMBER", userId, w, null, newValue = mapOf("role" to body.role, "userId" to userId))
        return jdbc.query(memberSql.format("workspace_members", "m.workspace_id = ? AND m.user_id = ?"), { rs, _ -> mapper(rs) }, w, userId).first()
    }

    private fun lockWorkspaceAdmins(w: UUID): List<UUID> =
        jdbc.query("SELECT user_id FROM workspace_members WHERE workspace_id = ? AND role = 'WORKSPACE_ADMIN' AND active FOR UPDATE", { rs, _ -> rs.getObject(1, UUID::class.java) }, w)

    private fun workspaceMember(w: UUID, userId: UUID): Map<String, Any?> =
        jdbc.queryForList("SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ? AND active FOR UPDATE", w, userId).firstOrNull()
            ?: throw ApiException.notFound("MEMBER_NOT_FOUND", "Member not found")

    @PatchMapping("/api/v1/workspaces/{w}/members/{userId}")
    @Transactional
    fun changeWorkspace(@PathVariable w: UUID, @PathVariable userId: UUID, @Valid @RequestBody body: ChangeRoleRequest, @AuthenticationPrincipal me: StudioUserDetails): MemberDto {
        access.forWorkspace(me.userId, w).require(Permission.MEMBER_MANAGE)
        if (body.role !in WORKSPACE_ROLES) throw ApiException.badRequest("INVALID_ROLE", "Role must be one of $WORKSPACE_ROLES")
        rejectSelfGrant(me, userId)
        val admins = lockWorkspaceAdmins(w)
        val old = workspaceMember(w, userId)["role"] as String
        if (old == body.role) return jdbc.query(memberSql.format("workspace_members", "m.workspace_id = ? AND m.user_id = ?"), { rs, _ -> mapper(rs) }, w, userId).first()
        if (old == "WORKSPACE_ADMIN" && admins.size <= 1) throw ApiException.conflict("LAST_ADMIN", "A workspace must keep at least one WORKSPACE_ADMIN")
        jdbc.update("UPDATE workspace_members SET role = ? WHERE workspace_id = ? AND user_id = ?", body.role, w, userId)
        audit.record("CHANGE_PERMISSION", "WORKSPACE_MEMBER", userId, w, null, oldValue = mapOf("role" to old), newValue = mapOf("role" to body.role))
        return jdbc.query(memberSql.format("workspace_members", "m.workspace_id = ? AND m.user_id = ?"), { rs, _ -> mapper(rs) }, w, userId).first()
    }

    @DeleteMapping("/api/v1/workspaces/{w}/members/{userId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeWorkspace(@PathVariable w: UUID, @PathVariable userId: UUID, @AuthenticationPrincipal me: StudioUserDetails) {
        access.forWorkspace(me.userId, w).require(Permission.MEMBER_MANAGE)
        val admins = lockWorkspaceAdmins(w)
        val old = workspaceMember(w, userId)["role"] as String
        if (old == "WORKSPACE_ADMIN" && admins.size <= 1) throw ApiException.conflict("LAST_ADMIN", "A workspace must keep at least one WORKSPACE_ADMIN")
        jdbc.update("UPDATE workspace_members SET active = FALSE WHERE workspace_id = ? AND user_id = ?", w, userId)
        val projects = jdbc.update("UPDATE project_members SET active = FALSE WHERE workspace_id = ? AND user_id = ? AND active", w, userId)
        audit.record("REMOVE_MEMBER", "WORKSPACE_MEMBER", userId, w, null, oldValue = mapOf("role" to old, "projectMembershipsRemoved" to projects))
    }

    // ---------------------------------------------------------------- project
    @GetMapping("/api/v1/workspaces/{w}/projects/{p}/members")
    @Transactional(readOnly = true)
    fun listProject(@PathVariable w: UUID, @PathVariable p: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<MemberDto> {
        access.forProject(me.userId, w, p).require(Permission.PROJECT_MEMBERS)
        return jdbc.query(memberSql.format("project_members", "m.project_id = ? AND m.workspace_id = ?"), { rs, _ -> mapper(rs) }, p, w)
    }

    @PostMapping("/api/v1/workspaces/{w}/projects/{p}/members")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    fun addProject(@PathVariable w: UUID, @PathVariable p: UUID, @Valid @RequestBody body: AddMemberRequest, @AuthenticationPrincipal me: StudioUserDetails): MemberDto {
        val pctx = access.forProject(me.userId, w, p)
        pctx.require(Permission.PROJECT_MEMBERS)
        if (body.role !in PROJECT_ROLES) throw ApiException.badRequest("INVALID_ROLE", "Role must be one of $PROJECT_ROLES")
        val (userId, _, _) = resolveUser(body.username, body.email, pctx.tenantId, me.userId)
        rejectSelfGrant(me, userId)
        val inWorkspace = jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND user_id = ? AND active", Long::class.java, w, userId)!!
        if (inWorkspace == 0L) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_WORKSPACE_MEMBER", "Add the user to the workspace first")
        val existing = jdbc.queryForList("SELECT active FROM project_members WHERE project_id = ? AND user_id = ? FOR UPDATE", p, userId).firstOrNull()
        if (existing?.get("active") == true) throw ApiException.conflict("ALREADY_MEMBER", "User is already a member of this project")
        if (existing == null) jdbc.update("INSERT INTO project_members (workspace_id, project_id, user_id, role, active) VALUES (?,?,?,?,TRUE)", w, p, userId, body.role)
        else jdbc.update("UPDATE project_members SET active = TRUE, role = ? WHERE project_id = ? AND user_id = ?", body.role, p, userId)
        audit.record("ADD_MEMBER", "PROJECT_MEMBER", userId, w, p, newValue = mapOf("role" to body.role))
        return jdbc.query(memberSql.format("project_members", "m.project_id = ? AND m.user_id = ?"), { rs, _ -> mapper(rs) }, p, userId).first()
    }

    private fun lockProjectOwners(p: UUID): List<UUID> =
        jdbc.query("SELECT user_id FROM project_members WHERE project_id = ? AND role = 'OWNER' AND active FOR UPDATE", { rs, _ -> rs.getObject(1, UUID::class.java) }, p)

    private fun projectMember(p: UUID, userId: UUID): String =
        jdbc.queryForList("SELECT role FROM project_members WHERE project_id = ? AND user_id = ? AND active FOR UPDATE", p, userId).firstOrNull()?.get("role") as String?
            ?: throw ApiException.notFound("MEMBER_NOT_FOUND", "Member not found")

    @PatchMapping("/api/v1/workspaces/{w}/projects/{p}/members/{userId}")
    @Transactional
    fun changeProject(@PathVariable w: UUID, @PathVariable p: UUID, @PathVariable userId: UUID, @Valid @RequestBody body: ChangeRoleRequest, @AuthenticationPrincipal me: StudioUserDetails): MemberDto {
        access.forProject(me.userId, w, p).require(Permission.PROJECT_MEMBERS)
        if (body.role !in PROJECT_ROLES) throw ApiException.badRequest("INVALID_ROLE", "Role must be one of $PROJECT_ROLES")
        rejectSelfGrant(me, userId)
        val owners = lockProjectOwners(p)
        val old = projectMember(p, userId)
        if (old != body.role) {
            if (old == "OWNER" && owners.size <= 1) throw ApiException.conflict("LAST_OWNER", "A project must keep at least one OWNER")
            jdbc.update("UPDATE project_members SET role = ? WHERE project_id = ? AND user_id = ?", body.role, p, userId)
            audit.record("CHANGE_PERMISSION", "PROJECT_MEMBER", userId, w, p, oldValue = mapOf("role" to old), newValue = mapOf("role" to body.role))
        }
        return jdbc.query(memberSql.format("project_members", "m.project_id = ? AND m.user_id = ?"), { rs, _ -> mapper(rs) }, p, userId).first()
    }

    @DeleteMapping("/api/v1/workspaces/{w}/projects/{p}/members/{userId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeProject(@PathVariable w: UUID, @PathVariable p: UUID, @PathVariable userId: UUID, @AuthenticationPrincipal me: StudioUserDetails) {
        access.forProject(me.userId, w, p).require(Permission.PROJECT_MEMBERS)
        val owners = lockProjectOwners(p)
        val old = projectMember(p, userId)
        if (old == "OWNER" && owners.size <= 1) throw ApiException.conflict("LAST_OWNER", "A project must keep at least one OWNER")
        jdbc.update("UPDATE project_members SET active = FALSE WHERE project_id = ? AND user_id = ?", p, userId)
        audit.record("REMOVE_MEMBER", "PROJECT_MEMBER", userId, w, p, oldValue = mapOf("role" to old))
    }
}
