package com.systemwebstudio.data.discovery

import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRepository
import com.systemwebstudio.data.datasource.DataSourceStatus
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayGuard
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.data.gateway.auditFields
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationCatalog
import com.systemwebstudio.data.query.QueryCatalog
import com.systemwebstudio.data.query.QueryParamSpec
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant
import java.util.UUID

/*
 * `AiDataCatalog` (contract app-definition §AI): the only description of the tenant's data that an AI planner may be given. C3 supplies it; the planner
 * "may only use data sources/operations/params from" it. It is a read-only projection of what the platform already knows — no new data model — built from
 * [AiSafeSchema] (masked structure, no raw samples) and the *approved* operation definitions, filtered by what the calling user may see.
 *
 * What is in it: data source id, display name and connector type; per data source the discovered structure (entities, fields with type / nullability /
 * key / `sensitive`, relations) and the ids and parameter shapes of the approved queries and mutations.
 * What is never in it: credentials or credential references, hosts, URLs, ports, any `configNonSecret`, SQL text, request paths, mutation targets, parameter
 * defaults, discovery warnings or metadata, cache settings, and sample values (unless explicitly asked for, in which case only [AiSafeSchema]'s output:
 * masked again, with every sensitive column removed outright).
 */

data class AiCatalogParam(val name: String, val type: String, val required: Boolean)
data class AiCatalogQuery(val id: String, val params: List<AiCatalogParam>, val maxRows: Int)
data class AiCatalogMutation(val id: String, val kind: String, val params: List<AiCatalogParam>, val entity: String?)

data class AiCatalogDataSource(
    val id: UUID, val name: String, val type: String,
    /** null until the data source has been discovered once */
    val schema: AiSchemaView?,
    val queries: List<AiCatalogQuery>, val mutations: List<AiCatalogMutation>
)

data class AiDataCatalog(val tenantId: UUID, val generatedAt: Instant, val dataSources: List<AiCatalogDataSource>) {
    /** the JSON a planner prompt is built from; every value in it is one of the fields above */
    fun toJson(): JsonNode = DataJson.toNode(linkedMapOf(
        "dataSources" to dataSources.map { ds ->
            linkedMapOf(
                "id" to ds.id.toString(), "name" to ds.name, "type" to ds.type,
                "schema" to ds.schema?.let { s -> linkedMapOf("version" to s.version, "entities" to s.entities.map(::entity)) },
                "queries" to ds.queries.map { q -> linkedMapOf("id" to q.id, "maxRows" to q.maxRows, "params" to q.params.map(::param)) },
                "mutations" to ds.mutations.map { m -> linkedMapOf("id" to m.id, "kind" to m.kind, "entity" to m.entity, "params" to m.params.map(::param)) }
            )
        }
    ))

    private fun param(p: AiCatalogParam) = linkedMapOf("name" to p.name, "type" to p.type, "required" to p.required)
    private fun entity(e: AiEntity) = linkedMapOf(
        "name" to e.name, "schema" to e.schema, "kind" to e.kind.name, "primaryKey" to e.primaryKey,
        "fields" to e.fields.map { linkedMapOf("name" to it.name, "type" to it.type.name, "nullable" to it.nullable, "primaryKey" to it.primaryKey, "sensitive" to it.sensitive) },
        "relations" to e.relations.map { linkedMapOf("name" to it.name, "from" to it.fromFields, "toEntity" to it.toEntity, "to" to it.toFields) },
        "sample" to e.sample
    )
}

/** Port the AI planner (C2) uses; the C0 wiring `AiDataCatalogAdapter` calls this implementation. */
interface AiDataCatalogProvider {
    fun catalog(ctx: GatewayContext, options: AiCatalogOptions): AiDataCatalog
    fun catalog(ctx: GatewayContext): AiDataCatalog = catalog(ctx, AiCatalogOptions())
}

/** [includeMaskedSamples]: also pass [AiSafeSchema]'s masked, sensitive-column-free sample rows (at most [MAX_SAMPLE_ROWS] per entity). Off by default. */
data class AiCatalogOptions(val includeMaskedSamples: Boolean = false) {
    companion object { const val MAX_SAMPLE_ROWS = 3 }
}

/**
 * Builds the catalog for the caller. **Per-caller filtering, default-deny**: a data source appears only if the caller may read it (`DATASOURCE_READ`) and
 * it is ACTIVE; its queries only if the caller may run queries on it (`QUERY_EXECUTE`), its mutations only with `MUTATION_EXECUTE`, its schema only with
 * `DATASOURCE_READ`. Nothing is listed on a failed or throwing permission check, and the whole result is empty for a caller without a workspace-scoped
 * context the authorizer accepts. The tenant is the context's; there is no way to ask for another one.
 */
class DefaultAiDataCatalogProvider(
    private val dataSources: DataSourceRepository, private val queries: QueryCatalog, private val mutations: MutationCatalog,
    private val schemas: SourceSchemaStore, private val guard: GatewayGuard, private val audit: DataAuditSink, private val clock: Clock = Clock.systemUTC()
) : AiDataCatalogProvider {

    override fun catalog(ctx: GatewayContext, options: AiCatalogOptions): AiDataCatalog {
        val visible = dataSources.list(ctx.tenantId)
            .filter { it.tenantId == ctx.tenantId && it.status == DataSourceStatus.ACTIVE && guard.allowed(ctx, GatewayOperation.DATASOURCE_READ, it.id) }
            .sortedBy { it.id.toString() }
            .take(MAX_DATA_SOURCES)
            .map { build(ctx, it, options) }
        audit.record(DataAuditActions.AI_CATALOG_BUILT, ctx.tenantId, null, mapOf("dataSources" to visible.size, "samples" to options.includeMaskedSamples) + ctx.auditFields())
        return AiDataCatalog(ctx.tenantId, clock.instant(), visible)
    }

    private fun build(ctx: GatewayContext, ds: DataSource, options: AiCatalogOptions): AiCatalogDataSource {
        val schema = schemas.latest(ctx.tenantId, ds.id)?.takeIf { it.tenantId == ctx.tenantId && it.dataSourceId == ds.id }?.let { AiSafeSchema.of(it) }?.let { view ->
            if (options.includeMaskedSamples) view.copy(entities = view.entities.map { e -> e.copy(sample = e.sample.take(AiCatalogOptions.MAX_SAMPLE_ROWS)) })
            else view.copy(entities = view.entities.map { e -> e.copy(sample = emptyList()) })                // no sample values unless explicitly requested
        }
        val q = if (guard.allowed(ctx, GatewayOperation.QUERY_EXECUTE, ds.id))
            queries.list(ctx.tenantId, ds.id).filter { it.tenantId == ctx.tenantId && it.dataSourceId == ds.id }.sortedBy { it.id }.take(MAX_OPERATIONS)
                .map { AiCatalogQuery(it.id, it.params.map(::param), it.maxRows) } else emptyList()
        val m = if (guard.allowed(ctx, GatewayOperation.MUTATION_EXECUTE, ds.id))
            mutations.list(ctx.tenantId, ds.id).filter { it.tenantId == ctx.tenantId && it.dataSourceId == ds.id }.sortedBy { it.id }.take(MAX_OPERATIONS)
                .map { AiCatalogMutation(it.id, it.kind.name, it.params.map(::param), it.entity) } else emptyList()
        return AiCatalogDataSource(ds.id, ds.name, ds.connectorType, schema, q, m)
    }

    private fun param(p: QueryParamSpec) = AiCatalogParam(p.name, p.type.name, p.required)          // the default value is deliberately left out

    companion object {
        const val MAX_DATA_SOURCES = 50
        const val MAX_OPERATIONS = 200
    }
}
