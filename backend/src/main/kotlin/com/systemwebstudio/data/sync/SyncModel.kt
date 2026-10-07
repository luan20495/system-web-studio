package com.systemwebstudio.data.sync

import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.mapping.FieldMapping
import com.systemwebstudio.data.query.QueryLimits
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * Sync V1 is **one-way, scheduled pull**: an approved query of a data source is executed on an interval, mapped through a mapping, and the
 * resulting records are handed to a [SyncSink] (an idempotent upsert-by-key port). [SyncDirection.PULL] is the only direction; the push /
 * two-way contract is written in the design doc (conflict policy, per-record version, tombstones) and deliberately has no code in V1.
 *
 * Incremental pulls use a cursor: [cursorField] is the view-model field whose maximum becomes the next checkpoint and [cursorParam] is the
 * query parameter that receives it on the next run. Without a cursor every run pulls the whole result (bounded by [maxPagesPerRun]).
 */
enum class SyncDirection { PULL }

enum class SyncJobStatus { ACTIVE, PAUSED }

data class SyncJob(
    val id: UUID, val tenantId: UUID, val dataSourceId: UUID, val name: String,
    val queryId: String, val mappingRef: String, val viewModelRef: String, val appVersionId: String? = null, val projectId: UUID? = null,
    /** workspace the job was created in: its owner's permission is re-checked there on every run (contract v2 data-runtime §5) */
    val workspaceId: UUID? = null,
    /** view-model field holding the record key (unique per record); every record without one is skipped and counted */
    val keyField: String,
    val cursorField: String? = null, val cursorParam: String? = null,
    val fixedParams: Map<String, JsonNode> = emptyMap(),
    val intervalSeconds: Int = 900, val pageSize: Int = 500, val maxPagesPerRun: Int = 20,
    val status: SyncJobStatus = SyncJobStatus.ACTIVE, val direction: SyncDirection = SyncDirection.PULL,
    val createdBy: UUID? = null, val createdAt: Instant, val updatedAt: Instant = createdAt, val version: Long = 1
) {
    init {
        require(NAME.matches(name)) { "invalid sync job name" }
        require(FieldMapping.TO.matches(keyField)) { "invalid key field" }
        require((cursorField == null) == (cursorParam == null)) { "cursorField and cursorParam go together" }
        require(cursorField == null || FieldMapping.TO.matches(cursorField)) { "invalid cursor field" }
        require(intervalSeconds in MIN_INTERVAL..MAX_INTERVAL) { "interval must be $MIN_INTERVAL..$MAX_INTERVAL seconds" }
        require(pageSize in 1..QueryLimits.MAX_ROWS && maxPagesPerRun in 1..MAX_PAGES) { "invalid paging" }
        require(fixedParams.size <= 40 && fixedParams.values.all { !DataJson.isContainer(it) }) { "invalid fixed parameters" }
    }
    fun revised(now: Instant, status: SyncJobStatus = this.status, intervalSeconds: Int = this.intervalSeconds) = copy(status = status, intervalSeconds = intervalSeconds, updatedAt = now, version = version + 1)
    companion object {
        val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}$")
        const val MIN_INTERVAL = 60; const val MAX_INTERVAL = 86_400; const val MAX_PAGES = 50
    }
}

/**
 * Mutable run state of a job, kept apart from the definition so a run never rewrites the definition and an edit never loses the checkpoint.
 * [checkpoint] = last committed cursor value (null before the first success); it moves only after the sink accepted every page of a run.
 */
data class SyncState(
    val jobId: UUID, val tenantId: UUID, val checkpoint: String? = null, val runCounter: Long = 0,
    val lastSuccessAt: Instant? = null, val lastErrorAt: Instant? = null, val lastErrorCode: String? = null, val consecutiveFailures: Int = 0,
    val nextRunAt: Instant, val leaseOwner: String? = null, val leaseUntil: Instant? = null,
    val lastRows: Int = 0, val lastPages: Int = 0
)

/** What a finished run writes back. Applied only if the caller still holds the lease and the job was not edited meanwhile. */
data class SyncStateUpdate(
    val jobVersion: Long, val checkpoint: String?, val success: Boolean, val errorCode: String?, val finishedAt: Instant, val nextRunAt: Instant,
    val rows: Int, val pages: Int, val pauseJob: Boolean
)

data class DueJob(val tenantId: UUID, val jobId: UUID)

/**
 * Port: persistence of jobs and their state (migration request in BOARD.md). Everything is tenant-scoped except [due], which exists for the
 * scheduler only and returns ids (never data); the scheduler then works on each job with its own tenant id.
 * [tryLease] and [finish] must be atomic: at most one worker holds a job at a time, and a worker whose lease expired and was taken over can
 * no longer commit (fencing by [owner]).
 */
interface SyncJobStore {
    fun find(tenantId: UUID, jobId: UUID): SyncJob?
    fun list(tenantId: UUID, dataSourceId: UUID? = null): List<SyncJob>
    fun save(job: SyncJob): SyncJob
    fun delete(tenantId: UUID, jobId: UUID)
    fun state(tenantId: UUID, jobId: UUID): SyncState?
    fun due(now: Instant, limit: Int): List<DueJob>
    /** @return the state as of the grant, or null when the job is paused, not due (unless [force]), missing, or leased by someone else whose lease is still valid */
    fun tryLease(tenantId: UUID, jobId: UUID, owner: String, now: Instant, leaseSeconds: Long, force: Boolean = false): SyncState?
    fun finish(tenantId: UUID, jobId: UUID, owner: String, update: SyncStateUpdate): Boolean
}

class SyncRecord(val key: String, val row: Map<String, JsonNode>) {
    override fun toString() = "SyncRecord(key=$key)"          // rows may be personal data
}

data class SyncApplyResult(val upserted: Int, val unchanged: Int)

/**
 * Port: where pulled records land (C2/C4 or a platform table decide; the table is a migration request). **Contract:** `apply` is an idempotent
 * upsert by `(tenantId, jobId, record key)`, and a repeated call with the same `(runKey, page)` — a retried page after a crash — changes nothing
 * and returns the same counts. A sink never sees another tenant's job and must scope its storage by [tenantId].
 */
interface SyncSink {
    fun apply(tenantId: UUID, jobId: UUID, runKey: String, page: Int, records: List<SyncRecord>): SyncApplyResult
}

