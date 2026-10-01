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
                    DeploymentStatus.BUILDING -> { artifactHash = build(d); null }
                    DeploymentStatus.DEPLOYING -> {
                        val project = jdbc.queryForMap("SELECT name, deployment_target FROM projects WHERE id = ?", d.projectId)
                        val result = provider.deploy(DeployRequest(d.id, project["name"] as String, d.versionNumber, d.visibility, project["deployment_target"] as String?, artifactHash.ifEmpty { build(d) }))
                        url = result.url; result.error
                    }
                    else -> "Unexpected state $current"
                }
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
        DeploymentStatus.BUILDING -> "Building artifact (mock)"
        DeploymentStatus.DEPLOYING -> "Deploying through the mock provider"
        else -> s
    }

    private fun snapshot(d: DeploymentDto) =
        jdbc.queryForObject("SELECT schema_snapshot::text FROM project_versions WHERE id = ?", String::class.java, d.versionId)!!

    private fun policy(d: DeploymentDto): String? {
        val active = jdbc.queryForObject("SELECT active FROM projects WHERE id = ?", Boolean::class.java, d.projectId)
        if (active != true) return "Project no longer exists"
        val problems = validator.validate(json.readTree(snapshot(d)))
        return if (problems.isEmpty()) null else "Page schema violates the registry: " + problems.first().let { "${it.path} ${it.message}" }
    }

    private fun security(d: DeploymentDto): String? {
        val text = snapshot(d).lowercase()
        forbidden.firstOrNull { it in text }?.let { return "Security check failed: forbidden content '$it' found in the page" }
        return null
    }

    private fun build(d: DeploymentDto): String =
        MessageDigest.getInstance("SHA-256").digest(snapshot(d).toByteArray()).joinToString("") { "%02x".format(it) }

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
