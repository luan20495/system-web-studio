package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.discovery.SchemaSnapshot
import com.systemwebstudio.data.discovery.SourceSchemaStore
import com.systemwebstudio.wiring.persistence.JdbcSupport.instant
import com.systemwebstudio.wiring.persistence.JdbcSupport.ts
import com.systemwebstudio.wiring.persistence.JdbcSupport.uuid
import com.systemwebstudio.wiring.persistence.JdbcSupport.uuidOrNull
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * `source_schemas` (V28): immutable, already-masked snapshots, one row per (tenant, data source, version). A second save of the same version is a
 * CONFLICT (two refreshes raced). A stored document that no longer decodes is treated as absent rather than leaking its content.
 * History is not pruned in this change (BLOCKERS: unbounded history); `history` is always capped by the caller's limit (max 100).
 */
class JdbcSourceSchemaStore(private val jdbc: JdbcTemplate) : SourceSchemaStore {
    override fun latest(tenantId: UUID, dataSourceId: UUID): SchemaSnapshot? =
        jdbc.query("$SELECT ORDER BY version DESC LIMIT 1", { rs, _ -> raw(rs) }, tenantId, dataSourceId).firstNotNullOfOrNull { it.toSnapshot() }

    override fun history(tenantId: UUID, dataSourceId: UUID, limit: Int): List<SchemaSnapshot> =
        jdbc.query("$SELECT ORDER BY version DESC LIMIT ?", { rs, _ -> raw(rs) }, tenantId, dataSourceId, limit.coerceIn(1, MAX_HISTORY)).mapNotNull { it.toSnapshot() }

    override fun save(snapshot: SchemaSnapshot) {
        try {
            jdbc.update(
                """INSERT INTO source_schemas (id, tenant_id, data_source_id, version, discovered_at, fingerprint, data_source_version, requested_by, includes_samples, snapshot)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))""",
                snapshot.id, snapshot.tenantId, snapshot.dataSourceId, snapshot.version, ts(snapshot.discoveredAt), snapshot.fingerprint,
                snapshot.dataSourceVersion, snapshot.requestedBy, snapshot.includesSamples, SchemaCodec.encode(snapshot.schema)
            )
        } catch (e: DuplicateKeyException) { throw JdbcSupport.conflict("a schema snapshot with this version already exists") }
    }

    private class Raw(
        val id: UUID, val tenantId: UUID, val dataSourceId: UUID, val version: Int, val discoveredAt: java.time.Instant, val fingerprint: String,
        val dataSourceVersion: Long, val requestedBy: UUID?, val includesSamples: Boolean, val snapshot: String
    ) {
        fun toSnapshot(): SchemaSnapshot? {
            val schema = try { SchemaCodec.decode(snapshot) } catch (e: RuntimeException) { return null }
            return SchemaSnapshot(id, tenantId, dataSourceId, version, discoveredAt, fingerprint, dataSourceVersion, requestedBy, includesSamples, schema)
        }
    }

    private fun raw(rs: ResultSet) = Raw(
        rs.uuid("id"), rs.uuid("tenant_id"), rs.uuid("data_source_id"), rs.getInt("version"), rs.instant("discovered_at"), rs.getString("fingerprint"),
        rs.getLong("data_source_version"), rs.uuidOrNull("requested_by"), rs.getBoolean("includes_samples"), rs.getString("snapshot")
    )

    private companion object {
        const val MAX_HISTORY = 100
        const val SELECT = "SELECT id, tenant_id, data_source_id, version, discovered_at, fingerprint, data_source_version, requested_by, includes_samples, snapshot::text AS snapshot " +
            "FROM source_schemas WHERE tenant_id = ? AND data_source_id = ?"
    }
}
