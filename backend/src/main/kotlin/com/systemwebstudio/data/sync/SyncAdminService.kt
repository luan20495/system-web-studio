package com.systemwebstudio.data.sync

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.DataSourceService
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.RateLimitGate
import com.systemwebstudio.data.gateway.auditFields
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayGuard
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.data.mapping.AppScope
import com.systemwebstudio.data.mapping.FieldType
import com.systemwebstudio.data.mapping.MappingCatalog
import com.systemwebstudio.data.mapping.MappingValidator
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryCatalog
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.util.UUID

/** What a caller says when creating a job. Tenant, creator and ids of the job itself are decided by the server. */
data class SyncJobSpec(
    val dataSourceId: UUID, val name: String, val queryId: String, val mappingRef: String, val viewModelRef: String, val keyField: String,
    val cursorField: String? = null, val cursorParam: String? = null, val fixedParams: Map<String, JsonNode> = emptyMap(),
    val intervalSeconds: Int = 900, val pageSize: Int = 500, val maxPagesPerRun: Int = 20
)

/** Create / pause / resume / delete / inspect sync jobs. Every method needs [GatewayOperation.SYNC_MANAGE] and is tenant-scoped; every change is audited. */
class SyncAdminService(
    private val store: SyncJobStore, private val service: DataSourceService, private val queries: QueryCatalog, private val mappings: MappingCatalog,
    private val guard: GatewayGuard, private val audit: DataAuditSink, private val runner: SyncRunner, private val limits: RateLimitGate,
    private val clock: Clock = Clock.systemUTC(), private val newId: () -> UUID = UUID::randomUUID
) {
    fun create(ctx: GatewayContext, spec: SyncJobSpec): SyncJob {
        guard.require(ctx, GatewayOperation.SYNC_MANAGE, spec.dataSourceId)
        val ds = service.resolve(ctx, spec.dataSourceId)
        if (store.list(ctx.tenantId, ds.id).size >= MAX_JOBS_PER_SOURCE) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "too many sync jobs for this data source")
        val job = try {
            SyncJob(newId(), ctx.tenantId, ds.id, spec.name, spec.queryId, spec.mappingRef, spec.viewModelRef, ctx.appVersionId, ctx.projectId, ctx.workspaceId, spec.keyField, spec.cursorField, spec.cursorParam,
                spec.fixedParams, spec.intervalSeconds, spec.pageSize, spec.maxPagesPerRun, createdBy = ctx.actorUserId, createdAt = clock.instant())
        } catch (e: IllegalArgumentException) { throw ConnectorFailure(FailureCodes.INVALID_PARAMS, e.message?.take(120) ?: "invalid sync job") }
        validate(ctx, job)
        store.list(ctx.tenantId, ds.id).firstOrNull { it.name == job.name }?.let { throw ConnectorFailure(FailureCodes.CONFLICT, "a sync job with this name exists") }
        val saved = store.save(job)
        audit.record(DataAuditActions.SYNC_JOB_CHANGED, ctx.tenantId, ds.id, mapOf("job" to saved.id.toString(), "change" to "created") + ctx.auditFields())
        return saved
    }

    fun pause(ctx: GatewayContext, jobId: UUID): SyncJob = change(ctx, jobId, "paused") { it.revised(clock.instant(), status = SyncJobStatus.PAUSED) }

    /** resuming re-validates the job against the current catalogs, so a job paused for a bad mapping only restarts once that is fixed */
    fun resume(ctx: GatewayContext, jobId: UUID): SyncJob = change(ctx, jobId, "resumed") { job -> job.revised(clock.instant(), status = SyncJobStatus.ACTIVE).also { validate(ctx, it) } }

    fun delete(ctx: GatewayContext, jobId: UUID) {
        val job = load(ctx, jobId)
        store.delete(ctx.tenantId, job.id)
        audit.record(DataAuditActions.SYNC_JOB_CHANGED, ctx.tenantId, job.dataSourceId, mapOf("job" to job.id.toString(), "change" to "deleted") + ctx.auditFields())
    }

    fun get(ctx: GatewayContext, jobId: UUID): Pair<SyncJob, SyncState?> { val j = load(ctx, jobId); return j to store.state(ctx.tenantId, j.id) }

    fun list(ctx: GatewayContext, dataSourceId: UUID): List<Pair<SyncJob, SyncState?>> {
        guard.require(ctx, GatewayOperation.SYNC_MANAGE, dataSourceId)
        service.resolve(ctx, dataSourceId)
        return store.list(ctx.tenantId, dataSourceId).map { it to store.state(ctx.tenantId, it.id) }
    }

    /** "sync now": runs immediately (still leased, so it never overlaps a scheduled run); at most one manual run per job per 30 seconds */
    fun runNow(ctx: GatewayContext, jobId: UUID): SyncRunResult {
        val job = load(ctx, jobId)
        if (!limits.allow("sync-now:${ctx.tenantId}:${job.id}", 1, 30)) throw ConnectorFailure(FailureCodes.RATE_LIMITED, "too many requests; retry later")
        return runner.run(ctx.tenantId, job.id, force = true)
    }

    private fun load(ctx: GatewayContext, jobId: UUID): SyncJob {
        val job = store.find(ctx.tenantId, jobId)
        // permission is checked against the job's data source when it exists; for an unknown id the tenant-level permission decides, so both look the same to a caller who lacks it
        guard.require(ctx, GatewayOperation.SYNC_MANAGE, job?.dataSourceId)
        if (job == null || job.tenantId != ctx.tenantId) throw ConnectorFailure(FailureCodes.SYNC_JOB_NOT_FOUND, "sync job not found")
        return job
    }

    private fun change(ctx: GatewayContext, jobId: UUID, what: String, f: (SyncJob) -> SyncJob): SyncJob {
        val job = load(ctx, jobId)
        val saved = store.save(f(job))
        audit.record(DataAuditActions.SYNC_JOB_CHANGED, ctx.tenantId, job.dataSourceId, mapOf("job" to job.id.toString(), "change" to what) + ctx.auditFields())
        return saved
    }

    private fun validate(ctx: GatewayContext, job: SyncJob) {
        val def = queries.find(ctx.tenantId, job.dataSourceId, job.queryId) ?: throw ConnectorFailure(FailureCodes.QUERY_NOT_FOUND, "query not found")
        val scope = AppScope(ctx.tenantId, ctx.projectId, ctx.appVersionId)
        val mapping = mappings.findMapping(scope, job.mappingRef) ?: throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "mapping not found")
        val vm = mappings.findViewModel(scope, job.viewModelRef) ?: throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "view model not found")
        MappingValidator.require(mapping, vm)
        val fields = vm.fields.associate { it.name to it.type }
        if (job.keyField !in fields) throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "key field is not in the view model")
        if (job.cursorField != null) {
            val type = fields[job.cursorField] ?: throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "cursor field is not in the view model")
            if (type !in setOf(FieldType.NUMBER, FieldType.DATETIME, FieldType.DATE, FieldType.STRING)) throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "cursor field must be a number, date, date-time or text")
            val p = def.params.firstOrNull { it.name == job.cursorParam } ?: throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "cursor parameter is not declared by the query")
            if (p.type == ParamType.BOOLEAN) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "a boolean cannot be a cursor")
        }
        job.fixedParams.keys.forEach { k -> if (def.params.none { it.name == k }) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "fixed parameter is not declared by the query") }
    }

    companion object { const val MAX_JOBS_PER_SOURCE = 20 }
}
