package com.systemwebstudio.data.gateway

import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.discovery.DiscoveredEntity
import com.systemwebstudio.data.discovery.DiscoveryService
import com.systemwebstudio.data.mapping.ViewModelDataJson
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.PageSpec
import tools.jackson.databind.JsonNode
import java.util.UUID

/*
 * Framework-agnostic HTTP layer of the gateway: strict request parsers, JSON response builders and the failure → status mapping.
 * The Spring controllers that call these (route table: `api.DataRoutes`; wiring proposal: docs/parallel/agents/C3_WIRING_PROPOSAL.md) are C0's wiring because they need
 * the `permitAll`/session rules in `identity/SecurityConfiguration.kt` and the real `TenantContext` resolver (BLOCKERS B-C3-01/02):
 * they are thin — parse, build the GatewayContext on the server, call the gateway, render — and contain no logic of their own.
 */

/** the safe error body: a stable code and the fixed message of the failure, nothing from the cause */
data class GatewayProblem(val status: Int, val code: String, val message: String, val retryAfterSeconds: Int? = null) {
    fun toJson(requestId: String? = null): JsonNode = DataJson.toNode(linkedMapOf("code" to code, "message" to message, "requestId" to requestId))
}

object GatewayProblems {
    fun of(e: ConnectorFailure): GatewayProblem = GatewayProblem(status(e.code), e.code, e.safeMessage, if (e.code == FailureCodes.RATE_LIMITED) 30 else null)
    /** anything that is not a [ConnectorFailure] is an internal error with no detail */
    fun internal() = GatewayProblem(500, FailureCodes.INTERNAL, "unexpected error")

    fun status(code: String): Int = when (code) {
        FailureCodes.PERMISSION_DENIED -> 403
        FailureCodes.NOT_FOUND, FailureCodes.QUERY_NOT_FOUND, FailureCodes.MUTATION_NOT_FOUND, FailureCodes.SYNC_JOB_NOT_FOUND, FailureCodes.TENANT_MISMATCH -> 404
        FailureCodes.INVALID_PARAMS, FailureCodes.INVALID_CONFIG, FailureCodes.INVALID_QUERY, FailureCodes.INVALID_CREDENTIAL, FailureCodes.INVALID_EXPRESSION -> 400
        FailureCodes.INVALID_MAPPING, FailureCodes.MAPPING_FAILED, FailureCodes.MUTATION_UNSUPPORTED, FailureCodes.MUTATION_REJECTED, FailureCodes.UNSUPPORTED_TYPE, FailureCodes.READ_ONLY_VIOLATION -> 422
        FailureCodes.PAYLOAD_TOO_LARGE -> 413
        FailureCodes.RATE_LIMITED, FailureCodes.REFRESH_TOO_SOON -> 429
        FailureCodes.DISABLED, FailureCodes.CONFLICT, FailureCodes.IDEMPOTENCY_CONFLICT, FailureCodes.IDEMPOTENCY_IN_PROGRESS, FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, FailureCodes.SYNC_ORDER_VIOLATION -> 409
        FailureCodes.NOT_IMPLEMENTED -> 501
        FailureCodes.TIMEOUT -> 504
        FailureCodes.ADDRESS_BLOCKED, FailureCodes.HOST_UNRESOLVED, FailureCodes.CONNECT_FAILED, FailureCodes.TLS_FAILED, FailureCodes.AUTH_REJECTED, FailureCodes.REDIRECT_BLOCKED,
        FailureCodes.UPSTREAM_STATUS, FailureCodes.RESPONSE_INVALID, FailureCodes.RESPONSE_TOO_LARGE, FailureCodes.RESPONSE_NOT_JSON, FailureCodes.QUERY_FAILED -> 502
        else -> 500
    }
}

/**
 * Strict parsers for request bodies. Unknown keys are refused, so a body that carries `sql`, `url`, `headers`, `credential`, `tenantId` …
 * fails instead of being silently ignored (and instead of being trusted by a later change).
 */
object GatewayRequests {
    private val QUERY_KEYS = setOf("dataSourceId", "operation", "params", "page", "mappingRef", "viewModelRef")
    private val MUTATION_KEYS = setOf("dataSourceId", "operation", "params", "idempotencyKey")
    private val PAGE_KEYS = setOf("limit", "offset")
    private const val MAX_BODY = 64 * 1024

    fun query(body: ByteArray): GatewayQuery = query(tree(body))
    fun mutation(body: ByteArray): GatewayMutation = mutation(tree(body))

    fun query(n: JsonNode): GatewayQuery {
        strict(n, QUERY_KEYS)
        val page = n.get("page")?.takeUnless { it.isNull }?.let { p ->
            strict(p, PAGE_KEYS)
            try { PageSpec(int(p, "limit") ?: throw bad("page.limit is required"), int(p, "offset") ?: 0) } catch (e: IllegalArgumentException) { throw bad("invalid page") }
        }
        return GatewayQuery(uuid(n, "dataSourceId"), text(n, "operation"), params(n), page, text(n, "mappingRef"), n.get("viewModelRef")?.takeUnless { it.isNull }?.let { textOf(it, "viewModelRef") })
    }

    fun mutation(n: JsonNode): GatewayMutation {
        strict(n, MUTATION_KEYS)
        return GatewayMutation(uuid(n, "dataSourceId"), text(n, "operation"), params(n), text(n, "idempotencyKey"))
    }

    fun dataSourceId(text: String): UUID = try { UUID.fromString(text) } catch (e: IllegalArgumentException) { throw ConnectorFailure(FailureCodes.NOT_FOUND, "data source not found") }

    private fun tree(body: ByteArray): JsonNode {
        if (body.size > MAX_BODY) throw ConnectorFailure(FailureCodes.PAYLOAD_TOO_LARGE, "request is too large")
        return try { DataJson.parse(body) } catch (e: Exception) { throw bad("body is not valid JSON") }
    }
    private fun strict(n: JsonNode?, allowed: Set<String>) {
        if (n == null || !n.isObject) throw bad("body must be an object")
        if (DataJson.keys(n).any { it !in allowed }) throw bad("body has a field that is not accepted")
    }
    private fun params(n: JsonNode): Map<String, JsonNode> {
        val p = n.get("params") ?: return emptyMap()
        if (p.isNull) return emptyMap()
        if (!p.isObject) throw bad("params must be an object")
        val keys = DataJson.keys(p)
        if (keys.size > DefaultDataGateway.MAX_PARAMS) throw bad("too many params")
        return keys.associateWith { k -> p.get(k).also { v -> if (DataJson.isContainer(v)) throw bad("params must be plain values") } }
    }
    private fun text(n: JsonNode, key: String): String = textOf(n.get(key) ?: throw bad("$key is required"), key)
    private fun textOf(v: JsonNode, key: String): String { if (!DataJson.isText(v)) throw bad("$key must be text"); return DataJson.text(v).also { if (it.length > 200) throw bad("$key is too long") } }
    private fun uuid(n: JsonNode, key: String): UUID = try { UUID.fromString(text(n, key)) } catch (e: IllegalArgumentException) { throw bad("$key is not a valid id") }
    private fun int(n: JsonNode, key: String): Int? = n.get(key)?.let { if (!it.isIntegralNumber) throw bad("$key must be an integer"); it.asInt() }
    private fun bad(msg: String) = ConnectorFailure(FailureCodes.INVALID_PARAMS, msg)
}

/** Response bodies. Rows come out of the mapping; nothing here touches raw source data. */
object GatewayResponses {
    fun query(r: GatewayQueryResponse): JsonNode {
        val body = ViewModelDataJson.toNode(r.data)
        return DataJson.toNode(linkedMapOf("dataSourceId" to r.dataSourceId.toString(), "operation" to r.operation, "cache" to r.cache.name, "result" to body))
    }

    fun mutation(r: GatewayMutationResponse): JsonNode =
        DataJson.toNode(linkedMapOf("operation" to r.operation, "kind" to r.kind.name, "affected" to r.affected, "replayed" to r.replayed, "output" to r.output))

    fun connection(r: ConnectionTestResult): JsonNode = when (r) {
        is ConnectionTestResult.Ok -> DataJson.toNode(linkedMapOf("ok" to true, "latencyMs" to r.latencyMillis, "warnings" to r.warnings))      // fixed-text advisories (B-C0-W-04: "no write privilege")
        is ConnectionTestResult.Failed -> DataJson.toNode(linkedMapOf("ok" to false, "code" to r.code, "message" to r.message))
    }

    fun schema(r: DiscoveryService.RefreshResult): JsonNode {
        val s = r.snapshot
        return DataJson.toNode(linkedMapOf(
            "version" to s.version, "discoveredAt" to s.discoveredAt.toString(), "changed" to r.changed, "includesSamples" to s.includesSamples, "truncated" to s.schema.truncated,
            "warnings" to s.schema.warnings, "entities" to s.schema.entities.map(::entity),
            "diff" to r.diff?.let { d -> linkedMapOf("addedEntities" to d.addedEntities, "removedEntities" to d.removedEntities, "changedEntities" to d.changedEntities.mapValues { (_, e) -> linkedMapOf("added" to e.addedFields, "removed" to e.removedFields, "changed" to e.changedFields) }) }
        ))
    }

    internal fun entity(e: DiscoveredEntity) = linkedMapOf(
        "name" to e.name, "schema" to e.schema, "kind" to e.kind.name, "primaryKey" to e.primaryKey, "metadata" to e.metadata,
        "fields" to e.fields.map { linkedMapOf("name" to it.name, "type" to it.type.name, "nullable" to it.nullable, "primaryKey" to it.primaryKey, "sourceType" to it.sourceType) },
        "relations" to e.relations.map { linkedMapOf("name" to it.name, "from" to it.fromFields, "toEntity" to it.toEntity, "toSchema" to it.toSchema, "to" to it.toFields) },
        "sample" to e.sample
    )
}
