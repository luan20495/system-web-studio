package com.systemwebstudio.publish

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.integration.deploy.DeployProvider
import com.systemwebstudio.integration.deploy.DeployRequest
import com.systemwebstudio.schema.PageSchemaValidator
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.UUID

/**
 * Runs one deployment through POLICY_CHECK -> SECURITY_CHECK -> BUILDING -> DEPLOYING -> RUNNING.
 * Each step is a compare-and-set on the status column, so a duplicate or redelivered message cannot run the same step twice, and a crashed
 * worker is resumed from the status it left behind.
 *
 * Failure handling (the final state is always deterministic and always says why):
 *  - every failure is classified ([FailureClassifier]): a stable code + the real reason are stored in `deployments.error`, never a generic text;
 *  - transient failures (render worker / storage / database blip) retry the same step a bounded number of times with backoff; the retry count
 *    is read from the history, so a restart does not reset it. Permanent failures and ambiguous ones (a deploy that timed out) are never retried;
 *  - DEPLOYING goes through [ReleaseDeployer]: switch, run the server runtime step, verify. If anything fails after the switch was attempted the
 *    previous release is made active again from its immutable artifact. A deployment is RUNNING only after verification said HEALTHY.
 */
@Service
class DeploymentProcessor(
    private val deployments: DeploymentRepository,
    private val provider: DeployProvider,
    private val validator: PageSchemaValidator,
    private val jdbc: JdbcTemplate,
    private val audit: AuditService,
    private val json: JsonMapper,
    private val builder: StaticSiteBuilder,
    private val runtime: com.systemwebstudio.runtime.ServerRuntimeService,
    private val buildJobs: com.systemwebstudio.code.BuildJobService,
    private val releases: ReleaseService,
    @Value("\${app.deploy.step-delay-ms:0}") private val stepDelayMs: Long,
    @Value("\${app.deploy.step-max-attempts:3}") maxAttempts: Int,
    @Value("\${app.deploy.retry-backoff-ms:500}") backoffMs: Long,
    @Value("\${app.deploy.build-timeout-seconds:300}") buildSeconds: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val retry = RetryPolicy(maxAttempts.coerceAtLeast(1), backoffMs)
    private val buildTimeoutMs = buildSeconds * 1000
    private val runner = StepRunner()

    private val forbidden = listOf("<script", "javascript:", "onerror=", "onload=", "data:text/html")

    /** what one step of one run produced */
    private sealed interface StepResult {
        data object Advance : StepResult
        /** a code build is still running: stop now, its completion re-queues this deployment */
        data object Wait : StepResult
        data class Fail(val failure: StepFailure, val rollback: RollbackResult? = null) : StepResult
    }

    /** values carried from one step of a run to the next */
    private class RunState(var artifactHash: String = "", var url: String? = null)

    fun process(deploymentId: UUID) {
        MDC.put("requestId", "job_" + deploymentId.toString().replace("-", "").take(16))
        try {
            run(deploymentId)
        } finally {
            MDC.remove("requestId")
        }
    }

    private fun run(id: UUID) {
        val d = deployments.find(id) ?: run { log.warn("Deployment {} not found", id); return }
        if (d.status in DeploymentStatus.terminal) return
        var current = d.status
        if (current == DeploymentStatus.QUEUED && !move(d, current, DeploymentStatus.POLICY_CHECK, "Checking policy")) return
        current = if (current == DeploymentStatus.QUEUED) DeploymentStatus.POLICY_CHECK else current
        val state = RunState()
        while (current != DeploymentStatus.RUNNING) {
            pause()
            when (val result = step(d, current, state)) {
                is StepResult.Wait -> return
                is StepResult.Fail -> {
                    val attemptsMade = deployments.retries(d.id, current) + 1
                    if (retry.canRetry(result.failure, attemptsMade)) {
                        deployments.event(d.id, current, "Retry $attemptsMade/${retry.maxAttempts}: ${result.failure.reason()}")
                        log.warn("Deployment {} step {} failed transiently (attempt {}): {}", id, current, attemptsMade, result.failure.message)
                        sleep(retry.delayBefore(attemptsMade + 1))
                        continue
                    }
                    fail(d, current, result.failure, attemptsMade, result.rollback)
                    return
                }
                is StepResult.Advance -> {
                    val next = DeploymentStatus.pipeline[DeploymentStatus.pipeline.indexOf(current) + 1]
                    val ok = if (next == DeploymentStatus.RUNNING) finish(d, current, state.url!!) else move(d, current, next, label(next))
                    if (!ok) return
                    current = next
                }
            }
        }
    }

    /** One attempt of one step. Never throws: whatever goes wrong becomes a classified [StepResult.Fail]. */
    private fun step(d: DeploymentDto, current: String, state: RunState): StepResult = try {
        when (current) {
            DeploymentStatus.POLICY_CHECK -> policy(d)?.let { StepResult.Fail(StepFailure(FailureCode.POLICY_REJECTED, it)) } ?: StepResult.Advance
            DeploymentStatus.SECURITY_CHECK -> security(d)?.let { StepResult.Fail(StepFailure(FailureCode.SECURITY_REJECTED, it)) } ?: StepResult.Advance
            DeploymentStatus.BUILDING -> buildStep(d, state)
            DeploymentStatus.DEPLOYING -> deployStep(d, state)
            else -> StepResult.Fail(StepFailure(FailureCode.INTERNAL_ERROR, "Unexpected state $current"))
        }
    } catch (e: Exception) {
        log.error("Deployment {} crashed in {}", d.id, current, e)
        StepResult.Fail(FailureClassifier.classify(current, e))
    }

    private fun buildStep(d: DeploymentDto, state: RunState): StepResult {
        if (isCodeApp(d)) {
            // code project: the sandbox runner builds it; the job's completion re-queues this deployment
            val r = codeBuild(d) ?: return StepResult.Wait
            if (r.startsWith("ERR:")) return StepResult.Fail(StepFailure(FailureCode.BUILD_FAILED, FailureClassifier.safe(r.removePrefix("ERR:"))))
            state.artifactHash = r
            return StepResult.Advance
        }
        state.artifactHash = runner.bounded(buildTimeoutMs) { build(d) }
        return StepResult.Advance
    }

    private fun deployStep(d: DeploymentDto, state: RunState): StepResult {
        val project = jdbc.queryForMap("SELECT name, deployment_target FROM projects WHERE id = ?", d.projectId)
        // read back from the row so a resumed job deploys the artifact the BUILDING step recorded
        val artifactId = jdbc.queryForObject("SELECT artifact_id FROM deployments WHERE id = ?", UUID::class.java, d.id)
        if (provider.buildsArtifacts && artifactId == null) return StepResult.Fail(StepFailure(FailureCode.ARTIFACT_MISSING, "No artifact was built for this deployment"))
        val hash = state.artifactHash.ifEmpty { artifactId?.let { a -> jdbc.queryForObject("SELECT sha256 FROM artifacts WHERE id = ?", String::class.java, a) } ?: build(d, record = false) }
        val request = DeployRequest(d.id, project["name"] as String, d.versionNumber, d.visibility, project["deployment_target"] as String?, hash, d.projectId, artifactId)
        // server apps: the same build's server part goes to the isolated runtime (blue/green, health-checked) right after the site switch
        val serverStep: (() -> Unit)? = if (artifactId != null && isServerApp(d)) ({ deployServerRuntime(d, artifactId) }) else null
        return when (val outcome = releases.deployer.deploy(request, serverStep)) {
            is DeployOutcome.Live -> { state.url = outcome.url; StepResult.Advance }
            is DeployOutcome.Failed -> StepResult.Fail(outcome.failure, outcome.rollback)
        }
    }

    /** Idempotent: when a worker died after handing the artifact to the runtime, the re-run does not create a second server deployment. */
    private fun deployServerRuntime(d: DeploymentDto, artifactId: UUID) {
        val already = jdbc.queryForObject(
            "SELECT count(*) FROM server_deployments sd, deployments dep WHERE dep.id = ? AND sd.project_id = ? AND sd.artifact_id = ? AND sd.rollback_of IS NULL AND sd.created_at >= dep.created_at",
            Long::class.java, d.id, d.projectId, artifactId) ?: 0L
        if (already > 0) return
        val commit = jdbc.queryForObject("SELECT commit_sha FROM project_versions WHERE id = ?", String::class.java, d.versionId)
        runtime.deploy(d.projectId, artifactId, commit, jdbc.queryForObject("SELECT requested_by FROM deployments WHERE id = ?", UUID::class.java, d.id))
    }

    private fun label(s: String) = when (s) {
        DeploymentStatus.SECURITY_CHECK -> "Running security checks"
        DeploymentStatus.BUILDING -> if (provider.buildsArtifacts) "Building (static site, or sandbox build for code apps)" else "Building artifact (mock)"
        DeploymentStatus.DEPLOYING -> if (provider.buildsArtifacts) "Switching the site to the new artifact" else "Deploying through the mock provider"
        else -> s
    }

    private fun snapshot(d: DeploymentDto) =
        jdbc.queryForObject("SELECT schema_snapshot::text FROM project_versions WHERE id = ?", String::class.java, d.versionId)!!

    private fun isServerApp(d: DeploymentDto) =
        jdbc.queryForObject("SELECT app_kind FROM projects WHERE id = ?", String::class.java, d.projectId) in com.systemwebstudio.code.CodeProjectService.SERVER_KINDS

    private fun isCodeApp(d: DeploymentDto) =
        jdbc.queryForObject("SELECT app_type FROM projects WHERE id = ?", String::class.java, d.projectId) == "STATIC_APP"

    /** null = still building (pause); "ERR:<message>" = failed; otherwise the artifact sha. Idempotent: re-runs only look at the job. */
    private fun codeBuild(d: DeploymentDto): String? {
        if (!provider.buildsArtifacts) return "ERR:Code apps need the static site provider (DEPLOY_PROVIDER=static)"
        val job = jdbc.queryForList("SELECT id, status, artifact_id, error, commit_sha FROM build_jobs WHERE deployment_id = ? ORDER BY queued_at DESC LIMIT 1", d.id).firstOrNull()
        if (job == null) {
            val commit = json.readTree(snapshot(d)).get("commit")?.asString() ?: return "ERR:This version has no commit"
            buildJobs.enqueue(d.projectId, commit, "PUBLISH", deploymentId = d.id,
                requestedBy = jdbc.queryForObject("SELECT requested_by FROM deployments WHERE id = ?", UUID::class.java, d.id))
            return null
        }
        return when (job["status"]) {
            "SUCCEEDED" -> {
                jdbc.update("UPDATE deployments SET artifact_id = ?, commit_sha = ? WHERE id = ?", job["artifact_id"], job["commit_sha"], d.id)
                jdbc.queryForObject("SELECT sha256 FROM artifacts WHERE id = ?", String::class.java, job["artifact_id"])
            }
            "FAILED" -> "ERR:Build failed: ${job["error"] ?: "see build log"}"
            else -> null
        }
    }

    private fun policy(d: DeploymentDto): String? {
        val active = jdbc.queryForObject("SELECT active FROM projects WHERE id = ?", Boolean::class.java, d.projectId)
        if (active != true) return "Project no longer exists"
        if (isCodeApp(d)) {
            val repo = jdbc.queryForObject("SELECT count(*) FROM repositories WHERE project_id = ? AND state = 'ACTIVE'", Long::class.java, d.projectId)
            return if (repo == 0L) "The project has no code repository" else null
        }
        val page = json.readTree(snapshot(d))
        val problems = validator.validate(page)
        if (problems.isNotEmpty()) return "Page schema violates the registry: " + problems.first().let { "${it.path} ${it.message}" }
        validator.unapprovedLinks(page).firstOrNull()?.let { return "Navigation links to a domain that is not approved: $it" }
        val refs = com.systemwebstudio.schema.PageSchemaValidator.assetRefs(page)
        if (refs.isNotEmpty()) {
            val ready = jdbc.queryForObject("SELECT count(*) FROM assets WHERE project_id = ? AND status = 'READY' AND id::text = ANY(string_to_array(?, ','))", Long::class.java,
                d.projectId, refs.joinToString(","))
            if (ready != refs.size.toLong()) return "The page references files that no longer exist in this project"
        }
        return null
    }

    private fun security(d: DeploymentDto): String? {
        if (isCodeApp(d)) return null      // code is scanned in the build sandbox (secrets, dependencies) and again when the output is stored
        val text = snapshot(d).lowercase()
        forbidden.firstOrNull { it in text }?.let { return "Security check failed: forbidden content '$it' found in the page" }
        return null
    }

    /** Real provider: render + store the artifact and record it on the deployment. Mock: a hash of the page, nothing is built. */
    private fun build(d: DeploymentDto, record: Boolean = true): String {
        if (!provider.buildsArtifacts) return MessageDigest.getInstance("SHA-256").digest(snapshot(d).toByteArray()).joinToString("") { "%02x".format(it) }
        val artifactId = builder.build(d.projectId, d.versionId, snapshot(d))
        if (record) jdbc.update("UPDATE deployments SET artifact_id = ? WHERE id = ?", artifactId, d.id)
        return jdbc.queryForObject("SELECT sha256 FROM artifacts WHERE id = ?", String::class.java, artifactId)!!
    }

    private fun pause() { if (stepDelayMs > 0) Thread.sleep(stepDelayMs) }
    private fun sleep(ms: Long) { if (ms > 0) Thread.sleep(ms) }

    private fun move(d: DeploymentDto, from: String, to: String, message: String): Boolean {
        val ok = deployments.transition(d.id, from, to, message)
        if (ok) audit.record("DEPLOY_STATUS_CHANGE", "DEPLOYMENT", d.id, workspaceOf(d), d.projectId, actorId = null, oldValue = mapOf("status" to from), newValue = mapOf("status" to to))
        return ok
    }

    private fun finish(d: DeploymentDto, from: String, url: String): Boolean {
        val ok = deployments.transition(d.id, from, DeploymentStatus.RUNNING, "Deployment is running", url = url)
        if (ok) {
            jdbc.update("UPDATE projects SET site_visibility = ? WHERE id = ?", d.visibility, d.projectId)   // no revision bump: background job
            audit.record("DEPLOY_STATUS_CHANGE", "DEPLOYMENT", d.id, workspaceOf(d), d.projectId, actorId = null,
                oldValue = mapOf("status" to from), newValue = mapOf("status" to DeploymentStatus.RUNNING, "url" to url, "provider" to provider.name))
        }
        return ok
    }

    private fun fail(d: DeploymentDto, from: String, failure: StepFailure, attempts: Int, rollback: RollbackResult?) {
        val rolledBack = rollback?.takeUnless { it is RollbackResult.NotSwitched }
        val error = (failure.reason(attempts) + (rolledBack?.let { " | rollback: ${it.summary}" } ?: "")).take(StepFailure.MAX_LENGTH)
        if (deployments.transition(d.id, from, DeploymentStatus.FAILED, null, error = error)) {
            audit.record("DEPLOY_STATUS_CHANGE", "DEPLOYMENT", d.id, workspaceOf(d), d.projectId, actorId = null,
                oldValue = mapOf("status" to from), newValue = mapOf("status" to DeploymentStatus.FAILED, "error" to error, "code" to failure.code.name))
        }
    }

    private fun workspaceOf(d: DeploymentDto): UUID = jdbc.queryForObject("SELECT workspace_id FROM projects WHERE id = ?", UUID::class.java, d.projectId)!!
}
