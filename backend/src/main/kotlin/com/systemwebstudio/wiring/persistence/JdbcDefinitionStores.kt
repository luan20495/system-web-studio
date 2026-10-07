package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.query.DefinitionStatus
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationDefinitionStore
import com.systemwebstudio.data.query.QueryDefinition
import com.systemwebstudio.data.query.QueryDefinitionStore
import com.systemwebstudio.data.query.StoredMutationDefinition
import com.systemwebstudio.data.query.StoredQueryDefinition
import com.systemwebstudio.wiring.persistence.JdbcSupport.instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Management view of `data_queries` / `data_mutations` (V28): every status, tenant- and data-source-scoped on every statement, documents written and read by the
 * same [DefinitionCodecs] the runtime catalogs use (so what the management API stores is exactly what the gateway loads). A stored document that does not decode
 * is not listed and not found. `replace` is an optimistic compare-and-set on `version`.
 */
class JdbcQueryDefinitionStore(private val jdbc: JdbcTemplate) : QueryDefinitionStore {
    private class Row(val kind: String, val id: String, val version: Long, val definition: String, val status: String, val createdAt: java.time.Instant, val updatedAt: java.time.Instant)

    private fun row(rs: java.sql.ResultSet) = Row(rs.getString("kind"), rs.getString("query_id"), rs.getLong("version"), rs.getString("definition"), rs.getString("status"), rs.instant("created_at"), rs.instant("updated_at"))

    private fun decode(r: Row, tenantId: UUID, dataSourceId: UUID): StoredQueryDefinition? {
        val def = DefinitionCodecs.decodeQuery(r.kind, r.id, tenantId, dataSourceId, r.version, r.definition) ?: return null
        val status = runCatching { DefinitionStatus.valueOf(r.status) }.getOrDefault(DefinitionStatus.DISABLED)      // unknown = off, never on
        return StoredQueryDefinition(def, status, r.createdAt, r.updatedAt)
    }

    override fun find(tenantId: UUID, dataSourceId: UUID, queryId: String): StoredQueryDefinition? =
        jdbc.query("$SELECT AND query_id = ?", { rs, _ -> row(rs) }, tenantId, dataSourceId, queryId).firstNotNullOfOrNull { decode(it, tenantId, dataSourceId) }

    override fun list(tenantId: UUID, dataSourceId: UUID): List<StoredQueryDefinition> =
        jdbc.query("$SELECT ORDER BY query_id LIMIT $MAX_LIST", { rs, _ -> row(rs) }, tenantId, dataSourceId).mapNotNull { decode(it, tenantId, dataSourceId) }

    override fun insert(def: QueryDefinition, status: DefinitionStatus) {
        try {
            jdbc.update(
                "INSERT INTO data_queries (tenant_id, data_source_id, query_id, kind, definition, status, version) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)",
                def.tenantId, def.dataSourceId, def.id, DefinitionCodecs.kindOf(def), DefinitionCodecs.encodeQuery(def), status.name, def.version
            )
        } catch (e: DuplicateKeyException) { throw JdbcSupport.conflict("a query definition with this id already exists") }
    }

    override fun replace(def: QueryDefinition, status: DefinitionStatus, expectedVersion: Long): Boolean = jdbc.update(
        """UPDATE data_queries SET kind = ?, definition = CAST(? AS jsonb), status = ?, version = ?, updated_at = CURRENT_TIMESTAMP
           WHERE tenant_id = ? AND data_source_id = ? AND query_id = ? AND version = ?""",
        DefinitionCodecs.kindOf(def), DefinitionCodecs.encodeQuery(def), status.name, def.version, def.tenantId, def.dataSourceId, def.id, expectedVersion
    ) == 1

    override fun delete(tenantId: UUID, dataSourceId: UUID, queryId: String): Boolean =
        jdbc.update("DELETE FROM data_queries WHERE tenant_id = ? AND data_source_id = ? AND query_id = ?", tenantId, dataSourceId, queryId) > 0

    private companion object {
        const val MAX_LIST = 1_000
        const val SELECT = "SELECT kind, query_id, definition::text AS definition, version, status, created_at, updated_at FROM data_queries WHERE tenant_id = ? AND data_source_id = ?"
    }
}

class JdbcMutationDefinitionStore(private val jdbc: JdbcTemplate) : MutationDefinitionStore {
    private class Row(val kind: String, val id: String, val version: Long, val definition: String, val status: String, val createdAt: java.time.Instant, val updatedAt: java.time.Instant)

    private fun row(rs: java.sql.ResultSet) = Row(rs.getString("kind"), rs.getString("mutation_id"), rs.getLong("version"), rs.getString("definition"), rs.getString("status"), rs.instant("created_at"), rs.instant("updated_at"))

    private fun decode(r: Row, tenantId: UUID, dataSourceId: UUID): StoredMutationDefinition? {
        val def = DefinitionCodecs.decodeMutation(r.kind, r.id, tenantId, dataSourceId, r.version, r.definition) ?: return null
        val status = runCatching { DefinitionStatus.valueOf(r.status) }.getOrDefault(DefinitionStatus.DISABLED)
        return StoredMutationDefinition(def, status, r.createdAt, r.updatedAt)
    }

    override fun find(tenantId: UUID, dataSourceId: UUID, mutationId: String): StoredMutationDefinition? =
        jdbc.query("$SELECT AND mutation_id = ?", { rs, _ -> row(rs) }, tenantId, dataSourceId, mutationId).firstNotNullOfOrNull { decode(it, tenantId, dataSourceId) }

    override fun list(tenantId: UUID, dataSourceId: UUID): List<StoredMutationDefinition> =
        jdbc.query("$SELECT ORDER BY mutation_id LIMIT $MAX_LIST", { rs, _ -> row(rs) }, tenantId, dataSourceId).mapNotNull { decode(it, tenantId, dataSourceId) }

    override fun insert(def: MutationDefinition, status: DefinitionStatus) {
        try {
            jdbc.update(
                "INSERT INTO data_mutations (tenant_id, data_source_id, mutation_id, kind, definition, status, version) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)",
                def.tenantId, def.dataSourceId, def.id, def.kind.name, DefinitionCodecs.encodeMutation(def), status.name, def.version
            )
        } catch (e: DuplicateKeyException) { throw JdbcSupport.conflict("a mutation definition with this id already exists") }
    }

    override fun replace(def: MutationDefinition, status: DefinitionStatus, expectedVersion: Long): Boolean = jdbc.update(
        """UPDATE data_mutations SET kind = ?, definition = CAST(? AS jsonb), status = ?, version = ?, updated_at = CURRENT_TIMESTAMP
           WHERE tenant_id = ? AND data_source_id = ? AND mutation_id = ? AND version = ?""",
        def.kind.name, DefinitionCodecs.encodeMutation(def), status.name, def.version, def.tenantId, def.dataSourceId, def.id, expectedVersion
    ) == 1

    override fun delete(tenantId: UUID, dataSourceId: UUID, mutationId: String): Boolean =
        jdbc.update("DELETE FROM data_mutations WHERE tenant_id = ? AND data_source_id = ? AND mutation_id = ?", tenantId, dataSourceId, mutationId) > 0

    override fun hasUnfinishedWrites(tenantId: UUID, dataSourceId: UUID, mutationId: String): Boolean =
        (jdbc.queryForObject(
            "SELECT count(*) FROM data_idempotency WHERE tenant_id = ? AND data_source_id = ? AND mutation_id = ? AND state IN ('RESERVED', 'UNKNOWN')",
            Long::class.java, tenantId, dataSourceId, mutationId
        ) ?: 0L) > 0L

    private companion object {
        const val MAX_LIST = 1_000
        const val SELECT = "SELECT kind, mutation_id, definition::text AS definition, version, status, created_at, updated_at FROM data_mutations WHERE tenant_id = ? AND data_source_id = ?"
    }
}
