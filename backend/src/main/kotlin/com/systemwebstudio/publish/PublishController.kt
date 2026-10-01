package com.systemwebstudio.publish

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.common.RateLimiter
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.deploy.DeployProvider
import com.systemwebstudio.integration.queue.JobQueue
import com.systemwebstudio.integration.queue.Queues
import com.systemwebstudio.schema.SchemaService
import com.systemwebstudio.version.SchemaRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest
import java.util.UUID

data class PublishRequest(
    @field:NotNull @field:Pattern(regexp = "PRIVATE|PUBLIC") val visibility: String?,
    @field:NotNull val expectedRevision: Long?
)

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}")
class PublishController(
    private val access: AccessService,
    private val deployments: DeploymentRepository,
    private val schemas: SchemaService,
    private val versions: SchemaRepository,
    private val queue: JobQueue,
    private val provider: DeployProvider,
    private val limiter: RateLimiter,
    private val audit: AuditService,
    private val jdbc: JdbcTemplate,
    @Value("\${app.rate-limit.publish-max:10}") private val publishMax: Long
) {
    private val keyPattern = Regex("^[A-Za-z0-9_.:-]{8,120}$")

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @PostMapping("/publish")
    @Transactional
    fun publish(
        @PathVariable workspaceId: UUID, @PathVariable projectId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: PublishRequest, @AuthenticationPrincipal me: StudioUserDetails
    ): ResponseEntity<DeploymentDto> {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_PUBLISH)
        if (!keyPattern.matches(idempotencyKey)) throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 8-120 characters of A-Z a-z 0-9 _ . : -")
        val project = ctx.project!!
        val requestHash = sha("${request.visibility}|${request.expectedRevision}")
        val deploymentId = UUID.randomUUID()
        val scope = "publish:$projectId:${me.userId}:$idempotencyKey"
        // The unique key serializes concurrent duplicates: the loser waits for the winner's commit, then sees the row.
        val inserted = jdbc.update("INSERT INTO idempotency_keys (scope_key, request_hash, resource_type, resource_id) VALUES (?,?,'DEPLOYMENT',?) ON CONFLICT DO NOTHING",
            scope, requestHash, deploymentId)
        if (inserted == 0) {
            val existing = jdbc.queryForMap("SELECT request_hash, resource_id FROM idempotency_keys WHERE scope_key = ?", scope)
            if (existing["request_hash"] != requestHash) throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used with a different request")
            val dep = deployments.findInProject(projectId, existing["resource_id"] as UUID) ?: throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS", "The original request is still being processed")
            return ResponseEntity.status(HttpStatus.ACCEPTED).header("Idempotent-Replay", "true").body(dep)
        }
        limiter.require("publish:${me.userId}", publishMax, 60, "publish")
        if (project.revision != request.expectedRevision) {
            throw ApiException.conflict("REVISION_CONFLICT", "Project changed elsewhere; reload and retry.", mapOf("currentRevision" to project.revision))
        }
        schemas.ensureInitialized(project, me.userId)
        val version = versions.latest(projectId) ?: throw ApiException.conflict("NO_VERSION", "Project has no version to publish")
        deployments.insert(deploymentId, workspaceId, projectId, version.id, me.userId, request.visibility!!, provider.name)
        audit.record("PUBLISH", "DEPLOYMENT", deploymentId, workspaceId, projectId,
            newValue = mapOf("visibility" to request.visibility, "versionNumber" to version.versionNumber, "revision" to project.revision))
        // Only talk to the broker once the row is durable.
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                runCatching { queue.publish(Queues.PUBLISH, deploymentId.toString()) }
                    .onFailure { org.slf4j.LoggerFactory.getLogger(PublishController::class.java).warn("Broker unavailable; recovery sweeper will retry {}", deploymentId) }
            }
        })
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(deployments.findInProject(projectId, deploymentId))
    }

    @GetMapping("/deployments")
    @Transactional(readOnly = true)
    fun list(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<DeploymentDto> {
        access.forProject(me.userId, workspaceId, projectId)
        return deployments.list(projectId)
    }

    @GetMapping("/deployments/{deploymentId}")
    @Transactional(readOnly = true)
    fun get(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable deploymentId: UUID, @AuthenticationPrincipal me: StudioUserDetails): DeploymentDto {
        access.forProject(me.userId, workspaceId, projectId)
        return deployments.findInProject(projectId, deploymentId) ?: throw ApiException.notFound("DEPLOYMENT_NOT_FOUND", "Deployment not found")
    }
}
