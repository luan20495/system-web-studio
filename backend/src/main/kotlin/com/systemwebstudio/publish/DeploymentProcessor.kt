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
 * Each step is a compare-and-set on the status column, so a duplicate or redelivered message cannot
 * run the same step twice, and a crashed worker is resumed from the status it left behind.
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
    @Value("\${app.deploy.step-delay-ms:0}") private val stepDelayMs: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val forbidden = listOf("<script", "javascript:", "onerror=", "onload=", "data:text/html")

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
        var artifactHash = ""
        var url: String? = null
        while (current != DeploymentStatus.RUNNING) {
            pause()
            val error: String? = try {
                when (current) {
                    DeploymentStatus.POLICY_CHECK -> policy(d)
                    DeploymentStatus.SECURITY_CHECK -> security(d)
                    DeploymentStatus.BUILDING -> if (isCodeApp(d)) {
                        // code project: the sandbox runner builds it; the job's completion re-queues this deployment
                        when (val r = codeBuild(d)) { null -> return; else -> { if (r.startsWith("ERR:")) r.removePrefix("ERR:") else { artifactHash = r; null } } }
                    } else { artifactHash = build(d); null }
                    DeploymentStatus.DEPLOYING -> {
                        val project = jdbc.queryForMap("SELECT name, deployment_target FROM projects WHERE id = ?", d.projectId)
                        // read back from the row so a resumed job deploys the artifact the BUILDING step recorded
                        val artifactId = jdbc.queryForObject("SELECT artifact_id FROM deployments WHERE id = ?", UUID::class.java, d.id)
                        if (provider.buildsArtifacts && artifactId == null) "No artifact was built"
                        else {
                            val result = provider.deploy(DeployRequest(d.id, project["name"] as String, d.versionNumber, d.visibility, project["deployment_target"] as String?,
                                artifactHash.ifEmpty { artifactId?.let { a -> jdbc.queryForObject("SELECT sha256 FROM artifacts WHERE id = ?", String::class.java, a) } ?: build(d, record = false) },
                                d.projectId, artifactId))
                            url = result.url
                            // server apps: the same build's server part now goes to the isolated runtime (blue/green, health-checked)
                            if (result.error == null && artifactId != null && isServerApp(d)) {
                                val commit = jdbc.queryForObject("SELECT commit_sha FROM project_versions WHERE id = ?", String::class.java, d.versionId)
                                runtime.deploy(d.projectId, artifactId, commit, jdbc.queryForObject("SELECT requested_by FROM deployments WHERE id = ?", UUID::class.java, d.id))
                            }
                            result.error
                        }
                    }
                    else -> "Unexpected state $current"
                }
            } catch (e: BuildFailure) {
                e.message ?: "Build failed"
            } catch (e: Exception) {
                log.error("Deployment {} crashed in {}", id, current, e)
                "Internal error during $current"
            }
            if (error != null) { fail(d, current, error); return }
            val next = DeploymentStatus.pipeline[DeploymentStatus.pipeline.indexOf(current) + 1]
            val ok = if (next == DeploymentStatus.RUNNING) finish(d, current, url!!) else move(d, current, next, label(next))
            if (!ok) return
            current = next
        }
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

    private fun fail(d: DeploymentDto, from: String, error: String) {
        if (deployments.transition(d.id, from, DeploymentStatus.FAILED, null, error = error)) {
            audit.record("DEPLOY_STATUS_CHANGE", "DEPLOYMENT", d.id, workspaceOf(d), d.projectId, actorId = null,
                oldValue = mapOf("status" to from), newValue = mapOf("status" to DeploymentStatus.FAILED, "error" to error))
        }
    }

    private fun workspaceOf(d: DeploymentDto): UUID = jdbc.queryForObject("SELECT workspace_id FROM projects WHERE id = ?", UUID::class.java, d.projectId)!!
}
