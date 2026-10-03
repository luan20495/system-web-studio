package com.systemwebstudio.project

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "workspaces")
class WorkspaceEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 160)
    var name: String = "",
    @Column(nullable = false, unique = true, length = 120)
    var slug: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

interface WorkspaceRepository : JpaRepository<WorkspaceEntity, UUID>

data class WorkspaceMemberKey(var workspaceId: UUID = UUID.randomUUID(), var userId: UUID = UUID.randomUUID()) : Serializable

@Entity
@Table(name = "workspace_members")
@IdClass(WorkspaceMemberKey::class)
class WorkspaceMemberEntity(
    @Id
    @Column(name = "workspace_id", nullable = false)
    var workspaceId: UUID = UUID.randomUUID(),
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    var role: String = "EDITOR",
    @Column(nullable = false)
    var active: Boolean = true,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

interface WorkspaceMemberRepository : JpaRepository<WorkspaceMemberEntity, WorkspaceMemberKey> {
    fun existsByWorkspaceIdAndUserIdAndActiveTrue(workspaceId: UUID, userId: UUID): Boolean
    fun findByWorkspaceIdAndUserIdAndActiveTrue(workspaceId: UUID, userId: UUID): WorkspaceMemberEntity?
}

@Entity
@Table(name = "projects")
class ProjectEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "workspace_id", nullable = false)
    var workspaceId: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 160)
    var name: String = "",
    @Column(name = "owner_user_id", nullable = false)
    var ownerUserId: UUID = UUID.randomUUID(),
    @Column(name = "project_access_policy", nullable = false, length = 32)
    var projectAccessPolicy: String = "PRIVATE_MEMBERS",
    @Column(name = "site_visibility", nullable = false, length = 16)
    var siteVisibility: String = "PRIVATE",
    @Column(length = 1000)
    var description: String? = null,
    @Column(nullable = false, length = 32)
    var framework: String = "nextjs",
    @Column(name = "auth_mode", nullable = false, length = 16)
    var authMode: String = "LOCAL",
    @Column(length = 253)
    var domain: String? = null,
    @Column(name = "custom_domain", length = 253)
    var customDomain: String? = null,
    @Column(name = "deployment_mode", nullable = false, length = 16)
    var deploymentMode: String = "MOCK",
    @Column(name = "deployment_target", length = 120)
    var deploymentTarget: String? = null,
    /** PAGE_SCHEMA (page schema + registry) or STATIC_APP (code project with a Git repository); fixed at creation (ADR 0008) */
    @Column(name = "app_type", nullable = false, length = 16)
    var appType: String = "PAGE_SCHEMA",
    @Version
    @Column(nullable = false)
    var revision: Long = 0,
    @Column(nullable = false)
    var active: Boolean = true,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

data class ProjectMemberKey(var projectId: UUID = UUID.randomUUID(), var userId: UUID = UUID.randomUUID()) : Serializable

@Entity
@Table(name = "project_members")
@IdClass(ProjectMemberKey::class)
class ProjectMemberEntity(
    @Column(name = "workspace_id", nullable = false)
    var workspaceId: UUID = UUID.randomUUID(),
    @Id
    @Column(name = "project_id", nullable = false)
    var projectId: UUID = UUID.randomUUID(),
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    var role: String = "VIEWER",
    @Column(nullable = false)
    var active: Boolean = true,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

interface ProjectRepository : JpaRepository<ProjectEntity, UUID> {
    fun findByIdAndWorkspaceIdAndActiveTrue(id: UUID, workspaceId: UUID): ProjectEntity?
    fun findAllByWorkspaceIdAndActiveTrueOrderByUpdatedAtDescIdAsc(workspaceId: UUID): List<ProjectEntity>

    @Query("select p from ProjectEntity p join ProjectMemberEntity m on m.projectId = p.id and m.workspaceId = p.workspaceId where p.workspaceId = :workspaceId and m.userId = :userId and m.active = true and p.active = true order by p.updatedAt desc, p.id")
    fun findVisibleProjects(@Param("workspaceId") workspaceId: UUID, @Param("userId") userId: UUID): List<ProjectEntity>

    @Query("select p from ProjectEntity p join ProjectMemberEntity m on m.projectId = p.id and m.workspaceId = p.workspaceId where p.id = :projectId and p.workspaceId = :workspaceId and m.userId = :userId and m.active = true and p.active = true")
    fun findVisibleProject(@Param("workspaceId") workspaceId: UUID, @Param("projectId") projectId: UUID, @Param("userId") userId: UUID): ProjectEntity?
}

interface ProjectMemberRepository : JpaRepository<ProjectMemberEntity, ProjectMemberKey> {
    fun existsByProjectIdAndUserIdAndActiveTrueAndRoleIn(projectId: UUID, userId: UUID, roles: Collection<String>): Boolean
    fun findByProjectIdAndUserIdAndActiveTrue(projectId: UUID, userId: UUID): ProjectMemberEntity?
}