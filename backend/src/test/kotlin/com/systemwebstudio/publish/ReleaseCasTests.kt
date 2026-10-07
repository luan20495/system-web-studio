package com.systemwebstudio.publish

import com.systemwebstudio.integration.deploy.DeployProvider
import com.systemwebstudio.integration.deploy.DeployRequest
import com.systemwebstudio.integration.deploy.DeployResult
import com.systemwebstudio.integration.deploy.DeployVerification
import com.systemwebstudio.integration.deploy.PointerFence
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The deployer under a REAL lease on a real PostgreSQL: the pointer moves only through the fence of the scope the operation holds. The provider is a
 * stand-in that does what the static provider does (compare-and-set through the fence) and can be held at the moment of the switch.
 */
@TestPropertySource(properties = ["app.deploy.provider=static", "app.render.url=http://127.0.0.1:9", "app.deploy.scope-lifecycle-wait-seconds=1"])
class ReleaseCasTests : IntegrationTestBase() {
    @Autowired lateinit var guard: JdbcScopeGuard
    @Autowired lateinit var store: JdbcReleaseStore
    @Autowired lateinit var sites: SiteService
    @Autowired lateinit var releases: ReleaseService
    @Autowired lateinit var staticProvider: StaticSiteDeployProvider

    private class FencingProvider : DeployProvider {
        override val name = "fencing"; override val buildsArtifacts = true
        @Volatile var atSwitch: (DeployRequest) -> Unit = {}
        @Volatile var health = DeployVerification.healthy()
        override fun deploy(request: DeployRequest): DeployResult {
            atSwitch(request)
            return if (request.fence!!.commit(request.deploymentId)) DeployResult("https://sites.example/x/", null) else DeployResult(null, PointerFence.REFUSED)
        }
        override fun verify(request: DeployRequest) = health
        override fun restore(projectId: UUID, previousDeploymentId: UUID?) = DeployResult(null, "fence required")
        override fun restore(projectId: UUID, previousDeploymentId: UUID?, fence: PointerFence?) =
            if (fence!!.commit(previousDeploymentId)) DeployResult(null, null) else DeployResult(null, PointerFence.REFUSED)
    }

    private val provider = FencingProvider()
    private var artifactCheck: (UUID) -> ArtifactCheck = { ArtifactCheck(true) }
    private fun deployer() = ReleaseDeployer(provider, store, { artifactCheck(it) }, StepRunner(), 10_000, 10_000)
    private val held = mutableListOf<ScopeLease>()
    private val pool = Executors.newCachedThreadPool()

    @AfterEach fun tidy() { held.forEach { it.release() }; held.clear(); pool.shutdownNow() }

    private class Fx(val sc: Scenario, val scope: ReleaseScope)
    private fun fx(): Fx {
        val sc = scenario(); sites.ensureSlug(sc.projectId, "Cas")
        jdbc.update("UPDATE sites SET lease_until = lease_until WHERE project_id = ?", sc.projectId)
        return Fx(sc, ReleaseScope(jdbc.queryForObject("SELECT tenant_id FROM projects WHERE id = ?", UUID::class.java, sc.projectId)!!, sc.projectId))
    }

    private class Dep(val id: UUID, val seq: Long, val artifact: UUID, val request: DeployRequest)
    private fun dep(f: Fx, status: String = "RUNNING"): Dep {
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, f.sc.projectId)
        val art = UUID.randomUUID(); val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO artifacts (id, project_id, sha256, storage_prefix, file_count, total_bytes, manifest) VALUES (?,?,?,?,1,5,'[]'::jsonb)""",
            art, f.sc.projectId, UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""), "${f.sc.projectId}/x")
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, artifact_id) VALUES (?,?,?,?,?,'PUBLIC',?,'static',?)",
            id, f.sc.ws, f.sc.projectId, version, f.sc.user.id, status, art)
        val seq = jdbc.queryForObject("SELECT activation_seq FROM deployments WHERE id = ?", Long::class.java, id)!!
        return Dep(id, seq, art, DeployRequest(id, "Cas", 1, "PUBLIC", null, "sha", f.sc.projectId, art))
    }

    private fun publishLease(f: Fx, d: Dep) = (guard.acquire(ScopeRequest(f.scope, ReleaseOperation.PUBLISH, d.id, d.id, d.seq)) as ScopeAcquisition.Acquired).lease.also { held += it }
    private fun rollbackLease(f: Fx) = (guard.acquire(ScopeRequest(f.scope, ReleaseOperation.ROLLBACK, UUID.randomUUID())) as ScopeAcquisition.Acquired).lease.also { held += it }
    private fun expire(f: Fx) { jdbc.update("UPDATE sites SET lease_until = now() - interval '1 second' WHERE project_id = ?", f.sc.projectId) }
    private fun site(f: Fx) = jdbc.queryForMap("SELECT current_deployment_id, pointer_version, active_seq, active_operation_id FROM sites WHERE project_id = ?", f.sc.projectId)
    private fun pointer(f: Fx) = site(f)["current_deployment_id"] as UUID?
    private fun status(id: UUID) = jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?", String::class.java, id)!!
    private fun events(id: UUID) = jdbc.queryForList("SELECT status FROM deployment_events WHERE deployment_id = ? ORDER BY created_at, id", String::class.java, id)
    private fun point(f: Fx, d: Dep) { val l = rollbackLease(f); l.fence.commit(d.id); l.release() }

    // ------------------------------------------------------------------ stale writers

    @Test
    fun `publish A holds the scope and is slow, its lease expires, B takes over and publishes, A finishes late - A's pointer write is refused and B stays active`() {
        val f = fx(); val a = dep(f, "DEPLOYING"); val b = dep(f, "DEPLOYING")
        val atSwitch = CountDownLatch(1); val go = CountDownLatch(1)
        provider.atSwitch = { if (it.deploymentId == a.id) { atSwitch.countDown(); go.await(20, TimeUnit.SECONDS) } }
        val leaseA = publishLease(f, a)
        val resultA = pool.submit<DeployOutcome> { deployer().deploy(a.request, leaseA) }
        assertThat(atSwitch.await(10, TimeUnit.SECONDS)).isTrue()                    // A is at the switch, about to write the pointer
        expire(f)                                                                     // ... and has stopped heartbeating
        val leaseB = publishLease(f, b)
        assertThat(deployer().deploy(b.request, leaseB)).isInstanceOf(DeployOutcome.Live::class.java)
        leaseB.release()
        val before = site(f)
        go.countDown()                                                                // A completes late
        val outcome = resultA.get(20, TimeUnit.SECONDS)
        assertThat(outcome).isInstanceOf(DeployOutcome.Failed::class.java)
        assertThat((outcome as DeployOutcome.Failed).failure.code).isEqualTo(FailureCode.STALE_PUBLISH)
        assertThat(outcome.rollingBack).isFalse()
        assertThat(site(f)).isEqualTo(before)                                         // not one column of the scope changed
        assertThat(pointer(f)).isEqualTo(b.id); assertThat(site(f)["active_operation_id"]).isEqualTo(b.id)
        assertThat(status(a.id)).isEqualTo("DEPLOYING"); assertThat(events(a.id)).isEmpty()      // this operation wrote nothing more: its status is settled by the processor
    }

    @Test
    fun `the same operation takes over - the earlier run of it goes silent while the resumed run decides`() {
        val f = fx(); val a = dep(f, "DEPLOYING")
        val atSwitch = CountDownLatch(1); val go = CountDownLatch(1); val slow = java.util.concurrent.atomic.AtomicBoolean(true)
        provider.atSwitch = { if (slow.getAndSet(false)) { atSwitch.countDown(); go.await(20, TimeUnit.SECONDS) } }
        val first = publishLease(f, a)
        val firstRun = pool.submit<DeployOutcome> { deployer().deploy(a.request, first) }
        assertThat(atSwitch.await(10, TimeUnit.SECONDS)).isTrue()
        expire(f)                                                                      // the worker is presumed dead: the message is redelivered
        val second = publishLease(f, a)
        assertThat(second.resumed).isTrue(); assertThat(second.fenceToken).isGreaterThan(first.fenceToken)
        assertThat(deployer().deploy(a.request, second)).isInstanceOf(DeployOutcome.Live::class.java)     // the resumed run succeeds (still holding the scope)
        go.countDown()                                                                 // the "dead" worker wakes up
        assertThat(firstRun.get(20, TimeUnit.SECONDS)).isEqualTo(DeployOutcome.Lost)  // same operation holds the scope: silence, not a failure
        assertThat(pointer(f)).isEqualTo(a.id)
        assertThat(site(f)["pointer_version"]).isEqualTo(1L)                           // the pointer moved exactly once
    }

    // ------------------------------------------------------------------ the application lifecycle goes through the same door (D-C0-33)

    @Test
    fun `archiving or deleting an application takes the site offline through the scope - a publish that was mid-flight is fenced out and an older one is stale`() {
        val f = fx(); val p = dep(f); point(f, p); val a = dep(f, "DEPLOYING"); val lease = publishLease(f, a)
        expire(f)                                                                      // A's worker is presumed dead; the lifecycle takes the scope over
        val off = releases.takeOffline(f.sc.projectId)
        assertThat(off.changed).isTrue(); assertThat(off.previous).isEqualTo(p.id)
        assertThat(pointer(f)).isNull()
        assertThat(site(f)["active_seq"] as Long).isGreaterThan(a.seq)                // a newer intent than the publish that was in flight
        assertThat(lease.fence.commit(a.id)).describedAs("the mid-flight publish can no longer activate").isFalse()
        assertThat(lease.lost).isTrue(); assertThat(pointer(f)).isNull()
        assertThat(guard.acquire(ScopeRequest(f.scope, ReleaseOperation.PUBLISH, a.id, a.id, a.seq))).isInstanceOf(ScopeAcquisition.Stale::class.java)
        assertThat(guard.holder(f.scope)).describedAs("the scope is free again").isNull()
        assertThat(releases.takeOffline(f.sc.projectId).changed).describedAs("already offline: nothing written").isFalse()
    }

    @Test
    fun `a release operation that is alive blocks the lifecycle for a bounded time - 409 SCOPE_BUSY, nothing changes, the operation is not disturbed`() {
        val f = fx(); val p = dep(f); point(f, p); val a = dep(f, "DEPLOYING"); val lease = publishLease(f, a)
        val before = site(f)
        val started = System.nanoTime()
        val e = org.junit.jupiter.api.Assertions.assertThrows(com.systemwebstudio.common.ApiException::class.java) { releases.takeOffline(f.sc.projectId) }
        assertThat(e.code).isEqualTo("SCOPE_BUSY"); assertThat(e.status.value()).isEqualTo(409)
        assertThat((System.nanoTime() - started) / 1_000_000).describedAs("waited, but only the configured time").isBetween(800L, 6_000L)
        assertThat(site(f)["current_deployment_id"]).isEqualTo(before["current_deployment_id"]); assertThat(site(f)["pointer_version"]).isEqualTo(before["pointer_version"])
        assertThat(lease.lost).isFalse()
        assertThat(lease.fence.commit(a.id)).describedAs("the live publish still completes").isTrue()
        lease.release()
        assertThat(releases.takeOffline(f.sc.projectId).changed).isTrue(); assertThat(pointer(f)).isNull()
    }

    @Test
    fun `an application that never had a site has nothing to take offline`() {
        val sc = scenario()
        assertThat(releases.takeOffline(sc.projectId).changed).isFalse()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sites WHERE project_id = ?", Long::class.java, sc.projectId)).isZero()
    }

    @Test
    fun `no production code writes the active pointer outside the scope guard`() {
        val root = java.io.File("src/main/kotlin")
        val writers = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""UPDATE\s+sites\s+SET[^"]*current_deployment_id""", RegexOption.IGNORE_CASE).containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertThat(writers).describedAs("files that UPDATE sites.current_deployment_id").containsExactly("JdbcScopeGuard.kt")
        assertThat(java.io.File("src/main/kotlin/com/systemwebstudio/publish/SiteService.kt").readText()).doesNotContain("fun point(")
        val inserts = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { Regex("""INSERT\s+INTO\s+sites[^"]*current_deployment_id""", RegexOption.IGNORE_CASE).containsMatchIn(it.readText()) }.toList()
        assertThat(inserts).isEmpty()
    }

    // ------------------------------------------------------------------ ROLLING_BACK and the automatic rollback

    @Test
    fun `a release that is unhealthy after the switch is ROLLING_BACK first, then the previous release is back - by compare-and-set`() {
        val f = fx(); val p = dep(f); point(f, p); val a = dep(f, "DEPLOYING")
        provider.health = DeployVerification.unhealthy("HTTP 503")
        val lease = publishLease(f, a)
        val outcome = deployer().deploy(a.request, lease) as DeployOutcome.Failed
        assertThat(outcome.rollingBack).isTrue(); assertThat(outcome.rollback).isInstanceOf(RollbackResult.Restored::class.java)
        assertThat(status(a.id)).isEqualTo("ROLLING_BACK")                              // durable until the processor finishes it as FAILED
        assertThat(events(a.id)).containsExactly("SWITCH", "ROLLING_BACK", "ROLLBACK_OK")
        assertThat(pointer(f)).isEqualTo(p.id)
        assertThat(store.recordedPrevious(a.id)).isEqualTo(p.id)                        // typed, recorded under the lease
        assertThat(site(f)["active_operation_id"]).isEqualTo(a.id)                     // the same operation moved it there and back
        lease.release()
        // a publish that was requested BEFORE this one is now stale; one requested after goes on
        val older = dep(f, "DEPLOYING").let { jdbc.update("UPDATE deployments SET activation_seq = ? WHERE id = ?", a.seq - 1000, it.id); Dep(it.id, a.seq - 1000, it.artifact, it.request) }
        assertThat(guard.acquire(ScopeRequest(f.scope, ReleaseOperation.PUBLISH, older.id, older.id, older.seq))).isInstanceOf(ScopeAcquisition.Stale::class.java)
        val newer = dep(f, "DEPLOYING"); publishLease(f, newer).release()
    }

    @Test
    fun `the first release failing after the switch - ROLLING_BACK, the pointer is NULL, never the failed release`() {
        val f = fx(); val a = dep(f, "DEPLOYING"); provider.health = DeployVerification.unhealthy("HTTP 502")
        val outcome = deployer().deploy(a.request, publishLease(f, a)) as DeployOutcome.Failed
        assertThat(outcome.rollback).isEqualTo(RollbackResult.TookOffline); assertThat(pointer(f)).isNull()
        assertThat(events(a.id)).containsExactly("ROLLING_BACK", "ROLLBACK_OFFLINE"); assertThat(status(a.id)).isEqualTo("ROLLING_BACK")
    }

    @Test
    fun `the previous release cannot be restored - fail closed - pointer NULL, ROLLBACK_FAILED then ROLLBACK_OFFLINE`() {
        val f = fx(); val p = dep(f); point(f, p); val a = dep(f, "DEPLOYING")
        provider.health = DeployVerification.unhealthy("HTTP 503")
        artifactCheck = { if (it == p.artifact) ArtifactCheck(false, "file index.html is missing") else ArtifactCheck(true) }
        val outcome = deployer().deploy(a.request, publishLease(f, a)) as DeployOutcome.Failed
        assertThat((outcome.rollback as RollbackResult.Failed).tookOffline).isTrue()
        assertThat(pointer(f)).isNull()
        assertThat(events(a.id)).containsExactly("SWITCH", "ROLLING_BACK", "ROLLBACK_FAILED", "ROLLBACK_OFFLINE")
    }

    // ------------------------------------------------------------------ manual rollback and ROLLED_BACK

    @Test
    fun `rolling back to an older release marks the newer one ROLLED_BACK in the same transaction as the pointer`() {
        val f = fx(); val older = dep(f); val newer = dep(f); point(f, newer)
        val lease = rollbackLease(f)
        val result = deployer().restoreRelease(f.sc.projectId, older.id, lease)
        assertThat(result).isInstanceOf(RollbackResult.Restored::class.java)
        assertThat(pointer(f)).isEqualTo(older.id)
        assertThat(status(newer.id)).isEqualTo("ROLLED_BACK"); assertThat(status(older.id)).isEqualTo("RUNNING")
        assertThat(events(newer.id)).contains("ROLLED_BACK"); assertThat(events(older.id)).contains("ROLLBACK_OK")
    }

    @Test
    fun `a roll-forward leaves the release it moved away from RUNNING`() {
        val f = fx(); val older = dep(f); val newer = dep(f); point(f, older)
        assertThat(deployer().restoreRelease(f.sc.projectId, newer.id, rollbackLease(f))).isInstanceOf(RollbackResult.Restored::class.java)
        assertThat(pointer(f)).isEqualTo(newer.id)
        assertThat(status(older.id)).isEqualTo("RUNNING"); assertThat(status(newer.id)).isEqualTo("RUNNING")
    }

    @Test
    fun `a rollback that loses the scope before its pointer write changes no status and no pointer`() {
        val f = fx(); val older = dep(f); val newer = dep(f); point(f, newer)
        val mine = rollbackLease(f)
        expire(f); val taker = rollbackLease(f)                                        // someone else holds the scope now
        val result = deployer().restoreRelease(f.sc.projectId, older.id, mine)
        assertThat(result).isEqualTo(RollbackResult.ScopeLost)
        assertThat(pointer(f)).isEqualTo(newer.id); assertThat(status(newer.id)).isEqualTo("RUNNING"); assertThat(status(older.id)).isEqualTo("RUNNING")
        taker.release()
    }

    @Test
    fun `a manual rollback that fails leaves every status and the pointer as they were`() {
        val f = fx(); val older = dep(f); val newer = dep(f); point(f, newer)
        artifactCheck = { ArtifactCheck(false, "artifact was removed by retention") }
        val result = deployer().restoreRelease(f.sc.projectId, older.id, rollbackLease(f))
        assertThat(result).isInstanceOf(RollbackResult.Failed::class.java)
        assertThat(pointer(f)).isEqualTo(newer.id)
        assertThat(status(newer.id)).isEqualTo("RUNNING"); assertThat(status(older.id)).isEqualTo("RUNNING")
        assertThat(events(older.id)).containsExactly("ROLLBACK_FAILED")               // an attempt, recorded on the target; nobody's status moved
    }

    @Test
    fun `two requests of one rollback converge - the second finds it done and reports AlreadyActive`() {
        val f = fx(); val older = dep(f); val newer = dep(f); point(f, newer)
        val op = UUID.randomUUID()
        val l1 = (guard.acquire(ScopeRequest(f.scope, ReleaseOperation.ROLLBACK, op)) as ScopeAcquisition.Acquired).lease.also { held += it }
        // a live lease of a rollback is not re-entered (a duplicate waits); once it has expired the retry takes the same operation over
        assertThat(guard.acquire(ScopeRequest(f.scope, ReleaseOperation.ROLLBACK, op))).isInstanceOf(ScopeAcquisition.Busy::class.java)
        expire(f)
        val l2 = (guard.acquire(ScopeRequest(f.scope, ReleaseOperation.ROLLBACK, op)) as ScopeAcquisition.Acquired).lease.also { held += it }
        assertThat(l2.fenceToken).isGreaterThan(l1.fenceToken)
        assertThat(deployer().restoreRelease(f.sc.projectId, older.id, l2)).isInstanceOf(RollbackResult.Restored::class.java)
        assertThat(deployer().restoreRelease(f.sc.projectId, older.id, l1)).isInstanceOf(RollbackResult.AlreadyActive::class.java)   // the first thread of it: fenced, but the goal is reached
        assertThat(events(older.id).count { it == "ROLLBACK_OK" }).isEqualTo(1)
    }

    // ------------------------------------------------------------------ the provider

    @Test
    fun `the static provider never moves the pointer without a fence`() {
        val f = fx(); val a = dep(f, "DEPLOYING")
        assertThat(staticProvider.deploy(a.request).error).contains("fence is missing")
        assertThat(staticProvider.restore(f.sc.projectId, a.id).error).contains("fence is missing")
        assertThat(staticProvider.restore(f.sc.projectId, a.id, null).error).contains("fence is missing")
        assertThat(pointer(f)).isNull()
    }

    @Test
    fun `a release that is ROLLING_BACK is never served`() {
        val f = fx(); val a = dep(f, "DEPLOYING"); point(f, a)
        val slug = jdbc.queryForObject("SELECT slug FROM sites WHERE project_id = ?", String::class.java, f.sc.projectId)!!
        jdbc.update("UPDATE deployments SET status = 'ROLLING_BACK' WHERE id = ?", a.id)
        assertThat(sites.live(slug)).isNull()
    }
}
