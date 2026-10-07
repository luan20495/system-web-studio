package com.systemwebstudio.app.definition

import com.systemwebstudio.schema.Violation
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** The document does not follow the AppDefinitionV2 structure; [violations] say where. */
class AppDefinitionFormatException(val violations: List<Violation>) :
    RuntimeException("Invalid app definition: " + violations.take(5).joinToString("; ") { "${it.path}: ${it.message}" })

/**
 * Adapter between the stored JSON (the Page Schema document, which is also where V2 lives: same `page_schemas` row, same immutable
 * `project_versions` snapshot, no migration) and the typed [AppDefinitionV2].
 *
 *  - [fromJson] reads ANY existing Page Schema as an AppDefinitionV2 with empty extensions; legacy keys are carried verbatim.
 *  - [toJson] writes it back in canonical form: a legacy schema comes back equal to the input; for a V2 document empty optional
 *    collections and default values (mode READ, cardinality LIST, field type STRING, trigger MANUAL, publish mode STATIC, visibility PRIVATE, requiresAuth false,
 *    param required true, nullable true, errorPolicy NULL_FIELD, version 1, enabled true)
 *    are omitted, so `fromJson(toJson(x)) == x` always holds and `toJson(fromJson(doc)) == doc` holds for canonical documents.
 */
@Component
class AppDefinitionCodec(private val json: JsonMapper) {
    fun fromJson(schema: JsonNode): AppDefinitionV2 {
        val problems = ArrayList<Violation>()
        val def = AppDefinitionReader(problems).read(schema)
        if (problems.isNotEmpty()) throw AppDefinitionFormatException(problems)
        return def
    }

    fun toJson(def: AppDefinitionV2): ObjectNode {
        val root = json.createObjectNode()
        if (def.schemaVersion != null) root.put(AppDefinitionKeys.SCHEMA_VERSION, def.schemaVersion)
        if (def.kind != null) root.put(AppDefinitionKeys.KIND, def.kind.name)
        for ((k, v) in def.pageSchema) root.attach(k, copy(v))
        if (def.theme != null) root.attach(AppDefinitionKeys.THEME, theme(def.theme))
        root.attach("dataSources", array(def.dataSources, this::dataSource))
        root.attach("viewModels", array(def.viewModels, this::viewModel))
        root.attach("queries", array(def.queries, this::query))
        root.attach("mappings", array(def.mappings, this::mapping))
        root.attach("actions", array(def.actions, this::action))
        root.attach("workflows", array(def.workflows, this::workflow))
        root.attach("permissions", array(def.permissions, this::permission))
        root.attach("dataBindings", array(def.dataBindings, this::dataBinding))
        if (def.publishConfig != null) root.attach(AppDefinitionKeys.PUBLISH_CONFIG, publishConfig(def.publishConfig))
        if (def.extensions.isNotEmpty()) {
            val ext = json.createObjectNode()
            for ((k, v) in def.extensions) ext.attach(k, copy(v))
            root.attach(AppDefinitionKeys.EXTENSIONS, ext)
        }
        return root
    }

    private fun copy(n: JsonNode): JsonNode = n.deepCopy()

    private fun ObjectNode.attach(key: String, value: JsonNode?) { if (value != null) set(key, value) }

    private fun ObjectNode.str(key: String, value: String?): ObjectNode { if (value != null) put(key, value); return this }

    private fun <T> array(items: List<T>, write: (T) -> ObjectNode): ArrayNode? {
        if (items.isEmpty()) return null
        val arr = json.createArrayNode()
        for (item in items) arr.add(write(item))
        return arr
    }

    private fun params(items: List<ParamDef>): ArrayNode? = array(items) { p ->
        val o = json.createObjectNode().str("name", p.name).str("type", p.type.name)
        if (!p.required) o.put("required", false)           // an omitted `required` means true (C3's default), so only false is written
        o.attach("default", p.defaultValue?.let { copy(it) })
        o
    }

    private fun dataSource(d: DataSourceDef): ObjectNode =
        json.createObjectNode().str("id", d.id).str("name", d.name).str("type", d.type).str("sourceRef", d.sourceRef).str("description", d.description)

    private fun query(q: QueryDef): ObjectNode {
        val o = json.createObjectNode().str("id", q.id).str("name", q.name).str("dataSourceRef", q.dataSourceRef)
        if (q.mode != QueryMode.READ) o.put("mode", q.mode.name)
        o.str("operationKey", q.operationKey)
        o.attach("params", params(q.params))
        if (q.maxRows != null) o.put("maxRows", q.maxRows)
        if (q.public) o.put("public", true)
        return o
    }

    private fun mapping(m: MappingDef): ObjectNode {
        val o = json.createObjectNode().str("id", m.id).str("name", m.name).str("description", m.description).str("queryRef", m.queryRef)
        if (m.errorPolicy != MappingErrorPolicy.NULL_FIELD) o.put("errorPolicy", m.errorPolicy.name)
        // an integer that fits an Int is written as an IntNode, exactly what parsing the document gives, so JsonNode equality (type sensitive) holds
        if (m.version != 1L) { if (m.version in Int.MIN_VALUE..Int.MAX_VALUE) o.put("version", m.version.toInt()) else o.put("version", m.version) }
        o.attach("fields", array(m.fields) { f ->
            val fo = json.createObjectNode().str("from", f.from).str("to", f.to)
            if (f.transforms.isNotEmpty()) { val ts = json.createArrayNode(); f.transforms.forEach { ts.add(copy(it)) }; fo.attach("transforms", ts) }
            fo.attach("default", f.defaultValue?.let { copy(it) })
            if (!f.nullable) fo.put("nullable", false)
            fo.attach("validation", f.validation?.let { validation(it) })
            fo.str("description", f.description)
        })
        return o
    }

    private fun validation(v: FieldValidationDef): ObjectNode {
        val o = json.createObjectNode()
        v.minLength?.let { o.put("minLength", it) }
        v.maxLength?.let { o.put("maxLength", it) }
        o.attach("min", v.min?.let { copy(it) })
        o.attach("max", v.max?.let { copy(it) })
        if (v.oneOf != null) { val arr = json.createArrayNode(); v.oneOf.forEach { arr.add(copy(it)) }; o.attach("oneOf", arr) }
        v.format?.let { o.put("format", it.name) }
        return o
    }

    private fun viewModel(m: ViewModelDef): ObjectNode {
        val o = json.createObjectNode().str("id", m.id).str("name", m.name).str("description", m.description).str("queryRef", m.queryRef).str("mappingRef", m.mappingRef)
        if (m.cardinality != Cardinality.LIST) o.put("cardinality", m.cardinality.name)
        o.attach("fields", array(m.fields) { f ->
            val fo = json.createObjectNode().str("name", f.name)
            if (f.type != FieldType.STRING) fo.put("type", f.type.name)
            fo.str("label", f.label).str("description", f.description)
        })
        return o
    }

    private fun strings(items: List<String>): ArrayNode? {
        if (items.isEmpty()) return null
        val arr = json.createArrayNode()
        items.forEach { arr.add(it) }
        return arr
    }

    private fun principal(p: PrincipalDef): ObjectNode =
        json.createObjectNode().str("kind", p.kind.name).str("userId", p.userId).str("tenantId", p.tenantId).str("groupId", p.groupId).str("role", p.role).str("departmentId", p.departmentId)

    private fun valueRef(r: ValueRefDef): ObjectNode {
        val o = json.createObjectNode().str("from", r.from.name).str("stepId", r.stepId).str("path", r.path)
        o.attach("value", r.value?.let { copy(it) })
        return o
    }

    private fun action(a: ActionDef): ObjectNode {
        val o = json.createObjectNode().str("id", a.id).str("name", a.name).str("type", a.type.name)
        if (!a.enabled) o.put("enabled", false)
        if (a.trigger != null) o.attach("trigger", json.createObjectNode().str("sectionId", a.trigger.sectionId).str("event", a.trigger.event))
        o.str("pageRef", a.pageRef).str("queryRef", a.queryRef).str("viewModelRef", a.viewModelRef).str("dataSourceRef", a.dataSourceRef)
            .str("operationKey", a.operationKey).str("workflowRef", a.workflowRef)
        o.str("channel", a.channel?.name).str("templateRef", a.templateRef).str("endpointRef", a.endpointRef)
        o.attach("recipients", array(a.recipients, this::principal))
        o.str("permissionRef", a.permissionRef)
        o.attach("inputs", array(a.inputs) { i ->
            val io = json.createObjectNode().str("name", i.name)
            if (i.type != ActionInputType.ANY) io.put("type", i.type.name)
            if (i.required) io.put("required", true)
            i.maxLength?.let { io.put("maxLength", it) }
            io
        })
        if (a.inputMapping.isNotEmpty()) {
            val m = json.createObjectNode()
            for ((name, src) in a.inputMapping) {
                val so = json.createObjectNode().str("source", src.source.name).str("path", src.path).str("name", src.name).str("key", src.key?.name)
                so.attach("value", src.value?.let { copy(it) })
                m.attach(name, so)
            }
            o.attach("inputMapping", m)
        }
        o.str("idempotency", a.idempotency?.name)
        if (a.limits != null) o.attach("limits", json.createObjectNode().also { l -> a.limits.timeoutMillis?.let { l.put("timeoutMillis", it) } })
        o.attach("onSuccess", strings(a.onSuccess))
        o.attach("onError", strings(a.onError))
        return o
    }

    private fun workflow(w: WorkflowDef): ObjectNode {
        val o = json.createObjectNode().str("id", w.id).str("name", w.name)
        if (!w.enabled) o.put("enabled", false)
        if (w.trigger != WorkflowTriggerType.MANUAL) o.put("trigger", w.trigger.name)
        o.str("schedule", w.schedule).str("timezone", w.timezone).str("startStepId", w.startStepId)
        if (w.compensateOnCancel) o.put("compensateOnCancel", true)
        if (w.limits != null) o.attach("limits", json.createObjectNode().also { l ->
            w.limits.maxSteps?.let { l.put("maxSteps", it) }; w.limits.maxStepExecutions?.let { l.put("maxStepExecutions", it) }
            w.limits.maxRetries?.let { l.put("maxRetries", it) }; w.limits.maxDurationSeconds?.let { l.put("maxDurationSeconds", it) }
            w.limits.maxPayloadBytes?.let { l.put("maxPayloadBytes", it) }; w.limits.maxDepth?.let { l.put("maxDepth", it) }
            w.limits.maxStepTimeoutMillis?.let { l.put("maxStepTimeoutMillis", it) }
        })
        o.attach("steps", array(w.steps, this::step))
        return o
    }

    private fun step(s: WorkflowStepDef): ObjectNode {
        val o = json.createObjectNode().str("id", s.id).str("kind", s.kind?.name).str("actionRef", s.actionRef)
        if (s.inputs.isNotEmpty()) {
            val m = json.createObjectNode()
            for ((name, ref) in s.inputs) m.attach(name, valueRef(ref))
            o.attach("inputs", m)
        }
        o.str("next", s.next).str("onError", s.onError)
        if (s.retry != null) o.attach("retry", json.createObjectNode().also { r ->
            s.retry.maxAttempts?.let { r.put("maxAttempts", it) }; s.retry.initialBackoffMillis?.let { r.put("initialBackoffMillis", it) }
            r.attach("multiplier", s.retry.multiplier?.let { copy(it) }); s.retry.maxBackoffMillis?.let { r.put("maxBackoffMillis", it) }
        })
        s.timeoutMillis?.let { o.put("timeoutMillis", it) }
        s.waitSeconds?.let { o.put("waitSeconds", it) }
        if (s.approval != null) o.attach("approval", json.createObjectNode().also { a ->
            a.str("title", s.approval.title)
            a.attach("approvers", array(s.approval.approvers, this::principal))
            s.approval.requiredApprovals?.let { a.put("requiredApprovals", it) }; s.approval.expiresInSeconds?.let { a.put("expiresInSeconds", it) }
            s.approval.allowSelfApproval?.let { a.put("allowSelfApproval", it) }
            a.str("notifyTemplateRef", s.approval.notifyTemplateRef).str("onReject", s.approval.onReject).str("onExpire", s.approval.onExpire)
        })
        o.attach("branches", array(s.branches) { b -> json.createObjectNode().also { bo -> bo.attach("condition", copy(b.condition)); bo.str("next", b.next) } })
        o.str("defaultNext", s.defaultNext).str("compensationActionRef", s.compensationActionRef)
        return o
    }

    private fun permission(p: PermissionDef): ObjectNode =
        json.createObjectNode().str("id", p.id).str("name", p.name).str("permission", p.permission).str("resourceType", p.resourceType.name).str("resourceRef", p.resourceRef)

    private fun dataBinding(b: DataBindingDef): ObjectNode =
        json.createObjectNode().str("id", b.id).str("sectionId", b.sectionId).str("prop", b.prop)
            .str("viewModelRef", b.viewModelRef).str("queryRef", b.queryRef).str("mappingRef", b.mappingRef)

    private fun theme(t: ThemeDef): ObjectNode {
        val o = json.createObjectNode()
        if (t.colors.isNotEmpty()) {
            val colors = json.createObjectNode()
            for ((k, v) in t.colors) colors.put(k, v)
            o.attach("colors", colors)
        }
        if (t.fontFamily != null) o.put("fontFamily", t.fontFamily.name)
        if (t.radius != null) o.put("radius", t.radius.name)
        return o
    }

    private fun publishConfig(c: PublishConfigDef): ObjectNode {
        val o = json.createObjectNode()
        if (c.mode != PublishMode.STATIC) o.put("mode", c.mode.name)
        if (c.visibility != PublishVisibility.PRIVATE) o.put("visibility", c.visibility.name)
        if (c.requiresAuth) o.put("requiresAuth", true)
        if (c.cacheSeconds != null) o.put("cacheSeconds", c.cacheSeconds)
        return o
    }
}
