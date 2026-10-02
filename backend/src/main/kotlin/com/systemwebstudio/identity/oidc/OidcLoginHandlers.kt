package com.systemwebstudio.identity.oidc

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.identity.DatabaseUserDetailsService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Maps a verified OIDC login to an internal user and a normal Studio session (same principal as password login, so RBAC,
 * the active-user check and Redis sessions behave identically).
 *
 * Authorization never comes from the IdP: the identity is (issuer, subject). An unknown identity gets an account with no
 * workspace membership, so it can see nothing until an administrator adds it. Linking to an existing account by email is off
 * by default and, when enabled, only for emails the IdP marks as verified.
 */
@Component
@ConditionalOnProperty("app.oidc.enabled", havingValue = "true")
class OidcLoginSuccessHandler(
    private val jdbc: JdbcTemplate,
    private val users: DatabaseUserDetailsService,
    private val encoder: PasswordEncoder,
    private val contexts: SecurityContextRepository,
    private val audit: AuditService,
    @Value("\${app.oidc.auto-provision:true}") private val autoProvision: Boolean,
    @Value("\${app.oidc.link-by-verified-email:false}") private val linkByEmail: Boolean,
    @Value("\${app.oidc.success-url:/}") private val successUrl: String
) : AuthenticationSuccessHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        val oidc = authentication.principal as? OidcUser
        val issuer = oidc?.issuer?.toString()
        val subject = oidc?.subject
        if (oidc == null || issuer.isNullOrBlank() || subject.isNullOrBlank()) return deny(request, response, "no_identity")
        val email = oidc.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 254 }
        val verified = oidc.emailVerified == true

        val userId = findByIdentity(issuer, subject) ?: when {
            linkByEmail && verified && email != null && findByEmail(email) != null -> link(issuer, subject, email, findByEmail(email)!!)
            autoProvision -> provision(issuer, subject, email.takeIf { verified }, oidc.fullName ?: oidc.preferredUsername)
            else -> return deny(request, response, "not_provisioned")
        }
        val row = jdbc.queryForList("SELECT username, enabled FROM users WHERE id = ?", userId).first()
        if (row["enabled"] != true) return deny(request, response, "disabled")

        jdbc.update("UPDATE external_identities SET last_login_at = now(), email = COALESCE(?, email) WHERE issuer = ? AND subject = ?", email, issuer, subject)
        val details = users.loadUserByUsername(row["username"] as String)
        request.getSession(true); request.changeSessionId()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken.authenticated(details, null, details.authorities)
        SecurityContextHolder.setContext(context)
        contexts.saveContext(context, request, response)
        audit.record("LOGIN_SUCCESS", "USER", userId, actorId = userId, newValue = mapOf("method" to "OIDC"))
        response.sendRedirect(successUrl)
    }

    private fun findByIdentity(issuer: String, subject: String): UUID? =
        jdbc.queryForList("SELECT user_id FROM external_identities WHERE issuer = ? AND subject = ?", issuer, subject).firstOrNull()?.get("user_id") as UUID?

    private fun findByEmail(email: String): UUID? =
        jdbc.queryForList("SELECT id FROM users WHERE lower(email) = ?", email).firstOrNull()?.get("id") as UUID?

    private fun link(issuer: String, subject: String, email: String, userId: UUID): UUID {
        jdbc.update("INSERT INTO external_identities (issuer, subject, user_id, email) VALUES (?,?,?,?) ON CONFLICT DO NOTHING", issuer, subject, userId, email)
        audit.record("LINK_IDENTITY", "USER", userId, actorId = userId, newValue = mapOf("issuer" to issuer))
        return userId
    }

    private fun provision(issuer: String, subject: String, verifiedEmail: String?, name: String?): UUID {
        val id = UUID.randomUUID()
        val username = "oidc-" + MessageDigest.getInstance("SHA-256").digest("$issuer|$subject".toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val secret = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val unusable = requireNotNull(encoder.encode(Base64.getEncoder().encodeToString(secret)))     // nobody knows it: password login is impossible
        val emailFree = verifiedEmail != null && findByEmail(verifiedEmail) == null
        jdbc.update("INSERT INTO users (id, username, password_hash, enabled, display_name, email, auth_source) VALUES (?,?,?,TRUE,?,?, 'OIDC') ON CONFLICT (username) DO NOTHING",
            id, username, unusable, name?.take(160), if (emailFree) verifiedEmail else null)
        val userId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", UUID::class.java, username)!!
        jdbc.update("INSERT INTO external_identities (issuer, subject, user_id, email) VALUES (?,?,?,?) ON CONFLICT DO NOTHING", issuer, subject, userId, verifiedEmail)
        audit.record("PROVISION_USER", "USER", userId, actorId = userId, newValue = mapOf("source" to "OIDC", "issuer" to issuer))
        return userId
    }

    private fun deny(request: HttpServletRequest, response: HttpServletResponse, reason: String) {
        log.warn("OIDC login denied: {}", reason)
        audit.record("LOGIN_FAILURE", "USER", null, actorId = null, newValue = mapOf("method" to "OIDC", "reason" to reason))
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        response.sendRedirect("/?sso_error=$reason")
    }
}

@Component
@ConditionalOnProperty("app.oidc.enabled", havingValue = "true")
class OidcLoginFailureHandler(private val audit: AuditService) : AuthenticationFailureHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun onAuthenticationFailure(request: HttpServletRequest, response: HttpServletResponse, exception: AuthenticationException) {
        // The exception text can echo IdP error details; log the type only and never the code/state/token parameters.
        log.warn("OIDC login failed: {}", exception.javaClass.simpleName)
        audit.record("LOGIN_FAILURE", "USER", null, actorId = null, newValue = mapOf("method" to "OIDC", "reason" to "idp_error"))
        response.sendRedirect("/?sso_error=failed")
    }
}
