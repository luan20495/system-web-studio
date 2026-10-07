package com.systemwebstudio.logic.notification

import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.ActionNotifyPort
import com.systemwebstudio.logic.action.AuditDomains
import com.systemwebstudio.logic.action.LogicAuditPort
import com.systemwebstudio.logic.action.LogicAuditRecord
import com.systemwebstudio.logic.action.NotifyChannel
import com.systemwebstudio.logic.action.NotifyRequest
import com.systemwebstudio.logic.action.PortOutcome
import com.systemwebstudio.logic.action.PrincipalResolution
import com.systemwebstudio.logic.action.PrincipalResolver
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * Notification ports. Nothing here names a vendor: an email provider, an SMS gateway or a webhook client is a [ChannelSender] written by
 * whoever integrates it (C0), keyed by [NotifyChannel]. Content is never free text from an action: it is an approved template (templateRef)
 * plus typed params, and a webhook target is a pre-registered endpoint (endpointRef) whose URL/credentials only the sender knows.
 */

/** An approved template. [requiredParams] are checked before anything is sent. */
data class NotificationTemplate(val ref: String, val channels: Set<NotifyChannel>, val requiredParams: Set<String> = emptySet())

/** Tenant-scoped catalogue of approved templates and registered webhook endpoints (C0/C2 adapter). */
interface NotificationCatalog {
    fun template(tenantId: UUID, templateRef: String): NotificationTemplate?
    fun endpointExists(tenantId: UUID, endpointRef: String): Boolean
}

/** One message to one recipient (or to one webhook endpoint when [recipientUserId] is null). */
data class Delivery(
    val tenantId: UUID,
    val channel: NotifyChannel,
    val recipientUserId: UUID?,
    val templateRef: String,
    val params: Map<String, JsonNode>,
    val endpointRef: String?,
    /** Stable per (idempotency key, recipient): a sender that supports it should pass it to the provider. */
    val deliveryKey: String
)

sealed interface SendOutcome {
    data class Sent(val providerRef: String? = null) : SendOutcome
    data class Failed(val code: String, val retryable: Boolean, val message: String? = null) : SendOutcome
}

interface ChannelSender {
    val channel: NotifyChannel
    /** May throw; the service treats that as a retryable failure and never lets the exception text reach the caller. */
    fun send(delivery: Delivery): SendOutcome
}

enum class DeliveryStatus { PENDING, SENT, FAILED }

/** Delivery ledger used to de-duplicate retries: a recipient who already got the message is not messaged twice. */
interface DeliveryStore {
    /**
     * True if the caller now owns the delivery: it is absent, previously FAILED, or its PENDING claim is older than the store's lease (the
     * claiming node died between claim and finish). False: already SENT or another caller is sending right now.
     * A reclaimed PENDING delivery may have reached the provider before the crash, so delivery is at-least-once unless the sender passes
     * [Delivery.deliveryKey] to a provider that de-duplicates on it.
     */
    fun tryClaim(tenantId: UUID, deliveryKey: String, now: Instant): Boolean
    fun status(tenantId: UUID, deliveryKey: String): DeliveryStatus?
    fun finish(tenantId: UUID, deliveryKey: String, status: DeliveryStatus, now: Instant)
}

class InMemoryDeliveryStore(private val lease: java.time.Duration = java.time.Duration.ofMinutes(5)) : DeliveryStore {
    private data class Row(val status: DeliveryStatus, val at: Instant)
    private val rows = ConcurrentHashMap<Pair<UUID, String>, Row>()
    override fun tryClaim(tenantId: UUID, deliveryKey: String, now: Instant): Boolean {
        var claimed = false
        rows.compute(tenantId to deliveryKey) { _, old ->
            val reclaimable = old == null || old.status == DeliveryStatus.FAILED || (old.status == DeliveryStatus.PENDING && old.at.plus(lease).isBefore(now))
            if (reclaimable) { claimed = true; Row(DeliveryStatus.PENDING, now) } else old
        }
        return claimed
    }
    override fun status(tenantId: UUID, deliveryKey: String) = rows[tenantId to deliveryKey]?.status
    override fun finish(tenantId: UUID, deliveryKey: String, status: DeliveryStatus, now: Instant) { rows[tenantId to deliveryKey] = Row(status, now) }
}

/** In-app inbox, in memory. A persistent inbox (table + read state) is a migration request; this class is the reference [ChannelSender]. */
class InMemoryInAppChannel : ChannelSender {
    override val channel = NotifyChannel.IN_APP
    data class Entry(val tenantId: UUID, val userId: UUID, val templateRef: String, val params: Map<String, JsonNode>)
    private val inbox = java.util.concurrent.CopyOnWriteArrayList<Entry>()
    override fun send(delivery: Delivery): SendOutcome {
        val user = delivery.recipientUserId ?: return SendOutcome.Failed("INVALID_RECIPIENT", false, "IN_APP needs a user")
        inbox += Entry(delivery.tenantId, user, delivery.templateRef, delivery.params)
        return SendOutcome.Sent()
    }
    fun inbox(tenantId: UUID, userId: UUID): List<Entry> = inbox.filter { it.tenantId == tenantId && it.userId == userId }
}

object NotificationErrorCodes { const val DELIVERY_FAILED = "DELIVERY_FAILED" }

/**
 * Sends notifications through the registered channel senders. Implements [ActionNotifyPort] so the NOTIFY action, workflow steps and
 * approvals all use the same path.
 */
class NotificationService(
    private val json: JsonMapper,
    private val catalog: NotificationCatalog,
    senders: List<ChannelSender>,
    private val principals: PrincipalResolver,
    private val deliveries: DeliveryStore,
    private val audit: LogicAuditPort? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val maxRecipients: Int = 100
) : ActionNotifyPort {

    private val senders = senders.associateBy { it.channel }
    private val log = System.getLogger(NotificationService::class.java.name)

    override fun send(ctx: ActionContext, request: NotifyRequest): PortOutcome {
        val sender = senders[request.channel]
            ?: return PortOutcome.Failure(ActionErrorCodes.NOT_IMPLEMENTED, false, "Channel ${request.channel} is not configured")
        val template = catalog.template(ctx.tenantId, request.templateRef)
            ?: return PortOutcome.Failure(ActionErrorCodes.INVALID_INPUT, false, "Unknown notification template")
        if (request.channel !in template.channels) return PortOutcome.Failure(ActionErrorCodes.INVALID_INPUT, false, "Template is not approved for ${request.channel}")
        val missing = template.requiredParams.filter { it !in request.params }
        if (missing.isNotEmpty()) return PortOutcome.Failure(ActionErrorCodes.INVALID_INPUT, false, "Missing template params: ${missing.sorted().joinToString()}")

        val targets: List<UUID?> = if (request.channel == NotifyChannel.WEBHOOK) {
            val ep = request.endpointRef ?: return PortOutcome.Failure(ActionErrorCodes.INVALID_INPUT, false, "WEBHOOK needs an endpointRef")
            if (!catalog.endpointExists(ctx.tenantId, ep)) return PortOutcome.Failure(ActionErrorCodes.INVALID_INPUT, false, "Unknown webhook endpoint")
            listOf(null)
        } else {
            if (request.endpointRef != null) return PortOutcome.Failure(ActionErrorCodes.INVALID_INPUT, false, "endpointRef is only valid for WEBHOOK")
            val users = linkedSetOf<UUID>()
            if (request.recipients.isEmpty()) users += ctx.actor.userId
            for (spec in request.recipients) {
                when (val r = principals.resolve(ctx, spec)) {
                    is PrincipalResolution.Resolved -> users += r.userIds
                    is PrincipalResolution.Denied -> return PortOutcome.Failure(ActionErrorCodes.FORBIDDEN, false, "A recipient is not addressable")
                }
            }
            if (users.size > maxRecipients) return PortOutcome.Failure(ActionErrorCodes.LIMIT_EXCEEDED, false, "At most $maxRecipients recipients")
            if (users.isEmpty()) return PortOutcome.Failure(ActionErrorCodes.INVALID_INPUT, false, "No recipients resolved")
            users.toList()
        }

        val base = request.idempotencyKey ?: UUID.randomUUID().toString()
        var sent = 0; var skipped = 0; var retryable = 0; var permanent = 0
        for (target in targets) {
            val dKey = "${request.channel}:${request.templateRef}:$base:${target ?: request.endpointRef}"
            if (!deliveries.tryClaim(ctx.tenantId, dKey, clock.instant())) {
                if (deliveries.status(ctx.tenantId, dKey) == DeliveryStatus.SENT) skipped++ else retryable++
                continue
            }
            val outcome = try {
                sender.send(Delivery(ctx.tenantId, request.channel, target, request.templateRef, request.params, request.endpointRef, dKey))
            } catch (e: Exception) {
                log.log(System.Logger.Level.WARNING, "Channel sender ${request.channel} threw ${e.javaClass.simpleName}")
                SendOutcome.Failed(NotificationErrorCodes.DELIVERY_FAILED, true)
            }
            when (outcome) {
                is SendOutcome.Sent -> { sent++; deliveries.finish(ctx.tenantId, dKey, DeliveryStatus.SENT, clock.instant()) }
                is SendOutcome.Failed -> {
                    deliveries.finish(ctx.tenantId, dKey, DeliveryStatus.FAILED, clock.instant())
                    if (outcome.retryable) retryable++ else permanent++
                }
            }
        }
        auditBestEffort(ctx, request, sent, skipped, retryable + permanent)
        val summary = json.createObjectNode().put("channel", request.channel.name).put("sent", sent).put("alreadySent", skipped).put("failed", retryable + permanent)
        return when {
            retryable > 0 -> PortOutcome.Failure(NotificationErrorCodes.DELIVERY_FAILED, true, "Delivery failed for $retryable recipient(s); retry resends only those")
            permanent > 0 && sent + skipped == 0 -> PortOutcome.Failure(NotificationErrorCodes.DELIVERY_FAILED, false, "Delivery failed permanently")
            else -> PortOutcome.Success(summary)
        }
    }

    private fun auditBestEffort(ctx: ActionContext, r: NotifyRequest, sent: Int, skipped: Int, failed: Int) {
        val port = audit ?: return
        try {
            port.record(
                LogicAuditRecord(
                    AuditDomains.NOTIFICATION, if (failed == 0) "SENT" else "PARTIAL_OR_FAILED", ctx.tenantId, ctx.actor, ctx.projectId, "notification", r.templateRef,
                    attributes = mapOf("channel" to r.channel.name, "sent" to sent.toString(), "alreadySent" to skipped.toString(), "failed" to failed.toString()),
                    at = clock.instant()
                )
            )
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "Notification audit failed: ${e.javaClass.simpleName}")
        }
    }
}
