package com.systemwebstudio.publish

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class DeploymentRepository(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper { rs, _ ->
        DeploymentDto(
            rs.getObject("id", UUID::class.java), rs.getObject("project_id", UUID::class.java), rs.getObject("version_id", UUID::class.java),
            rs.getInt("version_number"), rs.getString("visibility"), rs.getString("status"), rs.getString("url"), rs.getString("error"),
            rs.getString("provider"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("finished_at")?.toInstant(), emptyList(), rs.getString("provider") == "mock"
        )
    }
    private val select = """SELECT d.id, d.project_id, d.version_id, v.version_number, d.visibility, d.status, d.url, d.error, d.provider,
        d.created_at, d.updated_at, d.finished_at FROM deployments d JOIN project_versions v ON v.id = d.version_id"""

    fun find(id: UUID): DeploymentDto? = jdbc.query("$select WHERE d.id = ?", mapper, id).firstOrNull()?.let { withEvents(it) }

    fun findInProject(projectId: UUID, id: UUID): DeploymentDto? =
        jdbc.query("$select WHERE d.id = ? AND d.project_id = ?", mapper, id, projectId).firstOrNull()?.let { withEvents(it) }

    fun list(projectId: UUID): List<DeploymentDto> = jdbc.query("$select WHERE d.project_id = ? ORDER BY d.created_at DESC LIMIT 50", mapper, projectId)

    private fun withEvents(d: DeploymentDto): DeploymentDto = d.copy(events = jdbc.query(
        "SELECT status, message, created_at FROM deployment_events WHERE deployment_id = ? ORDER BY created_at, id",
        { rs, _ -> DeploymentEventDto(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()) }, d.id))

    fun insert(id: UUID, workspaceId: UUID, projectId: UUID, versionId: UUID, userId: UUID, visibility: String, provider: String) {
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?,?,?,?,?,?,'QUEUED',?)",
            id, workspaceId, projectId, versionId, userId, visibility, provider)
        event(id, DeploymentStatus.QUEUED, "Deployment queued")
    }

    fun event(id: UUID, status: String, message: String?) {
        jdbc.update("INSERT INTO deployment_events (id, deployment_id, status, message) VALUES (?,?,?,?)", UUID.randomUUID(), id, status, message?.take(1000))
    }

    /** Compare-and-set: only the worker that sees the expected current status may advance it. */
    fun transition(id: UUID, from: String, to: String, message: String?, url: String? = null, error: String? = null): Boolean {
        require(DeploymentStatus.allowed(from, to)) { "Illegal transition $from -> $to" }
        val finished = to in DeploymentStatus.terminal
        val n = jdbc.update(
            "UPDATE deployments SET status = ?, updated_at = now(), url = COALESCE(?, url), error = ?, finished_at = CASE WHEN ? THEN now() ELSE finished_at END WHERE id = ? AND status = ?",
            to, url, error, finished, id, from)
        if (n == 1) event(id, to, message ?: error)
        return n == 1
    }

    fun staleIds(queuedOlderThanSeconds: Long, inProgressOlderThanSeconds: Long): List<UUID> = jdbc.query(
        """SELECT id FROM deployments WHERE (status = 'QUEUED' AND updated_at < now() - make_interval(secs => ?))
           OR (status IN ('POLICY_CHECK','SECURITY_CHECK','BUILDING','DEPLOYING') AND updated_at < now() - make_interval(secs => ?))""",
        { rs, _ -> rs.getObject(1, UUID::class.java) }, queuedOlderThanSeconds.toDouble(), inProgressOlderThanSeconds.toDouble())
}
