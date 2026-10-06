package com.systemwebstudio.logic.action

import com.systemwebstudio.logic.arrayItems
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

sealed interface ResolveResult {
    data class Resolved(val values: Map<String, JsonNode>) : ResolveResult
    data class Rejected(val failure: ActionResult.Failed) : ResolveResult
}

/**
 * Builds the raw input map of a run from the definition's declarative `inputMapping`, the event payload, the previous action's result and
 * the **server-side** context. Nothing here evaluates code: a source is a typed lookup ([InputSource]) with a dotted path.
 *
 * Trust rules:
 *  - [InputSource.Context] values come from [ActionContext] only. A caller that tries to supply an input the definition derives from the
 *    context (e.g. `userId`) is rejected, not silently overridden — a client must never be able to act "as" someone else.
 *  - Event payload data can only reach *declared* inputs through an explicit mapping; the result still goes through [ActionInputBinder]
 *    (types, sizes, undeclared names) afterwards.
 */
class InputResolver(private val json: JsonMapper) {

    fun resolve(def: ActionDefinition, request: ActionRequest, ctx: ActionContext, now: Instant): ResolveResult {
        val serverDerived = def.inputMapping.filterValues { it is InputSource.Context }.keys
        val clashes = request.inputs.keys.filter { it in serverDerived }
        if (clashes.isNotEmpty()) {
            return ResolveResult.Rejected(
                failed(
                    ActionErrorCodes.INVALID_INPUT, "Input is derived by the server and cannot be supplied",
                    details = clashes.associateWith { "is derived by the server from the caller's context" }
                )
            )
        }
        val payload = request.payload ?: EventPayload.EMPTY
        val out = linkedMapOf<String, JsonNode>()
        for ((name, source) in def.inputMapping) {
            val value = when (source) {
                is InputSource.ComponentState -> lookup(payload.componentState, source.path)
                is InputSource.RouteParam -> payload.routeParams[source.name]
                is InputSource.FormField -> payload.form[source.name]
                is InputSource.ViewModelField -> lookup(payload.viewModel, source.path)
                is InputSource.PreviousResult -> request.previousResult?.let { descend(it, splitPath(source.path)) }
                is InputSource.Literal -> source.value
                is InputSource.Context -> contextValue(source.key, def, ctx, request, now)
            }
            if (value != null && !value.isNull) out[name] = value
        }
        // Explicit inputs (API callers, workflow steps) win over event-mapped values, never over server-derived ones (checked above).
        request.inputs.forEach { (k, v) -> out[k] = v }
        return ResolveResult.Resolved(out)
    }

    private fun contextValue(key: ContextKey, def: ActionDefinition, ctx: ActionContext, request: ActionRequest, now: Instant): JsonNode? = when (key) {
        ContextKey.USER_ID -> scalar(ctx.actor.userId.toString())
        ContextKey.TENANT_ID -> scalar(ctx.tenantId.toString())
        ContextKey.WORKSPACE_ID -> ctx.workspaceId?.let { scalar(it.toString()) }
        ContextKey.APP_ID -> (ctx.projectId ?: def.appId)?.let { scalar(it.toString()) }
        ContextKey.REQUEST_ID -> ctx.requestId?.let { scalar(it) }
        ContextKey.NOW -> scalar(now.toString())
    }

    private fun scalar(s: String): JsonNode = json.createObjectNode().put("v", s).get("v")

    private fun lookup(map: Map<String, JsonNode>, path: String): JsonNode? {
        val parts = splitPath(path)
        if (parts.isEmpty()) return null
        val root = map[parts.first()] ?: return null
        return descend(root, parts.drop(1))
    }

    private fun descend(start: JsonNode, parts: List<String>): JsonNode? {
        var node: JsonNode = start
        for (p in parts) {
            node = when {
                node.isObject -> node.get(p) ?: return null
                node.isArray -> p.toIntOrNull()?.let { i -> if (i in 0 until node.size()) node.get(i) else null } ?: return null
                else -> return null
            }
        }
        return node
    }

    companion object {
        const val MAX_PATH_SEGMENTS = 8
        private val SEGMENT = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun splitPath(path: String): List<String> = if (path.isEmpty()) emptyList() else path.split('.')

        /** Path syntax used by the validator: dotted plain segments, at most [MAX_PATH_SEGMENTS]; no expressions, wildcards or brackets. */
        fun isValidPath(path: String, allowEmpty: Boolean = false): Boolean {
            if (path.isEmpty()) return allowEmpty
            val parts = path.split('.')
            return parts.size <= MAX_PATH_SEGMENTS && parts.all { SEGMENT.matches(it) }
        }
    }
}

/** Parsing of declarative principal specs (`{"kind":"USER","userId":"…"}` …). Shared by NOTIFY recipients and approval approvers. */
object PrincipalSpecs {
    /** @return the spec, or a human-readable problem. */
    fun parse(node: JsonNode): Pair<PrincipalSpec?, String?> {
        if (!node.isObject) return null to "must be an object"
        val kind = node.get("kind")?.takeIf { it.isString }?.asString() ?: return null to "kind is required"
        fun str(name: String): String? = node.get(name)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
        return when (kind) {
            "USER" -> {
                val id = str("userId")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null to "userId must be a UUID"
                val tenant = str("tenantId")?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: return null to "tenantId must be a UUID" }
                PrincipalSpec.User(id, tenant) to null
            }
            "GROUP" -> (str("groupId")?.takeIf { ActionDefinitionValidator.REF_ID.matches(it) }?.let { PrincipalSpec.Group(it) }) to
                (if (str("groupId") == null || !ActionDefinitionValidator.REF_ID.matches(str("groupId")!!)) "groupId must be a plain id" else null)
            "ROLE" -> (str("role")?.takeIf { ActionDefinitionValidator.REF_ID.matches(it) }?.let { PrincipalSpec.Role(it) }) to
                (if (str("role") == null || !ActionDefinitionValidator.REF_ID.matches(str("role")!!)) "role must be a plain id" else null)
            "DEPARTMENT_MANAGER" -> {
                val dep = str("departmentId")
                if (dep != null && !ActionDefinitionValidator.REF_ID.matches(dep)) null to "departmentId must be a plain id"
                else PrincipalSpec.DepartmentManager(dep) to null
            }
            else -> null to "unknown kind '$kind' (USER, GROUP, DEPARTMENT_MANAGER, ROLE)"
        }
    }

    fun parseAll(node: JsonNode?, path: String, issues: MutableList<DefinitionIssue>): List<PrincipalSpec> {
        if (node == null) return emptyList()
        if (!node.isArray) { issues += DefinitionIssue(path, "must be an array"); return emptyList() }
        val out = mutableListOf<PrincipalSpec>()
        node.arrayItems().forEachIndexed { i, n ->
            val (spec, problem) = parse(n)
            if (spec != null) out += spec else issues += DefinitionIssue("$path[$i]", problem ?: "invalid")
        }
        return out
    }
}
