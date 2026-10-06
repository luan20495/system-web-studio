package com.systemwebstudio.data.sync.webhook

import com.systemwebstudio.data.cache.ChangeCause
import com.systemwebstudio.data.cache.DataChange
import com.systemwebstudio.data.cache.DataChangeListener
import com.systemwebstudio.data.cache.RecordOperation
import com.systemwebstudio.data.datasource.CredentialVault
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.RateLimitGate
import com.systemwebstudio.data.query.DataJson
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.util.UUID

/** What the HTTP layer hands over: the path id, the lower-cased headers it saw, the **raw** body bytes (signature is over the bytes, not a re-serialisation) and the peer address. */
class IngressRequest(val endpointId: String, val headers: Map<String, String>, val body: ByteArray, val remoteAddress: String?) {
    override fun toString() = "IngressRequest(${body.size} bytes)"
}

/** [status] is the HTTP status; [code] a stable word. Nothing from the payload or the endpoint is echoed. */
data class IngressResponse(val status: Int, val code: String) {
    companion object {
        val ACCEPTED = IngressResponse(202, "accepted")
        val DUPLICATE = IngressResponse(202, "duplicate")
        val UNAUTHORIZED = IngressResponse(401, "unauthorized")
        val BAD_REQUEST = IngressResponse(400, "bad_request")
        val TOO_LARGE = IngressResponse(413, "payload_too_large")
        val RATE_LIMITED = IngressResponse(429, "rate_limited")
        val UNAVAILABLE = IngressResponse(503, "unavailable")
    }
}

/**
 * The public side of webhooks. Order matters and is the security design:
 *
 *  1. size cap, then **per-peer rate limit before any lookup** (an unknown id costs the same as a known one and cannot be enumerated quickly)
 *  2. resolve the endpoint by id (the one non-tenant lookup); unknown, disabled, bad signature, stale timestamp all answer the same 401
 *  3. timestamp inside ±[TOLERANCE_SECONDS] → HMAC verified in constant time against the current secret (or the previous one during rotation)
 *  4. per-endpoint rate limit, **after** authentication so an attacker cannot exhaust a tenant's budget with unsigned requests
 *  5. replay guard keyed on the **signature** (a captured request can never be replayed, whatever headers are changed or added) and, for `v2`
 *     signatures, also on the **signed** delivery id (a retry of the same event with a fresh timestamp is recognised). An unsigned delivery header is
 *     never used as an identity
 *  6. body parsed as JSON; only the bounded event name and one record id are read
 *  7. effects declared on the endpoint: cache invalidation + events through the [DataChangeListener], optional workflow trigger
 *  8. audit (ids and codes only; rejections are audited at a bounded rate so a flood cannot flood the audit log)
 *
 * No step grants a permission: the endpoint was created by an authorised user and can do only what it declares. The payload is never stored,
 * logged or forwarded.
 */
class WebhookIngress(
    private val store: WebhookEndpointStore, private val vault: CredentialVault, private val replay: WebhookReplayGuard, private val limits: RateLimitGate,
    private val listener: DataChangeListener, private val workflows: WorkflowTriggerPort, private val audit: DataAuditSink, private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** never throws: an unexpected failure (store, replay memory, workflow engine down) is a 503 the sender will retry, with no detail */
    fun handle(req: IngressRequest): IngressResponse =
        try { process(req) } catch (e: Exception) { log.warn("webhook ingress failed: {}", e.javaClass.simpleName); IngressResponse.UNAVAILABLE }

    private fun process(req: IngressRequest): IngressResponse {
        if (req.body.size > MAX_BODY_BYTES) return IngressResponse.TOO_LARGE
        if (!limits.allow("webhook-peer:${req.remoteAddress ?: "unknown"}", PEER_PER_MINUTE, 60)) return IngressResponse.RATE_LIMITED

        val id = try { UUID.fromString(req.endpointId) } catch (e: IllegalArgumentException) { return IngressResponse.UNAUTHORIZED }
        val endpoint = store.resolveForIngress(id) ?: return IngressResponse.UNAUTHORIZED
        if (endpoint.id != id) return IngressResponse.UNAUTHORIZED                                  // a store bug must not hand back another endpoint
        if (endpoint.status != WebhookStatus.ACTIVE) return reject(endpoint, "disabled")

        val ts = req.headers[WebhookSignature.HEADER_TIMESTAMP]?.trim()?.takeIf { it.length in 1..12 && it.all(Char::isDigit) }?.toLongOrNull() ?: return reject(endpoint, "no_timestamp")
        val sig = req.headers[WebhookSignature.HEADER_SIGNATURE]?.trim() ?: return reject(endpoint, "no_signature")
        if (Math.abs(clock.instant().epochSecond - ts) > TOLERANCE_SECONDS) return reject(endpoint, "stale_timestamp")
        val delivery = req.headers[WebhookSignature.HEADER_DELIVERY]?.trim()?.takeIf { WebhookSignature.DELIVERY_ID.matches(it) }
        if (!signatureOk(endpoint, ts, req.body, sig, delivery)) return reject(endpoint, "bad_signature")

        if (!limits.allow("webhook:${endpoint.id}", endpoint.ratePerMinute.toLong(), 60)) { auditOnce(endpoint, DataAuditActions.WEBHOOK_REJECTED, "rate_limited"); return IngressResponse.RATE_LIMITED }

        // replay identity: always the signature (hashed to a bounded key); a delivery id only when the signature covers it. An unsigned header can be changed
        // by whoever replays the request, so it can never serve as the nonce.
        val signedDelivery = if (WebhookSignature.coversDelivery(sig)) delivery else null
        val sigKey = "sig:" + sha256Hex(sig)
        val idKey = signedDelivery?.let { "id:$it" }
        if (!replay.firstSeen(endpoint.id, sigKey, SIGNATURE_REPLAY_TTL_SECONDS)) { auditOnce(endpoint, DataAuditActions.WEBHOOK_REJECTED, "replay"); return IngressResponse.DUPLICATE }
        if (idKey != null && !replay.firstSeen(endpoint.id, idKey, REPLAY_TTL_SECONDS)) { auditOnce(endpoint, DataAuditActions.WEBHOOK_REJECTED, "replay"); return IngressResponse.DUPLICATE }
        val deliveryId = signedDelivery ?: ("sig-" + sha256Hex(sig).take(32))                      // identity handed to workflows: signed id, or derived from the signature
        fun forgetAll() { replay.forget(endpoint.id, sigKey); idKey?.let { replay.forget(endpoint.id, it) } }

        val doc = try { DataJson.parse(req.body) } catch (e: Exception) { forgetAll(); return IngressResponse.BAD_REQUEST }
        if (!doc.isObject) { forgetAll(); return IngressResponse.BAD_REQUEST }
        val event = scalarText(doc.get(endpoint.eventField))?.takeIf { WebhookEndpoint.EVENT_NAME.matches(it) }
        if (endpoint.acceptedEvents.isNotEmpty() && (event == null || event !in endpoint.acceptedEvents)) { auditOnce(endpoint, DataAuditActions.WEBHOOK_REJECTED, "event_not_accepted"); return IngressResponse.ACCEPTED.copy(code = "ignored") }
        val recordKey = endpoint.recordKeyField?.let { scalarText(doc.get(it)) }?.takeIf { RECORD_KEY.matches(it) }

        try {
            endpoint.workflowRef?.let { workflows.trigger(endpoint.tenantId, it, WebhookTriggerContext(endpoint.dataSourceId, endpoint.id, deliveryId, event, recordKey)) }
        } catch (e: Exception) {
            log.warn("webhook workflow trigger failed: {}", e.javaClass.simpleName)
            forgetAll()                                                   // let the sender's retry through
            return IngressResponse.UNAVAILABLE
        }
        listener.onChange(DataChange(endpoint.tenantId, endpoint.dataSourceId, ChangeCause.WEBHOOK, endpoint.invalidateQueryIds, endpoint.entity,
            if (endpoint.entity != null && recordKey != null) RecordOperation.UPDATED else null, listOfNotNull(recordKey)))
        audit.record(DataAuditActions.WEBHOOK_ACCEPTED, endpoint.tenantId, endpoint.dataSourceId, mapOf("endpoint" to endpoint.id.toString(), "event" to event, "workflow" to (endpoint.workflowRef != null), "request" to "webhook-${endpoint.id}"))
        return IngressResponse.ACCEPTED
    }

    private fun signatureOk(e: WebhookEndpoint, ts: Long, body: ByteArray, sig: String, delivery: String?): Boolean {
        val now = clock.instant()
        val refs = listOfNotNull(e.secretRef, e.previousSecretRef?.takeIf { e.previousValidUntil != null && e.previousValidUntil.isAfter(now) })
        // every candidate is tried (no early exit on the first mismatch leaks nothing beyond "valid for some secret")
        var ok = false
        for (ref in refs) {
            val secret = try { vault.openRef(e.tenantId, ref).get("secret") } catch (x: Exception) { null } ?: continue
            if (WebhookSignature.verify(secret, ts, body, sig, delivery)) ok = true
        }
        return ok
    }

    private fun reject(e: WebhookEndpoint, reason: String): IngressResponse { auditOnce(e, DataAuditActions.WEBHOOK_REJECTED, reason); return IngressResponse.UNAUTHORIZED }

    /** at most [AUDIT_REJECTIONS_PER_MINUTE] rejection records per endpoint per minute: a flood of bad requests must not flood the audit trail */
    private fun auditOnce(e: WebhookEndpoint, action: String, reason: String) {
        if (!limits.allow("webhook-audit:${e.id}", AUDIT_REJECTIONS_PER_MINUTE, 60)) return
        audit.record(action, e.tenantId, e.dataSourceId, mapOf("endpoint" to e.id.toString(), "reason" to reason, "request" to "webhook-${e.id}"))
    }

    private fun scalarText(n: JsonNode?): String? = when {
        n == null || n.isNull || DataJson.isContainer(n) -> null
        DataJson.isText(n) -> DataJson.text(n)
        else -> n.toString()
    }

    companion object {
        const val MAX_BODY_BYTES = 256 * 1024
        const val TOLERANCE_SECONDS = 300L
        /** a signature is only valid inside ±[TOLERANCE_SECONDS], i.e. for at most twice that; its replay record must outlive that window */
        const val SIGNATURE_REPLAY_TTL_SECONDS = 2 * TOLERANCE_SECONDS + 60
        /** signed delivery ids are remembered long enough to recognise a sender's late retries of the same event */
        const val REPLAY_TTL_SECONDS = 24L * 3600
        const val PEER_PER_MINUTE = 300L
        const val AUDIT_REJECTIONS_PER_MINUTE = 10L
        private fun sha256Hex(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private val RECORD_KEY = Regex("^[A-Za-z0-9_.:-]{1,100}$")
    }
}
