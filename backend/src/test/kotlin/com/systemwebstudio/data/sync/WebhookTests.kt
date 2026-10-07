package com.systemwebstudio.data.sync

import com.systemwebstudio.data.putNow
import com.systemwebstudio.data.testkit.RedisServerDouble
import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.InMemoryReplayGuard
import com.systemwebstudio.data.InMemoryWebhookEndpointStore
import com.systemwebstudio.data.LogCapture
import com.systemwebstudio.data.RecordingWorkflowPort
import com.systemwebstudio.data.cache.CacheKeyParts
import com.systemwebstudio.data.cache.CacheScope
import com.systemwebstudio.data.cache.DataEventType
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.data.sync.webhook.CreatedWebhook
import com.systemwebstudio.data.sync.webhook.IngressRequest
import com.systemwebstudio.data.sync.webhook.IngressResponse
import com.systemwebstudio.data.sync.webhook.RedisWebhookReplayGuard
import com.systemwebstudio.data.sync.webhook.WebhookAdminService
import com.systemwebstudio.data.sync.webhook.WebhookIngress
import com.systemwebstudio.data.sync.webhook.WebhookSignature
import com.systemwebstudio.data.sync.webhook.WebhookSpec
import com.systemwebstudio.data.sync.webhook.WebhookStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Webhook ingest: signed, replay-protected, rate-limited, tenant-resolved, audited — and with no authority beyond what its creator declared. */
class WebhookTests {
    private val f = GatewayFixture()
    private val store = InMemoryWebhookEndpointStore()
    private val replay = InMemoryReplayGuard()
    private val workflows = RecordingWorkflowPort()
    private val admin = WebhookAdminService(store, f.vault, f.service, f.queries, workflows, f.guard, f.audit, f.clock)
    private val ingress = WebhookIngress(store, f.vault, replay, f.limiter, f.notifier, workflows, f.audit, f.clock)

    private val tenant = f.tenant(); private val ctx: GatewayContext = f.ctx(tenant)
    private val ds: DataSource = f.register(tenant)
    init { f.query(tenant, ds, "customers"); f.query(tenant, ds, "orders") }

    private fun create(spec: WebhookSpec = WebhookSpec(ds.id, "orders hook", invalidateQueryIds = listOf("customers"), entity = "customer", recordKeyField = "id")): CreatedWebhook = admin.create(ctx, spec)
    private fun now() = f.clock.instant().epochSecond
    private fun body(vararg kv: Pair<String, Any?>) = com.systemwebstudio.data.query.DataJson.mapper.writeValueAsBytes(kv.toMap())
    private fun signed(w: CreatedWebhook, b: ByteArray = body("event" to "customer.updated", "id" to "c-42"), ts: Long = now(), delivery: String? = "delivery-0001", secret: String = w.secret, peer: String? = "203.0.113.9"): IngressRequest {
        val h = mutableMapOf(WebhookSignature.HEADER_TIMESTAMP to ts.toString(), WebhookSignature.HEADER_SIGNATURE to WebhookSignature.sign(secret, ts, b))
        if (delivery != null) h[WebhookSignature.HEADER_DELIVERY] = delivery
        return IngressRequest(w.endpoint.id.toString(), h, b, peer)
    }
    private fun cachePart(t: UUID = tenant.tenantId, d: UUID = ds.id) = CacheKeyParts(CacheScope(t, d, "customers"), 1, 1, null, 1, null, "{}", null)

    @Test fun `a correctly signed delivery is accepted and invalidates the declared queries and notifies subscribers`() {
        val w = create(); f.cache.putNow(cachePart(), "stale", 600)
        val sub = f.bus.subscribe(tenant.tenantId)
        assertThat(ingress.handle(signed(w))).isEqualTo(IngressResponse.ACCEPTED)
        assertThat(f.cache.get(cachePart())).isNull()
        val events = generateSequence { sub.next(10) }.toList()
        assertThat(events.map { it.type }).containsExactly(DataEventType.DATA_CHANGED, DataEventType.QUERY_INVALIDATED, DataEventType.RECORD_CHANGED)
        assertThat(events.last().recordKey).isEqualTo("c-42"); assertThat(events.last().entity).isEqualTo("customer")
        assertThat(f.audit.actions()).contains(DataAuditActions.WEBHOOK_ACCEPTED)
        assertThat(f.connector.queryCalls.get() + f.connector.mutationCalls.get()).isEqualTo(0)    // a webhook never reads or writes data
        sub.close()
    }

    @Test fun `wrong secret tampered body and tampered timestamp are all rejected without effect`() {
        val w = create(); f.cache.putNow(cachePart(), "keep", 600)
        val good = body("event" to "e", "id" to "1")
        val tamperedBody = signed(w, good).let { IngressRequest(it.endpointId, it.headers, body("event" to "e", "id" to "2"), it.remoteAddress) }
        val wrongSecret = signed(w, good, secret = "whsec_not-the-secret")
        val retimed = signed(w, good).let { IngressRequest(it.endpointId, it.headers + (WebhookSignature.HEADER_TIMESTAMP to (now() + 1).toString()), it.body, it.remoteAddress) }
        val noSig = signed(w, good).let { IngressRequest(it.endpointId, it.headers - WebhookSignature.HEADER_SIGNATURE, it.body, it.remoteAddress) }
        val noTs = signed(w, good).let { IngressRequest(it.endpointId, it.headers - WebhookSignature.HEADER_TIMESTAMP, it.body, it.remoteAddress) }
        val malformedSig = signed(w, good).let { IngressRequest(it.endpointId, it.headers + (WebhookSignature.HEADER_SIGNATURE to "v1=zz"), it.body, it.remoteAddress) }
        for (r in listOf(tamperedBody, wrongSecret, retimed, noSig, noTs, malformedSig)) assertThat(ingress.handle(r)).isEqualTo(IngressResponse.UNAUTHORIZED)
        assertThat(f.cache.get(cachePart())).isEqualTo("keep")
        assertThat(f.audit.actions().count { it == DataAuditActions.WEBHOOK_ACCEPTED }).isEqualTo(0)
    }

    @Test fun `stale or future timestamps are rejected even when the signature is valid`() {
        val w = create()
        assertThat(ingress.handle(signed(w, ts = now() - 301))).isEqualTo(IngressResponse.UNAUTHORIZED)
        assertThat(ingress.handle(signed(w, ts = now() + 301))).isEqualTo(IngressResponse.UNAUTHORIZED)
        assertThat(ingress.handle(signed(w, ts = now() - 299, delivery = "delivery-0002"))).isEqualTo(IngressResponse.ACCEPTED)
    }

    @Test fun `a replayed delivery is acknowledged but its effects do not run again`() {
        val w = create(); val sub = f.bus.subscribe(tenant.tenantId)
        assertThat(ingress.handle(signed(w))).isEqualTo(IngressResponse.ACCEPTED)
        val first = generateSequence { sub.next(10) }.toList().size
        assertThat(ingress.handle(signed(w))).isEqualTo(IngressResponse.DUPLICATE)
        assertThat(generateSequence { sub.next(10) }.toList()).isEmpty()
        assertThat(first).isGreaterThan(0)
        assertThat(f.audit.events.filter { it.details["reason"] == "replay" }).hasSize(1)
        // no delivery header: the signature itself is the identity, so a captured request cannot be replayed either
        val noId = signed(w, delivery = null, b = body("event" to "e", "id" to "9"))
        assertThat(ingress.handle(noId)).isEqualTo(IngressResponse.ACCEPTED)
        assertThat(ingress.handle(noId)).isEqualTo(IngressResponse.DUPLICATE)
        sub.close()
    }

    @Test fun `unknown disabled and malformed endpoint ids all look the same to the sender`() {
        val w = create()
        val unknown = IngressRequest(UUID.randomUUID().toString(), signed(w).headers, signed(w).body, "203.0.113.9")
        val garbage = IngressRequest("not-a-uuid", signed(w).headers, signed(w).body, "203.0.113.9")
        admin.disable(ctx, w.endpoint.id)
        val disabled = signed(w)
        assertThat(ingress.handle(unknown)).isEqualTo(IngressResponse.UNAUTHORIZED)
        assertThat(ingress.handle(garbage)).isEqualTo(IngressResponse.UNAUTHORIZED)
        assertThat(ingress.handle(disabled)).isEqualTo(IngressResponse.UNAUTHORIZED)
        admin.enable(ctx, w.endpoint.id)
        assertThat(ingress.handle(signed(w, delivery = "delivery-0003"))).isEqualTo(IngressResponse.ACCEPTED)
    }

    @Test fun `the tenant and data source come from the endpoint never from the request`() {
        val w = create()
        val other = f.tenant(); val dsB = f.register(other)
        f.cache.putNow(cachePart(other.tenantId, dsB.id), "B-data", 600); f.cache.putNow(cachePart(), "A-data", 600)
        val subB = f.bus.subscribe(other.tenantId)
        val evil = body("event" to "e", "id" to "1", "tenantId" to other.tenantId.toString(), "dataSourceId" to dsB.id.toString(), "tenant" to other.tenantId.toString())
        assertThat(ingress.handle(signed(w, evil))).isEqualTo(IngressResponse.ACCEPTED)
        assertThat(f.cache.get(cachePart())).isNull()                                                    // A was invalidated
        assertThat(f.cache.get(cachePart(other.tenantId, dsB.id))).isEqualTo("B-data")                   // B untouched
        assertThat(subB.next(10)).isNull()
        subB.close()
    }

    @Test fun `rate limits apply per peer before lookup and per endpoint only after authentication`() {
        val w = create(WebhookSpec(ds.id, "tiny", ratePerMinute = 3))
        // 1000 unsigned requests from one peer cannot consume the endpoint's budget...
        repeat(10) { ingress.handle(IngressRequest(w.endpoint.id.toString(), emptyMap(), body("x" to 1), "198.51.100.7")) }
        // ...so a legitimate sender from elsewhere still gets its 3 per minute
        repeat(3) { assertThat(ingress.handle(signed(w, body("event" to "e", "id" to "r$it"), delivery = "delivery-a$it"))).isEqualTo(IngressResponse.ACCEPTED) }   // distinct events: an identical request would be a replay
        assertThat(ingress.handle(signed(w, body("event" to "e", "id" to "r9"), delivery = "delivery-a9"))).isEqualTo(IngressResponse.RATE_LIMITED)
        f.clock.advanceSeconds(61)
        assertThat(ingress.handle(signed(w, delivery = "delivery-b0"))).isEqualTo(IngressResponse.ACCEPTED)
    }

    @Test fun `a single peer is limited before any endpoint is even looked up`() {
        val w = create()
        var limited = 0
        repeat(400) { if (ingress.handle(IngressRequest(w.endpoint.id.toString(), emptyMap(), body("x" to 1), "198.51.100.8")) == IngressResponse.RATE_LIMITED) limited++ }
        assertThat(limited).isGreaterThan(0)
    }

    @Test fun `oversized and malformed bodies are refused and a malformed one does not burn the delivery id`() {
        val w = create()
        assertThat(ingress.handle(signed(w, ByteArray(WebhookIngress.MAX_BODY_BYTES + 1)))).isEqualTo(IngressResponse.TOO_LARGE)
        val notJson = "not json".toByteArray()
        assertThat(ingress.handle(signed(w, notJson, delivery = "delivery-retry"))).isEqualTo(IngressResponse.BAD_REQUEST)
        assertThat(ingress.handle(signed(w, "[1,2]".toByteArray(), delivery = "delivery-retry"))).isEqualTo(IngressResponse.BAD_REQUEST)
        assertThat(ingress.handle(signed(w, body("event" to "e"), delivery = "delivery-retry"))).isEqualTo(IngressResponse.ACCEPTED)
    }

    @Test fun `only accepted events have an effect`() {
        val w = create(WebhookSpec(ds.id, "filtered", acceptedEvents = setOf("customer.updated"), invalidateQueryIds = listOf("customers")))
        f.cache.putNow(cachePart(), "keep", 600)
        assertThat(ingress.handle(signed(w, body("event" to "customer.deleted"), delivery = "delivery-e1")).code).isEqualTo("ignored")
        assertThat(ingress.handle(signed(w, body("noevent" to 1), delivery = "delivery-e2")).code).isEqualTo("ignored")
        assertThat(f.cache.get(cachePart())).isEqualTo("keep")
        assertThat(ingress.handle(signed(w, body("event" to "customer.updated"), delivery = "delivery-e3"))).isEqualTo(IngressResponse.ACCEPTED)
        assertThat(f.cache.get(cachePart())).isNull()
    }

    @Test fun `only identifier shaped values leave the payload and nothing else is stored or logged`() {
        val w = create()
        val sub = f.bus.subscribe(tenant.tenantId)
        LogCapture().use { logs ->
            ingress.handle(signed(w, body("event" to "customer.updated", "id" to "c 1; DROP TABLE", "email" to "ada@example.com", "note" to "PAYLOAD-SECRET")))
            ingress.handle(signed(w, body("event" to "customer.updated", "id" to "c-7", "email" to "ada@example.com", "note" to "PAYLOAD-SECRET"), delivery = "delivery-0009"))
            val seen = generateSequence { sub.next(10) }.toList().toString() + f.audit.text + logs.text
            for (leak in listOf("ada@example.com", "PAYLOAD-SECRET", "DROP TABLE", w.secret)) assertThat(seen).doesNotContain(leak)
            assertThat(seen).contains("c-7")
        }
        sub.close()
    }

    @Test fun `the workflow trigger gets identifiers only and a failure is retryable`() {
        val w = create(WebhookSpec(ds.id, "with workflow", workflowRef = "wf-orders", recordKeyField = "id"))
        workflows.failing = true
        assertThat(ingress.handle(signed(w, body("event" to "e", "id" to "r1", "secret" to "PAYLOAD-SECRET")))).isEqualTo(IngressResponse.UNAVAILABLE)
        workflows.failing = false
        assertThat(ingress.handle(signed(w, body("event" to "e", "id" to "r1", "secret" to "PAYLOAD-SECRET")))).isEqualTo(IngressResponse.ACCEPTED)   // same delivery id: not a replay, because the first never completed
        val (t, c) = workflows.triggers.single()
        assertThat(t).isEqualTo(tenant.tenantId); assertThat(c.recordKey).isEqualTo("r1"); assertThat(c.dataSourceId).isEqualTo(ds.id)
        assertThat(c.toString()).doesNotContain("PAYLOAD-SECRET")
    }

    @Test fun `infrastructure failures answer 503 and never accept unchecked`() {
        val w = create()
        replay.failing = true
        assertThat(ingress.handle(signed(w))).isEqualTo(IngressResponse.UNAVAILABLE)
        replay.failing = false; store.failResolve = true
        assertThat(ingress.handle(signed(w))).isEqualTo(IngressResponse.UNAVAILABLE)
        store.failResolve = false
        assertThat(ingress.handle(signed(w))).isEqualTo(IngressResponse.ACCEPTED)
    }

    @Test fun `rejections are audited at a bounded rate`() {
        val w = create()
        repeat(60) { i -> ingress.handle(IngressRequest(w.endpoint.id.toString(), mapOf(WebhookSignature.HEADER_TIMESTAMP to now().toString(), WebhookSignature.HEADER_SIGNATURE to "v1=" + "0".repeat(64)), body("i" to i), "10.0.0.${i % 200}")) }
        assertThat(f.audit.events.count { it.action == DataAuditActions.WEBHOOK_REJECTED }).isLessThanOrEqualTo(10)
        assertThat(f.audit.events.count { it.action == DataAuditActions.WEBHOOK_REJECTED }).isGreaterThan(0)
    }

    // ------------------------------------------------------------------------------------------------ secrets and administration

    @Test fun `the secret is visible once and stored only as ciphertext`() {
        val w = create()
        assertThat(w.secret).startsWith("whsec_")
        assertThat(w.toString()).doesNotContain(w.secret); assertThat(w.endpoint.toString()).doesNotContain(w.secret).doesNotContain(w.endpoint.secretRef)
        assertThat(f.credentialStore.find(tenant.tenantId, w.endpoint.secretRef)).startsWith("v1:")
        assertThat(f.credentialStore.find(tenant.tenantId, w.endpoint.secretRef)).doesNotContain(w.secret)
        assertThat(admin.list(ctx, ds.id).toString()).doesNotContain(w.secret)
        assertThat(f.audit.text).doesNotContain(w.secret)
        assertThat(w.path).isEqualTo("/api/v1/webhooks/data/${w.endpoint.id}")
        assertThat(create(WebhookSpec(ds.id, "second")).secret).isNotEqualTo(w.secret)
    }

    @Test fun `rotation keeps the old secret for the grace period only`() {
        val w = create()
        val rotated = admin.rotate(ctx, w.endpoint.id, graceSeconds = 600)
        assertThat(rotated.secret).isNotEqualTo(w.secret)
        assertThat(ingress.handle(signed(w, delivery = "delivery-old1"))).isEqualTo(IngressResponse.ACCEPTED)             // old secret, inside grace
        assertThat(ingress.handle(signed(rotated, delivery = "delivery-new1", secret = rotated.secret))).isEqualTo(IngressResponse.ACCEPTED)
        f.clock.advanceSeconds(601)
        assertThat(ingress.handle(signed(w, delivery = "delivery-old2", ts = now()))).isEqualTo(IngressResponse.UNAUTHORIZED)
        assertThat(ingress.handle(signed(rotated, delivery = "delivery-new2", secret = rotated.secret))).isEqualTo(IngressResponse.ACCEPTED)
        val again = admin.rotate(ctx, w.endpoint.id, graceSeconds = 0)
        assertThat(ingress.handle(signed(rotated, delivery = "delivery-new3", secret = rotated.secret))).isEqualTo(IngressResponse.UNAUTHORIZED)
        assertThat(ingress.handle(signed(again, delivery = "delivery-new4", secret = again.secret))).isEqualTo(IngressResponse.ACCEPTED)
    }

    @Test fun `administration needs its permission and another tenant sees nothing`() {
        val w = create()
        val b = f.tenant(); val ctxB = f.ctx(b)
        assertThat(f.failure { admin.rotate(ctxB, w.endpoint.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(f.failure { admin.disable(ctxB, w.endpoint.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(f.failure { admin.delete(ctxB, w.endpoint.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(f.failure { admin.list(ctxB, ds.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(f.failure { admin.create(ctxB, WebhookSpec(ds.id, "x")) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        f.authorizer.denied += GatewayOperation.WEBHOOK_MANAGE
        assertThat(f.failure { admin.create(ctx, WebhookSpec(ds.id, "y")) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(f.failure { admin.rotate(ctx, w.endpoint.id) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(f.failure { admin.rotate(ctx, UUID.randomUUID()) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)   // same answer for an unknown id
        assertThat(store.find(tenant.tenantId, w.endpoint.id)!!.status).isEqualTo(WebhookStatus.ACTIVE)
    }

    @Test fun `an endpoint can only be bound to things its creator can reference`() {
        assertThat(f.failure { create(WebhookSpec(ds.id, "bad query", invalidateQueryIds = listOf("nope"))) }.code).isEqualTo(FailureCodes.QUERY_NOT_FOUND)
        assertThat(f.failure { create(WebhookSpec(ds.id, "bad wf", workflowRef = "wf-missing")) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(f.failure { create(WebhookSpec(ds.id, "bad name;", ratePerMinute = 5)) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(f.failure { create(WebhookSpec(ds.id, "bad rate", ratePerMinute = 100_000)) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(f.failure { create(WebhookSpec(UUID.randomUUID(), "unknown ds")) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(f.credentialStore.size).isEqualTo(1)                                                  // failed creations left no orphan secret (the data source's own credential only)
    }

    @Test fun `deleting an endpoint removes its secrets and stops deliveries`() {
        val w = create(); admin.rotate(ctx, w.endpoint.id, 600)
        val e = store.find(tenant.tenantId, w.endpoint.id)!!
        admin.delete(ctx, w.endpoint.id)
        assertThat(f.credentialStore.find(tenant.tenantId, e.secretRef)).isNull(); assertThat(f.credentialStore.find(tenant.tenantId, e.previousSecretRef!!)).isNull()
        assertThat(ingress.handle(signed(w))).isEqualTo(IngressResponse.UNAUTHORIZED)
    }

    @Test fun `signature verification rejects malformed headers`() {
        val b = "x".toByteArray()
        val good = WebhookSignature.sign("s", 1, b)
        assertThat(WebhookSignature.verify("s", 1, b, good)).isTrue()
        assertThat(WebhookSignature.verify("s", 2, b, good)).isFalse()
        assertThat(WebhookSignature.verify("t", 1, b, good)).isFalse()
        assertThat(WebhookSignature.verify("s", 1, b, good.removePrefix("v1="))).isFalse()
        assertThat(WebhookSignature.verify("s", 1, b, "v2=" + good.removePrefix("v1="))).isFalse()
        assertThat(WebhookSignature.verify("s", 1, b, good.uppercase())).isFalse()
    }

    @Test fun `the redis replay guard is set if absent and forgettable`() {
        val redis = RedisServerDouble(); val g = RedisWebhookReplayGuard(redis); val e = UUID.randomUUID()
        assertThat(g.firstSeen(e, "d1", 60)).isTrue(); assertThat(g.firstSeen(e, "d1", 60)).isFalse(); assertThat(g.firstSeen(UUID.randomUUID(), "d1", 60)).isTrue()
        g.forget(e, "d1"); assertThat(g.firstSeen(e, "d1", 60)).isTrue()
    }
}
