package com.systemwebstudio.app.definition

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.component.ComponentOverlays
import com.systemwebstudio.component.ComponentRegistry
import com.systemwebstudio.schema.PageSchemaValidator
import com.systemwebstudio.schema.Violation
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode

/**
 * Options for one validation run. Empty on purpose: tenant / permission context (contract: ValidationContext) is added by C1 / C3
 * when those exist; this foundation validates the document on its own.
 *
 * @param checkPageSchema also run PageSchemaValidator over the page / section / site part (default). SchemaCommitService turns it off
 *        because it has already run that validator itself.
 */
data class ValidationContext(val checkPageSchema: Boolean = true)

data class ValidationResult(val violations: List<Violation>) {
    val valid: Boolean get() = violations.isEmpty()
}

/**
 * Validates an AppDefinitionV2 (docs/contracts/app-definition-v2.md).
 *
 * Page / section / site rules are NOT repeated here: they are delegated to [PageSchemaValidator]. This validator adds the rules of the
 * optional V2 keys: structure, patterns, limits, unique ids and references between definitions (dangling ids, reference cycles).
 * A plain legacy Page Schema has no V2 key and therefore passes through here unchanged.
 * Whether a referenced data source really exists in the data platform / tenant is a later phase (C3, C1); here only the definition itself is checked.
 */
interface AppDefinitionValidator {
    /** never throws; every problem with a path */
    fun validate(def: AppDefinitionV2, ctx: ValidationContext = ValidationContext()): ValidationResult
    /** throws ApiException 422 SCHEMA_INVALID like PageSchemaValidator.requireValid */
    fun requireValid(def: AppDefinitionV2, ctx: ValidationContext = ValidationContext())

    /** the stored JSON document (page part + V2 part) */
    fun validateDocument(schema: JsonNode, ctx: ValidationContext = ValidationContext()): ValidationResult
    fun requireValidDocument(schema: JsonNode, ctx: ValidationContext = ValidationContext())

    /** only the V2 part; a document without any V2 key is valid by definition (used by the commit path, after PageSchemaValidator) */
    fun validateExtensions(schema: JsonNode): ValidationResult
    fun requireValidExtensions(schema: JsonNode)
}

@Service
class DefaultAppDefinitionValidator(
    private val pageValidator: PageSchemaValidator,
    private val registry: ComponentRegistry,
    private val codec: AppDefinitionCodec
) : AppDefinitionValidator {

    override fun validate(def: AppDefinitionV2, ctx: ValidationContext): ValidationResult = validateDocument(codec.toJson(def), ctx)

    override fun requireValid(def: AppDefinitionV2, ctx: ValidationContext) = fail(validate(def, ctx))

    override fun validateDocument(schema: JsonNode, ctx: ValidationContext): ValidationResult {
        val problems = ArrayList<Violation>()
        if (ctx.checkPageSchema) problems += pageValidator.validate(schema)
        problems += validateExtensions(schema).violations
        return ValidationResult(problems)
    }

    override fun requireValidDocument(schema: JsonNode, ctx: ValidationContext) = fail(validateDocument(schema, ctx))

    override fun validateExtensions(schema: JsonNode): ValidationResult {
        if (!AppDefinitionKeys.declaresV2(schema)) return ValidationResult(emptyList())
        val problems = ArrayList<Violation>()
        val def = AppDefinitionReader(problems).read(schema)
        if (problems.isEmpty()) checkReferences(def, problems)
        return ValidationResult(problems)
    }

    override fun requireValidExtensions(schema: JsonNode) {
        if (!AppDefinitionKeys.declaresV2(schema)) return
        fail(validateExtensions(schema))
    }

    private fun fail(result: ValidationResult) {
        if (result.valid) return
        throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SCHEMA_INVALID", "App definition is invalid",
            mapOf("violations" to result.violations.take(20).map { mapOf("path" to it.path, "message" to it.message) }))
    }

    // ---- references between definitions ----

    private fun checkReferences(d: AppDefinitionV2, out: MutableList<Violation>) {
        val dataSources = uniqueIds("dataSources", d.dataSources.map { it.id }, out)
        val queries = uniqueIds("queries", d.queries.map { it.id }, out)
        val mappings = uniqueIds("mappings", d.mappings.map { it.id }, out)
        val viewModels = uniqueIds("viewModels", d.viewModels.map { it.id }, out)
        val actions = uniqueIds("actions", d.actions.map { it.id }, out)
        val workflows = uniqueIds("workflows", d.workflows.map { it.id }, out)
        uniqueIds("permissions", d.permissions.map { it.id }, out)
        uniqueIds("dataBindings", d.dataBindings.map { it.id }, out)
        val sections = d.sections().associateBy { it.id }
        val pageIds = d.pages().map { if (it.home) "home" else it.id }.toSet()
        val queryById = d.queries.associateBy { it.id }
        val mappingById = d.mappings.associateBy { it.id }

        d.queries.forEachIndexed { i, q ->
            known(q.dataSourceRef, dataSources, "queries[$i].dataSourceRef", "data source", out)
            uniqueNames("queries[$i].params", q.params.map { it.name }, out)
            if (q.public && q.mode != QueryMode.READ) out += Violation("queries[$i].public", "only a READ query can be offered to anonymous visitors")
        }

        d.mappings.forEachIndexed { i, m ->
            known(m.queryRef, queries, "mappings[$i].queryRef", "query", out)
            uniqueNames("mappings[$i].fields", m.fields.map { it.to }, out, "to")
        }

        d.viewModels.forEachIndexed { i, vm ->
            val at = "viewModels[$i]"
            uniqueNames("$at.fields", vm.fields.map { it.name }, out, "name")
            val queryKnown = vm.queryRef != null && known(vm.queryRef, queries, "$at.queryRef", "query", out)
            if (queryKnown && queryById.getValue(vm.queryRef!!).mode != QueryMode.READ) out += Violation("$at.queryRef", "a view model reads data; '${vm.queryRef}' is a WRITE query")
            val mappingKnown = vm.mappingRef != null && known(vm.mappingRef, mappings, "$at.mappingRef", "mapping", out)
            if (queryKnown && mappingKnown) {
                val m = mappingById.getValue(vm.mappingRef!!)
                if (m.queryRef != vm.queryRef) out += Violation("$at.mappingRef", "mapping '${m.id}' reads query '${m.queryRef}', not '${vm.queryRef}'")
            }
            if (mappingKnown) {
                val fieldNames = vm.fields.map { it.name }.toSet()
                mappingById.getValue(vm.mappingRef!!).fields.forEach { f ->
                    if (f.to !in fieldNames) out += Violation("$at.mappingRef", "mapping '${vm.mappingRef}' maps to '${f.to}', which is not a field of this view model")
                }
            }
        }

        d.actions.forEachIndexed { i, a ->
            val at = "actions[$i]"
            uniqueNames("$at.inputs", a.inputs.map { it.name }, out)
            val queryKnown = a.queryRef != null && known(a.queryRef, queries, "$at.queryRef", "query", out)
            if (a.viewModelRef != null) known(a.viewModelRef, viewModels, "$at.viewModelRef", "view model", out)
            if (a.workflowRef != null) known(a.workflowRef, workflows, "$at.workflowRef", "workflow", out)
            if (a.dataSourceRef != null) known(a.dataSourceRef, dataSources, "$at.dataSourceRef", "data source", out)
            if (a.permissionRef != null) {
                val permissions = d.permissions.map { it.id }.toSet()
                known(a.permissionRef, permissions, "$at.permissionRef", "permission", out)
            }
            if (a.trigger != null) {
                val section = sections[a.trigger.sectionId]
                if (section == null) out += Violation("$at.trigger.sectionId", "references unknown section '${a.trigger.sectionId}'")
                else checkTrigger(section.type, a.trigger.event, a.type, "$at.trigger", out)
            }
            when (a.type) {
                ActionType.REFRESH_QUERY -> {
                    if (a.queryRef == null) out += Violation("$at.queryRef", "is required for a REFRESH_QUERY action (a declared READ query)")
                    else if (queryKnown && queryById.getValue(a.queryRef).mode != QueryMode.READ)
                        out += Violation("$at.queryRef", "REFRESH_QUERY needs a READ query; '${a.queryRef}' is a WRITE query")
                }
                ActionType.SUBMIT_FORM, ActionType.CREATE_RECORD, ActionType.UPDATE_RECORD, ActionType.DELETE_RECORD -> {
                    if (a.queryRef == null) out += Violation("$at.queryRef", "is required for a ${a.type} action (a declared WRITE query)")
                    else if (queryKnown && queryById.getValue(a.queryRef).mode != QueryMode.WRITE)
                        out += Violation("$at.queryRef", "${a.type} needs a WRITE query; '${a.queryRef}' is a READ query (reads are bound with dataBindings or re-run with REFRESH_QUERY, not run as record actions)")
                }
                ActionType.CALL_API -> {
                    if (a.dataSourceRef == null) out += Violation("$at.dataSourceRef", "is required for a CALL_API action")
                    if (a.operationKey == null) out += Violation("$at.operationKey", "is required for a CALL_API action")
                }
                ActionType.START_WORKFLOW -> if (a.workflowRef == null) out += Violation("$at.workflowRef", "is required for a START_WORKFLOW action")
                ActionType.NAVIGATE -> {
                    if (a.pageRef == null) out += Violation("$at.pageRef", "is required for a NAVIGATE action")
                    else if (a.pageRef !in pageIds) out += Violation("$at.pageRef", "references unknown page '${a.pageRef}'")
                }
                ActionType.NOTIFY -> {
                    if (a.channel == null) out += Violation("$at.channel", "is required for a NOTIFY action (${NotifyChannelName.entries.joinToString { it.name }})")
                    if (a.templateRef == null) out += Violation("$at.templateRef", "is required for a NOTIFY action (an approved template)")
                    if (a.channel == NotifyChannelName.WEBHOOK && a.endpointRef == null) out += Violation("$at.endpointRef", "is required for a WEBHOOK notification (a registered endpoint, never a URL)")
                    if (a.channel != null && a.channel != NotifyChannelName.WEBHOOK && a.endpointRef != null) out += Violation("$at.endpointRef", "is only valid for the WEBHOOK channel")
                }
            }
            if (a.type != ActionType.NOTIFY) {
                if (a.channel != null) out += Violation("$at.channel", "is only valid for a NOTIFY action")
                if (a.templateRef != null) out += Violation("$at.templateRef", "is only valid for a NOTIFY action")
                if (a.endpointRef != null) out += Violation("$at.endpointRef", "is only valid for a NOTIFY action")
                if (a.recipients.isNotEmpty()) out += Violation("$at.recipients", "is only valid for a NOTIFY action")
            }
            // state-changing actions are never fire-and-forget (C4): a retry must not create a second effect
            val mutating = a.type != ActionType.NAVIGATE && a.type != ActionType.REFRESH_QUERY
            if (mutating && a.idempotency == IdempotencyPolicyName.NONE) out += Violation("$at.idempotency", "${a.type} changes state, so idempotency cannot be NONE")
            if (a.type == ActionType.START_WORKFLOW && a.idempotency != null && a.idempotency != IdempotencyPolicyName.REQUIRED)
                out += Violation("$at.idempotency", "START_WORKFLOW requires idempotency REQUIRED")
            val declared = a.inputs.map { it.name }.toSet()
            a.inputMapping.keys.filter { it !in declared }.forEach { out += Violation("$at.inputMapping.$it", "maps to an input that is not declared") }
            for ((field, chain) in listOf("onSuccess" to a.onSuccess, "onError" to a.onError)) chain.forEachIndexed { j, ref ->
                if (ref == a.id) out += Violation("$at.$field[$j]", "an action cannot chain to itself")
                else known(ref, actions, "$at.$field[$j]", "action", out)
            }
        }

        d.workflows.forEachIndexed { i, w ->
            val at = "workflows[$i]"
            val steps = HashSet<String>()
            w.steps.forEachIndexed { j, s -> if (!steps.add(s.id)) out += Violation("$at.steps[$j].id", "duplicate step id '${s.id}'") }
            w.startStepId?.let { known(it, steps, "$at.startStepId", "step", out) }
            val flow = HashMap<String, List<String>>()
            w.steps.forEachIndexed { j, s ->
                val sp = "$at.steps[$j]"
                val kind = s.kind ?: if (s.actionRef != null) StepKind.ACTION else StepKind.END
                if (kind == StepKind.ACTION) s.actionRef?.let { known(it, actions, "$sp.actionRef", "action", out) }
                else if (s.actionRef != null) out += Violation("$sp.actionRef", "is only valid for an ACTION step")
                s.compensationActionRef?.let { known(it, actions, "$sp.compensationActionRef", "action", out) }
                s.next?.let { known(it, steps, "$sp.next", "step", out) }
                s.onError?.let { known(it, steps, "$sp.onError", "step", out) }
                s.defaultNext?.let { known(it, steps, "$sp.defaultNext", "step", out) }
                s.branches.forEachIndexed { k, b -> known(b.next, steps, "$sp.branches[$k].next", "step", out) }
                s.approval?.let { ap ->
                    ap.onReject?.let { known(it, steps, "$sp.approval.onReject", "step", out) }
                    ap.onExpire?.let { known(it, steps, "$sp.approval.onExpire", "step", out) }
                }
                s.inputs.forEach { (name, ref) -> ref.stepId?.let { known(it, steps, "$sp.inputs.$name.stepId", "step", out) } }
                flow[s.id] = listOfNotNull(s.next, s.onError, s.defaultNext) + s.branches.map { it.next } + listOfNotNull(s.approval?.onReject, s.approval?.onExpire)
            }
            findCycles(flow).forEach { out += Violation(at + ".steps", "step cycle: " + it.joinToString(" -> ")) }
        }

        d.permissions.forEachIndexed { i, p ->
            val at = "permissions[$i].resourceRef"
            when (p.resourceType) {
                PermissionResourceType.QUERY -> known(p.resourceRef, queries, at, "query", out)
                PermissionResourceType.ACTION -> known(p.resourceRef, actions, at, "action", out)
                PermissionResourceType.WORKFLOW -> known(p.resourceRef, workflows, at, "workflow", out)
                PermissionResourceType.VIEW_MODEL -> known(p.resourceRef, viewModels, at, "view model", out)
                PermissionResourceType.DATA_SOURCE -> known(p.resourceRef, dataSources, at, "data source", out)
            }
        }

        val componentProps by lazy { registry.versions() }
        val bound = HashSet<String>()
        d.dataBindings.forEachIndexed { i, b ->
            val at = "dataBindings[$i]"
            if (!bound.add(b.sectionId + "#" + b.prop)) out += Violation(at, "section '${b.sectionId}' prop '${b.prop}' is already bound")
            val section = sections[b.sectionId]
            if (section == null) out += Violation("$at.sectionId", "references unknown section '${b.sectionId}'")
            else if (section.type != null && section.componentVersion != null) {
                val entry = componentProps["${section.type}@${section.componentVersion}"]
                if (entry != null && entry.dto.propsSchema.get("properties")?.has(b.prop) != true)
                    out += Violation("$at.prop", "component '${section.type}' of section '${b.sectionId}' has no prop '${b.prop}'")
            }
            if (b.viewModelRef != null) known(b.viewModelRef, viewModels, "$at.viewModelRef", "view model", out)
            val queryKnown = b.queryRef != null && known(b.queryRef, queries, "$at.queryRef", "query", out)
            if (queryKnown && queryById.getValue(b.queryRef!!).mode != QueryMode.READ) out += Violation("$at.queryRef", "a data binding reads data; '${b.queryRef}' is a WRITE query")
            if (b.mappingRef != null && known(b.mappingRef, mappings, "$at.mappingRef", "mapping", out) && queryKnown && mappingById.getValue(b.mappingRef).queryRef != b.queryRef)
                out += Violation("$at.mappingRef", "mapping '${b.mappingRef}' reads query '${mappingById.getValue(b.mappingRef).queryRef}', not '${b.queryRef}'")
        }

        // action -> workflow -> action: a workflow that (indirectly) starts the action that starts it
        val graph = LinkedHashMap<String, List<String>>()
        val owner = HashMap<String, String>()
        d.actions.forEachIndexed { i, a -> graph["action '${a.id}'"] = listOfNotNull(a.workflowRef?.let { "workflow '$it'" }); owner["action '${a.id}'"] = "actions[$i]" }
        d.workflows.forEachIndexed { i, w -> graph["workflow '${w.id}'"] = w.steps.mapNotNull { st -> st.actionRef?.let { "action '$it'" } }; owner["workflow '${w.id}'"] = "workflows[$i]" }
        findCycles(graph).forEach { out += Violation(owner[it.first()] ?: "actions", "reference cycle: " + it.joinToString(" -> ")) }
    }

    /**
     * Component Registry V2 (D-C2-12): a component that DECLARES its events accepts only those events, and only the action types each
     * supports. A component without a declaration (not in [ComponentOverlays]) is not checked, so registry components added later keep working.
     */
    private fun checkTrigger(componentType: String?, event: String, action: ActionType, at: String, out: MutableList<Violation>) {
        val overlay = ComponentOverlays.of(componentType) ?: return
        val declared = overlay.events.firstOrNull { it.name == event }
        when {
            declared == null -> out += Violation("$at.event", "component '$componentType' does not emit '$event'" +
                (if (overlay.events.isEmpty()) " (it emits no events)" else " (it emits: " + overlay.events.joinToString { it.name } + ")"))
            action.name !in declared.supportedActions -> out += Violation("$at.event", "$action is not supported on '$componentType.$event' (supported: " + declared.supportedActions.joinToString() + ")")
        }
    }

    /** reports duplicate ids of one collection; returns the set of ids */
    private fun uniqueIds(collection: String, ids: List<String>, out: MutableList<Violation>): Set<String> {
        val seen = HashSet<String>()
        ids.forEachIndexed { i, id ->
            if (!seen.add(id)) out += Violation("$collection[$i].id", "duplicate id '$id'")
            // ids are LOCAL names; a runtime id (a UUID) must never be stored as one: AppDataBindingResolver derives runtime ids per call
            if (AppDefinitionReader.UUID_TEXT.matches(id.lowercase())) out += Violation("$collection[$i].id", "'$id' looks like a runtime id (UUID); use a local name, the runtime id is resolved at run time")
        }
        return seen
    }

    private fun uniqueNames(path: String, names: List<String>, out: MutableList<Violation>, field: String = "name") {
        val seen = HashSet<String>()
        names.forEachIndexed { i, n -> if (!seen.add(n)) out += Violation("$path[$i].$field", "duplicate $field '$n'") }
    }

    /** true when [id] is in [known]; otherwise reports a dangling reference at [at] */
    private fun known(id: String, known: Set<String>, at: String, what: String, out: MutableList<Violation>): Boolean {
        if (id in known) return true
        out += Violation(at, "references unknown $what '$id'")
        return false
    }

    /** every cycle found by a depth-first search, each as the list of nodes ending with the node it returns to; edges to unknown nodes are ignored */
    internal fun findCycles(graph: Map<String, List<String>>): List<List<String>> {
        val state = HashMap<String, Int>()      // 1 = on the current path, 2 = finished
        val path = ArrayList<String>()
        val cycles = ArrayList<List<String>>()
        fun visit(node: String) {
            state[node] = 1; path.add(node)
            for (next in graph[node].orEmpty()) {
                if (next !in graph) continue
                when (state[next]) {
                    null -> visit(next)
                    1 -> cycles.add(path.subList(path.indexOf(next), path.size).toList() + next)
                    else -> {}
                }
            }
            path.removeAt(path.size - 1); state[node] = 2
        }
        for (node in graph.keys) if (state[node] == null) visit(node)
        return cycles
    }
}
