package com.systemwebstudio.wiring

import com.systemwebstudio.data.query.PageSpec
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.approval.DecisionKind
import tools.jackson.databind.JsonNode

/** A request body that is not acceptable. [code] is stable; [message] never repeats a value the client sent. */
class BadRuntimeRequest(val code: String, message: String) : RuntimeException(message, null, false, false)

/**
 * C0 · strict parsing of the three request bodies of `docs/contracts/v2/runtime-api.md`. A field that is not listed is refused (so a body carrying `tenantId`,
 * `userId`, `dataSourceId`, `sql`, `url`, `eventId`, … fails instead of being ignored — and instead of being trusted by a later change). Pure: no Spring, no IO.
 */
object RuntimeRequests {
    const val INVALID = "INVALID_REQUEST"
    const val MAX_PARAMS = 40
    const val MAX_INPUTS = 100
    private val QUERY_KEYS = setOf("mode", "params", "page", "mappingRef")
    private val ACTION_KEYS = setOf("mode", "inputs", "idempotencyKey", "trigger")
    private val WORKFLOW_KEYS = setOf("mode", "input", "idempotencyKey")
    private val DECISION_KEYS = setOf("decision", "comment")
    private val PAGE_KEYS = setOf("limit", "offset")
    private val TRIGGER_KEYS = setOf("eventName")
    private val MAPPING_REF = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val EVENT_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

    data class QueryRun(val mode: ExecutionMode, val params: Map<String, JsonNode>, val page: PageSpec?, val mappingRef: String?)
    data class ActionRun(val mode: ExecutionMode, val inputs: Map<String, JsonNode>, val idempotencyKey: String?, val eventName: String?)
    data class WorkflowStart(val mode: ExecutionMode, val input: JsonNode?, val idempotencyKey: String)

    data class ApprovalDecisionRequest(val decision: DecisionKind, val comment: String?)

    /** `{"decision":"APPROVE"|"REJECT","comment"?}` - nothing else: who decides and for which approval comes from the session and the path, never from the body. */
    fun approvalDecision(body: JsonNode?): ApprovalDecisionRequest {
        val n = obj(body, DECISION_KEYS) ?: throw bad("decision is required")
        val v = n.get("decision")?.takeUnless { it.isNull } ?: throw bad("decision is required")
        if (!v.isString) throw bad("decision must be APPROVE or REJECT")
        val kind = DecisionKind.entries.firstOrNull { it.name == v.asString() } ?: throw bad("decision must be APPROVE or REJECT")
        return ApprovalDecisionRequest(kind, text(n, "comment", 1000))
    }

    fun queryRun(body: JsonNode?): QueryRun {
        val n = obj(body, QUERY_KEYS)
        val page = n?.get("page")?.takeUnless { it.isNull }?.let { p ->
            strict(p, PAGE_KEYS)
            val limit = int(p, "limit") ?: throw bad("page.limit is required")
            val offset = int(p, "offset") ?: 0
            try { PageSpec(limit, offset) } catch (e: IllegalArgumentException) { throw bad("page is out of range") }
        }
        val mappingRef = text(n, "mappingRef", 64)?.also { if (!MAPPING_REF.matches(it)) throw bad("mappingRef is not a valid id") }
        return QueryRun(mode(n), plainMap(n, "params", MAX_PARAMS, plainValuesOnly = true), page, mappingRef)
    }

    fun actionRun(body: JsonNode?): ActionRun {
        val n = obj(body, ACTION_KEYS)
        val eventName = n?.get("trigger")?.takeUnless { it.isNull }?.let { t ->
            strict(t, TRIGGER_KEYS)
            text(t, "eventName", 128)?.also { if (!EVENT_NAME.matches(it)) throw bad("trigger.eventName is not valid") }
        }
        return ActionRun(mode(n), plainMap(n, "inputs", MAX_INPUTS, plainValuesOnly = false), text(n, "idempotencyKey", 128), eventName)
    }

    fun workflowStart(body: JsonNode?): WorkflowStart {
        val n = obj(body, WORKFLOW_KEYS)
        val key = text(n, "idempotencyKey", 128) ?: throw BadRuntimeRequest("IDEMPOTENCY_KEY_REQUIRED", "An idempotency key is required to start a workflow.")
        val input = n?.get("input")?.takeUnless { it.isNull }
        if (input != null && !input.isObject) throw bad("input must be an object")
        return WorkflowStart(mode(n), input, key)
    }

    // --- helpers ---------------------------------------------------------------------------------------------------------------------------------------------

    /** A missing or empty body is `{}`. Anything that is not an object, or has a field outside [allowed], is refused. */
    private fun obj(body: JsonNode?, allowed: Set<String>): JsonNode? {
        if (body == null || body.isNull || body.isMissingNode) return null
        strict(body, allowed)
        return body
    }

    private fun strict(n: JsonNode, allowed: Set<String>) {
        if (!n.isObject) throw bad("the body must be a JSON object")
        if (n.propertyNames().asSequence().toList().any { it !in allowed }) throw bad("the body has a field that is not accepted")
    }

    private fun mode(n: JsonNode?): ExecutionMode {
        val v = n?.get("mode")?.takeUnless { it.isNull } ?: return ExecutionMode.LIVE
        if (!v.isString) throw bad("mode must be LIVE or TEST")
        return ExecutionMode.entries.firstOrNull { it.name == v.asString() } ?: throw bad("mode must be LIVE or TEST")
    }

    private fun text(n: JsonNode?, key: String, max: Int): String? {
        val v = n?.get(key)?.takeUnless { it.isNull } ?: return null
        if (!v.isString) throw bad("$key must be text")
        val s = v.asString()
        if (s.isEmpty() || s.length > max) throw bad("$key has an invalid length")
        return s
    }

    private fun int(n: JsonNode, key: String): Int? {
        val v = n.get(key)?.takeUnless { it.isNull } ?: return null
        if (!v.isIntegralNumber || !v.canConvertToInt()) throw bad("$key must be an integer")
        return v.asInt()
    }

    private fun plainMap(n: JsonNode?, key: String, max: Int, plainValuesOnly: Boolean): Map<String, JsonNode> {
        val v = n?.get(key)?.takeUnless { it.isNull } ?: return emptyMap()
        if (!v.isObject) throw bad("$key must be an object")
        val names = v.propertyNames().asSequence().toList()
        if (names.size > max) throw bad("$key has too many entries")
        val out = LinkedHashMap<String, JsonNode>()
        for (name in names) {
            val value = v.get(name) ?: continue
            if (plainValuesOnly && (value.isObject || value.isArray)) throw bad("$key must hold plain values")
            out[name] = value
        }
        return out
    }

    private fun bad(message: String) = BadRuntimeRequest(INVALID, message)
}
