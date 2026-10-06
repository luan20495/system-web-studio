package com.systemwebstudio.data.sync.webhook

import java.io.InputStream
import java.util.UUID

/**
 * Canonical route of the public webhook ingest (contract data-runtime §6). C0 adds exactly this path to `permitAll` and CSRF-exempts only it; the
 * controller is a thin adapter over [WebhookIngress] (see [WebhookHttp]) and is part of C0's wiring.
 */
object WebhookRoutes {
    const val PREFIX = "/api/v1/webhooks/data/"
    const val PATH = "/api/v1/webhooks/data/{endpointId}"
    const val METHOD = "POST"
    fun pathFor(endpointId: UUID) = PREFIX + endpointId
}

/**
 * Framework-agnostic helpers for the webhook controller, so the security-relevant HTTP decisions are tested here rather than left to wiring:
 *
 * - **body limit**: [readBody] reads at most [WebhookIngress.MAX_BODY_BYTES] + 1 bytes and refuses a declared `Content-Length` above the limit before reading
 *   anything — an oversized body is never buffered;
 * - **headers**: only the three protocol headers are taken, and a header that arrives more than once is treated as absent (no "first one wins" ambiguity);
 * - **no tenant from the request**: the produced [IngressRequest] has no tenant, query string or cookie; the tenant is resolved from the endpoint id only;
 * - **uniform answers**: [IngressResponse] → status and a fixed JSON body with a stable code, never anything from the request or the endpoint.
 *
 * The controller must pass the **transport peer address** as `remoteAddress` (not a client-supplied `X-Forwarded-For` unless the platform's trusted-proxy
 * configuration has already resolved it).
 */
object WebhookHttp {
    private val HEADERS = setOf(WebhookSignature.HEADER_SIGNATURE, WebhookSignature.HEADER_TIMESTAMP, WebhookSignature.HEADER_DELIVERY)

    /** @return the body, or null when it is larger than [WebhookIngress.MAX_BODY_BYTES] (answer 413) */
    fun readBody(stream: InputStream, declaredLength: Long?): ByteArray? {
        if (declaredLength != null && declaredLength > WebhookIngress.MAX_BODY_BYTES) return null
        val out = java.io.ByteArrayOutputStream(minOf(8192, WebhookIngress.MAX_BODY_BYTES + 1))
        val buf = ByteArray(8192); var total = 0
        while (true) {
            val n = stream.read(buf, 0, minOf(buf.size, WebhookIngress.MAX_BODY_BYTES + 1 - total))
            if (n < 0) break
            total += n; out.write(buf, 0, n)
            if (total > WebhookIngress.MAX_BODY_BYTES) return null
        }
        return out.toByteArray()
    }

    /** [allHeaders]: header name → every value received for it */
    fun request(endpointId: String, allHeaders: Map<String, List<String>>, body: ByteArray, peerAddress: String?): IngressRequest {
        val picked = HashMap<String, String>(); val seen = HashSet<String>(); val repeated = HashSet<String>()
        for ((name, values) in allHeaders) {
            val key = name.lowercase()
            if (key !in HEADERS) continue
            if (!seen.add(key) || values.size != 1) repeated += key else picked[key] = values[0]
        }
        // a repeated protocol header is dropped entirely: the request then fails authentication like any other unsigned one
        repeated.forEach { picked.remove(it) }
        return IngressRequest(endpointId, picked, body, peerAddress)
    }

    fun responseBody(r: IngressResponse): String = "{\"status\":\"${r.code}\"}"
}
