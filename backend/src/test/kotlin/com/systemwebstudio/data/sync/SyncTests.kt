package com.systemwebstudio.data.sync

import com.systemwebstudio.data.putNow
import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.InMemorySyncJobStore
import com.systemwebstudio.data.InMemorySyncSink
import com.systemwebstudio.data.cache.CacheKeyParts
import com.systemwebstudio.data.cache.CacheScope
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.data.mapping.MappingJson
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.SqlQueryDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Sync V1: one-way scheduled pull with lease, checkpoint, retry/backoff, pause, idempotent delivery and tenant isolation. */
class SyncTests {
    private val f = GatewayFixture()
    private val store = InMemorySyncJobStore(f.clock)
    private val sink = InMemorySyncSink()
    private val runner = SyncRunner(store, f.service, f.queries, f.mappings, sink, f.notifier, f.audit, f.guard, clock = f.clock, owner = "node-1")
    private val admin = SyncAdminService(store, f.service, f.queries, f.mappings, f.guard, f.audit, runner, f.limiter, f.clock)

    private val tenant = f.tenant()
    private val ctx: GatewayContext = f.ctx(tenant)
    private lateinit var ds: DataSource
    private var source: MutableList<Map<String, Any?>> = (1..5).map { mapOf("id" to it, "rev" to it * 10, "name" to "n$it") }.toMutableList()

    private fun setup(pageSize: Int = 2) {
        ds = f.register(tenant)
        f.queries.add(SqlQueryDefinition("items", tenant.tenantId, ds.id, "SELECT 1", listOf(QueryParamSpec("since", ParamType.INTEGER, false)), 1000, 0, 1))
        f.mappings.put(tenant.tenantId, MappingJson.mapping(DataJson.parse("""{"id":"m-items","queryRef":"items","fields":[{"from":"id","to":"id","nullable":false},{"from":"rev","to":"rev"},{"from":"name","to":"name"}]}""".toByteArray())))
        f.mappings.put(tenant.tenantId, MappingJson.viewModel(DataJson.parse("""{"id":"vm-items","mappingRef":"m-items","fields":[{"name":"id","type":"NUMBER"},{"name":"rev","type":"NUMBER"},{"name":"name","type":"STRING"}]}""".toByteArray())))
        f.connector.rowsProvider = { req, _ ->
            val since = req.params["since"]?.asLong()
            val ordered = source.filter { since == null || (it["rev"] as Int) >= since }.sortedBy { it["rev"] as Int }
            val off = req.page?.offset ?: 0; val lim = req.page?.limit ?: ordered.size
            ordered.drop(off).take(lim)
        }
        pageSizeUsed = pageSize
    }
    private var pageSizeUsed = 2
    private fun spec(cursor: Boolean = true, name: String = "items sync", key: String = "id") = SyncJobSpec(ds.id, name, "items", "m-items", "vm-items", key,
        if (cursor) "rev" else null, if (cursor) "since" else null, emptyMap(), 60, pageSizeUsed, 10)
    private fun newJob(cursor: Boolean = true) = admin.create(ctx, spec(cursor))
    private fun failure(block: () -> Unit) = f.failure(block)

    @Test fun `first run pulls every page applies it and commits the checkpoint`() {
        setup(); val job = newJob()
        val r = runner.run(tenant.tenantId, job.id, force = false)
        assertThat(r.outcome).isEqualTo(SyncRunResult.Outcome.SUCCESS)
        assertThat(r.rows).isEqualTo(5); assertThat(r.pages).isEqualTo(3); assertThat(r.upserted).isEqualTo(5)
        assertThat(r.checkpoint).isEqualTo("50")
        assertThat(sink.keysOf(tenant.tenantId, job.id)).containsExactly("1", "2", "3", "4", "5")
        val s = store.state(tenant.tenantId, job.id)!!
        assertThat(s.checkpoint).isEqualTo("50"); assertThat(s.lastSuccessAt).isEqualTo(f.clock.instant()); assertThat(s.consecutiveFailures).isEqualTo(0)
        assertThat(s.nextRunAt).isEqualTo(f.clock.instant().plusSeconds(60)); assertThat(s.leaseOwner).isNull()
    }

    @Test fun `the next run asks only for what changed since the checkpoint`() {
        setup(); val job = newJob(); runner.run(tenant.tenantId, job.id, false)
        val seenSince = mutableListOf<Long?>()
        f.connector.queryHook = { req, _, _ -> seenSince += req.params["since"]?.asLong() }
        source += mapOf("id" to 6, "rev" to 60, "name" to "n6"); source[0] = mapOf("id" to 1, "rev" to 70, "name" to "renamed")
        f.clock.advanceSeconds(61)
        val r = runner.run(tenant.tenantId, job.id, false)
        assertThat(seenSince.first()).isEqualTo(50L)
        assertThat(r.checkpoint).isEqualTo("70")
        assertThat(r.upserted).isGreaterThanOrEqualTo(2)
        assertThat(sink.rows[Triple(tenant.tenantId, job.id, "1")]!!["name"].toString()).isEqualTo("\"renamed\"")
        assertThat(sink.keysOf(tenant.tenantId, job.id)).containsExactly("1", "2", "3", "4", "5", "6")
    }

    @Test fun `running the same data again changes nothing`() {
        setup(); val job = newJob(cursor = false)
        runner.run(tenant.tenantId, job.id, false)
        val before = sink.rows.toMap()
        f.clock.advanceSeconds(61)
        val r = runner.run(tenant.tenantId, job.id, false)
        assertThat(r.upserted).isEqualTo(0)
        assertThat(sink.rows.toMap()).isEqualTo(before)
        assertThat(sink.keysOf(tenant.tenantId, job.id)).hasSize(5)
    }

    @Test fun `a crash in the sink keeps the checkpoint and the retry delivers without duplicates`() {
        setup(); val job = newJob()
        sink.failOnPage = 1
        val r = runner.run(tenant.tenantId, job.id, false)
        assertThat(r.outcome).isEqualTo(SyncRunResult.Outcome.FAILED_RETRY)
        assertThat(store.state(tenant.tenantId, job.id)!!.checkpoint).isNull()          // nothing committed
        assertThat(sink.keysOf(tenant.tenantId, job.id)).hasSize(2)                     // page 0 landed before the crash
        sink.failOnPage = null
        f.clock.advanceSeconds(31)                                                      // first backoff is 30s
        val ok = runner.run(tenant.tenantId, job.id, false)
        assertThat(ok.outcome).isEqualTo(SyncRunResult.Outcome.SUCCESS)
        assertThat(sink.keysOf(tenant.tenantId, job.id)).containsExactly("1", "2", "3", "4", "5")
        assertThat(store.state(tenant.tenantId, job.id)!!.checkpoint).isEqualTo("50")
        assertThat(store.state(tenant.tenantId, job.id)!!.consecutiveFailures).isEqualTo(0)
    }

    @Test fun `a repeated page of the same run is not applied twice`() {
        val t = UUID.randomUUID(); val j = UUID.randomUUID()
        val rec = listOf(SyncRecord("1", mapOf("a" to DataJson.toNode(1))))
        val a = sink.apply(t, j, "run-1", 0, rec); val b = sink.apply(t, j, "run-1", 0, rec)
        assertThat(a).isEqualTo(b); assertThat(sink.keysOf(t, j)).hasSize(1)
    }

    @Test fun `transient failures back off exponentially then pause and keep lastSuccess`() {
        setup(); val job = newJob()
        runner.run(tenant.tenantId, job.id, false)
        val lastOk = store.state(tenant.tenantId, job.id)!!.lastSuccessAt
        f.connector.queryHook = { _, _, _ -> throw ConnectorFailure(FailureCodes.UPSTREAM_STATUS, "upstream error") }
        val delays = mutableListOf<Long>()
        repeat(SyncRunner.MAX_CONSECUTIVE_FAILURES - 1) {
            f.clock.advanceSeconds(4_000)
            val r = runner.run(tenant.tenantId, job.id, false)
            assertThat(r.outcome).isEqualTo(SyncRunResult.Outcome.FAILED_RETRY); assertThat(r.errorCode).isEqualTo(FailureCodes.UPSTREAM_STATUS)
            val s = store.state(tenant.tenantId, job.id)!!
            delays += s.nextRunAt.epochSecond - f.clock.instant().epochSecond
        }
        assertThat(delays.take(5)).containsExactly(30L, 60L, 120L, 240L, 480L)
        assertThat(delays.last()).isLessThanOrEqualTo(SyncRunner.BACKOFF_CAP_SECONDS)
        f.clock.advanceSeconds(4_000)
        assertThat(runner.run(tenant.tenantId, job.id, false).outcome).isEqualTo(SyncRunResult.Outcome.FAILED_PAUSED)
        assertThat(store.find(tenant.tenantId, job.id)!!.status).isEqualTo(SyncJobStatus.PAUSED)
        val s = store.state(tenant.tenantId, job.id)!!
        assertThat(s.lastSuccessAt).isEqualTo(lastOk); assertThat(s.lastErrorCode).isEqualTo(FailureCodes.UPSTREAM_STATUS); assertThat(s.lastErrorAt).isNotNull()
        assertThat(s.checkpoint).isEqualTo("50")
        f.clock.advanceSeconds(10_000)
        assertThat(runner.run(tenant.tenantId, job.id, false).outcome).isEqualTo(SyncRunResult.Outcome.NOT_RUN)   // paused jobs do not run
    }

    @Test fun `a permanent failure pauses at once and says why`() {
        setup(); val job = newJob()
        f.mappings.put(tenant.tenantId, MappingJson.mapping(DataJson.parse("""{"id":"m-items","queryRef":"items","fields":[{"from":"id","to":"id"}]}""".toByteArray())))   // mapping no longer matches the view model
        val r = runner.run(tenant.tenantId, job.id, false)
        assertThat(r.outcome).isEqualTo(SyncRunResult.Outcome.FAILED_PAUSED); assertThat(r.errorCode).isEqualTo(FailureCodes.INVALID_MAPPING)
        assertThat(store.find(tenant.tenantId, job.id)!!.status).isEqualTo(SyncJobStatus.PAUSED)
        assertThat(f.connector.queryCalls.get()).isEqualTo(0)
    }

    @Test fun `a source that ignores the cursor is detected and the job pauses without moving the checkpoint`() {
        setup(); val job = newJob(); runner.run(tenant.tenantId, job.id, false)
        f.connector.rowsProvider = { _, _ -> source.take(2) }                           // always the oldest rows, whatever "since" says
        f.clock.advanceSeconds(61)
        val r = runner.run(tenant.tenantId, job.id, false)
        assertThat(r.errorCode).isEqualTo(FailureCodes.SYNC_ORDER_VIOLATION); assertThat(r.outcome).isEqualTo(SyncRunResult.Outcome.FAILED_PAUSED)
        assertThat(store.state(tenant.tenantId, job.id)!!.checkpoint).isEqualTo("50")
    }

    @Test fun `only one worker holds a job and an expired lease can be taken over but the old holder cannot commit`() {
        setup(); val job = newJob()
        assertThat(store.tryLease(tenant.tenantId, job.id, "node-A", f.clock.instant(), 300)).isNotNull()
        assertThat(store.tryLease(tenant.tenantId, job.id, "node-B", f.clock.instant(), 300, force = true)).isNull()
        assertThat(runner.run(tenant.tenantId, job.id, true).outcome).isEqualTo(SyncRunResult.Outcome.NOT_RUN)   // node-1 sees A's lease
        store.expireLease(job.id, f.clock.instant().minusSeconds(1))
        assertThat(store.tryLease(tenant.tenantId, job.id, "node-B", f.clock.instant(), 300, force = true)).isNotNull()
        val update = SyncStateUpdate(job.version, "999", true, null, f.clock.instant(), f.clock.instant(), 1, 1, false)
        assertThat(store.finish(tenant.tenantId, job.id, "node-A", update)).isFalse()   // fenced
        assertThat(store.finish(tenant.tenantId, job.id, "node-B", update)).isTrue()
    }

    @Test fun `a job edited during a run is not committed over`() {
        setup(); val job = newJob()
        f.connector.queryHook = { _, _, _ -> store.save(store.find(tenant.tenantId, job.id)!!.revised(f.clock.instant(), intervalSeconds = 120)) }
        val r = runner.run(tenant.tenantId, job.id, false)
        assertThat(r.outcome).isEqualTo(SyncRunResult.Outcome.SUPERSEDED)
        assertThat(store.state(tenant.tenantId, job.id)!!.checkpoint).isNull()
    }

    @Test fun `records without a key are skipped and counted`() {
        setup(); source.add(mapOf("id" to null, "rev" to 5, "name" to "ghost"))
        // the mapping lets a missing id through (a non-nullable id would drop the row earlier, inside the mapping); the runner must still refuse a keyless record
        f.mappings.put(tenant.tenantId, MappingJson.mapping(DataJson.parse("""{"id":"m-items","queryRef":"items","fields":[{"from":"id","to":"id","nullable":true},{"from":"rev","to":"rev"},{"from":"name","to":"name"}]}""".toByteArray())))
        val job = newJob()
        val r = runner.run(tenant.tenantId, job.id, false)
        assertThat(r.skippedNoKey).isEqualTo(1); assertThat(r.rows).isEqualTo(6); assertThat(r.upserted).isEqualTo(5)
        assertThat(sink.keysOf(tenant.tenantId, job.id)).containsExactly("1", "2", "3", "4", "5")
    }

    @Test fun `tick runs due active jobs only and a successful sync invalidates cached queries and notifies`() {
        setup(); val job = newJob()
        val parts = CacheKeyParts(CacheScope(tenant.tenantId, ds.id, "items"), ds.version, 1, "m-items", 1, "vm-items", "{}", null)
        f.cache.putNow(parts, "stale", 600)
        val sub = f.bus.subscribe(tenant.tenantId)
        assertThat(runner.tick()).isEqualTo(1)
        assertThat(f.cache.get(parts)).isNull()
        assertThat(generateSequence { sub.next(10) }.map { it.type.wire }.toList()).contains("QueryInvalidated")
        assertThat(runner.tick()).isEqualTo(0)                                          // not due again yet
        f.clock.advanceSeconds(61); admin.pause(ctx, job.id)
        assertThat(runner.tick()).isEqualTo(0)
        sub.close()
    }

    @Test fun `another tenant cannot see run pause or delete a job`() {
        setup(); val job = newJob()
        val b = f.tenant(); val ctxB = f.ctx(b)
        assertThat(failure { admin.get(ctxB, job.id) }.code).isEqualTo(FailureCodes.SYNC_JOB_NOT_FOUND)
        assertThat(failure { admin.pause(ctxB, job.id) }.code).isEqualTo(FailureCodes.SYNC_JOB_NOT_FOUND)
        assertThat(failure { admin.runNow(ctxB, job.id) }.code).isEqualTo(FailureCodes.SYNC_JOB_NOT_FOUND)
        assertThat(failure { admin.delete(ctxB, job.id) }.code).isEqualTo(FailureCodes.SYNC_JOB_NOT_FOUND)
        assertThat(failure { admin.list(ctxB, ds.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(runner.run(b.tenantId, job.id, true).outcome).isEqualTo(SyncRunResult.Outcome.NOT_RUN)
        assertThat(store.find(tenant.tenantId, job.id)).isNotNull()
    }

    @Test fun `managing sync needs its permission and a denial looks the same for an unknown job`() {
        setup()
        f.authorizer.denied += GatewayOperation.SYNC_MANAGE
        assertThat(failure { admin.create(ctx, spec()) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(failure { admin.get(ctx, UUID.randomUUID()) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        f.authorizer.denied.clear()
        val job = newJob()
        f.authorizer.denied += GatewayOperation.SYNC_MANAGE
        assertThat(failure { admin.get(ctx, job.id) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(failure { admin.runNow(ctx, job.id) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
    }

    @Test fun `creating a job validates it against the approved query mapping and view model`() {
        setup()
        assertThat(failure { admin.create(ctx, spec().copy(queryId = "nope")) }.code).isEqualTo(FailureCodes.QUERY_NOT_FOUND)
        assertThat(failure { admin.create(ctx, spec().copy(mappingRef = "nope")) }.code).isEqualTo(FailureCodes.INVALID_MAPPING)
        assertThat(failure { admin.create(ctx, spec().copy(keyField = "missing")) }.code).isEqualTo(FailureCodes.INVALID_MAPPING)
        assertThat(failure { admin.create(ctx, spec().copy(cursorParam = "undeclared")) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(failure { admin.create(ctx, spec().copy(cursorField = "name", cursorParam = null)) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(failure { admin.create(ctx, spec().copy(intervalSeconds = 5)) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(failure { admin.create(ctx, spec().copy(fixedParams = mapOf("undeclared" to DataJson.toNode(1)))) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(failure { admin.create(ctx.copy(tenant = TenantContext(f.tenant().tenantId, null)), spec()) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        newJob(); assertThat(failure { admin.create(ctx, spec()) }.code).isEqualTo(FailureCodes.CONFLICT)
    }

    @Test fun `resume revalidates and manual run is rate limited`() {
        setup(); val job = newJob()
        runner.run(tenant.tenantId, job.id, false)
        admin.pause(ctx, job.id)
        f.mappings.put(tenant.tenantId, MappingJson.viewModel(DataJson.parse("""{"id":"vm-items","mappingRef":"m-items","fields":[{"name":"rev","type":"NUMBER"},{"name":"name","type":"STRING"}]}""".toByteArray())))
        assertThat(failure { admin.resume(ctx, job.id) }.code).isEqualTo(FailureCodes.INVALID_MAPPING)
        assertThat(store.find(tenant.tenantId, job.id)!!.status).isEqualTo(SyncJobStatus.PAUSED)
        setupViewModelBack(); admin.resume(ctx, job.id)
        assertThat(admin.runNow(ctx, job.id).outcome).isEqualTo(SyncRunResult.Outcome.SUCCESS)
        assertThat(failure { admin.runNow(ctx, job.id) }.code).isEqualTo(FailureCodes.RATE_LIMITED)
    }
    private fun setupViewModelBack() = f.mappings.put(tenant.tenantId, MappingJson.viewModel(DataJson.parse("""{"id":"vm-items","mappingRef":"m-items","fields":[{"name":"id","type":"NUMBER"},{"name":"rev","type":"NUMBER"},{"name":"name","type":"STRING"}]}""".toByteArray())))

    @Test fun `audit records carry ids and counts and never a row value`() {
        setup(); val job = newJob(); runner.run(tenant.tenantId, job.id, false)
        f.connector.queryHook = { _, _, cred -> throw IllegalStateException("boom ${cred.get("authValue")}") }
        f.clock.advanceSeconds(61); runner.run(tenant.tenantId, job.id, false)
        assertThat(f.audit.actions()).contains(DataAuditActions.SYNC_JOB_CHANGED, DataAuditActions.SYNC_RUN)
        assertThat(f.audit.text).doesNotContain("n1").doesNotContain("renamed").doesNotContain(f.secret).doesNotContain("boom")
    }

    @Test fun `backoff doubles from 30 seconds and is capped`() {
        assertThat(SyncRunner.backoff(1)).isEqualTo(30L); assertThat(SyncRunner.backoff(2)).isEqualTo(60L); assertThat(SyncRunner.backoff(7)).isEqualTo(1_920L)
        assertThat(SyncRunner.backoff(8)).isEqualTo(3_600L); assertThat(SyncRunner.backoff(500)).isEqualTo(3_600L)
    }

    @Test fun `cursor comparison follows the field type`() {
        assertThat(Cursors.compare(com.systemwebstudio.data.mapping.FieldType.NUMBER, "9", "10")).isLessThan(0)
        assertThat(Cursors.compare(com.systemwebstudio.data.mapping.FieldType.STRING, "9", "10")).isGreaterThan(0)
        assertThat(Cursors.compare(com.systemwebstudio.data.mapping.FieldType.DATETIME, "2024-03-05T10:00:00.5Z", "2024-03-05T10:00:00Z")).isGreaterThan(0)
        assertThat(failure { Cursors.compare(com.systemwebstudio.data.mapping.FieldType.NUMBER, "x", "1") }.code).isEqualTo(FailureCodes.INVALID_MAPPING)
    }

    // ------------------------------------------------------------------------------------------------ owner permission re-check (contract v2 data-runtime §5)

    private fun capturingRunner(seen: MutableList<GatewayContext>) =
        SyncRunner(store, f.service, f.queries, f.mappings, sink, f.notifier, f.audit,
            com.systemwebstudio.data.gateway.GatewayGuard({ c, op, id -> seen += c; f.authorizer.authorize(c, op, id) }, f.audit), clock = f.clock, owner = "node-2")

    @Test fun `a run acts as the job owner with the workspace and request id of the job`() {
        setup(); val job = newJob()
        val seen = java.util.concurrent.CopyOnWriteArrayList<GatewayContext>()
        assertThat(capturingRunner(seen).run(tenant.tenantId, job.id, false).outcome).isEqualTo(SyncRunResult.Outcome.SUCCESS)
        assertThat(seen.isNotEmpty()).isTrue()
        assertThat(seen.all { it.actorKind == com.systemwebstudio.tenancy.ActorKind.USER }).isTrue()
        assertThat(seen.all { it.actorUserId == tenant.actorUserId }).isTrue()
        assertThat(seen.all { it.workspaceId == tenant.workspaceId }).isTrue()
        assertThat(seen.all { it.tenantId == tenant.tenantId }).isTrue()
        assertThat(seen.all { it.requestId!!.startsWith("sync-" + job.id) }).isTrue()
    }

    @Test fun `a job whose owner lost the permission pauses and pulls nothing`() {
        setup(); val job = newJob()
        f.authorizer.denied += GatewayOperation.QUERY_EXECUTE
        val r = runner.run(tenant.tenantId, job.id, false)
        assertThat(r.outcome).isEqualTo(SyncRunResult.Outcome.FAILED_PAUSED)
        assertThat(r.errorCode).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(f.connector.queryCalls.get()).isEqualTo(0)                    // nothing was pulled, the credential was not even decrypted
        assertThat(f.connector.credentialsSeen).isEmpty()
        assertThat(sink.keysOf(tenant.tenantId, job.id)).isEmpty()
        assertThat(store.find(tenant.tenantId, job.id)!!.status).isEqualTo(SyncJobStatus.PAUSED)
        f.authorizer.denied.clear(); f.clock.advanceSeconds(3_600)
        assertThat(runner.run(tenant.tenantId, job.id, false).outcome).isEqualTo(SyncRunResult.Outcome.NOT_RUN)   // stays paused until an authorised user resumes it
    }

    @Test fun `losing the permission later keeps the checkpoint and the delivered records`() {
        setup(); val job = newJob(); runner.run(tenant.tenantId, job.id, false)
        val before = sink.keysOf(tenant.tenantId, job.id).toList()
        f.authorizer.denied += GatewayOperation.QUERY_EXECUTE; f.clock.advanceSeconds(61)
        assertThat(runner.run(tenant.tenantId, job.id, false).outcome).isEqualTo(SyncRunResult.Outcome.FAILED_PAUSED)
        assertThat(store.state(tenant.tenantId, job.id)!!.checkpoint).isEqualTo("50")
        assertThat(sink.keysOf(tenant.tenantId, job.id).toList()).isEqualTo(before)
    }

    @Test fun `an authorizer outage pauses the job instead of running it`() {
        setup(); val job = newJob()
        f.authorizer.failWith = IllegalStateException("access service down")
        assertThat(runner.run(tenant.tenantId, job.id, false).outcome).isEqualTo(SyncRunResult.Outcome.FAILED_PAUSED)
        assertThat(f.connector.queryCalls.get()).isEqualTo(0)
    }
}
