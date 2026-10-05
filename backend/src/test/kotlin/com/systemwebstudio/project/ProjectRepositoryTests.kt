package com.systemwebstudio.project

import com.systemwebstudio.identity.UserEntity
import com.systemwebstudio.identity.UserRepository
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class ProjectRepositoryTests : IntegrationTestBase() {
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var workspaces: WorkspaceRepository
    @Autowired lateinit var projects: ProjectRepository
    @Autowired lateinit var workspaceMembers: WorkspaceMemberRepository
    @Autowired lateinit var projectMembers: ProjectMemberRepository

    @Test
    fun `project reads are scoped to workspace and active membership`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val workspaceA = UUID.randomUUID()
        val workspaceB = UUID.randomUUID()
        workspaces.save(WorkspaceEntity(id = workspaceA, name = "A", slug = "workspace-a-$suffix"))
        workspaces.save(WorkspaceEntity(id = workspaceB, name = "B", slug = "workspace-b-$suffix"))
        val userA = users.save(UserEntity(username = "member-a-$suffix", passwordHash = "test-hash"))
        val userB = users.save(UserEntity(username = "member-b-$suffix", passwordHash = "test-hash"))
        workspaceMembers.save(WorkspaceMemberEntity(workspaceId = workspaceA, userId = userA.id))
        workspaceMembers.save(WorkspaceMemberEntity(workspaceId = workspaceB, userId = userB.id))

        val now = Instant.now()
        val projectA = projects.save(ProjectEntity(workspaceId = workspaceA, name = "Private A", ownerUserId = userA.id, createdAt = now, updatedAt = now))
        val projectB = projects.save(ProjectEntity(workspaceId = workspaceB, name = "Private B", ownerUserId = userB.id, createdAt = now, updatedAt = now))
        projectMembers.save(ProjectMemberEntity(workspaceId = workspaceA, projectId = projectA.id, userId = userA.id, role = "OWNER"))
        projectMembers.save(ProjectMemberEntity(workspaceId = workspaceB, projectId = projectB.id, userId = userB.id, role = "OWNER"))

        assertThat(projects.findVisibleProjects(workspaceA, userA.id)).extracting<String> { it.name }.containsExactly("Private A")
        assertThat(projects.findVisibleProject(workspaceB, projectA.id, userA.id)).isNull()
        assertThat(projects.findVisibleProjects(workspaceA, userB.id)).isEmpty()
    }
}
