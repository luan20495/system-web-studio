package com.systemwebstudio.logic.action.canonical

import com.systemwebstudio.logic.action.ActionBindingPort
import com.systemwebstudio.logic.action.ActionDefinition
import com.systemwebstudio.logic.action.ActionDefinitionProvider
import com.systemwebstudio.logic.action.ActionDefinitionValidator
import com.systemwebstudio.logic.action.ActionLimits
import com.systemwebstudio.logic.action.ActionRef
import com.systemwebstudio.logic.action.ActionType
import com.systemwebstudio.logic.action.ContextKey
import com.systemwebstudio.logic.action.DefinitionIssue
import com.systemwebstudio.logic.action.EventType
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.IdempotencyPolicy
import com.systemwebstudio.logic.action.InputSource
import com.systemwebstudio.logic.action.InputSpec
import com.systemwebstudio.logic.action.InputType
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.UUID

/**
 * Anti-corruption layer between the canonical **AppDefinition JSON** (C2, `docs/contracts/app-definition-v2.md`) and C4's domain model.
 * `logic.*` imports no C2 class: the contract is the JSON shape. The adapter that implements this port (C2/C0 wiring) returns the stored
 * document; it must be tenant-scoped and return `null` for another tenant's app, exactly like a missing one.
 */
interface AppDefinitionSource {
    /** [ExecutionMode.LIVE] → the published version, [ExecutionMode.TEST] → the working draft. */
    fun load(tenantId: UUID, appId: UUID, mode: ExecutionMode): JsonNode?
}

/** Parsed view of one AppDefinition: usable actions, the reasons the others are not, and the event bindings. */
class ParsedActions(
    val actions: Map<String, ActionDefinition>,
    val issues: Map<String, List<DefinitionIssue>>,
    val refs: List<ActionRef>
)

/**
 * Reads `actions[]` of an AppDefinition into [ActionDefinition]s and `ActionDef.trigger` into [ActionRef]s.
 *
 * Reconciliation with C2 (see DECISIONS D-C4-01 / BLOCKERS B-C4-02):
 *  - C2's provisional `ActionType` (RUN_QUERY, WRITE_DATA, CALL_CONNECTOR_OPERATION, START_WORKFLOW, NOTIFY, SET_VALUE) is mapped to the
 *    finalized C4 set: RUN_QUERY→REFRESH_QUERY, WRITE_DATA→SUBMIT_FORM, CALL_CONNECTOR_OPERATION→CALL_API. SET_VALUE is client-side state
 *    and has no server action (it is skipped, not executed).
 *  - The finalized names (NAVIGATE, SUBMIT_FORM, CREATE_RECORD, UPDATE_RECORD, DELETE_RECORD, CALL_API, REFRESH_QUERY) and the additive fields
 *    `pageRef, channel, templateRef, endpointRef, recipients, inputMapping, idempotency, onSuccess, onError, limits` are what C4 asks C2 to add;
 *    this reader accepts them when present. C2's validator remains the gatekeeper for unknown fields.
 *  - Every reference must resolve inside the same document (queryRef → queries[] of the right mode, dataSourceRef → dataSources[],
 *    workflowRef → workflows[], permissionRef → permissions[]); an action with a dangling reference is **not offered** (fail closed).
 */
object CanonicalActionReader {

    fun parse(tenantId: UUID, appId: UUID, doc: JsonNode): ParsedActions {
        val queryModes = doc.array("queries").associate { it.text("id") to it.text("mode") }
        val dataSourceIds = doc.array("dataSources").mapNotNull { it.text("id") }.toSet()
        val workflowIds = doc.array("workflows").mapNotNull { it.text("id") }.toSet()
        val permissions = doc.array("permissions").associateBy { it.text("id") }

        val actions = linkedMapOf<String, ActionDefinition>()
        val issues = linkedMapOf<String, List<DefinitionIssue>>()
        val refs = mutableListOf<ActionRef>()

        for (node in doc.array("actions")) {
            val id = node.text("id") ?: continue
            val problems = mutableListOf<DefinitionIssue>()
            val type = mapType(node.text("type"))
            if (type == null) {
                problems += DefinitionIssue("type", "'${node.text("type")}' has no server-side action (client-only or unknown)")
                issues[id] = problems
                continue
            }
            val config = linkedMapOf<String, JsonNode>()
            fun copy(from: String, to: String = from) { node.get(from)?.takeIf { !it.isNull }?.let { config[to] = it } }

            when (type) {
                ActionType.NAVIGATE -> { copy("pageRef", "pageId"); if (!config.containsKey("pageId")) problems += DefinitionIssue("pageRef", "is required for NAVIGATE") }
                ActionType.REFRESH_QUERY -> {
                    copy("queryRef")
                    val q = node.text("queryRef")
                    if (q == null || queryModes[q] == null) problems += DefinitionIssue("queryRef", "must reference a declared query")
                    else if (queryModes[q] != "READ") problems += DefinitionIssue("queryRef", "REFRESH_QUERY needs a READ query")
                }
                ActionType.SUBMIT_FORM, ActionType.CREATE_RECORD, ActionType.UPDATE_RECORD, ActionType.DELETE_RECORD -> {
                    copy("queryRef")
                    val q = node.text("queryRef")
                    if (q == null || queryModes[q] == null) problems += DefinitionIssue("queryRef", "must reference a declared query")
                    else if (queryModes[q] != "WRITE") problems += DefinitionIssue("queryRef", "$type needs a WRITE query")
                }
                ActionType.CALL_API -> {
                    copy("dataSourceRef"); copy("operationKey")
                    if (node.text("dataSourceRef") !in dataSourceIds) problems += DefinitionIssue("dataSourceRef", "must reference a declared data source")
                    if (node.text("operationKey").isNullOrBlank()) problems += DefinitionIssue("operationKey", "an approved operation is required (no raw URL)")
                }
                ActionType.NOTIFY -> { copy("channel"); copy("templateRef"); copy("endpointRef"); copy("recipients") }
                ActionType.START_WORKFLOW -> {
                    copy("workflowRef")
                    if (node.text("workflowRef") !in workflowIds) problems += DefinitionIssue("workflowRef", "must reference a declared workflow")
                }
            }

            var requiredPermission: String? = null
            node.text("permissionRef")?.let { ref ->
                val p = permissions[ref]
                if (p == null || p.text("resourceType") != "ACTION" || p.text("resourceRef") != id) problems += DefinitionIssue("permissionRef", "must reference an ACTION permission of this action")
                else requiredPermission = p.text("permission")
            }

            val inputs = node.array("inputs").mapNotNull { n ->
                val name = n.text("name") ?: return@mapNotNull null
                InputSpec(name, inputType(n.text("type")), n.get("required")?.asBoolean(false) ?: false)
            }
            val mapping = linkedMapOf<String, InputSource>()
            node.get("inputMapping")?.takeIf { it.isObject }?.let { m ->
                for (name in m.propertyNames()) {
                    val src = source(m.get(name))
                    if (src == null) problems += DefinitionIssue("inputMapping.$name", "unknown or invalid source") else mapping[name] = src
                }
            }
            val idem = node.text("idempotency")?.let { s -> IdempotencyPolicy.entries.firstOrNull { it.name == s } }
            if (node.text("idempotency") != null && idem == null) problems += DefinitionIssue("idempotency", "must be NONE, OPTIONAL or REQUIRED")

            val limits = node.get("limits")?.takeIf { it.isObject }?.get("timeoutMillis")?.takeIf { it.isNumber }?.let { t ->
                val ms = t.asLong(0)
                if (ms > 0) ActionLimits(timeout = Duration.ofMillis(ms)) else { problems += DefinitionIssue("limits.timeoutMillis", "must be positive"); null }
            } ?: ActionLimits()

            val def = ActionDefinition(
                id = id, tenantId = tenantId, type = type, name = node.text("name") ?: id, inputs = inputs, config = config,
                idempotency = idem ?: if (type.mutatesState) IdempotencyPolicy.REQUIRED else IdempotencyPolicy.NONE,
                limits = limits, enabled = node.get("enabled")?.asBoolean(true) ?: true, appId = appId, inputMapping = mapping,
                requiredPermission = requiredPermission,
                onSuccess = node.array("onSuccess").mapNotNull { it.takeIf { n -> n.isString }?.asString() },
                onError = node.array("onError").mapNotNull { it.takeIf { n -> n.isString }?.asString() }
            )
            problems += ActionDefinitionValidator.validate(def)
            if (problems.isNotEmpty()) { issues[id] = problems; continue }
            actions[id] = def

            node.get("trigger")?.takeIf { it.isObject }?.let { t ->
                val section = t.text("sectionId")
                val event = EventType.fromWire(t.text("event"))
                if (section != null && event != null) refs += ActionRef("$section.${event.wire}.$id", section, event, id)
                else issues[id] = (issues[id] ?: emptyList()) + DefinitionIssue("trigger", "needs sectionId and a known event (${EventType.entries.joinToString { it.wire }})")
            }
        }
        // Chained ids must exist, otherwise the chain would silently do nothing. An action whose chain dangles is not offered (fail closed);
        // removing it can orphan another chain, so repeat until nothing changes.
        do {
            val dangling = actions.values.filter { d -> (d.onSuccess + d.onError).any { it !in actions } }
            dangling.forEach { d ->
                (d.onSuccess + d.onError).filter { it !in actions }.distinct().forEach { missing ->
                    issues[d.id] = (issues[d.id] ?: emptyList()) + DefinitionIssue("onSuccess/onError", "chained action '$missing' does not exist or is not usable")
                }
                actions.remove(d.id)
                refs.removeAll { it.actionId == d.id }
            }
        } while (dangling.isNotEmpty())
        return ParsedActions(actions, issues, refs)
    }

    internal fun mapType(wire: String?): ActionType? = when (wire) {
        null -> null
        "RUN_QUERY" -> ActionType.REFRESH_QUERY
        "WRITE_DATA" -> ActionType.SUBMIT_FORM
        "CALL_CONNECTOR_OPERATION" -> ActionType.CALL_API
        else -> ActionType.entries.firstOrNull { it.name == wire }
    }

    private fun inputType(wire: String?) = when (wire) {
        "STRING", "DATE" -> InputType.STRING
        "NUMBER" -> InputType.NUMBER
        "BOOLEAN" -> InputType.BOOLEAN
        else -> InputType.ANY
    }

    /** `{ "source": "FORM_FIELD", "name": "email" }` — typed lookups only, there is no expression language. */
    private fun source(n: JsonNode?): InputSource? {
        if (n == null || !n.isObject) return null
        return when (n.text("source")) {
            "COMPONENT_STATE" -> n.text("path")?.let { InputSource.ComponentState(it) }
            "ROUTE_PARAM" -> n.text("name")?.let { InputSource.RouteParam(it) }
            "FORM_FIELD" -> n.text("name")?.let { InputSource.FormField(it) }
            "VIEW_MODEL" -> n.text("path")?.let { InputSource.ViewModelField(it) }
            "PREVIOUS_RESULT" -> InputSource.PreviousResult(n.text("path") ?: "")
            "LITERAL" -> n.get("value")?.let { InputSource.Literal(it) }
            "CONTEXT" -> n.text("key")?.let { k -> ContextKey.entries.firstOrNull { it.name == k }?.let { InputSource.Context(it) } }
            else -> null
        }
    }
}

internal fun JsonNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.asString()
internal fun JsonNode.array(name: String): List<JsonNode> = get(name)?.takeIf { it.isArray }?.toList().orEmpty()

/** Serves the runtime's two lookups from the AppDefinition of the app. The document is loaded per call; cache in the adapter if needed. */
class CanonicalActionCatalog(private val source: AppDefinitionSource) : ActionDefinitionProvider, ActionBindingPort {
    private fun parsed(tenantId: UUID, appId: UUID, mode: ExecutionMode): ParsedActions? =
        source.load(tenantId, appId, mode)?.let { CanonicalActionReader.parse(tenantId, appId, it) }

    override fun find(tenantId: UUID, appId: UUID, actionId: String, mode: ExecutionMode): ActionDefinition? =
        parsed(tenantId, appId, mode)?.actions?.get(actionId)

    override fun refsFor(tenantId: UUID, appId: UUID, sectionId: String, event: EventType, mode: ExecutionMode): List<ActionRef> =
        parsed(tenantId, appId, mode)?.refs.orEmpty().filter { it.sectionId == sectionId && it.event == event }

    /** Why an action is not offered. For the author's diagnostics (never shown to end users). */
    fun diagnose(tenantId: UUID, appId: UUID, mode: ExecutionMode): Map<String, List<DefinitionIssue>> =
        parsed(tenantId, appId, mode)?.issues.orEmpty()
}
