package com.systemwebstudio.identity

import com.systemwebstudio.access.MeTenancyService
import com.systemwebstudio.access.TenantMembershipSummary
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
data class WorkspaceSummary(
    val id: UUID, val name: String, val role: String,
    /** tenant of the workspace (server-derived) */
    val tenantId: UUID? = null,
    /** canonical permission codes the caller holds in this workspace (for UI gating only; the server re-checks every call) */
    val permissions: List<String> = emptyList()
)
data class ProjectScopeSummary(
    val projectId: UUID,
    val workspaceId: UUID,
    /** informational only; clients must gate on permissions, never on this role string */
    val role: String,
    /** canonical permissions resolved from this exact project membership */
    val permissions: List<String>
)
data class MeResponse(
    val id: UUID, val username: String, val displayName: String,
    val roles: List<String>, val workspaces: List<WorkspaceSummary>,
    val systemAdmin: Boolean = false,
    /** primary tenant of the caller (null = no tenant membership) */
    val tenantId: UUID? = null,
    /** TENANT_ADMIN | MEMBER in the primary tenant; source of truth is tenant_members */
    val tenantRole: String? = null,
    /** true for SYSTEM_ADMIN (platform scope; no business-data access unless [businessAccess]) */
    val platformScope: Boolean = false,
    val businessAccess: Boolean = false,
    val tenants: List<TenantMembershipSummary> = emptyList(),
    /** platform + primary-tenant permissions as canonical codes (portal routing) */
    val permissions: List<String> = emptyList(),
    /** project-scoped permissions. These are never unioned into tenant/workspace/global permission lists. */
    val projectScopes: List<ProjectScopeSummary> = emptyList()
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
    private val meTenancy: MeTenancyService,
    private val codeProjects: com.systemwebstudio.code.CodeProjectService,
    private val runtime: com.systemwebstudio.runtime.ServerRuntimeService,
    @Value("\${app.local-login.enabled:true}") private val localLogin: Boolean,
    @Value("\${app.oidc.enabled:false}") private val oidc: Boolean,
    private val settings: com.systemwebstudio.settings.SettingsService,
    @Value("\${app.signup.invite-code:}") private val inviteCode: String,
    @Value("\${app.rate-limit.login-user-max:5}") private val userMax: Long,
    @Value("\${app.rate-limit.login-ip-max:50}") private val ipMax: Long,
    @Value("\${app.rate-limit.login-window-seconds:900}") private val windowSeconds: Long,
    private val registrations: org.springframework.beans.factory.ObjectProvider<org.springframework.security.oauth2.client.registration.ClientRegistrationRepository>,
    @Value("\${app.oidc.post-logout-redirect-uri:}") private val postLogoutRedirect: String,
    @Value("\${app.sites.studio-origin}") private val studioOrigin: String,
    @Value("\${app.saml.enabled:false}") private val samlEnabled: Boolean,
    @Value("\${app.saml.idp-hint:}") private val samlHint: String,
    @Value("\${app.saml.label:}") private val samlLabel: String
) {
    @GetMapping("/csrf")
    fun csrf(token: CsrfToken): Map<String, String> = mapOf("token" to token.token)

    /** Lets the UI show the right sign-in options without hard-coding the deployment's identity strategy. */
    @GetMapping("/config")
    fun config(): Map<String, Any> = mapOf("localLogin" to localLogin, "oidc" to oidc, "oidcLoginUrl" to "/oauth2/authorization/oidc", "signup" to settings.bool("signup.enabled"), "signupInviteRequired" to inviteCode.isNotBlank(),
        "codeProjects" to codeProjects.available, "serverApps" to (codeProjects.available && runtime.available),
        "codeAppPublicPublish" to settings.bool("source-apps.public-publish-enabled"), "publicPublish" to settings.bool("publish.public-enabled"),
        // SAML is offered only through the OIDC provider's identity brokering (kc_idp_hint); MFA is enforced by the identity provider
        "needsSetup" to (jdbc.queryForObject("SELECT count(*) FROM users WHERE system_admin AND enabled", Long::class.java) == 0L),
        "saml" to (oidc && samlEnabled && samlHint.isNotBlank()), "samlLabel" to samlLabel, "samlLoginUrl" to "/oauth2/authorization/oidc?idp=saml", "mfa" to "IDP")

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
        request.getSession(true).maxInactiveInterval = settings.int("session.timeout-minutes") * 60
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
            // every workspace is visible to a system admin; the role is the real membership role, or ADMIN when not a member
            jdbc.query("""SELECT w.id, w.name, coalesce(m.role, 'ADMIN') AS role, w.tenant_id FROM workspaces w
                LEFT JOIN workspace_members m ON m.workspace_id = w.id AND m.user_id = ? AND m.active ORDER BY w.name""", { rs, _ ->
                val role = rs.getString("role")
                WorkspaceSummary(rs.getObject("id", UUID::class.java), rs.getString("name"), role, rs.getObject("tenant_id", UUID::class.java), meTenancy.workspacePermissions(role))
            }, principal.userId)
        } else {
            jdbc.query(
                """SELECT w.id, w.name, m.role, w.tenant_id FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id
                   WHERE m.user_id = ? AND m.active ORDER BY w.name""", { rs, _ ->
                    val role = rs.getString("role")
                    WorkspaceSummary(rs.getObject("id", UUID::class.java), rs.getString("name"), role, rs.getObject("tenant_id", UUID::class.java), meTenancy.workspacePermissions(role))
                }, principal.userId
            )
        }
        val roles = principal.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }
        val systemAdmin = jdbc.queryForObject("SELECT system_admin FROM users WHERE id = ?", Boolean::class.java, principal.userId) == true    // live value, not the login-time snapshot
        val projectScopes = jdbc.query(
            """SELECT p.id AS project_id, p.workspace_id, pm.role, p.lifecycle,
                      EXISTS (SELECT 1 FROM workspace_members wm WHERE wm.workspace_id = p.workspace_id AND wm.user_id = pm.user_id AND wm.active) AS workspace_active,
                      EXISTS (SELECT 1 FROM tenant_members tm WHERE tm.tenant_id = w.tenant_id AND tm.user_id = pm.user_id AND tm.active) AS tenant_active,
                      t.status AS tenant_status
               FROM project_members pm
               JOIN projects p ON p.id = pm.project_id AND p.workspace_id = pm.workspace_id
               JOIN workspaces w ON w.id = p.workspace_id
               JOIN tenants t ON t.id = w.tenant_id
               WHERE pm.user_id = ? AND pm.active AND p.active
               ORDER BY p.workspace_id, p.id""",
            { rs, _ ->
                val workspaceActive = rs.getBoolean("workspace_active")
                val tenantActive = rs.getBoolean("tenant_active")
                val tenantStatus = rs.getString("tenant_status")
                val usable = systemAdmin || (workspaceActive && tenantActive && tenantStatus == "ACTIVE")
                if (!usable) null else {
                    val role = rs.getString("role")
                    ProjectScopeSummary(
                        rs.getObject("project_id", UUID::class.java),
                        rs.getObject("workspace_id", UUID::class.java),
                        role,
                        meTenancy.projectPermissions(role, rs.getString("lifecycle") == "ARCHIVED")
                    )
                }
            },
            principal.userId
        ).filterNotNull()
        val t = meTenancy.forUser(principal.userId, systemAdmin)
        return MeResponse(principal.userId, principal.username, principal.displayName ?: principal.username, roles, workspaces, systemAdmin,
            t.tenantId, t.tenantRole, t.platformScope, t.businessAccess, t.tenants, t.permissions, projectScopes)
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication?): Map<String, String> {
        audit.record("LOGOUT", "USER", AuditService.currentActorId())
        val idToken = request.getSession(false)?.getAttribute(com.systemwebstudio.identity.oidc.ID_TOKEN_ATTR) as? String
        SecurityContextLogoutHandler().logout(request, response, authentication)
        // RP-initiated logout (OpenID Connect RP-Initiated Logout 1.0): end the IdP session too, then come back to the Studio login
        val endSession = if (idToken != null) endSessionUrl(idToken) else null
        return if (endSession != null) mapOf("status" to "logged_out", "redirect" to endSession) else mapOf("status" to "logged_out")
    }

    private fun endSessionUrl(idToken: String): String? {
        val reg = runCatching { registrations.ifAvailable?.findByRegistrationId(com.systemwebstudio.identity.oidc.OidcConfiguration.REGISTRATION_ID) }.getOrNull() ?: return null
        val endpoint = reg.providerDetails.configurationMetadata["end_session_endpoint"]?.toString()?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        val back = postLogoutRedirect.ifBlank { "${studioOrigin.trimEnd('/')}/login" }
        fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)
        return "$endpoint${if ('?' in endpoint) "&" else "?"}id_token_hint=${enc(idToken)}&post_logout_redirect_uri=${enc(back)}&client_id=${enc(reg.clientId)}"
    }
}
