package com.systemwebstudio.identity

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.common.RateLimiter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class LoginRequest(@field:NotBlank @field:Size(max = 120) val username: String, @field:NotBlank @field:Size(max = 256) val password: String)
data class WorkspaceSummary(val id: UUID, val name: String, val role: String)
data class MeResponse(
    val id: UUID, val username: String, val displayName: String,
    val roles: List<String>, val workspaces: List<WorkspaceSummary>
)

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authenticationManager: AuthenticationManager,
    private val securityContextRepository: SecurityContextRepository,
    private val hashGate: PasswordHashGate,
    private val rateLimiter: RateLimiter,
    private val audit: AuditService,
    private val jdbc: JdbcTemplate,
    @Value("\${app.local-login.enabled:true}") private val localLogin: Boolean,
    @Value("\${app.oidc.enabled:false}") private val oidc: Boolean,
    @Value("\${app.signup.enabled:false}") private val signup: Boolean,
    @Value("\${app.signup.invite-code:}") private val inviteCode: String,
    @Value("\${app.rate-limit.login-user-max:5}") private val userMax: Long,
    @Value("\${app.rate-limit.login-ip-max:50}") private val ipMax: Long,
    @Value("\${app.rate-limit.login-window-seconds:900}") private val windowSeconds: Long
) {
    @GetMapping("/csrf")
    fun csrf(token: CsrfToken): Map<String, String> = mapOf("token" to token.token)

    /** Lets the UI show the right sign-in options without hard-coding the deployment's identity strategy. */
    @GetMapping("/config")
    fun config(): Map<String, Any> = mapOf("localLogin" to localLogin, "oidc" to oidc, "oidcLoginUrl" to "/oauth2/authorization/oidc", "signup" to signup, "signupInviteRequired" to inviteCode.isNotBlank())

    @PostMapping("/login")
    fun login(@Valid @RequestBody body: LoginRequest, request: HttpServletRequest, response: HttpServletResponse): MeResponse {
        if (!localLogin) throw ApiException.notFound("LOCAL_LOGIN_DISABLED", "Password login is disabled; sign in with SSO")
        val username = body.username.trim()
        val userKey = "login:fail:u:${username.lowercase()}"
        val ipKey = "login:fail:ip:${request.remoteAddr}"
        // Checked before hashing so a blocked client cannot burn Argon2 CPU.
        val byUser = rateLimiter.peek(userKey, userMax)
        val byIp = rateLimiter.peek(ipKey, ipMax)
        if (!byUser.allowed || !byIp.allowed) {
            audit.record("LOGIN_FAILURE", "USER", username.take(64), actorId = null, newValue = mapOf("reason" to "rate_limited"))
            throw ApiException.tooManyRequests("Too many failed login attempts; retry later.", maxOf(byUser.retryAfterSeconds, byIp.retryAfterSeconds))
        }
        val authentication = try {
            hashGate.run { authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, body.password)) }
        } catch (_: AuthenticationException) {
            rateLimiter.hit(userKey, userMax, windowSeconds)
            rateLimiter.hit(ipKey, ipMax, windowSeconds)
            audit.record("LOGIN_FAILURE", "USER", username.take(64), actorId = null, newValue = mapOf("reason" to "bad_credentials"))
            throw ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password")
        }
        rateLimiter.reset(userKey)
        request.getSession(true)
        request.changeSessionId()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)
        val principal = authentication.principal as StudioUserDetails
        audit.record("LOGIN_SUCCESS", "USER", principal.userId, actorId = principal.userId)
        return me(principal)
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: StudioUserDetails): MeResponse {
        val workspaces = if (principal.systemAdmin) {
            jdbc.query("SELECT id, name FROM workspaces ORDER BY name") { rs, _ ->
                WorkspaceSummary(rs.getObject("id", UUID::class.java), rs.getString("name"), "ADMIN")
            }
        } else {
            jdbc.query(
                """SELECT w.id, w.name, m.role FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id
                   WHERE m.user_id = ? AND m.active ORDER BY w.name""", { rs, _ ->
                    WorkspaceSummary(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getString("role"))
                }, principal.userId
            )
        }
        val roles = principal.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }
        return MeResponse(principal.userId, principal.username, principal.displayName ?: principal.username, roles, workspaces)
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication?): Map<String, String> {
        audit.record("LOGOUT", "USER", AuditService.currentActorId())
        SecurityContextLogoutHandler().logout(request, response, authentication)
        return mapOf("status" to "logged_out")
    }
}
