package com.systemwebstudio.data.sync

import com.systemwebstudio.data.cache.ChangeCause
import com.systemwebstudio.data.cache.DataChange
import com.systemwebstudio.data.cache.DataChangeListener
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayGuard
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.tenancy.ActorKind
import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.DataSourceService
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.auditFields
import com.systemwebstudio.data.mapping.AppScope
import com.systemwebstudio.data.mapping.FieldType
import com.systemwebstudio.data.mapping.MappingCatalog
import com.systemwebstudio.data.mapping.MappingEngine
import com.systemwebstudio.data.mapping.MappingValidator
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.PageSpec
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryCatalog
import com.systemwebstudio.data.query.QueryRequest
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What one run did. Counts only. */
data class SyncRunResult(val jobId: UUID, val outcome: Outcome, val rows: Int, val pages: Int, val upserted: Int, val skippedNoKey: Int, val checkpoint: String?, val errorCode: String? = null) {
    enum class Outcome { SUCCESS, FAILED_RETRY, FAILED_PAUSED, NOT_RUN, SUPERSEDED }
}

/**
 * Runs sync jobs. One run = (lease) → pull pages with the checkpoint → map → keep records with a key → apply to the sink page by page →
 * **only then** commit the new checkpoint. A failure anywhere leaves the checkpoint where it was, so the next run re-delivers the same pages
 * and the sink's idempotent upsert makes that harmless (at-least-once delivery, exactly-once effect).
 *
 * Failure policy: transient causes (timeouts, upstream 5xx, connection, rate limit) back off exponentially (base [BACKOFF_BASE_SECONDS], cap
 * [BACKOFF_CAP_SECONDS]) and the job pauses after [MAX_CONSECUTIVE_FAILURES]; permanent causes (bad mapping, missing query, permission, disabled
 * source, a source that ignores the cursor) pause the job immediately so it does not hammer a third party. A paused job is resumed by an
 * authorised user through [SyncAdminService].
 *
 * A run acts **as the job's owner**: it builds a [GatewayContext] for the job's creator ([ActorKind.USER], the workspace/project/version the job was
 * created in) and asks the [GatewayGuard] for `QUERY_EXECUTE` on every run, so a user who lost access (removed from the workspace, role
 * downgraded, tenant suspended) can no longer pull data through an old job (contract v2 data-runtime §5; C1's authorizer denies non-USER actors for now, which
 * is why the run is not a SYSTEM actor). A denial is permanent: the job pauses until an authorised user resumes it. Everything a run touches is tenant-scoped.
 */
class SyncRunner(
    private val store: SyncJobStore, private val service: DataSourceService, private val queries: QueryCatalog, private val mappings: MappingCatalog,
    private val sink: SyncSink, private val listener: DataChangeListener, private val audit: DataAuditSink, private val guard: GatewayGuard,
    private val engine: MappingEngine = MappingEngine(), private val clock: Clock = Clock.systemUTC(), private val owner: String = "node-" + UUID.randomUUID().toString().take(8)
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Scheduler entry point: runs due jobs (bounded). @return the number of jobs attempted */
    fun tick(maxJobs: Int = 10): Int {
        var n = 0
        for (d in store.due(clock.instant(), maxJobs.coerceIn(1, 50))) { run(d.tenantId, d.jobId, force = false); n++ }
        return n
    }

    fun run(tenantId: UUID, jobId: UUID, force: Boolean): SyncRunResult {
        val now = clock.instant()
        val state = store.tryLease(tenantId, jobId, owner, now, LEASE_SECONDS, force) ?: return notRun(jobId)
        val job = store.find(tenantId, jobId)
        if (job == null || job.status != SyncJobStatus.ACTIVE) { return notRun(jobId) }
        val started = now
        var rows = 0; var pages = 0; var upserted = 0; var noKey = 0
        var checkpoint = state.checkpoint
        try {
            val ctx = GatewayContext(TenantContext(tenantId, null), job.createdBy, ActorKind.USER, "sync-$jobId-${state.runCounter + 1}", job.workspaceId, job.projectId, job.appVersionId)
            guard.require(ctx, GatewayOperation.QUERY_EXECUTE, job.dataSourceId)
            val ds = service.resolve(ctx, job.dataSourceId)
            val def = queries.find(tenantId, ds.id, job.queryId) ?: throw ConnectorFailure(FailureCodes.QUERY_NOT_FOUND, "query not found")
            if (def.tenantId != tenantId || def.dataSourceId != ds.id) throw ConnectorFailure(FailureCodes.QUERY_NOT_FOUND, "query not found")
            val scope = AppScope(tenantId, job.projectId, job.appVersionId)
            val mapping = mappings.findMapping(scope, job.mappingRef) ?: throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "mapping not found")
            val vm = mappings.findViewModel(scope, job.viewModelRef) ?: throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "view model not found")
            MappingValidator.require(mapping, vm)
            val fieldTypes = vm.fields.associate { it.name to it.type }
            if (job.keyField !in fieldTypes || (job.cursorField != null && job.cursorField !in fieldTypes)) throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "key or cursor field is not in the view model")
            val cursorType = job.cursorField?.let { fieldTypes.getValue(it) }
            val cursorParamType = job.cursorParam?.let { p -> def.params.firstOrNull { it.name == p }?.type ?: throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "cursor parameter is not declared by the query") }
            val runKey = "$jobId:${state.runCounter + 1}"
            val deadline = started.plusSeconds(RUN_BUDGET_SECONDS)
            val previous = state.checkpoint
            var best: String? = previous

            for (page in 0 until job.maxPagesPerRun) {
                if (clock.instant().isAfter(deadline)) break
                val params = LinkedHashMap<String, JsonNode>(job.fixedParams)
                if (job.cursorParam != null && previous != null) params[job.cursorParam] = cursorNode(previous, cursorParamType!!)
                val result = service.runQuery(ctx, ds.id, QueryRequest(def.id, params, PageSpec(job.pageSize, page * job.pageSize), ctx.tenant))
                val data = engine.apply(result, mapping, vm)
                val records = ArrayList<SyncRecord>(data.rows.size)
                for (row in data.rows) {
                    val key = row[job.keyField]?.takeUnless { it.isNull }?.let { if (DataJson.isText(it)) DataJson.text(it) else it.toString() }
                    if (key == null || key.isEmpty() || key.length > 200) { noKey++; continue }
                    if (job.cursorField != null) {
                        val c = row[job.cursorField]?.takeUnless { it.isNull }?.let { Cursors.render(it, cursorType!!) }
                        if (c != null) {
                            if (previous != null && Cursors.compare(cursorType!!, c, previous) < 0) throw ConnectorFailure(FailureCodes.SYNC_ORDER_VIOLATION, "source returned records older than the checkpoint")
                            if (best == null || Cursors.compare(cursorType!!, c, best) > 0) best = c
                        }
                    }
                    records += SyncRecord(key, row)
                }
                rows += data.rows.size; pages++
                if (records.isNotEmpty()) upserted += sink.apply(tenantId, jobId, runKey, page, records).upserted
                if (result.rows.size < job.pageSize) break                       // a short page is the last page
            }
            checkpoint = best
            val finished = clock.instant()
            val committed = store.finish(tenantId, jobId, owner, SyncStateUpdate(job.version, checkpoint, true, null, finished, finished.plusSeconds(job.intervalSeconds.toLong()), rows, pages, false))
            if (!committed) { auditRun(job, ctx, rows, pages, upserted, false, "SUPERSEDED"); return SyncRunResult(jobId, SyncRunResult.Outcome.SUPERSEDED, rows, pages, upserted, noKey, state.checkpoint) }
            auditRun(job, ctx, rows, pages, upserted, true, null)
            if (upserted > 0) listener.onChange(DataChange(tenantId, ds.id, ChangeCause.SYNC, listOf(job.queryId), null, null, emptyList()))
            return SyncRunResult(jobId, SyncRunResult.Outcome.SUCCESS, rows, pages, upserted, noKey, checkpoint)
        } catch (e: Exception) {
            val code = if (e is ConnectorFailure) e.code else FailureCodes.INTERNAL
            if (e !is ConnectorFailure) log.error("sync run failed unexpectedly: {}", e.javaClass.name)
            val failures = state.consecutiveFailures + 1
            val permanent = code in PERMANENT || failures >= MAX_CONSECUTIVE_FAILURES
            val finished = clock.instant()
            val next = finished.plusSeconds(backoff(failures))
            store.finish(tenantId, jobId, owner, SyncStateUpdate(job.version, state.checkpoint, false, code, finished, next, rows, pages, permanent))
            audit.record(DataAuditActions.SYNC_RUN, tenantId, job.dataSourceId, mapOf("job" to jobId.toString(), "ok" to false, "code" to code, "rows" to rows, "pages" to pages,
                "paused" to permanent, "failures" to failures, "actor" to job.createdBy?.toString(), "request" to "sync-$jobId-${state.runCounter + 1}"))
            return SyncRunResult(jobId, if (permanent) SyncRunResult.Outcome.FAILED_PAUSED else SyncRunResult.Outcome.FAILED_RETRY, rows, pages, upserted, noKey, state.checkpoint, code)
        }
    }

    private fun notRun(id: UUID) = SyncRunResult(id, SyncRunResult.Outcome.NOT_RUN, 0, 0, 0, 0, null)

    private fun auditRun(job: SyncJob, ctx: GatewayContext, rows: Int, pages: Int, upserted: Int, ok: Boolean, code: String?) =
        audit.record(DataAuditActions.SYNC_RUN, job.tenantId, job.dataSourceId, mapOf("job" to job.id.toString(), "ok" to ok, "code" to code, "rows" to rows, "pages" to pages, "upserted" to upserted) + ctx.auditFields())

    private fun cursorNode(value: String, type: ParamType): JsonNode = when (type) {
        ParamType.INTEGER -> DataJson.toNode(value.toLongOrNull() ?: throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "checkpoint does not fit the cursor parameter"))
        ParamType.NUMBER -> DataJson.toNode(runCatching { BigDecimal(value) }.getOrNull() ?: throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "checkpoint does not fit the cursor parameter"))
        ParamType.BOOLEAN -> throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "a boolean cannot be a cursor")
        else -> DataJson.toNode(value)
    }

    companion object {
        const val LEASE_SECONDS = 300L
        const val RUN_BUDGET_SECONDS = 60L
        const val BACKOFF_BASE_SECONDS = 30L
        const val BACKOFF_CAP_SECONDS = 3_600L
        const val MAX_CONSECUTIVE_FAILURES = 10
        /** failures that will not fix themselves: pause instead of retrying */
        val PERMANENT = setOf(FailureCodes.INVALID_MAPPING, FailureCodes.MAPPING_FAILED, FailureCodes.QUERY_NOT_FOUND, FailureCodes.INVALID_PARAMS, FailureCodes.INVALID_QUERY,
            FailureCodes.PERMISSION_DENIED, FailureCodes.DISABLED, FailureCodes.NOT_FOUND, FailureCodes.TENANT_MISMATCH, FailureCodes.SYNC_ORDER_VIOLATION,
            FailureCodes.UNSUPPORTED_TYPE, FailureCodes.NOT_IMPLEMENTED, FailureCodes.INVALID_CONFIG, FailureCodes.INVALID_CREDENTIAL, FailureCodes.AUTH_REJECTED, FailureCodes.ADDRESS_BLOCKED)
        fun backoff(failures: Int): Long = minOf(BACKOFF_CAP_SECONDS, BACKOFF_BASE_SECONDS shl (failures - 1).coerceIn(0, 16))
    }
}

/** Cursor values compared by the view-model type of the cursor field; the stored checkpoint is always a canonical string. */
internal object Cursors {
    fun render(node: JsonNode, type: FieldType): String = when (type) {
        FieldType.NUMBER -> node.decimalValue().stripTrailingZeros().toPlainString()
        else -> if (DataJson.isText(node)) DataJson.text(node) else node.toString()
    }.also { if (it.length > 200) throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "cursor value is too long") }

    fun compare(type: FieldType, a: String, b: String): Int = try {
        when (type) {
            FieldType.NUMBER -> BigDecimal(a).compareTo(BigDecimal(b))
            FieldType.DATETIME -> Instant.parse(a).compareTo(Instant.parse(b))
            FieldType.DATE -> LocalDate.parse(a).compareTo(LocalDate.parse(b))
            else -> a.compareTo(b)
        }
    } catch (e: Exception) { throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "cursor value does not match its type") }
}
