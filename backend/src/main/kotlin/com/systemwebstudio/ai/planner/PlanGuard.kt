package com.systemwebstudio.ai.planner

import com.systemwebstudio.app.definition.AppDefinitionKeys
import com.systemwebstudio.app.definition.AppDefinitionReader
import com.systemwebstudio.app.definition.QueryMode
import com.systemwebstudio.schema.OperationTypes
import com.systemwebstudio.schema.SchemaOperation
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * The trust boundary of the AI planner. Model output is untrusted DATA: [parse] accepts only the agreed shape (operations, from a closed
 * vocabulary and a closed set of fields), [checkOperations] refuses what an AI may never do whatever the document looks like, and
 * [checkResult] compares the document BEFORE and AFTER the operations were applied (so merged updates and combinations are judged by what
 * they produce, not by how they are phrased). Nothing here runs a tool, a query or a request.
 *
 * AI may NOT: write the document itself, create / change / remove a data source (none of the operations can, and the result is checked),
 * use a data source or operation that was not granted to the user, weaken or change a permission, change how the app is published, carry a
 * URL / SQL / credential / script in any value. All of these are refused before anything is stored.
 *
 * [toOperation] turns one JSON object into a [SchemaOperation] (Jackson in production); it is a parameter so this class stays pure.
 */
class PlanGuard(private val toOperation: (JsonNode) -> SchemaOperation, private val json: JsonMapper) {

    // ---- parsing (BAD_OUTPUT: the model may be tried again / another model used) ----

    fun parse(content: String): ParsedPlan {
        val text = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw BadPlanOutput("no JSON object")
        val root: JsonNode = try { json.readTree(text.substring(start, end + 1)) } catch (e: Exception) { throw BadPlanOutput("invalid JSON") }
        if (root !is ObjectNode) throw BadPlanOutput("not an object")
        // "AI must not write JSON directly": a document (or part of one) is not an answer, however it is named
        root.propertyNames().firstOrNull { it in DOCUMENT_KEYS }?.let { throw BadPlanOutput("answers with a document ('$it'); only operations are accepted") }
        val ops = root.get("operations") ?: throw BadPlanOutput("operations are missing")
        if (ops !is ArrayNode) throw BadPlanOutput("operations must be an array")
        if (ops.size() > MAX_OPS) throw BadPlanOutput("too many operations (at most $MAX_OPS)")
        val operations = ops.toList().mapIndexed { i, node ->
            if (node !is ObjectNode) throw BadPlanOutput("operations[$i] is not an object")
            node.propertyNames().firstOrNull { it !in OP_FIELDS }?.let { throw BadPlanOutput("operations[$i] has an unknown field '$it'") }
            val type = node.get("type")?.takeIf { it.isString }?.asString() ?: throw BadPlanOutput("operations[$i] has no type")
            if (type !in OperationTypes.planner) throw BadPlanOutput("operations[$i] has an unknown type '$type'")
            try { toOperation(node) } catch (e: Exception) { throw BadPlanOutput("operations[$i] is not understood") }
        }
        val message = root.get("message")?.takeIf { it.isString }?.asString()?.trim()?.take(500).orEmpty()
            .ifEmpty { if (operations.isEmpty()) "Không có thay đổi nào." else "Đã đề xuất ${operations.size} thay đổi." }
        val claimed = root.get("sources")?.takeIf { it.isObject }?.let { src ->
            listOf("blocks", "templates").associateWith { k ->
                src.get(k)?.takeIf { it.isArray }?.toList()?.mapNotNull { it.takeIf { n -> n.isString }?.asString()?.take(64) }?.take(20).orEmpty()
            }
        }
        return ParsedPlan(message, operations, claimed)
    }

    // ---- what an AI may never propose (checked on the operations themselves) ----

    fun checkOperations(ops: List<SchemaOperation>): List<PlanViolation> {
        val out = ArrayList<PlanViolation>()
        ops.forEachIndexed { i, op ->
            val at = "operations[$i]"
            if (op.type !in OperationTypes.planner) out += PlanViolation(at, "operation type '${op.type}' is not allowed")
            if (op.type in NEVER) out += PlanViolation(at, NEVER_WHY.getValue(op.type))
            if (op.type == "UPDATE_ACTION" && op.definition?.has("permissionRef") == true)
                out += PlanViolation("$at.definition.permissionRef", "the permission of an action is not changed by AI")
            scan(op.value, "$at.value", out); scan(op.item, "$at.item", out); scan(op.props, "$at.props", out); scan(op.definition, "$at.definition", out)
        }
        return out
    }

    /** forbidden keys (credential / SQL / URL / code names) and URL-like strings anywhere in a value the model supplied */
    private fun scan(n: JsonNode?, at: String, out: MutableList<PlanViolation>, depth: Int = 1) {
        if (n == null || depth > MAX_DEPTH) return
        when {
            n.isString -> if (AppDefinitionReader.looksLikeUrl(n.asString())) out += PlanViolation(at, "URLs are not allowed; refer to a registered data source by id")
            n.isArray -> n.forEachIndexed { i, e -> scan(e, "$at[$i]", out, depth + 1) }
            n.isObject -> n.propertyNames().forEach { k ->
                if (AppDefinitionReader.isForbiddenKey(k)) out += PlanViolation("$at.$k", "key '$k' is not allowed (no URLs, SQL, credentials or code)")
                scan(n.get(k), "$at.$k", out, depth + 1)
            }
        }
    }

    // ---- what the proposal produced (checked on before / after) ----

    fun checkResult(current: JsonNode, next: JsonNode, ctx: PlannerContext): List<PlanViolation> {
        val out = ArrayList<PlanViolation>()
        if (same(current, next, "dataSources").not()) out += PlanViolation("dataSources", "data sources are granted by the data platform; AI cannot add, change or remove them")
        if (same(current, next, AppDefinitionKeys.PUBLISH_CONFIG).not()) out += PlanViolation("publishConfig", "AI never changes how the app is published")

        val nextPermissions = byId(next, "permissions")
        byId(current, "permissions").forEach { (id, was) ->
            if (nextPermissions[id] != was) out += PlanViolation("permissions", "permission '$id' was removed or changed; AI cannot weaken permissions")
        }
        val nextActions = byId(next, "actions")
        byId(current, "actions").forEach { (id, was) ->
            val now = nextActions[id] ?: return@forEach
            if (was.get("permissionRef") != now.get("permissionRef")) out += PlanViolation("actions", "action '$id' lost or changed its permission; AI cannot weaken permissions")
        }

        val dataSources = byId(next, "dataSources")
        val queries = byId(next, "queries")
        val queriesBefore = byId(current, "queries")
        queries.forEach { (id, q) -> if (queriesBefore[id] != q) grantedOperation(q, "queries[$id]", dataSources, ctx, out, requireMode = modeOf(q)) }
        val actionsBefore = byId(current, "actions")
        nextActions.forEach { (id, a) ->
            if (actionsBefore[id] == a) return@forEach
            val type = a.get("type")?.asString()
            val named = a.has("dataSourceRef") || a.has("operationKey")
            if (type != "CALL_API") { if (named) out += PlanViolation("actions[$id]", "only a CALL_API action names a data source operation") }
            else grantedOperation(a, "actions[$id]", dataSources, ctx, out, requireMode = null)
        }
        val mappingsBefore = byId(current, "mappings")
        byId(next, "mappings").forEach { (id, m) ->
            if (mappingsBefore[id] == m) return@forEach
            val q = queries[m.get("queryRef")?.asString()] ?: return@forEach
            val op = operationOf(q, dataSources, ctx) ?: return@forEach
            if (op.fields.isEmpty()) return@forEach
            (m.get("fields") as? ArrayNode)?.forEachIndexed { i, f ->
                val from = f.get("from")?.asString()?.substringBefore('.') ?: return@forEachIndexed
                if (from !in op.fields) out += PlanViolation("mappings[$id].fields[$i].from", "'$from' is not a field of operation '${op.key}'")
            }
        }
        return out
    }

    private fun modeOf(def: JsonNode): QueryMode = if (def.get("mode")?.asString() == "WRITE") QueryMode.WRITE else QueryMode.READ

    private fun operationOf(def: JsonNode, dataSources: Map<String, ObjectNode>, ctx: PlannerContext): GrantedOperation? {
        val ds = dataSources[def.get("dataSourceRef")?.asString()] ?: return null
        val ref = ds.get("sourceRef")?.asString() ?: return null
        val key = def.get("operationKey")?.asString() ?: return null
        return ctx.granted.firstOrNull { it.sourceRef == ref }?.operations?.firstOrNull { it.key == key }
    }

    /** a query / CALL_API action may only use a data source of the document that is granted, and an operation that source offers */
    private fun grantedOperation(def: ObjectNode, at: String, dataSources: Map<String, ObjectNode>, ctx: PlannerContext, out: MutableList<PlanViolation>, requireMode: QueryMode?) {
        val dsId = def.get("dataSourceRef")?.asString() ?: return        // a missing reference is the validator's finding
        val ds = dataSources[dsId] ?: return                               // so is an unknown one
        val ref = ds.get("sourceRef")?.asString()
        val key = def.get("operationKey")?.asString()
        if (ref == null) {
            if (key != null) out += PlanViolation("$at.operationKey", "data source '$dsId' is not bound yet; bind it before naming an operation")
            return
        }
        val source = ctx.granted.firstOrNull { it.sourceRef == ref }
        if (source == null) { out += PlanViolation("$at.dataSourceRef", "data source '$dsId' is not granted to you"); return }
        if (key == null) return
        val op = source.operations.firstOrNull { it.key == key }
        if (op == null) { out += PlanViolation("$at.operationKey", "operation '$key' is not offered by data source '$dsId'"); return }
        if (requireMode != null && op.mode != requireMode) out += PlanViolation("$at.operationKey", "operation '$key' is a ${op.mode} operation, not ${requireMode}")
        if (op.params.isNotEmpty()) (def.get("params") as? ArrayNode)?.forEachIndexed { i, p ->
            val name = p.get("name")?.asString()
            if (name != null && name !in op.params) out += PlanViolation("$at.params[$i].name", "'$name' is not a parameter of operation '$key'")
        }
    }

    private fun same(a: JsonNode, b: JsonNode, key: String): Boolean = a.get(key) == b.get(key)

    private fun byId(doc: JsonNode, collection: String): Map<String, ObjectNode> {
        val out = LinkedHashMap<String, ObjectNode>()
        (doc.get(collection) as? ArrayNode)?.forEach { n -> if (n is ObjectNode) n.get("id")?.takeIf { it.isString }?.asString()?.let { out[it] = n } }
        return out
    }

    companion object {
        const val MAX_OPS = 40
        const val MAX_DEPTH = 12

        /** the fields of SchemaOperation (kept in sync by a test); anything else in a model answer is refused, not silently dropped */
        val OP_FIELDS = setOf("type", "sectionId", "sectionType", "itemId", "arrayPath", "path", "value", "item", "props", "beforeSectionId",
            "afterSectionId", "index", "pageId", "definitionId", "definition")

        /** top-level members that would mean "here is the document": never accepted as an answer */
        val DOCUMENT_KEYS = setOf("schema", "pageSchema", "appDefinition", "document", "app", "page", "sections", "pages", "site") + AppDefinitionKeys.V2_KEYS

        private val NEVER_WHY = mapOf(
            "UPDATE_PUBLISH_CONFIG" to "AI never changes how the app is published",
            "UPDATE_PERMISSION_REF" to "AI cannot change a permission",
            "REMOVE_PERMISSION_REF" to "AI cannot remove a permission"
        )
        val NEVER = NEVER_WHY.keys
    }
}
