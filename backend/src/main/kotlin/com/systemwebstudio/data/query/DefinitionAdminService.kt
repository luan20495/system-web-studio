package com.systemwebstudio.data.query

import com.systemwebstudio.data.cache.ChangeCause
import com.systemwebstudio.data.cache.DataChange
import com.systemwebstudio.data.cache.DataChangeListener
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.DataConnectorRegistry
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRepository
import com.systemwebstudio.data.datasource.DataSourceScope
import com.systemwebstudio.data.datasource.DataTransactions
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.NoTransactions
import com.systemwebstudio.data.datasource.RateLimitGate
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayGuard
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.data.gateway.auditFields
import tools.jackson.databind.JsonNode
import java.util.UUID

/** What a client may say when it creates a query definition: the id and kind route/body-level, the document as raw JSON that [DefinitionDocuments] validates. */
class QueryDefinitionCreate(val queryId: String, val kind: String, val definition: JsonNode, val status: DefinitionStatus = DefinitionStatus.ACTIVE) {
    override fun toString() = "QueryDefinitionCreate(queryId=$queryId, kind=$kind)"
}
class MutationDefinitionCreate(val mutationId: String, val kind: MutationKind, val definition: JsonNode, val status: DefinitionStatus = DefinitionStatus.ACTIVE) {
    override fun toString() = "MutationDefinitionCreate(mutationId=$mutationId, kind=$kind)"
}
/** At least one of [definition] (replaces the whole document) and [status]; [expectedVersion], when given, must equal the stored version. */
class DefinitionPatch(val definition: JsonNode? = null, val status: DefinitionStatus? = null, val expectedVersion: Long? = null) {
    override fun toString() = "DefinitionPatch(definition=${definition != null}, status=$status, expectedVersion=$expectedVersion)"
}

/**
 * Management API contract §3.5: create / read / change / delete the approved query and mutation definitions of one data source. Definitions are the ONLY
 * things a browser can name at run time, which is why this is the only way a tenant defines new ones — and why it is strict: the document is rebuilt through
 * the Kotlin definition types (the same validation the gateway applies on every load), checked by the connector of the data source (SQL guard, mutation target,
 * `writable`), and never echoed back into an error.
 *
 * Authorisation is C1's, through [GatewayGuard] (default deny): listing summaries needs `DATASOURCE_READ` (DATA_SOURCE_VIEW); the full definition and every
 * change need `DATASOURCE_MANAGE` (DATA_SOURCE_MANAGE). No new permission exists. Every change runs in one [DataTransactions] unit with its audit row
 * (`DATA_QUERY_DEFINITION_CHANGED` / `DATA_MUTATION_DEFINITION_CHANGED`: ids, version, status and a hash of the document, never the document), so a change that
 * cannot be audited is not applied. A data source of another workspace or tenant is exactly as "not found" as one that does not exist.
 */
class DefinitionAdminService(
    private val repository: DataSourceRepository,
    private val registry: DataConnectorRegistry,
    private val guard: GatewayGuard,
    private val limits: RateLimitGate,
    private val audit: DataAuditSink,
    private val queries: QueryDefinitionStore,
    private val mutations: MutationDefinitionStore,
    private val listener: DataChangeListener,
    private val scope: DataSourceScope = DataSourceScope.WORKSPACE,
    private val tx: DataTransactions = NoTransactions
) {
    // ------------------------------------------------------------------------------------------------ queries

    /** summaries (the caller projects id, kind, status, version, parameter names): needs only DATA_SOURCE_VIEW */
    fun listQueries(ctx: GatewayContext, dataSourceId: UUID): List<StoredQueryDefinition> {
        guard.require(ctx, GatewayOperation.DATASOURCE_READ, dataSourceId)
        source(ctx, dataSourceId)
        return queries.list(ctx.tenantId, dataSourceId)
    }

    fun getQuery(ctx: GatewayContext, dataSourceId: UUID, queryId: String): StoredQueryDefinition {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, dataSourceId)
        source(ctx, dataSourceId)
        return queries.find(ctx.tenantId, dataSourceId, checkId(queryId)) ?: throw notFound("query definition not found")
    }

    fun createQuery(ctx: GatewayContext, dataSourceId: UUID, create: QueryDefinitionCreate): StoredQueryDefinition {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, dataSourceId)
        throttle(ctx)
        val stored = tx.run {
            val ds = source(ctx, dataSourceId, lock = true)
            val def = DefinitionDocuments.parseQuery(create.kind, checkId(create.queryId), ctx.tenantId, dataSourceId, 1, create.definition)
            registry.require(ds.connectorType).validateQueryDefinition(def, ds.ref.configNonSecret)
            queries.insert(def, create.status)
            trail(DataAuditActions.QUERY_DEFINITION_CHANGED, ctx, dataSourceId, "create", "queryId", def.id, def.version, create.status, DefinitionDocuments.fingerprint(def))
            queries.find(ctx.tenantId, dataSourceId, def.id) ?: throw ConnectorFailure(FailureCodes.INTERNAL, "unexpected error")
        }
        invalidated(ctx, dataSourceId)
        return stored
    }

    fun updateQuery(ctx: GatewayContext, dataSourceId: UUID, queryId: String, patch: DefinitionPatch): StoredQueryDefinition {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, dataSourceId)
        throttle(ctx)
        val stored = tx.run {
            val ds = source(ctx, dataSourceId, lock = true)
            val current = queries.find(ctx.tenantId, dataSourceId, checkId(queryId)) ?: throw notFound("query definition not found")
            checkExpected(patch.expectedVersion, current.definition.version)
            val status = patch.status ?: current.status
            val def = patch.definition?.let { DefinitionDocuments.parseQuery(DefinitionDocuments.kindOf(current.definition), current.definition.id, ctx.tenantId, dataSourceId, current.definition.version + 1, it) }
                ?: current.definition.withVersion(current.definition.version + 1)
            if (patch.definition != null) registry.require(ds.connectorType).validateQueryDefinition(def, ds.ref.configNonSecret)
            if (!queries.replace(def, status, current.definition.version)) throw conflict()
            trail(DataAuditActions.QUERY_DEFINITION_CHANGED, ctx, dataSourceId, "update", "queryId", def.id, def.version, status, DefinitionDocuments.fingerprint(def))
            queries.find(ctx.tenantId, dataSourceId, def.id) ?: throw ConnectorFailure(FailureCodes.INTERNAL, "unexpected error")
        }
        invalidated(ctx, dataSourceId)
        return stored
    }

    fun deleteQuery(ctx: GatewayContext, dataSourceId: UUID, queryId: String) {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, dataSourceId)
        throttle(ctx)
        tx.run {
            source(ctx, dataSourceId, lock = true)
            val current = queries.find(ctx.tenantId, dataSourceId, checkId(queryId)) ?: throw notFound("query definition not found")
            if (!queries.delete(ctx.tenantId, dataSourceId, current.definition.id)) throw notFound("query definition not found")
            trail(DataAuditActions.QUERY_DEFINITION_CHANGED, ctx, dataSourceId, "delete", "queryId", current.definition.id, current.definition.version, current.status, DefinitionDocuments.fingerprint(current.definition))
        }
        invalidated(ctx, dataSourceId)
    }

    // ------------------------------------------------------------------------------------------------ mutations

    fun listMutations(ctx: GatewayContext, dataSourceId: UUID): List<StoredMutationDefinition> {
        guard.require(ctx, GatewayOperation.DATASOURCE_READ, dataSourceId)
        source(ctx, dataSourceId)
        return mutations.list(ctx.tenantId, dataSourceId)
    }

    fun getMutation(ctx: GatewayContext, dataSourceId: UUID, mutationId: String): StoredMutationDefinition {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, dataSourceId)
        source(ctx, dataSourceId)
        return mutations.find(ctx.tenantId, dataSourceId, checkId(mutationId)) ?: throw notFound("mutation definition not found")
    }

    fun createMutation(ctx: GatewayContext, dataSourceId: UUID, create: MutationDefinitionCreate): StoredMutationDefinition {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, dataSourceId)
        throttle(ctx)
        val stored = tx.run {
            val ds = source(ctx, dataSourceId, lock = true)
            val def = DefinitionDocuments.parseMutation(create.kind, checkId(create.mutationId), ctx.tenantId, dataSourceId, 1, create.definition)
            registry.require(ds.connectorType).validateMutationDefinition(def, ds.ref.configNonSecret)
            mutations.insert(def, create.status)
            trail(DataAuditActions.MUTATION_DEFINITION_CHANGED, ctx, dataSourceId, "create", "mutationId", def.id, def.version, create.status, DefinitionDocuments.fingerprint(def))
            mutations.find(ctx.tenantId, dataSourceId, def.id) ?: throw ConnectorFailure(FailureCodes.INTERNAL, "unexpected error")
        }
        invalidated(ctx, dataSourceId)
        return stored
    }

    fun updateMutation(ctx: GatewayContext, dataSourceId: UUID, mutationId: String, patch: DefinitionPatch): StoredMutationDefinition {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, dataSourceId)
        throttle(ctx)
        val stored = tx.run {
            val ds = source(ctx, dataSourceId, lock = true)
            val current = mutations.find(ctx.tenantId, dataSourceId, checkId(mutationId)) ?: throw notFound("mutation definition not found")
            checkExpected(patch.expectedVersion, current.definition.version)
            val status = patch.status ?: current.status
            val def = patch.definition?.let { DefinitionDocuments.parseMutation(current.definition.kind, current.definition.id, ctx.tenantId, dataSourceId, current.definition.version + 1, it) }
                ?: current.definition.copy(version = current.definition.version + 1)
            if (patch.definition != null) registry.require(ds.connectorType).validateMutationDefinition(def, ds.ref.configNonSecret)
            if (!mutations.replace(def, status, current.definition.version)) throw conflict()
            trail(DataAuditActions.MUTATION_DEFINITION_CHANGED, ctx, dataSourceId, "update", "mutationId", def.id, def.version, status, DefinitionDocuments.fingerprint(def))
            mutations.find(ctx.tenantId, dataSourceId, def.id) ?: throw ConnectorFailure(FailureCodes.INTERNAL, "unexpected error")
        }
        invalidated(ctx, dataSourceId)
        return stored
    }

    /** refused with CONFLICT while an idempotency record of the mutation is RESERVED or UNKNOWN: a write may still be running or may have been applied */
    fun deleteMutation(ctx: GatewayContext, dataSourceId: UUID, mutationId: String) {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, dataSourceId)
        throttle(ctx)
        tx.run {
            source(ctx, dataSourceId, lock = true)
            val current = mutations.find(ctx.tenantId, dataSourceId, checkId(mutationId)) ?: throw notFound("mutation definition not found")
            if (mutations.hasUnfinishedWrites(ctx.tenantId, dataSourceId, current.definition.id))
                throw ConnectorFailure(FailureCodes.CONFLICT, "a write through this mutation may still be in flight or its outcome is unknown; it cannot be deleted yet")
            if (!mutations.delete(ctx.tenantId, dataSourceId, current.definition.id)) throw notFound("mutation definition not found")
            trail(DataAuditActions.MUTATION_DEFINITION_CHANGED, ctx, dataSourceId, "delete", "mutationId", current.definition.id, current.definition.version, current.status, DefinitionDocuments.fingerprint(current.definition))
        }
        invalidated(ctx, dataSourceId)
    }

    // ------------------------------------------------------------------------------------------------ internals

    /** Same answer for "missing", "another tenant's" and "another workspace's" data source; with [lock] the row is held until the unit ends (serialises editors of one source) */
    private fun source(ctx: GatewayContext, dataSourceId: UUID, lock: Boolean = false): DataSource {
        val ds = when (scope) {
            DataSourceScope.TENANT -> repository.find(ctx.tenantId, dataSourceId)
            DataSourceScope.WORKSPACE -> (ctx.workspaceId ?: throw notFound("data source not found")).let { w ->
                if (lock) repository.findInWorkspaceForUpdate(ctx.tenantId, w, dataSourceId) else repository.findInWorkspace(ctx.tenantId, w, dataSourceId)
            }
        }
        if (ds == null || ds.tenantId != ctx.tenantId || (scope == DataSourceScope.WORKSPACE && ds.workspaceId != ctx.workspaceId)) throw notFound("data source not found")
        return ds
    }

    private fun trail(action: String, ctx: GatewayContext, dataSourceId: UUID, change: String, idKey: String, id: String, version: Long, status: DefinitionStatus, hash: String) {
        audit.record(action, ctx.tenantId, dataSourceId, mapOf("change" to change, idKey to id, "version" to version, "status" to status.name, "hash" to hash) + ctx.auditFields())
    }

    /** a changed definition makes cached results of the data source stale; this runs after the unit committed */
    private fun invalidated(ctx: GatewayContext, dataSourceId: UUID) { listener.onChange(DataChange(ctx.tenantId, dataSourceId, ChangeCause.DATASOURCE_UPDATED)) }

    private fun checkId(id: String): String = id.takeIf { SqlQueryDefinition.ID.matches(it) } ?: throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "invalid definition id")
    private fun checkExpected(expected: Long?, stored: Long) { if (expected != null && expected != stored) throw conflict() }
    private fun conflict() = ConnectorFailure(FailureCodes.CONFLICT, "the definition changed; reload it and try again")
    private fun notFound(message: String) = ConnectorFailure(FailureCodes.NOT_FOUND, message)

    private fun throttle(ctx: GatewayContext) {
        if (!limits.allow("data-source:manage:${ctx.tenantId}", 60, 60)) throw ConnectorFailure(FailureCodes.RATE_LIMITED, "too many requests; retry later")
    }

    private fun QueryDefinition.withVersion(v: Long): QueryDefinition = when (this) { is SqlQueryDefinition -> copy(version = v); is RestQueryDefinition -> copy(version = v) }
}
