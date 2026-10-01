package com.systemwebstudio.integration.git

import com.systemwebstudio.version.SchemaRepository
import org.springframework.stereotype.Component
import java.util.UUID

data class GitCommit(val id: String, val message: String, val author: String?, val createdAt: java.time.Instant)

/** Port for source history. V1 ships only the database-backed adapter; GitHub/GitLab/Gitea adapters are future work. */
interface GitProvider {
    val name: String
    fun createRepository(projectId: UUID): String
    fun history(projectId: UUID): List<GitCommit>
    fun commit(projectId: UUID, versionId: UUID, message: String): String
    fun diff(projectId: UUID, fromVersion: UUID, toVersion: UUID): String
    fun restore(projectId: UUID, versionId: UUID)
    fun createBranch(projectId: UUID, name: String)
}

/** The project_versions table IS the history; no external repository exists, so no fake commit SHAs are produced. */
@Component
class DatabaseBackedGitProvider(private val versions: SchemaRepository) : GitProvider {
    override val name = "database"
    override fun createRepository(projectId: UUID) = "db://projects/$projectId"
    override fun history(projectId: UUID) = versions.versions(projectId).map { GitCommit(it.id.toString(), it.summary, it.createdByName, it.createdAt) }
    override fun commit(projectId: UUID, versionId: UUID, message: String) = versionId.toString()
    override fun diff(projectId: UUID, fromVersion: UUID, toVersion: UUID): String = throw UnsupportedOperationException("diff is not implemented in V1")
    override fun restore(projectId: UUID, versionId: UUID) = throw UnsupportedOperationException("restore goes through the versions API")
    override fun createBranch(projectId: UUID, name: String) = throw UnsupportedOperationException("branches are not implemented in V1")
}
