package com.systemwebstudio.data.sync.webhook

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `X-Xweb-Signature`, two forms; the timestamp is always signed, so a captured request cannot be re-dated, and comparison is constant-time:
 *
 * - `v1=<hex>` = HMAC-SHA256(secret, "<timestamp>.<raw body>"). Any `X-Xweb-Delivery` header that comes with it is **not authenticated** and is ignored.
 * - `v2=<hex>` = HMAC-SHA256(secret, "v2.<timestamp>.<length of delivery id>.<delivery id>.<raw body>"): the delivery id is part of what is signed, so it
 *   is a real, sender-chosen event id the platform can de-duplicate on (a retry of the same event with a fresh timestamp is recognised). The id must match
 *   [DELIVERY_ID]. The `v2.` tag and the length prefix keep the two forms (and every delivery-id/body split) from ever producing the same MAC input: a `v1`
 *   input starts with a digit, a `v2` input with `v`.
 *
 * Replay protection never trusts a header that is not covered by the signature (see [WebhookIngress]). The headers are `X-Xweb-Timestamp` (Unix seconds),
 * `X-Xweb-Delivery` (optional unless `v2`). Senders (and tests) use [sign]; the platform only ever [verify]s.
 */
object WebhookSignature {
    const val HEADER_SIGNATURE = "x-xweb-signature"
    const val HEADER_TIMESTAMP = "x-xweb-timestamp"
    const val HEADER_DELIVERY = "x-xweb-delivery"
    private const val V1 = "v1="
    private const val V2 = "v2="
    val DELIVERY_ID = Regex("^[A-Za-z0-9._:-]{8,128}$")

    /** `delivery == null` → `v1`; otherwise `v2` over the delivery id too */
    fun sign(secret: String, timestampSeconds: Long, body: ByteArray, delivery: String? = null): String {
        require(delivery == null || DELIVERY_ID.matches(delivery)) { "invalid delivery id" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        if (delivery != null) mac.update("v2.".toByteArray(Charsets.UTF_8))
        mac.update(timestampSeconds.toString().toByteArray(Charsets.UTF_8)); mac.update('.'.code.toByte())
        if (delivery != null) { mac.update(delivery.length.toString().toByteArray(Charsets.UTF_8)); mac.update('.'.code.toByte()); mac.update(delivery.toByteArray(Charsets.UTF_8)); mac.update('.'.code.toByte()) }
        mac.update(body)
        return (if (delivery == null) V1 else V2) + mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    /** [delivery] is the `X-Xweb-Delivery` header as received (or null); it only matters for a `v2` signature, which fails without it */
    fun verify(secret: String, timestampSeconds: Long, body: ByteArray, header: String, delivery: String? = null): Boolean {
        if (header.length != V1.length + 64) return false
        val expected = when {
            header.startsWith(V1) -> sign(secret, timestampSeconds, body, null)
            header.startsWith(V2) -> sign(secret, timestampSeconds, body, delivery?.takeIf { DELIVERY_ID.matches(it) } ?: return false)
            else -> return false
        }
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), header.toByteArray(Charsets.UTF_8))
    }

    /** `true` when the signature form covers the delivery id */
    fun coversDelivery(header: String) = header.startsWith(V2)
}
