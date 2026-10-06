package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.wiring.DataSourceSlotBindings
import com.systemwebstudio.wiring.persistence.JdbcSupport.uuid
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * `data_source_bindings` (V28), READ side: AppDefinition local data-source id (slot) -> registered data source, per project and mode.
 * TEST and LIVE are separate rows (the mode is part of the primary key), so a TEST run can never resolve a LIVE binding or the reverse.
 * Default-deny: nothing bound answers an empty map (the resolver then reports `DATA_SOURCE_UNBOUND`). The runtime never writes here.
 */
class JdbcDataSourceSlotBindings(private val jdbc: JdbcTemplate) : DataSourceSlotBindings {
    override fun bindings(tenantId: UUID, projectId: UUID, mode: ExecutionMode): Map<String, UUID> {
        val out = LinkedHashMap<String, UUID>()
        jdbc.query(
            "SELECT slot_id, data_source_id FROM data_source_bindings WHERE tenant_id = ? AND project_id = ? AND mode = ?",
            { rs, _ -> out[rs.getString("slot_id")] = rs.uuid("data_source_id"); 1 },
            tenantId, projectId, mode.name
        )
        return out
    }
}

/**
 * WRITE side of the bindings, deliberately not used by the runtime. It exists for fixtures and for the management API that is still missing
 * (BLOCKERS B-C0-W-03). It only binds a data source that belongs to the same tenant (and, when the source has a workspace, the same workspace as
 * the project); the composite foreign keys of V28 enforce the rest.
 */
class DataSourceBindingWriter(private val jdbc: JdbcTemplate) {
    /** @return true when the binding was written; false when the data source is not the tenant's / the workspace's */
    fun bind(tenantId: UUID, workspaceId: UUID, projectId: UUID, mode: ExecutionMode, slotId: String, dataSourceId: UUID, createdBy: UUID?): Boolean {
        val matches = jdbc.queryForObject(
            "SELECT COUNT(*) FROM data_sources WHERE tenant_id = ? AND id = ? AND (workspace_id IS NULL OR workspace_id = ?)",
            Long::class.java, tenantId, dataSourceId, workspaceId
        ) ?: 0L
        if (matches == 0L) return false
        jdbc.update(
            """INSERT INTO data_source_bindings (tenant_id, workspace_id, project_id, mode, slot_id, data_source_id, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (project_id, mode, slot_id) DO UPDATE SET data_source_id = EXCLUDED.data_source_id, updated_at = CURRENT_TIMESTAMP
               WHERE data_source_bindings.tenant_id = EXCLUDED.tenant_id""",
            tenantId, workspaceId, projectId, mode.name, slotId, dataSourceId, createdBy
        )
        return true
    }

    fun unbind(tenantId: UUID, projectId: UUID, mode: ExecutionMode, slotId: String): Boolean =
        jdbc.update("DELETE FROM data_source_bindings WHERE tenant_id = ? AND project_id = ? AND mode = ? AND slot_id = ?", tenantId, projectId, mode.name, slotId) > 0
}
