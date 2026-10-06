package com.systemwebstudio.data.sync.webhook

import java.time.Instant
import java.util.UUID

enum class WebhookStatus { ACTIVE, DISABLED }

/**
 * A signed inbound endpoint (`POST /api/v1/webhooks/data/{id}`, public by necessity — so it carries **no ambient authority**).
 * What an accepted delivery can do is fixed here by an authorised user (permission `WEBHOOK_MANAGE`) when the endpoint is created, and is
 * nothing more than: tell the platform that data of [dataSourceId] changed (drop the named cached queries or all of them, emit
 * `QueryInvalidated` / `DataChanged` / `RecordChanged`) and optionally start the workflow [workflowRef]. A delivery cannot read data, run a
 * query, run a mutation, choose a different data source or tenant, or put its payload anywhere: only a bounded event name and one record
 * identifier are taken from it.
 *
 * The secret itself lives in the credential store ([secretRef]); during a rotation [previousSecretRef] stays valid until [previousValidUntil].
 */
data class WebhookEndpoint(
    val id: UUID, val tenantId: UUID, val dataSourceId: UUID, val name: String, val status: WebhookStatus,
    val secretRef: String, val previousSecretRef: String? = null, val previousValidUntil: Instant? = null,
    /** event names accepted from the sender's `eventField`; empty = any event (still bounded to [EVENT_NAME]) */
    val acceptedEvents: Set<String> = emptySet(), val eventField: String = "event",
    val invalidateQueryIds: List<String> = emptyList(), val entity: String? = null, val recordKeyField: String? = null,
    val workflowRef: String? = null, val ratePerMinute: Int = 120,
    val createdBy: UUID?, val createdAt: Instant, val updatedAt: Instant = createdAt, val version: Long = 1
) {
    init {
        require(NAME.matches(name)) { "invalid webhook name" }
        require(FIELD.matches(eventField) && (recordKeyField == null || FIELD.matches(recordKeyField))) { "invalid field name" }
        require(acceptedEvents.size <= 50 && acceptedEvents.all { EVENT_NAME.matches(it) }) { "invalid accepted events" }
        require(invalidateQueryIds.size <= 50 && invalidateQueryIds.all { ID.matches(it) }) { "invalid query ids" }
        require(entity == null || ID.matches(entity)) { "invalid entity" }
        require(workflowRef == null || ID.matches(workflowRef)) { "invalid workflow reference" }
        require(ratePerMinute in 1..600) { "invalid rate" }
    }
    override fun toString() = "WebhookEndpoint(id=$id, tenant=$tenantId, ds=$dataSourceId, status=$status)"        // never the secret references
    companion object {
        val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}$")
        val FIELD = Regex("^[A-Za-z][A-Za-z0-9_]{0,39}$")
        val EVENT_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
        val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}

/**
 * Port: endpoint persistence (migration request in BOARD.md). Every lookup is tenant-scoped **except** [resolveForIngress], the single
 * place in the platform that finds a row by id alone — it has to, because an unauthenticated sender only knows the endpoint id. It returns
 * the endpoint (and with it the tenant) so everything after it can be scoped; it is called by nothing but [WebhookIngress].
 */
interface WebhookEndpointStore {
    fun find(tenantId: UUID, id: UUID): WebhookEndpoint?
    fun list(tenantId: UUID, dataSourceId: UUID?): List<WebhookEndpoint>
    fun save(endpoint: WebhookEndpoint): WebhookEndpoint
    fun delete(tenantId: UUID, id: UUID)
    fun resolveForIngress(id: UUID): WebhookEndpoint?
}

/** Port: replay memory. `firstSeen` is an atomic set-if-absent (Redis `SET NX EX`); [forget] undoes it when processing failed so the sender's retry is not mistaken for a replay. */
interface WebhookReplayGuard {
    fun firstSeen(endpointId: UUID, key: String, ttlSeconds: Long): Boolean
    fun forget(endpointId: UUID, key: String)
}

/** Port to the Workflow engine (not C3's). Called with identifiers only; the workflow reads data through the gateway under its own permissions. */
interface WorkflowTriggerPort {
    fun exists(tenantId: UUID, workflowRef: String): Boolean
    fun trigger(tenantId: UUID, workflowRef: String, context: WebhookTriggerContext)
}

data class WebhookTriggerContext(val dataSourceId: UUID, val endpointId: UUID, val deliveryId: String, val eventName: String?, val recordKey: String?)
