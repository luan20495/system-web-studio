package com.systemwebstudio.logic.action

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Idempotency keys, as fixed by `docs/contracts/v2/data-runtime.md` §4.
 *
 * The key a **client** sends ("client key") is only ever an input. What C4 stores, logs, audits and forwards (to C3's `DataGateway`, to the
 * notification and workflow ports) is the **derived key**:
 *
 *     base64url( sha256( tenantId | appId | userId | actionId | clientKey ) )      // 43 chars, no padding, [A-Za-z0-9_-]
 *
 * Properties that the tests pin down:
 *  - **stable**: same canonical inputs ⇒ same key, so a retry of the same request reuses the same downstream reservation;
 *  - **scoped**: tenant, app, acting user and action are part of the digest, so two users (or two tenants) sending the same client key can
 *    never collide or replay each other's result, which is what C3 (no user in its scope) needs;
 *  - **opaque**: the digest cannot be reversed to the client key, so nothing downstream, no log line and no database row holds a raw key.
 *
 * Every component is validated before hashing so the `|` separator is unambiguous (UUIDs, a reference id and a key from the closed
 * client-key alphabet never contain it).
 */
object IdempotencyKeys {
    /** What a client may send. */
    val CLIENT_KEY = Regex("^[A-Za-z0-9._:-]{1,128}$")
    /** What C3 accepts (`GatewayMutation.idempotencyKey`). The derived key always matches. */
    val PORT_KEY = Regex("^[A-Za-z0-9_-]{8,128}$")
    /** Exactly what [derive] produces: base64url of a 32-byte digest, no padding. Port requests accept nothing looser, so a short raw key cannot pass. */
    val DERIVED_KEY = Regex("^[A-Za-z0-9_-]{43}$")
    private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun isValidClientKey(key: String) = CLIENT_KEY.matches(key)

    /** @throws IllegalArgumentException when a component would make the encoding ambiguous (callers validate first; the runtime never passes such input). */
    fun derive(tenantId: UUID, appId: UUID, userId: UUID, actionId: String, clientKey: String): String {
        require(ID.matches(actionId)) { "actionId is not a plain id" }
        require(CLIENT_KEY.matches(clientKey)) { "client key is not in the allowed alphabet" }
        val digest = MessageDigest.getInstance("SHA-256").digest("$tenantId|$appId|$userId|$actionId|$clientKey".toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** A one-shot key for a run that has no client key (OPTIONAL/NONE policy): unique per run, so it de-duplicates nothing and says so. */
    fun forFreshRun(tenantId: UUID, appId: UUID, userId: UUID, actionId: String, runId: String) =
        derive(tenantId, appId, userId, actionId, "run:$runId")

    /** Safe to log: an irreversible 8-char fingerprint of whatever string is given (a client key, a derived key). Never log the key itself. */
    fun redact(key: String?): String = if (key == null) "-" else "k#" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(8)
}
