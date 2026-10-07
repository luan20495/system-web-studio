package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.query.MutationCatalog
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.QueryCatalog
import com.systemwebstudio.data.query.QueryDefinition
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/** the columns of one stored definition, decoded afterwards so a corrupt document is skipped instead of failing the query */
internal class RawDefinition(val kind: String, val id: String, val version: Long, val definition: String)

private fun raw(rs: ResultSet, idColumn: String) = RawDefinition(rs.getString("kind"), rs.getString(idColumn), rs.getLong("version"), rs.getString("definition"))

/**
 * `data_queries` (V28). Only ACTIVE rows are visible through the port; a DISABLED row, a definition that does not decode, or a row of another
 * tenant / data source is "not found". [save] and [disable] are not part of the port: they exist for fixtures and for the future management API
 * (BLOCKERS B-C0-W-03), which this change deliberately does not invent.
 */
class JdbcQueryCatalog(private val jdbc: JdbcTemplate) : QueryCatalog {
    override fun find(tenantId: UUID, dataSourceId: UUID, queryId: String): QueryDefinition? =
        jdbc.query("SELECT $SELECT AND query_id = ?", { rs, _ -> raw(rs, "query_id") }, tenantId, dataSourceId, queryId)
            .firstNotNullOfOrNull { decode(it.kind, it.id, tenantId, dataSourceId, it.version, it.definition) }

    override fun list(tenantId: UUID, dataSourceId: UUID): List<QueryDefinition> =
        jdbc.query("SELECT $SELECT ORDER BY query_id LIMIT $MAX_LIST", { rs, _ -> raw(rs, "query_id") }, tenantId, dataSourceId)
            .mapNotNull { decode(it.kind, it.id, tenantId, dataSourceId, it.version, it.definition) }

    /** insert, or replace with a strictly newer [QueryDefinition.version]; same or older version is a CONFLICT and changes nothing */
    fun save(def: QueryDefinition) {
        val changed = jdbc.update(
            """INSERT INTO data_queries (tenant_id, data_source_id, query_id, kind, definition, status, version) VALUES (?, ?, ?, ?, CAST(? AS jsonb), 'ACTIVE', ?)
               ON CONFLICT (tenant_id, data_source_id, query_id) DO UPDATE SET kind = EXCLUDED.kind, definition = EXCLUDED.definition, status = 'ACTIVE',
                   version = EXCLUDED.version, updated_at = CURRENT_TIMESTAMP
               WHERE data_queries.version < EXCLUDED.version""",
            def.tenantId, def.dataSourceId, def.id, DefinitionCodecs.kindOf(def), DefinitionCodecs.encodeQuery(def), def.version
        )
        if (changed == 0) throw JdbcSupport.conflict("the query definition changed concurrently")
    }

    fun disable(tenantId: UUID, dataSourceId: UUID, queryId: String): Boolean =
        jdbc.update("UPDATE data_queries SET status = 'DISABLED', updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND data_source_id = ? AND query_id = ?", tenantId, dataSourceId, queryId) > 0

    private fun decode(kind: String, id: String, t: UUID, d: UUID, v: Long, json: String) = DefinitionCodecs.decodeQuery(kind, id, t, d, v, json)

    private companion object {
        const val MAX_LIST = 1_000
        const val SELECT = "kind, query_id, definition::text AS definition, version FROM data_queries WHERE tenant_id = ? AND data_source_id = ? AND status = 'ACTIVE'"
    }
}

/** `data_mutations` (V28): the write-side twin of [JdbcQueryCatalog]; the `kind` column carries the [com.systemwebstudio.data.query.MutationKind]. */
class JdbcMutationCatalog(private val jdbc: JdbcTemplate) : MutationCatalog {
    override fun find(tenantId: UUID, dataSourceId: UUID, mutationId: String): MutationDefinition? =
        jdbc.query("SELECT $SELECT AND mutation_id = ?", { rs, _ -> raw(rs, "mutation_id") }, tenantId, dataSourceId, mutationId)
            .firstNotNullOfOrNull { decode(it.kind, it.id, tenantId, dataSourceId, it.version, it.definition) }

    override fun list(tenantId: UUID, dataSourceId: UUID): List<MutationDefinition> =
        jdbc.query("SELECT $SELECT ORDER BY mutation_id LIMIT $MAX_LIST", { rs, _ -> raw(rs, "mutation_id") }, tenantId, dataSourceId)
            .mapNotNull { decode(it.kind, it.id, tenantId, dataSourceId, it.version, it.definition) }

    fun save(def: MutationDefinition) {
        val changed = jdbc.update(
            """INSERT INTO data_mutations (tenant_id, data_source_id, mutation_id, kind, definition, status, version) VALUES (?, ?, ?, ?, CAST(? AS jsonb), 'ACTIVE', ?)
               ON CONFLICT (tenant_id, data_source_id, mutation_id) DO UPDATE SET kind = EXCLUDED.kind, definition = EXCLUDED.definition, status = 'ACTIVE',
                   version = EXCLUDED.version, updated_at = CURRENT_TIMESTAMP
               WHERE data_mutations.version < EXCLUDED.version""",
            def.tenantId, def.dataSourceId, def.id, def.kind.name, DefinitionCodecs.encodeMutation(def), def.version
        )
        if (changed == 0) throw JdbcSupport.conflict("the mutation definition changed concurrently")
    }

    fun disable(tenantId: UUID, dataSourceId: UUID, mutationId: String): Boolean =
        jdbc.update("UPDATE data_mutations SET status = 'DISABLED', updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND data_source_id = ? AND mutation_id = ?", tenantId, dataSourceId, mutationId) > 0

    private fun decode(kind: String, id: String, t: UUID, d: UUID, v: Long, json: String) = DefinitionCodecs.decodeMutation(kind, id, t, d, v, json)

    private companion object {
        const val MAX_LIST = 1_000
        const val SELECT = "kind, mutation_id, definition::text AS definition, version FROM data_mutations WHERE tenant_id = ? AND data_source_id = ? AND status = 'ACTIVE'"
    }
}
