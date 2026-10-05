package com.systemwebstudio.version

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

data class VersionRow(
    val id: UUID, val projectId: UUID, val versionNumber: Int, val kind: String, val summary: String,
    val promptId: UUID?, val restoredFromVersionId: UUID?, val sourceRevision: Long?,
    val createdBy: UUID?, val createdByName: String?, val createdAt: Instant, val schemaSnapshot: JsonNode? = null
)

@Repository
class SchemaRepository(private val jdbc: JdbcTemplate, private val json: JsonMapper) {
    fun currentSchema(projectId: UUID): JsonNode? =
        jdbc.query("SELECT schema FROM page_schemas WHERE project_id = ?", { rs, _ -> json.readTree(rs.getString(1)) }, projectId).firstOrNull()

    fun upsertSchema(projectId: UUID, workspaceId: UUID, schema: JsonNode) {
        jdbc.update(
            """INSERT INTO page_schemas (project_id, workspace_id, schema, updated_at) VALUES (?,?,CAST(? AS jsonb), now())
               ON CONFLICT (project_id) DO UPDATE SET schema = EXCLUDED.schema, updated_at = now()""",
            projectId, workspaceId, json.writeValueAsString(schema)
        )
    }

    /** Callers hold the project row lock (revision CAS), so max+1 cannot race. */
    fun nextVersionNumber(projectId: UUID): Int =
        jdbc.queryForObject("SELECT COALESCE(MAX(version_number), 0) + 1 FROM project_versions WHERE project_id = ?", Int::class.java, projectId)!!

    fun insertVersion(
        workspaceId: UUID, projectId: UUID, number: Int, schema: JsonNode, kind: String, summary: String,
        promptId: UUID?, restoredFrom: UUID?, sourceRevision: Long?, createdBy: UUID?
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO project_versions (id, workspace_id, project_id, version_number, schema_snapshot, kind, summary,
               prompt_id, restored_from_version_id, source_revision, created_by) VALUES (?,?,?,?,CAST(? AS jsonb),?,?,?,?,?,?)""",
            id, workspaceId, projectId, number, json.writeValueAsString(schema), kind, summary.take(500), promptId, restoredFrom, sourceRevision, createdBy
        )
        return id
    }

    fun insertUsage(versionId: UUID, projectId: UUID, schema: JsonNode) {
        val counts = LinkedHashMap<Pair<String, String>, Int>()
        schema.get("sections")?.forEach { s ->
            val key = s.get("type").asString() to s.get("componentVersion").asString()
            counts[key] = (counts[key] ?: 0) + 1
        }
        counts.forEach { (k, n) ->
            jdbc.update("INSERT INTO component_usage (version_id, project_id, component_id, component_version, usage_count) VALUES (?,?,?,?,?)",
                versionId, projectId, k.first, k.second, n)
        }
    }

    private val mapper = org.springframework.jdbc.core.RowMapper { rs, _ ->
        VersionRow(
            rs.getObject("id", UUID::class.java), rs.getObject("project_id", UUID::class.java), rs.getInt("version_number"),
            rs.getString("kind"), rs.getString("summary"), rs.getObject("prompt_id", UUID::class.java),
            rs.getObject("restored_from_version_id", UUID::class.java), rs.getObject("source_revision") as Long?,
            rs.getObject("created_by", UUID::class.java), rs.getString("created_by_name"), rs.getTimestamp("created_at").toInstant()
        )
    }

    private val select = """SELECT v.id, v.project_id, v.version_number, v.kind, v.summary, v.prompt_id, v.restored_from_version_id,
        v.source_revision, v.created_by, COALESCE(u.display_name, u.username) AS created_by_name, v.created_at
        FROM project_versions v LEFT JOIN users u ON u.id = v.created_by"""

    fun versions(projectId: UUID, limit: Int = 200): List<VersionRow> =
        jdbc.query("$select WHERE v.project_id = ? ORDER BY v.version_number DESC LIMIT ?", mapper, projectId, limit)

    fun version(projectId: UUID, versionId: UUID): VersionRow? {
        val row = jdbc.query("$select WHERE v.project_id = ? AND v.id = ?", mapper, projectId, versionId).firstOrNull() ?: return null
        val snapshot = jdbc.queryForObject("SELECT schema_snapshot FROM project_versions WHERE id = ?", String::class.java, versionId)
        return row.copy(schemaSnapshot = json.readTree(snapshot))
    }

    fun latest(projectId: UUID): VersionRow? = jdbc.query("$select WHERE v.project_id = ? ORDER BY v.version_number DESC LIMIT 1", mapper, projectId).firstOrNull()
}
