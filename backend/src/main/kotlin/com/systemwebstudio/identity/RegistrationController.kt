package com.systemwebstudio.identity

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.common.RateLimiter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.util.UUID

data class RegisterRequest(
    @field:NotBlank @field:Size(min = 3, max = 40) val username: String,
    @field:NotBlank @field:Size(min = 6, max = 128) val password: String,
    @field:Size(max = 80) val displayName: String? = null,
    @field:Size(max = 100) val inviteCode: String? = null
)

/**
 * Self-service sign-up for a public deployment (off unless SIGNUP_ENABLED=true). Every account gets its own workspace and is
 * its WORKSPACE_ADMIN, so users are isolated from each other by the normal RBAC. Deliberately no email field: an unverified
 * email stored here could later be used by "add member by email" to claim someone else's address.
 * Abuse limits: per-IP rate limit, optional invite code, total user cap, hashing concurrency gate. Password policy (product decision): at least 6 characters with both letters and digits; online guessing is bounded by the login throttle.
 */
@RestController
@RequestMapping("/api/v1/auth")
class RegistrationController(
    private val jdbc: JdbcTemplate,
    private val encoder: PasswordEncoder,
    private val hashGate: PasswordHashGate,
    private val limiter: RateLimiter,
    private val audit: AuditService,
    private val tx: org.springframework.transaction.support.TransactionTemplate,
    private val settings: com.systemwebstudio.settings.SettingsService,
    @Value("\${app.signup.invite-code:}") private val inviteCode: String,
    @Value("\${app.signup.max-users:500}") private val maxUsers: Long,
    @Value("\${app.signup.ip-max-per-hour:5}") private val ipMax: Long
) {
    private val usernamePattern = Regex("^[a-z0-9][a-z0-9._-]{2,39}$")

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody body: RegisterRequest, request: HttpServletRequest): Map<String, String> {
        if (!settings.bool("signup.enabled")) throw ApiException.notFound("SIGNUP_DISABLED", "Sign-up is not available")
        limiter.require("signup:ip:${request.remoteAddr}", ipMax, 3600, "signup")
        if (inviteCode.isNotBlank() && !MessageDigest.isEqual((body.inviteCode ?: "").toByteArray(), inviteCode.toByteArray()))
            throw ApiException.forbidden("Invalid invite code", "INVALID_INVITE_CODE")
        val username = body.username.trim().lowercase()
        if (!usernamePattern.matches(username)) throw ApiException.badRequest("INVALID_USERNAME", "Username: 3-40 characters a-z 0-9 . _ - and must start with a letter or digit")
        if (body.password.lowercase().contains(username)) throw ApiException.badRequest("WEAK_PASSWORD", "Password must not contain the username")
        if (!body.password.any { it.isLetter() } || !body.password.any { it.isDigit() }) throw ApiException.badRequest("WEAK_PASSWORD", "Password must contain both letters and digits")
        if (body.password.toSet().size < 4) throw ApiException.badRequest("WEAK_PASSWORD", "Password is too repetitive")
        if (jdbc.queryForObject("SELECT count(*) FROM users", Long::class.java)!! >= maxUsers) throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SIGNUP_FULL", "Sign-up is temporarily closed")
        val hash = hashGate.run { requireNotNull(encoder.encode(body.password)) }
        create(username, hash, body.displayName?.trim()?.ifEmpty { null } ?: username)
        return mapOf("username" to username)
    }

    // One transaction for user + workspace + membership + audit (the hash is computed before it so no connection waits on Argon2).
    private fun create(username: String, hash: String, displayName: String) = tx.executeWithoutResult {
        val userId = UUID.randomUUID(); val workspaceId = UUID.randomUUID()
        val inserted = jdbc.update("INSERT INTO users (id, username, password_hash, enabled, display_name, auth_source) VALUES (?,?,?,TRUE,?, 'LOCAL') ON CONFLICT (username) DO NOTHING", userId, username, hash, displayName.take(160))
        if (inserted == 0) throw ApiException.conflict("USERNAME_TAKEN", "That username is already taken")
        jdbc.update("INSERT INTO workspaces (id, name, slug) VALUES (?,?,?)", workspaceId, "${displayName.take(60)} — workspace", "u-" + workspaceId.toString().replace("-", "").take(16))
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role, active) VALUES (?,?,'WORKSPACE_ADMIN',TRUE)", workspaceId, userId)
        audit.record("REGISTER", "USER", userId, workspaceId, null, actorId = userId, newValue = mapOf("source" to "self-service"))
    }
}
