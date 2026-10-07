package com.systemwebstudio.data.sync.webhook

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.CredentialVault
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.DataSourceService
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.auditFields
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayGuard
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.data.query.QueryCatalog
import org.springframework.data.redis.core.StringRedisTemplate
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

data class WebhookSpec(
    val dataSourceId: UUID, val name: String, val acceptedEvents: Set<String> = emptySet(), val eventField: String = "event",
    val invalidateQueryIds: List<String> = emptyList(), val entity: String? = null, val recordKeyField: String? = null,
    val workflowRef: String? = null, val ratePerMinute: Int = 120
)

/** The only time a secret is visible: in the response of create/rotate. [secret] is not stored anywhere in clear and is not part of any later read. */
class CreatedWebhook(val endpoint: WebhookEndpoint, val secret: String) {
    override fun toString() = "CreatedWebhook(${endpoint.id})"
    /** path of the public endpoint; the host is the deployment's */
    val path: String get() = WebhookRoutes.pathFor(endpoint.id)
}

/** Creates and manages inbound endpoints. Permission `WEBHOOK_MANAGE`, tenant-scoped, audited; no method returns a secret except at creation and rotation. */
class WebhookAdminService(
    private val store: WebhookEndpointStore, private val vault: CredentialVault, private val service: DataSourceService, private val queries: QueryCatalog,
    private val workflows: WorkflowTriggerPort, private val guard: GatewayGuard, private val audit: DataAuditSink,
    private val clock: Clock = Clock.systemUTC(), private val newId: () -> UUID = UUID::randomUUID, private val random: SecureRandom = SecureRandom()
) {
    fun create(ctx: GatewayContext, spec: WebhookSpec): CreatedWebhook {
        guard.require(ctx, GatewayOperation.WEBHOOK_MANAGE, spec.dataSourceId)
        val ds = service.resolve(ctx, spec.dataSourceId)
        if (store.list(ctx.tenantId, ds.id).size >= MAX_PER_SOURCE) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "too many webhooks for this data source")
        spec.invalidateQueryIds.forEach { q -> queries.find(ctx.tenantId, ds.id, q) ?: throw ConnectorFailure(FailureCodes.QUERY_NOT_FOUND, "query not found") }
        if (spec.workflowRef != null && !workflows.exists(ctx.tenantId, spec.workflowRef)) throw ConnectorFailure(FailureCodes.NOT_FOUND, "workflow not found")
        val secret = newSecret()
        val ref = vault.store(ctx.tenantId, mapOf("secret" to secret))
        val endpoint = try {
            WebhookEndpoint(newId(), ctx.tenantId, ds.id, spec.name, WebhookStatus.ACTIVE, ref, null, null, spec.acceptedEvents, spec.eventField, spec.invalidateQueryIds, spec.entity,
                spec.recordKeyField, spec.workflowRef, spec.ratePerMinute, ctx.actorUserId, clock.instant())
        } catch (e: IllegalArgumentException) { vault.discard(ctx.tenantId, ref); throw ConnectorFailure(FailureCodes.INVALID_PARAMS, e.message?.take(120) ?: "invalid webhook") }
        val saved = try { store.save(endpoint) } catch (e: Exception) { vault.discard(ctx.tenantId, ref); throw e }
        record(ctx, saved, "created")
        return CreatedWebhook(saved, secret)
    }

    /** New secret now; the old one keeps working for [graceSeconds] (at most a day) so the sender can switch without dropping deliveries. */
    fun rotate(ctx: GatewayContext, id: UUID, graceSeconds: Long = 3_600): CreatedWebhook {
        val e = load(ctx, id)
        val secret = newSecret()
        val ref = vault.store(ctx.tenantId, mapOf("secret" to secret))
        val grace = graceSeconds.coerceIn(0, MAX_GRACE_SECONDS)
        val now = clock.instant()
        val saved = try {
            store.save(e.copy(secretRef = ref, previousSecretRef = e.secretRef.takeIf { grace > 0 }, previousValidUntil = now.plusSeconds(grace).takeIf { grace > 0 }, updatedAt = now, version = e.version + 1))
        } catch (x: Exception) { vault.discard(ctx.tenantId, ref); throw x }
        e.previousSecretRef?.let { vault.discard(ctx.tenantId, it) }                  // a secret older than the previous one is superseded
        if (grace == 0L) vault.discard(ctx.tenantId, e.secretRef)
        record(ctx, saved, "rotated")
        return CreatedWebhook(saved, secret)
    }

    fun disable(ctx: GatewayContext, id: UUID) = setStatus(ctx, id, WebhookStatus.DISABLED, "disabled")
    fun enable(ctx: GatewayContext, id: UUID) = setStatus(ctx, id, WebhookStatus.ACTIVE, "enabled")

    fun delete(ctx: GatewayContext, id: UUID) {
        val e = load(ctx, id)
        store.delete(ctx.tenantId, id)
        listOfNotNull(e.secretRef, e.previousSecretRef).forEach { vault.discard(ctx.tenantId, it) }
        record(ctx, e, "deleted")
    }

    fun list(ctx: GatewayContext, dataSourceId: UUID): List<WebhookEndpoint> {
        guard.require(ctx, GatewayOperation.WEBHOOK_MANAGE, dataSourceId)
        service.resolve(ctx, dataSourceId)
        return store.list(ctx.tenantId, dataSourceId)
    }

    private fun setStatus(ctx: GatewayContext, id: UUID, status: WebhookStatus, what: String): WebhookEndpoint {
        val e = load(ctx, id)
        val saved = store.save(e.copy(status = status, updatedAt = clock.instant(), version = e.version + 1))
        record(ctx, saved, what); return saved
    }

    private fun load(ctx: GatewayContext, id: UUID): WebhookEndpoint {
        val e = store.find(ctx.tenantId, id)
        guard.require(ctx, GatewayOperation.WEBHOOK_MANAGE, e?.dataSourceId)
        if (e == null || e.tenantId != ctx.tenantId) throw ConnectorFailure(FailureCodes.NOT_FOUND, "webhook not found")
        return e
    }

    private fun record(ctx: GatewayContext, e: WebhookEndpoint, change: String) =
        audit.record(DataAuditActions.WEBHOOK_ENDPOINT_CHANGED, ctx.tenantId, e.dataSourceId, mapOf("endpoint" to e.id.toString(), "change" to change) + ctx.auditFields())

    private fun newSecret(): String = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    companion object { const val MAX_PER_SOURCE = 10; const val MAX_GRACE_SECONDS = 86_400L }
}

/** [WebhookReplayGuard] on the platform's Redis: `SET key NX EX ttl`. A Redis outage rejects the delivery (503) rather than accepting it unchecked. */
class RedisWebhookReplayGuard(private val redis: StringRedisTemplate) : WebhookReplayGuard {
    override fun firstSeen(endpointId: UUID, key: String, ttlSeconds: Long): Boolean =
        redis.opsForValue().setIfAbsent("xw:webhook:replay:$endpointId:$key", "1", Duration.ofSeconds(ttlSeconds)) == true
    override fun forget(endpointId: UUID, key: String) { redis.delete("xw:webhook:replay:$endpointId:$key") }
}
