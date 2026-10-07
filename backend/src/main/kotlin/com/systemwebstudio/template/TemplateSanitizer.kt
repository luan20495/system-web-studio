package com.systemwebstudio.template

import com.systemwebstudio.app.definition.AppDefinitionKeys
import com.systemwebstudio.app.definition.AppDefinitionLimits
import com.systemwebstudio.app.definition.AppDefinitionReader
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/*
 * Template V2 (D-C2-09). A template is a PORTABLE copy of an AppDefinition: it may carry UI (sections / pages), the data contract it expects
 * (view models, query declarations, mappings), bindings, action / workflow / permission declarations and sample data, but nothing that
 * belongs to one tenant or one project: no connector id, no operation key of a tenant connector (SYSTEM scope), no publish intent, no file,
 * no credential, no URL. Using a template re-binds the data slots to the new project's own, granted data sources.
 */

/**
 * Who may see / use a template.
 *   PRIVATE = the author only (templates.visibility = PRIVATE)
 *   TENANT  = everyone of one tenant (PROVISIONAL: needs a tenant column, migration request in BOARD; tenant ≈ workspace until C1 T2)
 *   SYSTEM  = the whole platform (templates.visibility = COMPANY, approved by a system admin; plus the built-in business templates)
 */
enum class TemplateScope {
    PRIVATE, TENANT, SYSTEM;

    companion object {
        fun of(visibility: String, tenantScoped: Boolean = false): TemplateScope = when {
            visibility == "COMPANY" -> SYSTEM
            tenantScoped -> TENANT
            else -> PRIVATE
        }
    }
}

/** One thing the template needs from the project that uses it: a data source to bind, with the queries / view models that expect it. */
data class DataSlot(val dataSourceId: String, val type: String, val name: String?, val queries: List<SlotQuery>, val viewModels: List<SlotViewModel>)
data class SlotQuery(val id: String, val mode: String, val params: List<String>)
data class SlotViewModel(val id: String, val queryRef: String?, val fields: List<String>)

data class SanitizedTemplate(val schema: JsonNode, val removed: List<String>)

/**
 * Pure (no Spring, no database). [sanitize] returns the portable form of a document and what it had to remove; [issues] is what a document
 * would lose, i.e. an empty list means it is already safe for the scope (the review check and the tests use it).
 */
class TemplateSanitizer(private val json: JsonMapper) {

    fun sanitize(schema: JsonNode, scope: TemplateScope): SanitizedTemplate {
        if (!schema.isObject) return SanitizedTemplate(schema, emptyList())
        val root = schema.deepCopy() as ObjectNode
        val removed = ArrayList<String>()

        // the publish draft is the author's intention for ONE project; the authoritative state is publish_configs (D-C2-06)
        if (root.has(AppDefinitionKeys.PUBLISH_CONFIG)) { root.remove(AppDefinitionKeys.PUBLISH_CONFIG); removed += "publishConfig: belongs to the project, not the template" }

        // connector ids are tenant data: the data source stays as a slot to bind, the registration is dropped
        (root.get("dataSources") as? ArrayNode)?.forEachIndexed { i, ds ->
            if (ds is ObjectNode && ds.has("sourceRef")) { ds.remove("sourceRef"); removed += "dataSources[$i].sourceRef: tenant connector id" }
        }
        if (scope == TemplateScope.SYSTEM) {
            // an operation key names an operation of ONE tenant's connector; a platform-wide template must not leak it
            for (collection in listOf("queries", "actions")) (root.get(collection) as? ArrayNode)?.forEachIndexed { i, d ->
                if (d is ObjectNode && d.has("operationKey")) { d.remove("operationKey"); removed += "$collection[$i].operationKey: tenant connector operation" }
            }
        }
        sanitizeExtensions(root, scope, removed)
        return SanitizedTemplate(root, removed)
    }

    fun issues(schema: JsonNode, scope: TemplateScope): List<String> = sanitize(schema, scope).removed

    /** the data contract a project must satisfy to use the template, derived from the declarations (never stored separately) */
    fun dataSlots(schema: JsonNode): List<DataSlot> {
        val queries = (schema.get("queries") as? ArrayNode)?.filterIsInstance<ObjectNode>().orEmpty()
        val viewModels = (schema.get("viewModels") as? ArrayNode)?.filterIsInstance<ObjectNode>().orEmpty()
        return (schema.get("dataSources") as? ArrayNode)?.filterIsInstance<ObjectNode>().orEmpty().mapNotNull { ds ->
            val id = ds.get("id")?.takeIf { it.isString }?.asString() ?: return@mapNotNull null
            val own = queries.filter { it.get("dataSourceRef")?.asString() == id }
            val ownIds = own.mapNotNull { it.get("id")?.asString() }.toSet()
            DataSlot(id, ds.get("type")?.asString().orEmpty(), ds.get("name")?.asString(),
                own.map { q -> SlotQuery(q.get("id").asString(), q.get("mode")?.asString() ?: "READ",
                    (q.get("params") as? ArrayNode)?.mapNotNull { it.get("name")?.asString() }.orEmpty()) },
                viewModels.filter { it.get("queryRef")?.asString() in ownIds }.map { vm ->
                    SlotViewModel(vm.get("id").asString(), vm.get("queryRef")?.asString(), (vm.get("fields") as? ArrayNode)?.mapNotNull { it.get("name")?.asString() }.orEmpty())
                })
        }
    }

    /** the sample rows a template ships with, by view model id; null when it has none */
    fun sampleData(schema: JsonNode): JsonNode? = schema.get(AppDefinitionKeys.EXTENSIONS)?.get(SAMPLE_NAMESPACE)?.get("sampleData")

    // ---- extensions ----

    private fun sanitizeExtensions(root: ObjectNode, scope: TemplateScope, removed: MutableList<String>) {
        val ext = root.get(AppDefinitionKeys.EXTENSIONS) as? ObjectNode ?: return
        for (key in ext.propertyNames().toList()) {
            val at = "extensions.$key"
            val value = ext.get(key)
            when {
                // third-party namespaces are not reviewed by the platform: they never travel in a platform-wide template
                scope == TemplateScope.SYSTEM && !key.startsWith("xweb.") -> { ext.remove(key); removed += "$at: namespace is not reviewed by the platform" }
                key == SAMPLE_NAMESPACE -> {
                    val cleaned = cleanTemplateNamespace(value, root, at, removed)
                    if (cleaned == null || cleaned.size() == 0) ext.remove(key) else ext.set(key, cleaned)
                }
                else -> firstProblem(value, at)?.let { ext.remove(key); removed += "$at: $it" }
            }
        }
        if (ext.size() == 0) root.remove(AppDefinitionKeys.EXTENSIONS)
    }

    /** extensions["xweb.template"] = { contractVersion?, sampleData? }; sample rows must fit the declared view models and stay small and inert */
    private fun cleanTemplateNamespace(value: JsonNode?, root: ObjectNode, at: String, removed: MutableList<String>): ObjectNode? {
        if (value !is ObjectNode) { removed += "$at: must be an object"; return null }
        val out = json.createObjectNode()
        for (k in value.propertyNames().toList()) when (k) {
            "contractVersion" -> if (value.get(k).isInt) out.set(k, value.get(k)) else removed += "$at.$k: must be an integer"
            "sampleData" -> cleanSampleData(value.get(k), root, "$at.$k", removed)?.takeIf { it.size() > 0 }?.let { out.set(k, it) }
            else -> removed += "$at.$k: unknown member"
        }
        return out
    }

    private fun cleanSampleData(sample: JsonNode?, root: ObjectNode, at: String, removed: MutableList<String>): ObjectNode? {
        if (sample !is ObjectNode) { removed += "$at: must be an object"; return null }
        val fields = HashMap<String, Set<String>>()
        (root.get("viewModels") as? ArrayNode)?.forEach { vm ->
            val id = vm.get("id")?.takeIf { it.isString }?.asString() ?: return@forEach
            fields[id] = (vm.get("fields") as? ArrayNode)?.mapNotNull { it.get("name")?.asString() }.orEmpty().toSet()
        }
        val out = json.createObjectNode()
        for (vmId in sample.propertyNames().toList()) {
            val p = "$at.$vmId"
            val allowed = fields[vmId]
            val rows = sample.get(vmId)
            if (allowed == null) { removed += "$p: not a view model of this template"; continue }
            if (rows !is ArrayNode) { removed += "$p: must be an array of rows"; continue }
            if (rows.size() > MAX_SAMPLE_ROWS) { removed += "$p: at most $MAX_SAMPLE_ROWS rows"; continue }
            val clean = json.createArrayNode()
            var ok = true
            rows.forEachIndexed rowLoop@{ i, row ->
                if (row !is ObjectNode) { removed += "$p[$i]: must be an object"; ok = false; return@rowLoop }
                val r = json.createObjectNode()
                for (f in row.propertyNames().toList()) {
                    val cell = row.get(f)
                    when {
                        f !in allowed -> { removed += "$p[$i].$f: not a field of the view model"; ok = false }
                        AppDefinitionReader.isForbiddenKey(f) -> { removed += "$p[$i].$f: key is not allowed"; ok = false }
                        cell.isString && (cell.asString().length > MAX_SAMPLE_TEXT || AppDefinitionReader.looksLikeUrl(cell.asString())) ->
                            { removed += "$p[$i].$f: text too long or contains a URL"; ok = false }
                        cell.isString || cell.isNumber || cell.isBoolean -> r.set(f, cell)
                        else -> { removed += "$p[$i].$f: only text, numbers and booleans"; ok = false }
                    }
                }
                clean.add(r)
            }
            if (ok) out.set(vmId, clean)
        }
        return out
    }

    /** first credential-like key / URL anywhere in free-form JSON, or null */
    private fun firstProblem(n: JsonNode?, at: String, depth: Int = 1): String? {
        if (n == null) return null
        if (depth > AppDefinitionLimits.MAX_EXTENSION_DEPTH) return "nested too deeply"
        return when {
            n.isString -> if (AppDefinitionReader.looksLikeUrl(n.asString())) "contains a URL" else null
            n.isArray -> n.firstNotNullOfOrNull { firstProblem(it, at, depth + 1) }
            n.isObject -> n.propertyNames().firstNotNullOfOrNull { k ->
                if (AppDefinitionReader.isForbiddenKey(k)) "key '$k' is not allowed (no URLs, SQL, credentials or code)" else firstProblem(n.get(k), at, depth + 1)
            }
            else -> null
        }
    }

    companion object {
        const val SAMPLE_NAMESPACE = "xweb.template"
        const val MAX_SAMPLE_ROWS = 20
        const val MAX_SAMPLE_TEXT = 200
    }
}
