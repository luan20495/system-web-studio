package com.systemwebstudio.publish

import com.systemwebstudio.integration.deploy.DeployProvider
import com.systemwebstudio.integration.deploy.DeployRequest
import com.systemwebstudio.integration.storage.ArtifactStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Release bookkeeping on top of the existing tables, with no schema change:
 *  - the ACTIVE release is `sites.current_deployment_id`;
 *  - history is `deployment_events` (status has no CHECK constraint, so SWITCH / ROLLBACK_OK / ROLLBACK_FAILED / ROLLBACK_OFFLINE can be recorded);
 *  - the previous release of a switch is recorded once, as a SWITCH event, only when there is a previous release (the first release and the
 *    mock provider write nothing extra).
 */
@Component
class JdbcReleaseStore(private val jdbc: JdbcTemplate, private val deployments: DeploymentRepository) : ReleaseStore {
    override fun activeDeployment(projectId: UUID): UUID? =
        jdbc.query("SELECT current_deployment_id FROM sites WHERE project_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, projectId).firstOrNull()

    /** the release that was active when this deployment started switching: a typed column (V30), written once, always another deployment of the same app */
    override fun recordedPrevious(deploymentId: UUID): UUID? =
        jdbc.query("SELECT previous_deployment_id FROM deployments WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, deploymentId).firstOrNull()

    override fun recordPrevious(deploymentId: UUID, previous: UUID) {
        val written = jdbc.update("""UPDATE deployments d SET previous_deployment_id = ? WHERE d.id = ? AND d.previous_deployment_id IS NULL AND d.id <> ?
            AND EXISTS (SELECT 1 FROM deployments p WHERE p.id = ? AND p.project_id = d.project_id)""", previous, deploymentId, previous, previous)
        if (written == 1) deployments.event(deploymentId, "SWITCH", "Replacing release ${label(previous)} [$previous]")      // the text is for people, the column is the state
    }

    override fun beginRollback(deploymentId: UUID, reason: String): Boolean =
        deployments.transition(deploymentId, DeploymentStatus.DEPLOYING, DeploymentStatus.ROLLING_BACK, "Rolling back: $reason") ||
            jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?", String::class.java, deploymentId) == DeploymentStatus.ROLLING_BACK

    override fun markRolledBackIfNewer(left: UUID, target: UUID) {
        val n = jdbc.update("""UPDATE deployments l SET status = 'ROLLED_BACK', updated_at = now()
            WHERE l.id = ? AND l.status = 'RUNNING' AND l.activation_seq > (SELECT t.activation_seq FROM deployments t WHERE t.id = ?)""", left, target)
        if (n == 1) deployments.event(left, DeploymentStatus.ROLLED_BACK, "Rolled back to release ${label(target)}")
    }

    override fun leaseOperation(projectId: UUID): UUID? =
        jdbc.query("SELECT lease_operation_id FROM sites WHERE project_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, projectId).firstOrNull()

    override fun artifactOf(deploymentId: UUID): UUID? =
        jdbc.query("SELECT artifact_id FROM deployments WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, deploymentId).firstOrNull()

    override fun label(deploymentId: UUID): String {
        val n = jdbc.query("SELECT v.version_number FROM deployments d JOIN project_versions v ON v.id = d.version_id WHERE d.id = ?",
            { rs, _ -> rs.getInt(1) }, deploymentId).firstOrNull()
        return (n?.let { "v$it " } ?: "") + "(" + deploymentId.toString().take(8) + ")"
    }

    override fun event(deploymentId: UUID, status: String, message: String) = deployments.event(deploymentId, status, message)
}

/**
 * Can this artifact still be served? The record exists and was not removed by retention, the manifest still hashes to the artifact's
 * sha256 (it was not altered), and every file of it is in the store with the recorded size. Reads metadata only (a stat per file).
 */
@Component
class StoredArtifactVerifier(
    private val jdbc: JdbcTemplate, private val json: JsonMapper, private val store: ArtifactStore,
    /** artifacts up to this many bytes get their content re-read and hashed before activation (`app.deploy.verify-bytes-limit`, default 16 MiB; 0 = never) */
    @Value("\${app.deploy.verify-bytes-limit:16777216}") private val contentLimitBytes: Long
) : ArtifactVerifier {
    override fun verify(artifactId: UUID): ArtifactCheck = check(artifactId, deep = false)

    override fun verifyContent(artifactId: UUID): ArtifactCheck = check(artifactId, deep = true)

    private fun check(artifactId: UUID, deep: Boolean): ArtifactCheck {
        val row = jdbc.queryForList("SELECT storage_prefix, manifest::text AS manifest, sha256, deleted_at FROM artifacts WHERE id = ?", artifactId).firstOrNull()
            ?: return ArtifactCheck(false, "the artifact record does not exist")
        if (row["deleted_at"] != null) return ArtifactCheck(false, "the artifact was removed by retention")
        val files = try { json.readTree(row["manifest"] as String).toList().map { json.treeToValue(it, ManifestFile::class.java) } }
            catch (e: Exception) { return ArtifactCheck(false, "the manifest cannot be read") }
        if (files.isEmpty()) return ArtifactCheck(false, "the manifest is empty")
        // the artifact id is content-addressed: sha256 of the canonical manifest. A different hash means the record was changed.
        val recomputed = StaticSiteBuilder.sha256(json.writeValueAsString(files).toByteArray())
        if (!recomputed.equals((row["sha256"] as String).trim(), ignoreCase = true)) return ArtifactCheck(false, "the manifest no longer matches the artifact checksum")
        val prefix = row["storage_prefix"] as String
        for (f in files) {
            val size = try { store.size("$prefix/${f.path}") } catch (e: Exception) { return ArtifactCheck(false, "the artifact store could not be read: " + FailureClassifier.safe(e.message, 100)) }
            if (size == null) return ArtifactCheck(false, "file ${f.path} is missing in the artifact store")
            if (size != f.size.toLong()) return ArtifactCheck(false, "file ${f.path} has ${size} bytes, expected ${f.size}")
        }
        // the bytes themselves, for artifacts that are small enough to be worth re-reading
        if (deep && contentLimitBytes > 0 && files.sumOf { it.size.toLong() } <= contentLimitBytes) {
            for (f in files) {
                val bytes = try { store.get("$prefix/${f.path}") } catch (e: Exception) { return ArtifactCheck(false, "the artifact store could not be read: " + FailureClassifier.safe(e.message, 100)) }
                    ?: return ArtifactCheck(false, "file ${f.path} is missing in the artifact store")
                if (!StaticSiteBuilder.sha256(bytes).equals(f.sha256, ignoreCase = true)) return ArtifactCheck(false, "file ${f.path} was altered: its checksum differs from the manifest")
            }
        }
        return ArtifactCheck(true)
    }
}

/**
 * One place that deploys a release, verifies it and goes back to an earlier one: used by the deployment processor and the rollback / unpublish
 * endpoints. Every operation takes the release scope first ([ReleaseScopeGuard]), so publish, rollback and unpublish of one app never run at the
 * same time and the pointer is only moved by whoever holds it.
 */
@Service
class ReleaseService(
    provider: DeployProvider, releases: ReleaseStore, verifier: ArtifactVerifier, runtime: RuntimePlane,
    private val guard: ReleaseScopeGuard, private val sites: SiteService, private val jdbc: JdbcTemplate,
    @Value("\${app.deploy.deploy-timeout-seconds:120}") deploySeconds: Long,
    @Value("\${app.deploy.verify-timeout-seconds:60}") verifySeconds: Long,
    @Value("\${app.deploy.scope-duplicate-wait-seconds:60}") duplicateWaitSeconds: Long
) {
    private val duplicateWaitNanos = duplicateWaitSeconds * 1_000_000_000L
    val deployer = ReleaseDeployer(provider, releases, verifier, StepRunner(), deploySeconds * 1000, verifySeconds * 1000, runtime)

    /** (tenant, app, PRODUCTION): the tenant is the project's own, never taken from a request */
    fun scopeOf(projectId: UUID): ReleaseScope =
        ReleaseScope(jdbc.queryForObject("SELECT tenant_id FROM projects WHERE id = ?", UUID::class.java, projectId)!!, projectId)

    /**
     * Publish = the DEPLOYING step of [request.deploymentId], whose activation number is [seq]. The scope row exists first (the first publish creates
     * the site). Busy is not a failure ([DeployOutcome.Busy]); an overtaken publish fails explicitly as STALE_PUBLISH.
     */
    fun publish(scope: ReleaseScope, request: DeployRequest, seq: Long): DeployOutcome {
        sites.ensureSlug(scope.appId, request.projectName)
        return when (val a = guard.acquire(ScopeRequest(scope, ReleaseOperation.PUBLISH, request.deploymentId, request.deploymentId, seq))) {
            is ScopeAcquisition.Busy -> DeployOutcome.Busy(a.holder)
            is ScopeAcquisition.Stale -> DeployOutcome.Failed(StepFailure(FailureCode.STALE_PUBLISH, a.reason), RollbackResult.NotSwitched)
            is ScopeAcquisition.Acquired -> try { deployer.deploy(request, a.lease) } finally { a.lease.release() }
        }
    }

    /**
     * A deployment that is ROLLING_BACK after a crash: finish the undo under the scope. null = the scope is busy (try again later); a newer
     * operation having moved the pointer means there is nothing of this deployment left to undo.
     */
    fun resumeRollback(scope: ReleaseScope, request: DeployRequest, seq: Long): RollbackResult? =
        when (val a = guard.acquire(ScopeRequest(scope, ReleaseOperation.PUBLISH, request.deploymentId, request.deploymentId, seq))) {
            is ScopeAcquisition.Busy -> null
            is ScopeAcquisition.Stale -> RollbackResult.NotSwitched
            is ScopeAcquisition.Acquired -> try { deployer.resumeRollback(request, a.lease) } finally { a.lease.release() }
        }

    /**
     * Manual rollback: never waits for ANOTHER operation (a busy scope is `409 SCOPE_BUSY`, an overtaken one `409 ROLLBACK_STALE`). A duplicate of
     * the same operation (same Idempotency-Key) does wait for its original, then finds the work done: that is how retries converge.
     * [expectedActive] (optional): the release the client believes is active; a different one is `409 ROLLBACK_STALE` and nothing changes, unless
     * the target already is the active release (a retry after success).
     */
    fun rollback(scope: ReleaseScope, target: UUID, operationId: UUID = UUID.randomUUID(), expectedActive: UUID? = null): RollbackResult =
        hold(ScopeRequest(scope, ReleaseOperation.ROLLBACK, operationId)) { lease ->
            staleCheck(lease, expectedActive, alreadyDone = lease.activeDeploymentId == target)
            deployer.restoreRelease(scope.appId, target, lease)
        }

    /** What an unpublish did: [changed] = false when the site was already offline (a retry, or nothing to do). */
    data class Unpublished(val changed: Boolean, val previous: UUID?)

    /**
     * Unpublish: the pointer goes to NULL through the fence of the scope this request holds. Already offline = nothing to do, nothing written.
     * Same rules as rollback for a busy scope, [expectedActive] and a duplicate of the same operation.
     */
    fun unpublish(scope: ReleaseScope, operationId: UUID = UUID.randomUUID(), expectedActive: UUID? = null): Unpublished =
        hold(ScopeRequest(scope, ReleaseOperation.UNPUBLISH, operationId)) { lease ->
            val active = lease.activeDeploymentId
            if (active == null) return@hold Unpublished(false, null)
            staleCheck(lease, expectedActive, alreadyDone = false)
            if (!lease.fence.commit(null)) throw ReleaseScopeGuard.busy(scope, guard.holder(scope))
            Unpublished(true, active)
        }

    private fun staleCheck(lease: ScopeLease, expectedActive: UUID?, alreadyDone: Boolean) {
        if (expectedActive != null && !alreadyDone && lease.activeDeploymentId != expectedActive)
            throw com.systemwebstudio.common.ApiException.conflict("ROLLBACK_STALE", "The active release is not the one this request expected; reload and decide again",
                mapOf("activeDeploymentId" to lease.activeDeploymentId, "expectedActiveDeploymentId" to expectedActive))
    }

    /**
     * The identity of a rollback / unpublish. Without an Idempotency-Key every request is its own operation. With one, the identity is derived from
     * (kind, app, user, key): the same key is the same operation however often and from wherever it is sent, and the same key with another body is refused.
     */
    fun operationId(kind: ReleaseOperation, projectId: UUID, userId: UUID, key: String?, body: String): UUID {
        if (key == null) return UUID.randomUUID()
        if (!KEY.matches(key)) throw com.systemwebstudio.common.ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 8-120 characters of A-Z a-z 0-9 _ . : -")
        val scopeKey = "site-op:$kind:$projectId:$userId:$key"
        val id = UUID.nameUUIDFromBytes(scopeKey.toByteArray())
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
        val fresh = jdbc.update("INSERT INTO idempotency_keys (scope_key, request_hash, resource_type, resource_id) VALUES (?,?,'SITE_OPERATION',?) ON CONFLICT DO NOTHING", scopeKey, hash, id)
        log.info("site operation {} app={} op={} requestHash={} {}", kind, projectId, id, hash.take(12), if (fresh == 1) "new" else "replay")      // the hash, never the key
        val stored = jdbc.queryForObject("SELECT request_hash FROM idempotency_keys WHERE scope_key = ?", String::class.java, scopeKey)
        if (stored != hash) throw com.systemwebstudio.common.ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used with a different request")
        return id
    }

    private fun <T> hold(request: ScopeRequest, body: (ScopeLease) -> T): T {
        var a = guard.acquire(request)
        // the same operation (a duplicate request) still running: wait for it to finish, then run as the retry that finds its work done
        val deadline = System.nanoTime() + duplicateWaitNanos
        while (a is ScopeAcquisition.Busy && a.holder?.operationId == request.operationId && System.nanoTime() < deadline) {
            Thread.sleep(150); a = guard.acquire(request)
        }
        return when (a) {
            is ScopeAcquisition.Busy -> throw ReleaseScopeGuard.busy(request.scope, a.holder)
            is ScopeAcquisition.Stale -> throw com.systemwebstudio.common.ApiException.conflict("ROLLBACK_STALE", a.reason)
            is ScopeAcquisition.Acquired -> try { body(a.lease) } finally { a.lease.release() }
        }
    }

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    private companion object { val KEY = Regex("^[A-Za-z0-9_.:-]{8,120}$") }
}
