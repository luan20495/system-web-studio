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
    private val uuid = Regex("\\[([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})]")

    override fun activeDeployment(projectId: UUID): UUID? =
        jdbc.query("SELECT current_deployment_id FROM sites WHERE project_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, projectId).firstOrNull()

    /**
     * The release that was active when this deployment started switching. There is no typed column for it (a migration C0 has not reserved), so
     * it is read back from the SWITCH event the switch wrote. Text is not trusted: the id is accepted only when it is another deployment of the
     * SAME project, otherwise it is ignored and the deployer falls back to the site's own pointer.
     */
    override fun recordedPrevious(deploymentId: UUID): UUID? {
        val parsed = jdbc.query("SELECT message FROM deployment_events WHERE deployment_id = ? AND status = 'SWITCH' ORDER BY created_at, id LIMIT 1",
            { rs, _ -> rs.getString(1) }, deploymentId).firstOrNull()?.let { uuid.find(it)?.groupValues?.get(1) }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return null
        if (parsed == deploymentId) return null
        val sameProject = jdbc.queryForObject("SELECT count(*) FROM deployments p JOIN deployments d ON d.project_id = p.project_id WHERE p.id = ? AND d.id = ?",
            Long::class.java, parsed, deploymentId)!! > 0
        return parsed.takeIf { sameProject }
    }

    override fun recordPrevious(deploymentId: UUID, previous: UUID) =
        deployments.event(deploymentId, "SWITCH", "Replacing release ${label(previous)} [$previous]")

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
class StoredArtifactVerifier(private val jdbc: JdbcTemplate, private val json: JsonMapper, private val store: ArtifactStore) : ArtifactVerifier {
    override fun verify(artifactId: UUID): ArtifactCheck {
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
        return ArtifactCheck(true)
    }
}

/**
 * One place that deploys a release, verifies it and goes back to an earlier one: used by the deployment processor and the rollback / unpublish
 * endpoints. Each operation runs inside [ReleaseScopeGuard], so publish, rollback and unpublish of one app are one family of operations.
 */
@Service
class ReleaseService(
    provider: DeployProvider, releases: ReleaseStore, verifier: ArtifactVerifier, runtime: RuntimePlane, private val guard: ReleaseScopeGuard,
    @Value("\${app.deploy.deploy-timeout-seconds:120}") deploySeconds: Long,
    @Value("\${app.deploy.verify-timeout-seconds:60}") verifySeconds: Long
) {
    val deployer = ReleaseDeployer(provider, releases, verifier, StepRunner(), deploySeconds * 1000, verifySeconds * 1000, runtime)

    fun publish(scope: ReleaseScope, request: DeployRequest): DeployOutcome =
        guard.run(scope, ReleaseOperation.PUBLISH) { deployer.deploy(request) }

    fun rollback(scope: ReleaseScope, target: UUID): RollbackResult =
        guard.run(scope, ReleaseOperation.ROLLBACK) { deployer.restoreRelease(scope.appId, target) }

    /** [takeOffline] is the actual change (pointing the site at nothing); it is only run while the scope is held. */
    fun <T> unpublish(scope: ReleaseScope, takeOffline: () -> T): T =
        guard.run(scope, ReleaseOperation.UNPUBLISH) { takeOffline() }
}
