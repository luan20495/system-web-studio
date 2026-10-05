package com.systemwebstudio.identity

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID

/**
 * Production has no seed users. If BOOTSTRAP_ADMIN_USERNAME / BOOTSTRAP_ADMIN_PASSWORD are set and that user does not exist,
 * one operator account (system admin, with its own workspace) is created at startup. The password is read from the
 * environment only; it is never logged. Remove the variables afterwards if you prefer.
 */
@Configuration
@Profile("prod")
class BootstrapAdmin {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun bootstrapAdminRunner(
        jdbc: JdbcTemplate, encoder: PasswordEncoder,
        @Value("\${app.bootstrap.admin-username:}") username: String,
        @Value("\${app.bootstrap.admin-password:}") password: String
    ) = ApplicationRunner {
        if (username.isBlank() && password.isBlank()) return@ApplicationRunner
        require(username.matches(Regex("^[a-z0-9][a-z0-9._-]{2,39}$"))) { "BOOTSTRAP_ADMIN_USERNAME must be 3-40 chars a-z 0-9 . _ -" }
        require(password.length >= 14) { "BOOTSTRAP_ADMIN_PASSWORD must be at least 14 characters" }
        if (jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Long::class.java, username)!! > 0) return@ApplicationRunner
        // first administrator only: once an enabled system administrator exists the bootstrap values are ignored (no accidental second admin)
        if (jdbc.queryForObject("SELECT count(*) FROM users WHERE system_admin AND enabled", Long::class.java)!! > 0) { log.info("Bootstrap admin skipped: a system administrator already exists"); return@ApplicationRunner }
        val userId = UUID.randomUUID(); val workspaceId = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, username, password_hash, enabled, display_name, system_admin) VALUES (?,?,?,TRUE,?,TRUE)", userId, username, requireNotNull(encoder.encode(password)), "Operator")
        jdbc.update("INSERT INTO workspaces (id, name, slug) VALUES (?,?,?)", workspaceId, "Operations", "ops-" + workspaceId.toString().take(8))
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role, active) VALUES (?,?,'WORKSPACE_ADMIN',TRUE)", workspaceId, userId)
        log.info("Bootstrap admin '{}' created", username)
    }
}
