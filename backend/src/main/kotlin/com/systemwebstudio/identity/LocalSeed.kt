package com.systemwebstudio.identity

import com.systemwebstudio.project.ProjectEntity
import com.systemwebstudio.project.ProjectMemberEntity
import com.systemwebstudio.project.ProjectMemberRepository
import com.systemwebstudio.project.ProjectRepository
import com.systemwebstudio.schema.SchemaService
import com.systemwebstudio.project.WorkspaceMemberEntity
import com.systemwebstudio.project.WorkspaceMemberRepository
import com.systemwebstudio.project.WorkspaceEntity
import com.systemwebstudio.project.WorkspaceRepository
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Configuration
@Profile("local")
class LocalSeed {
    @Bean
    fun seedLocalAdmin(
        users: UserRepository,
        workspaces: WorkspaceRepository,
        workspaceMembers: WorkspaceMemberRepository,
        passwordEncoder: PasswordEncoder,
        environment: Environment,
        projects: ProjectRepository,
        projectMembers: ProjectMemberRepository,
        schemas: SchemaService
    ) = ApplicationRunner {
        val password = environment.getRequiredProperty("LOCAL_ADMIN_PASSWORD")
        require(password.length >= 14) { "LOCAL_ADMIN_PASSWORD must be at least 14 characters" }
        val workspaceId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        if (!workspaces.existsById(workspaceId)) {
            workspaces.save(WorkspaceEntity(id = workspaceId, name = "Local Workspace", slug = "local"))
        }
        if (users.findByUsername("local.admin") == null) {
            val passwordHash = requireNotNull(passwordEncoder.encode(password))
            val user = users.save(UserEntity(username = "local.admin", passwordHash = passwordHash, displayName = "Local Admin", systemAdmin = true))
            workspaceMembers.save(
                WorkspaceMemberEntity(
                    workspaceId = workspaceId,
                    userId = user.id,
                    role = "WORKSPACE_ADMIN"
                )
            )
        }
        // Keep every local.* account on the current dev password (it may have changed since the account was first created).
        listOf("local.admin", "local.editor", "local.publisher", "local.viewer").forEach { name ->
            users.findByUsername(name)?.let { u ->
                if (!passwordEncoder.matches(password, u.passwordHash)) { u.passwordHash = requireNotNull(passwordEncoder.encode(password)); users.save(u) }
            }
        }
        // Demo accounts (same dev password) so every role can be exercised in the browser. Local profile only.
        val demoProjectId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
        val admin = requireNotNull(users.findByUsername("local.admin"))
        val roles = mapOf("local.editor" to ("EDITOR" to "EDITOR"), "local.publisher" to ("VIEWER" to "PUBLISHER"), "local.viewer" to ("VIEWER" to "VIEWER"))
        if (!projects.existsById(demoProjectId)) {
            val now = java.time.Instant.now()
            val demo = projects.saveAndFlush(ProjectEntity(id = demoProjectId, workspaceId = workspaceId, name = "Water Purifier Website", ownerUserId = admin.id, createdAt = now, updatedAt = now))
            projectMembers.save(ProjectMemberEntity(workspaceId = workspaceId, projectId = demo.id, userId = admin.id, role = "OWNER"))
            schemas.ensureInitialized(demo, admin.id)
        }
        roles.forEach { (username, r) ->
            if (users.findByUsername(username) == null) {
                val u = users.save(UserEntity(username = username, passwordHash = requireNotNull(passwordEncoder.encode(password)), displayName = username.substringAfter('.').replaceFirstChar { it.uppercase() }))
                workspaceMembers.save(WorkspaceMemberEntity(workspaceId = workspaceId, userId = u.id, role = r.first))
                projectMembers.save(ProjectMemberEntity(workspaceId = workspaceId, projectId = demoProjectId, userId = u.id, role = r.second))
            }
        }
    }
}