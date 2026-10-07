package com.systemwebstudio.identity

import com.systemwebstudio.admin.AdminGuard
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.common.RateLimiter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

object PasswordPolicy {
    /** same rules as sign-up, minimum 8 characters for accounts activated by link */
    fun check(username: String, password: String) {
        if (password.length < 8) throw ApiException.badRequest("WEAK_PASSWORD", "Password must be at least 8 characters")
        if (password.length > 200) throw ApiException.badRequest("WEAK_PASSWORD", "Password is too long")
        if (password.lowercase().contains(username.lowercase())) throw ApiException.badRequest("WEAK_PASSWORD", "Password must not contain the username")
        if (!password.any { it.isLetter() } || !password.any { it.isDigit() }) throw ApiException.badRequest("WEAK_PASSWORD", "Password must contain both letters and digits")
        if (password.toSet().size < 4) throw ApiException.badRequest("WEAK_PASSWORD", "Password is too repetitive")
    }
}

data class CreateUserRequest(
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{2,39}$") val username: String,
    @field:NotBlank @field:Size(max = 160) val displayName: String,
    @field:Size(max = 254) val email: String? = null,
    @field:NotNull val workspaceId: UUID?,
    @field:NotBlank @field:Pattern(regexp = "WORKSPACE_ADMIN|EDITOR|PUBLISHER|VIEWER") val role: String
)
data class CreateWorkspaceRequest(@field:NotBlank @field:Size(max = 160) val name: String)
data class SystemAdminRequest(@field:NotNull val grant: Boolean?, val confirm: Boolean? = null)
data class ActivationLink(val userId: UUID, val username: String, val displayName: String, val purpose: String, val token: String, val expiresAt: Instant)
data class InspectRequest(@field:NotBlank @field:Size(max = 200) val token: String)
data class CompleteRequest(@field:NotBlank @field:Size(max = 200) val token: String, @field:NotBlank @field:Size(max = 200) val password: String)

/**
 * Account lifecycle for the web flow (core product): an admin creates the account, the platform makes a ONE-TIME link (random token, only its hash is
 * stored, 24 h, single use), the employee opens it and chooses a password. The same mechanism resets passwords. The first system administrator is an
 * operator action (bootstrap); promoting someone to system administrator is a separate, confirmed action and never self-service.
 */
@Service
class AccountService(
    private val jdbc: JdbcTemplate, private val audit: AuditService, private val encoder: PasswordEncoder, private val hashGate: PasswordHashGate,
    private val sessions: FindByIndexNameSessionRepository<*>
) {
    private val random = SecureRandom()
    private val usernamePattern = Regex("^[a-z0-9][a-z0-9._-]{2,39}$")
    companion object { val TTL: Duration = Duration.ofHours(24); fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }

    private fun newToken(userId: UUID, purpose: String, by: UUID?): Pair<String, Instant> {
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val expires = Instant.now().plus(TTL)
        // a new link replaces every earlier unused link of that user
        jdbc.update("DELETE FROM account_tokens WHERE user_id = ? AND used_at IS NULL", userId)
        jdbc.update("INSERT INTO account_tokens (id, user_id, purpose, token_hash, expires_at, created_by) VALUES (?,?,?,?,?,?)", UUID.randomUUID(), userId, purpose, sha256(token), java.sql.Timestamp.from(expires), by)
        return token to expires
    }

    @Transactional
    fun create(adminId: UUID, r: CreateUserRequest): ActivationLink {
        val username = r.username.trim().lowercase()
        if (!usernamePattern.matches(username) || username.startsWith("oidc-")) throw ApiException.badRequest("INVALID_USERNAME", "Tên đăng nhập 3–40 ký tự: chữ thường, số, dấu . _ -")
        val email = r.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (email != null && !Regex("^[^\\s@<>\"]{1,64}@[^\\s@<>\"]{1,190}\\.[A-Za-z]{2,24}$").matches(email)) throw ApiException.badRequest("INVALID_EMAIL", "Email không hợp lệ")
        if (email != null && jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = ?", Long::class.java, email)!! > 0) throw ApiException.conflict("EMAIL_TAKEN", "Email này đã được dùng")
        if (jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE id = ?", Long::class.java, r.workspaceId)!! == 0L) throw ApiException.notFound("WORKSPACE_NOT_FOUND", "Không tìm thấy không gian làm việc")
        val id = UUID.randomUUID()
        val unusable = requireNotNull(encoder.encode(Base64.getEncoder().encodeToString(ByteArray(48).also(random::nextBytes))))   // nobody knows it: login is impossible until activation
        val n = jdbc.update("INSERT INTO users (id, username, password_hash, enabled, display_name, email, auth_source, activated_at) VALUES (?,?,?,TRUE,?,?, 'LOCAL', NULL) ON CONFLICT (username) DO NOTHING",
            id, username, unusable, r.displayName.trim(), email)
        if (n == 0) throw ApiException.conflict("USERNAME_TAKEN", "Tên đăng nhập này đã tồn tại")
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role, active) VALUES (?,?,?,TRUE)", r.workspaceId, id, r.role)
        val (token, exp) = newToken(id, "ACTIVATION", adminId)
        audit.record("USER_CREATED", "USER", id, r.workspaceId, actorId = adminId, newValue = mapOf("username" to username, "role" to r.role))
        audit.record("ACTIVATION_LINK_CREATED", "USER", id, actorId = adminId, newValue = mapOf("expiresAt" to exp.toString()))
        return ActivationLink(id, username, r.displayName.trim(), "ACTIVATION", token, exp)
    }


    /**
     * Tenant-scoped provisioning used by TENANT_ADMIN / SYSTEM_ADMIN after the caller has passed TENANT_MEMBERS authorization.
     * Creates a pending LOCAL account, tenant membership and optional workspace membership atomically, then returns the existing
     * one-time activation link. No password is accepted, stored or logged here.
     */
    @Transactional
    fun createTenantUser(
        adminId: UUID,
        tenantId: UUID,
        usernameInput: String,
        displayNameInput: String,
        emailInput: String?,
        tenantRole: String,
        workspaceId: UUID?,
        workspaceRole: String?
    ): ActivationLink {
        val username = usernameInput.trim().lowercase()
        if (!usernamePattern.matches(username) || username.startsWith("oidc-"))
            throw ApiException.badRequest("INVALID_USERNAME", "Tên đăng nhập 3–40 ký tự: chữ thường, số, dấu . _ -")
        val displayName = displayNameInput.trim()
        if (displayName.isBlank() || displayName.length > 160)
            throw ApiException.badRequest("VALIDATION_FAILED", "displayName is required (max 160 characters)")
        val email = emailInput?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (email != null && !Regex("^[^\\s@<>\"]{1,64}@[^\\s@<>\"]{1,190}\\.[A-Za-z]{2,24}$").matches(email))
            throw ApiException.badRequest("INVALID_EMAIL", "Email không hợp lệ")
        if (email != null && jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = ?", Long::class.java, email)!! > 0)
            throw ApiException.conflict("EMAIL_TAKEN", "Email này đã được dùng")
        if (jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ? AND status <> 'DELETED'", Long::class.java, tenantId)!! == 0L)
            throw ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found")
        if (tenantRole !in setOf("TENANT_ADMIN", "MEMBER"))
            throw ApiException.badRequest("TENANT_ROLE_INVALID", "Role must be TENANT_ADMIN or MEMBER")
        if ((workspaceId == null) != (workspaceRole == null))
            throw ApiException.badRequest("VALIDATION_FAILED", "workspaceId and workspaceRole must be supplied together")
        if (workspaceRole != null && workspaceRole !in setOf("WORKSPACE_ADMIN", "EDITOR", "PUBLISHER", "VIEWER"))
            throw ApiException.badRequest("INVALID_ROLE", "Invalid workspace role")
        if (workspaceId != null && jdbc.queryForObject(
                "SELECT count(*) FROM workspaces WHERE id = ? AND tenant_id = ?",
                Long::class.java, workspaceId, tenantId
            )!! == 0L)
            throw ApiException.notFound("WORKSPACE_NOT_FOUND", "Workspace not found")

        val id = UUID.randomUUID()
        val unusable = requireNotNull(encoder.encode(Base64.getEncoder().encodeToString(ByteArray(48).also(random::nextBytes))))
        val inserted = jdbc.update(
            """INSERT INTO users (id, username, password_hash, enabled, display_name, email, auth_source, activated_at, system_admin)
               VALUES (?,?,?,TRUE,?,?, 'LOCAL', NULL, FALSE) ON CONFLICT (username) DO NOTHING""",
            id, username, unusable, displayName, email
        )
        if (inserted == 0) throw ApiException.conflict("USERNAME_TAKEN", "Tên đăng nhập này đã tồn tại")

        jdbc.update(
            """INSERT INTO tenant_members (tenant_id, user_id, role, active, created_by)
               VALUES (?,?,?,TRUE,?)""",
            tenantId, id, tenantRole, adminId
        )
        if (workspaceId != null) {
            jdbc.update(
                """INSERT INTO workspace_members (workspace_id, user_id, role, active, tenant_id)
                   VALUES (?,?,?,TRUE,?)""",
                workspaceId, id, workspaceRole, tenantId
            )
        }

        val (token, exp) = newToken(id, "ACTIVATION", adminId)
        audit.record("USER_CREATED", "USER", id, workspaceId, actorId = adminId,
            newValue = mapOf("username" to username, "tenantId" to tenantId, "tenantRole" to tenantRole, "workspaceRole" to workspaceRole))
        audit.record("TENANT_MEMBER_SET", "TENANT", tenantId, actorId = adminId,
            newValue = mapOf("userId" to id, "role" to tenantRole))
        if (workspaceId != null) audit.record("ADD_MEMBER", "WORKSPACE_MEMBER", id, workspaceId, null, actorId = adminId,
            newValue = mapOf("role" to workspaceRole, "userId" to id))
        audit.record("ACTIVATION_LINK_CREATED", "USER", id, actorId = adminId, newValue = mapOf("expiresAt" to exp.toString()))
        return ActivationLink(id, username, displayName, "ACTIVATION", token, exp)
    }

    /** a link for a pending account (activation) or an active one (password reset) */
    @Transactional
    fun link(adminId: UUID, userId: UUID): ActivationLink {
        val u = jdbc.queryForList("SELECT username, display_name, activated_at, auth_source, enabled FROM users WHERE id = ?", userId).firstOrNull() ?: throw ApiException.notFound("USER_NOT_FOUND", "Không tìm thấy người dùng")
        if (u["auth_source"] != "LOCAL") throw ApiException.conflict("NOT_LOCAL_ACCOUNT", "Tài khoản này đăng nhập bằng tài khoản công ty; không có mật khẩu để đặt lại")
        if (u["enabled"] != true) throw ApiException.conflict("ACCOUNT_DISABLED", "Hãy mở khóa tài khoản trước")
        val purpose = if (u["activated_at"] == null) "ACTIVATION" else "RESET"
        val (token, exp) = newToken(userId, purpose, adminId)
        audit.record(if (purpose == "RESET") "PASSWORD_RESET_LINK_CREATED" else "ACTIVATION_LINK_CREATED", "USER", userId, actorId = adminId, newValue = mapOf("expiresAt" to exp.toString()))
        return ActivationLink(userId, u["username"] as String, (u["display_name"] as String?) ?: u["username"] as String, purpose, token, exp)
    }

    private data class Row(val tokenId: UUID, val userId: UUID, val username: String, val displayName: String, val purpose: String)
    private fun find(token: String): Row {
        val bad = ApiException(HttpStatus.GONE, "LINK_INVALID", "Liên kết không hợp lệ hoặc đã hết hạn. Hãy nhờ quản trị viên tạo liên kết mới.")
        if (!Regex("^[A-Za-z0-9_-]{30,100}$").matches(token)) throw bad
        return jdbc.query("""SELECT t.id, t.user_id, u.username, coalesce(u.display_name, u.username), t.purpose FROM account_tokens t JOIN users u ON u.id = t.user_id
            WHERE t.token_hash = ? AND t.used_at IS NULL AND t.expires_at > now() AND u.enabled AND u.auth_source = 'LOCAL'""",
            { rs, _ -> Row(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getString(5)) }, sha256(token)).firstOrNull() ?: throw bad
    }

    fun inspect(token: String): Map<String, String> = find(token).let { mapOf("username" to it.username, "displayName" to it.displayName, "purpose" to it.purpose) }

    fun complete(token: String, password: String) {
        val row = find(token)
        PasswordPolicy.check(row.username, password)
        val hash = hashGate.run { requireNotNull(encoder.encode(password)) }
        // single use even under concurrency: the UPDATE succeeds for exactly one caller
        val claimed = jdbc.update("UPDATE account_tokens SET used_at = now() WHERE id = ? AND used_at IS NULL AND expires_at > now()", row.tokenId)
        if (claimed == 0) throw ApiException(HttpStatus.GONE, "LINK_INVALID", "Liên kết không hợp lệ hoặc đã hết hạn. Hãy nhờ quản trị viên tạo liên kết mới.")
        jdbc.update("UPDATE users SET password_hash = ?, activated_at = coalesce(activated_at, now()) WHERE id = ?", hash, row.userId)
        sessions.findByPrincipalName(row.username).keys.forEach { sessions.deleteById(it) }     // a reset signs the account out everywhere
        audit.record(if (row.purpose == "RESET") "PASSWORD_RESET_COMPLETED" else "ACCOUNT_ACTIVATED", "USER", row.userId, actorId = row.userId)
    }

    @Transactional
    fun setSystemAdmin(adminId: UUID, targetId: UUID, grant: Boolean) {
        if (targetId == adminId) throw ApiException.conflict("CANNOT_CHANGE_SELF", "Bạn không thể tự đổi quyền Quản trị hệ thống của chính mình")
        val t = jdbc.queryForList("SELECT enabled, system_admin, activated_at FROM users WHERE id = ? FOR UPDATE", targetId).firstOrNull() ?: throw ApiException.notFound("USER_NOT_FOUND", "Không tìm thấy người dùng")
        if (grant && (t["enabled"] != true || t["activated_at"] == null)) throw ApiException.conflict("NOT_ACTIVE", "Chỉ cấp quyền cho tài khoản đã kích hoạt và đang hoạt động")
        if (!grant && t["system_admin"] == true && jdbc.queryForObject("SELECT count(*) FROM users WHERE system_admin AND enabled AND id <> ?", Long::class.java, targetId)!! == 0L)
            throw ApiException.conflict("LAST_SYSTEM_ADMIN", "Không thể gỡ quyền của quản trị hệ thống cuối cùng")
        jdbc.update("UPDATE users SET system_admin = ? WHERE id = ?", grant, targetId)
        audit.record(if (grant) "SYSTEM_ADMIN_GRANTED" else "SYSTEM_ADMIN_REVOKED", "USER", targetId, actorId = adminId)
    }
}

@RestController
class AccountController(private val guard: AdminGuard, private val accounts: AccountService, private val limiter: RateLimiter, private val jdbc: JdbcTemplate, private val audit: AuditService) {
    @PostMapping("/api/v1/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody r: CreateUserRequest, @AuthenticationPrincipal me: StudioUserDetails): ActivationLink { guard.require(me.userId); return accounts.create(me.userId, r) }

    @PostMapping("/api/v1/admin/users/{id}/activation-link")
    fun link(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): ActivationLink { guard.require(me.userId); return accounts.link(me.userId, id) }

    /** separate, explicit, confirmed; never for oneself */
    @PostMapping("/api/v1/admin/users/{id}/system-admin")
    fun systemAdmin(@PathVariable id: UUID, @Valid @RequestBody r: SystemAdminRequest, @AuthenticationPrincipal me: StudioUserDetails): Map<String, Boolean> {
        guard.require(me.userId)
        if (r.confirm != true) throw ApiException(HttpStatus.PRECONDITION_REQUIRED, "CONFIRMATION_REQUIRED", "Cần xác nhận để đổi quyền Quản trị hệ thống")
        accounts.setSystemAdmin(me.userId, id, r.grant!!); return mapOf("systemAdmin" to r.grant)
    }

    @PostMapping("/api/v1/admin/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun workspace(@Valid @RequestBody r: CreateWorkspaceRequest, @AuthenticationPrincipal me: StudioUserDetails): Map<String, Any> {
        guard.require(me.userId)
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO workspaces (id, name, slug) VALUES (?,?,?)", id, r.name.trim(), "w-" + id.toString().replace("-", "").take(16))
        audit.record("WORKSPACE_CREATED", "WORKSPACE", id, id, actorId = me.userId, newValue = mapOf("name" to r.name.trim()))
        return mapOf("id" to id, "name" to r.name.trim())
    }

    // ---- public: the employee opens the link and chooses a password (rate limited per IP; the token is the secret)
    @PostMapping("/api/v1/auth/activation/inspect")
    fun inspect(@Valid @RequestBody r: InspectRequest, request: HttpServletRequest): Map<String, String> {
        limiter.require("activation:ip:${request.remoteAddr}", 30, 600, "activation"); return accounts.inspect(r.token)
    }

    @PostMapping("/api/v1/auth/activation/complete")
    fun complete(@Valid @RequestBody r: CompleteRequest, request: HttpServletRequest): Map<String, String> {
        limiter.require("activation:ip:${request.remoteAddr}", 30, 600, "activation"); accounts.complete(r.token, r.password); return mapOf("status" to "ok")
    }
}
