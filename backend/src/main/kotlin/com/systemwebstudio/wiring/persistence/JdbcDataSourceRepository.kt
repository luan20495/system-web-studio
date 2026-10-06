package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceRepository
import com.systemwebstudio.data.datasource.DataSourceStatus
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.wiring.persistence.JdbcSupport.instant
import com.systemwebstudio.wiring.persistence.JdbcSupport.ts
import com.systemwebstudio.wiring.persistence.JdbcSupport.uuid
import com.systemwebstudio.wiring.persistence.JdbcSupport.uuidOrNull
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * `data_sources` (V28). Tenant-scoped: there is no lookup by id alone. Credential material is never selected here (it lives in
 * `data_credentials`); only the opaque `credential_ref` is. Optimistic concurrency: a save only lands when its [DataSource.version] is newer
 * than the stored one (the service always writes `version + 1`), otherwise the caller gets a CONFLICT failure and nothing changes.
 */
class JdbcDataSourceRepository(private val jdbc: JdbcTemplate) : DataSourceRepository {

    override fun find(tenantId: UUID, id: UUID): DataSource? =
        jdbc.query("SELECT $COLUMNS FROM data_sources WHERE tenant_id = ? AND id = ?", { rs, _ -> map(rs) }, tenantId, id).firstOrNull()

    /** B-C0-W-05: ownership is part of the query; a source of another workspace (or one without a workspace) is simply not found */
    override fun findInWorkspace(tenantId: UUID, workspaceId: UUID, id: UUID): DataSource? =
        jdbc.query("SELECT $COLUMNS FROM data_sources WHERE tenant_id = ? AND workspace_id = ? AND id = ?", { rs, _ -> map(rs) }, tenantId, workspaceId, id).firstOrNull()

    /** B-C0-W-03: the workspace filter is part of the query */
    override fun listInWorkspace(tenantId: UUID, workspaceId: UUID): List<DataSource> =
        jdbc.query("SELECT $COLUMNS FROM data_sources WHERE tenant_id = ? AND workspace_id = ? ORDER BY created_at, id LIMIT $MAX_LIST", { rs, _ -> map(rs) }, tenantId, workspaceId)

    override fun list(tenantId: UUID): List<DataSource> =
        jdbc.query("SELECT $COLUMNS FROM data_sources WHERE tenant_id = ? ORDER BY created_at, id LIMIT $MAX_LIST", { rs, _ -> map(rs) }, tenantId)

    override fun save(dataSource: DataSource): DataSource {
        val config = DataJson.mapper.writeValueAsString(dataSource.ref.configNonSecret)
        val changed = try {
            jdbc.update(
                """INSERT INTO data_sources (id, tenant_id, workspace_id, type, name, status, config_nonsecret, credential_ref, created_by, created_at, updated_at, version)
                   VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                   ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, config_nonsecret = EXCLUDED.config_nonsecret,
                       credential_ref = EXCLUDED.credential_ref, updated_at = EXCLUDED.updated_at, version = EXCLUDED.version
                   WHERE data_sources.tenant_id = EXCLUDED.tenant_id AND data_sources.version < EXCLUDED.version""",
                dataSource.id, dataSource.tenantId, dataSource.workspaceId, dataSource.connectorType, dataSource.name, dataSource.status.name, config,
                dataSource.credentialRef, dataSource.createdBy, ts(dataSource.createdAt), ts(dataSource.updatedAt), dataSource.version
            )
        } catch (e: DuplicateKeyException) { throw JdbcSupport.conflict("a data source with this name already exists") }
        if (changed == 0) throw JdbcSupport.conflict("the data source changed concurrently")
        return dataSource
    }

    /**
     * One transaction (its own connection, so it does not depend on an ambient Spring transaction): refuse while a binding uses the source, then remove the
     * rows that only exist for it, then the source itself. Every statement carries the tenant. The credential row is not touched here.
     */
    override fun delete(tenantId: UUID, id: UUID): Boolean {
        val dataSource = jdbc.dataSource ?: throw IllegalStateException("no data source")
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                fun count(sql: String): Long = c.prepareStatement(sql).use { ps -> ps.setObject(1, tenantId); ps.setObject(2, id); ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) } }
                fun remove(sql: String): Int = c.prepareStatement(sql).use { ps -> ps.setObject(1, tenantId); ps.setObject(2, id); ps.executeUpdate() }
                if (count("SELECT count(*) FROM data_sources WHERE tenant_id = ? AND id = ?") == 0L) { c.rollback(); return false }
                if (count("SELECT count(*) FROM data_source_bindings WHERE tenant_id = ? AND data_source_id = ?") > 0L) { c.rollback(); throw JdbcSupport.conflict("the data source is still bound to an application; remove the bindings first") }
                remove("DELETE FROM data_idempotency WHERE tenant_id = ? AND data_source_id = ?")
                remove("DELETE FROM source_schemas WHERE tenant_id = ? AND data_source_id = ?")
                remove("DELETE FROM data_queries WHERE tenant_id = ? AND data_source_id = ?")
                remove("DELETE FROM data_mutations WHERE tenant_id = ? AND data_source_id = ?")
                val removed = remove("DELETE FROM data_sources WHERE tenant_id = ? AND id = ?")
                c.commit()
                return removed > 0
            } catch (e: Exception) {
                runCatching { c.rollback() }
                if (e is org.springframework.dao.DataAccessException || e is java.sql.SQLException) throw JdbcSupport.conflict("the data source could not be removed; it may still be in use")
                throw e
            }
        }
    }

    private fun map(rs: ResultSet): DataSource {
        val cfg = DataJson.parse(rs.getString("config_nonsecret").toByteArray())
        val config = LinkedHashMap<String, String>()
        for (k in DataJson.keys(cfg)) config[k] = DataJson.text(cfg.get(k))
        val status = runCatching { DataSourceStatus.valueOf(rs.getString("status")) }.getOrDefault(DataSourceStatus.DISABLED)   // unknown = off, never on
        return DataSource(
            ref = DataSourceRef(rs.uuid("id"), rs.uuid("tenant_id"), rs.getString("type"), config), name = rs.getString("name"), status = status,
            credentialRef = rs.getString("credential_ref"), workspaceId = rs.uuidOrNull("workspace_id"), createdBy = rs.uuidOrNull("created_by"),
            createdAt = rs.instant("created_at"), updatedAt = rs.instant("updated_at"), version = rs.getLong("version")
        )
    }

    private companion object {
        const val MAX_LIST = 1_000
        const val COLUMNS = "id, tenant_id, workspace_id, type, name, status, config_nonsecret, credential_ref, created_by, created_at, updated_at, version"
    }
}
