package com.systemwebstudio.support

import com.systemwebstudio.identity.UserEntity
import com.systemwebstudio.identity.UserRepository
import com.systemwebstudio.project.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class TestFixtures(
    @Autowired val users: UserRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val workspaceMembers: WorkspaceMemberRepository,
    @Autowired val projects: ProjectRepository,
    @Autowired val projectMembers: ProjectMemberRepository,
    @Autowired val encoder: PasswordEncoder
) {
    companion object { const val PASSWORD = "test-pass-12345" }

    private val hash by lazy { encoder.encode(PASSWORD)!! }
    fun unique(prefix: String) = prefix + "-" + UUID.randomUUID().toString().take(8)

    fun user(prefix: String = "user", systemAdmin: Boolean = false): UserEntity =
        users.save(UserEntity(username = unique(prefix), passwordHash = hash, displayName = prefix, systemAdmin = systemAdmin))

    fun workspace(): UUID = workspaces.save(WorkspaceEntity(name = "WS", slug = unique("ws"))).id

    fun member(workspaceId: UUID, user: UserEntity, role: String) {
        workspaceMembers.save(WorkspaceMemberEntity(workspaceId = workspaceId, userId = user.id, role = role))
    }

    fun project(workspaceId: UUID, owner: UserEntity, name: String = "Project"): ProjectEntity {
        val now = Instant.now()
        val p = projects.save(ProjectEntity(workspaceId = workspaceId, name = name, ownerUserId = owner.id, createdAt = now, updatedAt = now))
        projectMembers.save(ProjectMemberEntity(workspaceId = workspaceId, projectId = p.id, userId = owner.id, role = "OWNER"))
        return p
    }

    fun projectRole(p: ProjectEntity, user: UserEntity, role: String) {
        projectMembers.save(ProjectMemberEntity(workspaceId = p.workspaceId, projectId = p.id, userId = user.id, role = role))
    }

    @Transactional
    fun disable(userId: UUID) { users.findById(userId).get().enabled = false }
}
