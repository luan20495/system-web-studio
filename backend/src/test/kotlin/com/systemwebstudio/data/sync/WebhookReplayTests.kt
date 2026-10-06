package com.systemwebstudio.data.sync

import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.InMemoryReplayGuard
import com.systemwebstudio.data.InMemoryWebhookEndpointStore
import com.systemwebstudio.data.RecordingWorkflowPort
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.sync.webhook.CreatedWebhook
import com.systemwebstudio.data.sync.webhook.IngressRequest
import com.systemwebstudio.data.sync.webhook.IngressResponse
import com.systemwebstudio.data.sync.webhook.WebhookAdminService
import com.systemwebstudio.data.sync.webhook.WebhookHttp
import com.systemwebstudio.data.sync.webhook.WebhookIngress
import com.systemwebstudio.data.sync.webhook.WebhookRoutes
import com.systemwebstudio.data.sync.webhook.WebhookSignature
import com.systemwebstudio.data.sync.webhook.WebhookSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID

/**
 * Replay protection of the public webhook route `POST /api/v1/webhooks/data/{endpointId}`.
 * The defect this guards: the replay identity used to be the *unsigned* `X-Xweb-Delivery` header, so a captured, validly signed request could be sent again
 * with another delivery id and be processed a second time inside the timestamp window. The identity is now the signature (and, for `v2`, the signed delivery id).
 */
class WebhookReplayTests {
    private val f = GatewayFixture()
    private val store = InMemoryWebhookEndpointStore()
    private val replay = InMemoryReplayGuard()
    private val workflows = RecordingWorkflowPort()
    private val admin = WebhookAdminService(store, f.vault, f.service, f.queries, workflows, f.guard, f.audit, f.clock)
    private val ingress = WebhookIngress(store, f.vault, replay, f.limiter, f.notifier, workflows, f.audit, f.clock)
    private val tenant = f.tenant(); private val ctx = f.ctx(tenant)
    private val ds: DataSource = f.register(tenant)
    init { f.query(tenant, ds, "customers") }

    private fun create(workflow: String? = null): CreatedWebhook =
        admin.create(ctx, WebhookSpec(ds.id, "hook", invalidateQueryIds = listOf("customers"), entity = "customer", recordKeyField = "id", workflowRef = workflow))
    private fun now() = f.clock.instant().epochSecond
    private fun body(vararg kv: Pair<String, Any?>) = DataJson.mapper.writeValueAsBytes(kv.toMap())
    private val event = body("event" to "customer.updated", "id" to "c-42")

    /** a request exactly as a sender would produce it: v1 (no signed delivery id) with whatever delivery header is passed along */
    private fun v1(w: CreatedWebhook, b: ByteArray = event, ts: Long = now(), delivery: String? = null) =
        IngressRequest(w.endpoint.id.toString(), headers(WebhookSignature.sign(w.secret, ts, b), ts, delivery), b, "203.0.113.9")
    private fun v2(w: CreatedWebhook, delivery: String, b: ByteArray = event, ts: Long = now(), headerDelivery: String? = delivery) =
        IngressRequest(w.endpoint.id.toString(), headers(WebhookSignature.sign(w.secret, ts, b, delivery), ts, headerDelivery), b, "203.0.113.9")
    private fun headers(sig: String, ts: Long, delivery: String?): Map<String, String> =
        mutableMapOf(WebhookSignature.HEADER_SIGNATURE to sig, WebhookSignature.HEADER_TIMESTAMP to ts.toString()).also { if (delivery != null) it[WebhookSignature.HEADER_DELIVERY] = delivery }
    private fun events(sub: com.systemwebstudio.data.cache.DataEventSubscription) = generateSequence { sub.next(10) }.toList().size

    // ---------------------------------------------------------------- the attack

    @Test fun `a captured request replayed with another delivery id is still a replay`() {
        val w = create(workflow = "wf-orders"); val sub = f.bus.subscribe(tenant.tenantId)
        val original = v1(w, delivery = "delivery-original")
        assertThat(ingress.handle(original)).isEqualTo(IngressResponse.ACCEPTED)
        val firstEvents = events(sub)
        assertThat(firstEvents).isGreaterThan(0)

        // the attacker has the request (sniffed or logged), cannot re-sign, but can change any header that the signature does not cover
        for (changed in listOf("delivery-attacker-1", "delivery-attacker-2", null, "x".repeat(8)))
            assertThat(ingress.handle(IngressRequest(original.endpointId, headers(original.headers[WebhookSignature.HEADER_SIGNATURE]!!, original.headers[WebhookSignature.HEADER_TIMESTAMP]!!.toLong(), changed), original.body, "198.51.100.77")))
                .isEqualTo(IngressResponse.DUPLICATE)
        assertThat(events(sub)).isEqualTo(0)                                    // no second invalidation, no second event…
        assertThat(workflows.triggers).hasSize(1)                               // …and no second workflow run
        assertThat(f.audit.events.count { it.action == DataAuditActions.WEBHOOK_ACCEPTED }).isEqualTo(1)
        sub.close()
    }

    @Test fun `the workflow identity is derived from the signature, not from an unsigned header`() {
        val w = create(workflow = "wf-orders")
        ingress.handle(v1(w, delivery = "delivery-claimed-by-sender"))
        val id = workflows.triggers.single().second.deliveryId
        assertThat(id).startsWith("sig-").doesNotContain("claimed")
        // a different body is a different request with a different identity
        ingress.handle(v1(w, body("event" to "e", "id" to "9"), delivery = "delivery-claimed-by-sender"))
        assertThat(workflows.triggers.map { it.second.deliveryId }.toSet()).hasSize(2)
    }

    @Test fun `the replay record is keyed by signature - the same signature never passes twice, different requests do`() {
        val w = create()
        assertThat(ingress.handle(v1(w))).isEqualTo(IngressResponse.ACCEPTED)
        assertThat(ingress.handle(v1(w))).isEqualTo(IngressResponse.DUPLICATE)                         // identical (ts, body)
        assertThat(ingress.handle(v1(w, body("event" to "customer.updated", "id" to "c-43")))).isEqualTo(IngressResponse.ACCEPTED)
        f.clock.advanceSeconds(1)
        assertThat(ingress.handle(v1(w, ts = now()))).isEqualTo(IngressResponse.ACCEPTED)             // a fresh timestamp is a fresh signature
    }

    // ---------------------------------------------------------------- signed delivery id (v2)

    @Test fun `a signed delivery id de-duplicates an event even when the sender re-signs the retry`() {
        val w = create(); val sub = f.bus.subscribe(tenant.tenantId)
        assertThat(ingress.handle(v2(w, "evt-2026-0001"))).isEqualTo(IngressResponse.ACCEPTED)
        assertThat(events(sub)).isGreaterThan(0)
        f.clock.advanceSeconds(30)
        assertThat(ingress.handle(v2(w, "evt-2026-0001", ts = now()))).isEqualTo(IngressResponse.DUPLICATE)   // new timestamp, new signature, same event id
        assertThat(events(sub)).isEqualTo(0)
        assertThat(ingress.handle(v2(w, "evt-2026-0002", ts = now()))).isEqualTo(IngressResponse.ACCEPTED)
        sub.close()
    }

    @Test fun `the delivery id of a v2 signature cannot be changed or removed`() {
        val w = create()
        val good = v2(w, "evt-2026-0001")
        assertThat(ingress.handle(IngressRequest(good.endpointId, good.headers + (WebhookSignature.HEADER_DELIVERY to "evt-2026-9999"), good.body, "203.0.113.9"))).isEqualTo(IngressResponse.UNAUTHORIZED)
        assertThat(ingress.handle(IngressRequest(good.endpointId, good.headers - WebhookSignature.HEADER_DELIVERY, good.body, "203.0.113.9"))).isEqualTo(IngressResponse.UNAUTHORIZED)
        assertThat(ingress.handle(good)).isEqualTo(IngressResponse.ACCEPTED)                                      // the untouched request still works
    }

    @Test fun `v1 and v2 signatures cannot be converted into each other`() {
        val b = event; val ts = now()
        val v1sig = WebhookSignature.sign("s3cret", ts, b)
        val v2sig = WebhookSignature.sign("s3cret", ts, b, "evt-2026-0001")
        assertThat(v1sig).startsWith("v1="); assertThat(v2sig).startsWith("v2=")
        assertThat(v1sig.removePrefix("v1=")).isNotEqualTo(v2sig.removePrefix("v2="))
        assertThat(WebhookSignature.verify("s3cret", ts, b, "v2=" + v1sig.removePrefix("v1="), "evt-2026-0001")).isFalse()
        assertThat(WebhookSignature.verify("s3cret", ts, b, "v1=" + v2sig.removePrefix("v2="), "evt-2026-0001")).isFalse()
        // a body that merely begins with a delivery-id-looking prefix does not collide with the v2 form either, and neither does another id/body split
        assertThat(WebhookSignature.sign("s3cret", ts, "evt-2026-0001.${String(b)}".toByteArray()).removePrefix("v1=")).isNotEqualTo(WebhookSignature.sign("s3cret", ts, b, "evt-2026-0001").removePrefix("v2="))
        assertThat(WebhookSignature.sign("s3cret", ts, "b.c".toByteArray(), "evt-a.0001").removePrefix("v2=")).isNotEqualTo(WebhookSignature.sign("s3cret", ts, "c".toByteArray(), "evt-a.0001.b").removePrefix("v2="))
        assertThat(WebhookSignature.verify("s3cret", ts, b, v2sig, null)).isFalse()
        assertThat(WebhookSignature.verify("s3cret", ts, b, v2sig, "short")).isFalse()
        assertThat(WebhookSignature.coversDelivery(v2sig)).isTrue(); assertThat(WebhookSignature.coversDelivery(v1sig)).isFalse()
    }

    // ---------------------------------------------------------------- failure handling and window

    @Test fun `a failed delivery releases both replay records so the sender's retry goes through`() {
        val w = create(workflow = "wf-orders")
        workflows.failing = true
        assertThat(ingress.handle(v2(w, "evt-2026-0001"))).isEqualTo(IngressResponse.UNAVAILABLE)
        workflows.failing = false
        assertThat(ingress.handle(v2(w, "evt-2026-0001"))).isEqualTo(IngressResponse.ACCEPTED)           // same signature, same event id: not a replay, the first never completed
        assertThat(workflows.triggers).hasSize(1)
        assertThat(ingress.handle(v2(w, "evt-2026-0001"))).isEqualTo(IngressResponse.DUPLICATE)
    }

    @Test fun `the replay record outlives the window in which a signature is acceptable`() {
        assertThat(WebhookIngress.SIGNATURE_REPLAY_TTL_SECONDS).isGreaterThan(2 * WebhookIngress.TOLERANCE_SECONDS)
        assertThat(WebhookIngress.REPLAY_TTL_SECONDS).isGreaterThanOrEqualTo(WebhookIngress.SIGNATURE_REPLAY_TTL_SECONDS)
        val w = create()
        val req = v1(w)
        assertThat(ingress.handle(req)).isEqualTo(IngressResponse.ACCEPTED)
        f.clock.advanceSeconds(WebhookIngress.TOLERANCE_SECONDS + 1)
        assertThat(ingress.handle(req)).isEqualTo(IngressResponse.UNAUTHORIZED)                          // past tolerance: the signed timestamp alone refuses it
    }

    @Test fun `a replayed request is audited by id and reason only`() {
        val w = create()
        ingress.handle(v1(w)); ingress.handle(v1(w))
        val rejection = f.audit.events.single { it.details["reason"] == "replay" }
        assertThat(rejection.tenantId).isEqualTo(tenant.tenantId)
        assertThat(rejection.details.keys).containsExactlyInAnyOrder("endpoint", "reason", "request")
        assertThat(rejection.details.values.map { it.toString() }.none { it.contains("c-42") || it.contains("v1=") }).isTrue()
    }

    // ---------------------------------------------------------------- tenant resolution and the HTTP edge

    @Test fun `a tenant named in the body or in headers is ignored - the endpoint decides`() {
        val w = create(); val other = f.tenant()
        val b = body("event" to "e", "id" to "r1", "tenantId" to other.tenantId.toString(), "dataSourceId" to UUID.randomUUID().toString())
        val sub = f.bus.subscribe(tenant.tenantId); val otherSub = f.bus.subscribe(other.tenantId)
        val req = v1(w, b)
        val withHeaders = WebhookHttp.request(req.endpointId, req.headers.mapValues { listOf(it.value) } + mapOf("X-Tenant-Id" to listOf(other.tenantId.toString()), "Cookie" to listOf("x=y")), b, "203.0.113.9")
        assertThat(withHeaders.headers.keys.all { it in setOf(WebhookSignature.HEADER_SIGNATURE, WebhookSignature.HEADER_TIMESTAMP, WebhookSignature.HEADER_DELIVERY) }).isTrue()
        assertThat(ingress.handle(withHeaders)).isEqualTo(IngressResponse.ACCEPTED)
        assertThat(events(sub)).isGreaterThan(0); assertThat(events(otherSub)).isEqualTo(0)
        assertThat(f.audit.events.filter { it.action == DataAuditActions.WEBHOOK_ACCEPTED }.map { it.tenantId }).containsExactly(tenant.tenantId)
        sub.close(); otherSub.close()
    }

    @Test fun `the canonical route and the body limit`() {
        assertThat(WebhookRoutes.PATH).isEqualTo("/api/v1/webhooks/data/{endpointId}")
        assertThat(WebhookRoutes.METHOD).isEqualTo("POST")
        val id = UUID.randomUUID()
        assertThat(WebhookRoutes.pathFor(id)).isEqualTo("/api/v1/webhooks/data/$id")
        assertThat(create().path).startsWith(WebhookRoutes.PREFIX)

        val max = WebhookIngress.MAX_BODY_BYTES
        val neverRead = object : InputStream() { override fun read(): Int = error("must not read an oversized declared body") }
        assertThat(WebhookHttp.readBody(neverRead, max + 1L)).isNull()                                         // refused on Content-Length alone
        assertThat(WebhookHttp.readBody(ByteArrayInputStream(ByteArray(max)), max.toLong())!!.size).isEqualTo(max)
        assertThat(WebhookHttp.readBody(ByteArrayInputStream(ByteArray(max + 1)), null)).isNull()              // undeclared (chunked) body is cut off at the limit
        assertThat(WebhookHttp.readBody(ByteArrayInputStream(ByteArray(max + 1)), 10)).isNull()                // a lying Content-Length does not help
        var consumed = 0
        val endless = object : InputStream() { override fun read(): Int { consumed++; return 1 }; override fun read(b: ByteArray, off: Int, len: Int): Int { consumed += len; java.util.Arrays.fill(b, off, off + len, 1); return len } }
        assertThat(WebhookHttp.readBody(endless, null)).isNull()
        assertThat(consumed <= max + 8192).isTrue()                                                   // an endless stream is never buffered beyond the limit
    }

    @Test fun `a repeated protocol header is dropped, so the request fails authentication`() {
        val w = create(); val good = v1(w)
        val doubled = WebhookHttp.request(good.endpointId, mapOf(
            "X-Xweb-Signature" to listOf(good.headers[WebhookSignature.HEADER_SIGNATURE]!!, "v1=" + "0".repeat(64)),
            "X-Xweb-Timestamp" to listOf(good.headers[WebhookSignature.HEADER_TIMESTAMP]!!)), good.body, "203.0.113.9")
        assertThat(doubled.headers.containsKey(WebhookSignature.HEADER_SIGNATURE)).isFalse()
        assertThat(ingress.handle(doubled)).isEqualTo(IngressResponse.UNAUTHORIZED)
        val single = WebhookHttp.request(good.endpointId, good.headers.mapValues { listOf(it.value) }, good.body, "203.0.113.9")
        assertThat(ingress.handle(single)).isEqualTo(IngressResponse.ACCEPTED)
        assertThat(WebhookHttp.responseBody(IngressResponse.DUPLICATE)).isEqualTo("{\"status\":\"duplicate\"}")
    }
}
