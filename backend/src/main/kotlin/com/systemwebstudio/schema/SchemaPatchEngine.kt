package com.systemwebstudio.schema

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.component.ComponentRegistry
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** Applies structured operations to a copy of the schema. Validation happens afterwards, in the caller. */
@Service
class SchemaPatchEngine(private val registry: ComponentRegistry, private val json: JsonMapper) {
    private val definitionPatch = DefinitionPatch(json)

    companion object {
        private val SEGMENT = Regex("^[A-Za-z][A-Za-z0-9]{0,39}$")
        private val FORBIDDEN = setOf("__proto__", "constructor", "prototype")
        const val MAX_OPERATIONS = 50
    }

    private fun bad(msg: String): Nothing = throw ApiException.badRequest("INVALID_OPERATION", msg)

    fun apply(schema: JsonNode, ops: List<SchemaOperation>): JsonNode {
        if (ops.isEmpty()) bad("at least one operation is required")
        if (ops.size > MAX_OPERATIONS) bad("at most $MAX_OPERATIONS operations per request")
        val copy = schema.deepCopy() as ObjectNode
        copy.get("sections") as? ArrayNode ?: bad("schema has no sections array")
        ops.forEachIndexed { i, op ->
            try {
                if (op.type in OperationTypes.definitions) definitionPatch.apply(copy, op)
                else if (op.type in OperationTypes.site) applySite(copy, op)
                else applyOne(sectionsFor(copy, op), op, allSections(copy))
            } catch (e: ApiException) {
                throw ApiException.badRequest("INVALID_OPERATION", "operation[$i] ${op.type}: ${e.message}", mapOf("operationIndex" to i))
            }
        }
        return copy
    }

    /** home sections first, then each extra page's sections (section ids are unique across the whole site) */
    private fun allSections(root: ObjectNode): List<ArrayNode> =
        listOf(root.get("sections") as ArrayNode) + (root.get("pages") as? ArrayNode)?.mapNotNull { it.get("sections") as? ArrayNode }.orEmpty()

    private fun page(root: ObjectNode, id: String?): ObjectNode? =
        (root.get("pages") as? ArrayNode)?.firstOrNull { it.get("id")?.asString() == id } as? ObjectNode

    /** the sections array an operation works on: ADD_SECTION → its page (default home); others → the page that holds sectionId */
    private fun sectionsFor(root: ObjectNode, op: SchemaOperation): ArrayNode {
        if (op.type == "ADD_SECTION") {
            if (op.pageId == null || op.pageId == "home") return root.get("sections") as ArrayNode
            return page(root, op.pageId)?.get("sections") as? ArrayNode ?: bad("page '${op.pageId}' not found")
        }
        val id = op.sectionId ?: bad("sectionId is required")
        return allSections(root).firstOrNull { arr -> arr.any { it.get("id")?.asString() == id } } ?: bad("section '$id' not found")
    }

    private fun applySite(root: ObjectNode, op: SchemaOperation) {
        val site = (root.get("site") as? ObjectNode) ?: json.createObjectNode().also { root.set("site", it) }
        when (op.type) {
            "ADD_PAGE" -> {
                val pages = (root.get("pages") as? ArrayNode) ?: json.createArrayNode().also { root.set("pages", it) }
                val id = op.pageId ?: bad("pageId is required")
                if (id == "home" || pages.any { it.get("id")?.asString() == id }) bad("page '$id' already exists")
                val p = op.props?.takeIf { it.isObject } ?: bad("props {slug, title} are required")
                val node = json.createObjectNode().put("id", id)
                listOf("slug", "title", "seo").forEach { k -> p.get(k)?.let { node.set(k, it.deepCopy()) } }
                node.set("sections", json.createArrayNode())
                pages.add(node)
            }
            "UPDATE_PAGE" -> {
                val p = op.props?.takeIf { it.isObject } ?: bad("props object is required")
                val target = if (op.pageId == null || op.pageId == "home") (site.get("home") as? ObjectNode ?: json.createObjectNode().also { site.set("home", it) })
                    else page(root, op.pageId) ?: bad("page '${op.pageId}' not found")
                val allowed = if (op.pageId == null || op.pageId == "home") setOf("title", "seo") else setOf("slug", "title", "seo")
                p.propertyNames().forEach { k -> if (k !in allowed) bad("'$k' cannot be changed on this page"); target.set(k, p.get(k).deepCopy()) }
            }
            "REMOVE_PAGE" -> {
                val pages = root.get("pages") as? ArrayNode ?: bad("page '${op.pageId}' not found")
                val i = pages.indexOfFirst { it.get("id")?.asString() == op.pageId }
                if (i < 0) bad("page '${op.pageId}' not found")
                pages.remove(i)
                // links to the removed page go too
                (site.get("navigation") as? ArrayNode)?.let { nav -> nav.indexOfFirst { it.get("pageId")?.asString() == op.pageId }.takeIf { it >= 0 }?.let { nav.remove(it) } }
            }
            "SET_NAVIGATION" -> site.set("navigation", op.value?.takeIf { it.isArray }?.deepCopy() ?: bad("value must be an array of links"))
            "UPDATE_SITE" -> {
                val p = op.props?.takeIf { it.isObject } ?: bad("props object is required")
                p.propertyNames().forEach { k -> if (k !in setOf("title", "notFound")) bad("'$k' cannot be changed here"); site.set(k, p.get(k).deepCopy()) }
            }
        }
    }

    private fun indexOf(sections: ArrayNode, id: String?): Int {
        if (id == null) bad("sectionId is required")
        val i = sections.indexOfFirst { it.get("id")?.asString() == id }
        if (i < 0) bad("section '$id' not found")
        return i
    }

    private fun insertionIndex(sections: ArrayNode, op: SchemaOperation, excluding: Int? = null): Int = when {
        op.beforeSectionId != null -> indexOf(sections, op.beforeSectionId)
        op.afterSectionId != null -> indexOf(sections, op.afterSectionId) + 1
        op.index != null -> op.index.coerceIn(0, sections.size())
        else -> sections.size()
    }

    private fun applyOne(sections: ArrayNode, op: SchemaOperation, all: List<ArrayNode>) {
        if (op.type !in OperationTypes.all) bad("unsupported operation type '${op.type}'")
        when (op.type) {
            "ADD_SECTION" -> {
                val type = op.sectionType ?: bad("sectionType is required")
                val component = registry.list().firstOrNull { it.id == type && it.status == "ACTIVE" } ?: bad("component '$type' is not in the registry")
                val id = op.sectionId ?: generateId(all, type)
                if (all.any { arr -> arr.any { it.get("id")?.asString() == id } }) bad("section id '$id' already exists")
                val section = json.createObjectNode()
                section.put("id", id); section.put("type", type); section.put("componentVersion", component.latestVersion)
                section.set("props", op.props?.takeIf { it.isObject }?.deepCopy() ?: json.createObjectNode())
                sections.insert(insertionIndex(sections, op), section)
            }
            "REMOVE_SECTION" -> sections.remove(indexOf(sections, op.sectionId))
            "MOVE_SECTION" -> {
                val from = indexOf(sections, op.sectionId)
                val node = sections.remove(from)
                sections.insert(insertionIndex(sections, op).coerceAtMost(sections.size()), node)
            }
            "UPDATE_SECTION" -> {
                val section = sections.get(indexOf(sections, op.sectionId)) as ObjectNode
                val patch = op.props?.takeIf { it.isObject } ?: bad("props object is required")
                val props = section.get("props") as ObjectNode
                patch.propertyNames().forEach { key -> checkSegment(key); props.set(key, patch.get(key).deepCopy()) }
            }
            "UPDATE_PROP" -> {
                val section = sections.get(indexOf(sections, op.sectionId))
                val value = op.value?.takeIf { !it.isNull } ?: bad("value is required")
                val path = op.path ?: bad("path is required")
                var target: ObjectNode = section.get("props") as ObjectNode
                if (op.itemId != null) target = findItem(target, op.arrayPath ?: "items", op.itemId)
                if (op.itemId != null && path == "id") bad("item id is immutable")
                setPath(target, path, value.deepCopy())
            }
            "ADD_ITEM" -> {
                val props = sections.get(indexOf(sections, op.sectionId)).get("props") as ObjectNode
                val array = array(props, op.arrayPath ?: defaultArray(props))
                val item = op.item?.takeIf { it.isObject } ?: bad("item object is required")
                val id = item.get("id")?.takeIf { it.isString }?.asString() ?: bad("item.id is required")
                if (array.any { it.get("id")?.asString() == id }) bad("item id '$id' already exists")
                array.insert((op.index ?: array.size()).coerceIn(0, array.size()), item.deepCopy())
            }
            "REMOVE_ITEM" -> {
                val props = sections.get(indexOf(sections, op.sectionId)).get("props") as ObjectNode
                val array = array(props, op.arrayPath ?: defaultArray(props))
                val i = array.indexOfFirst { it.get("id")?.asString() == (op.itemId ?: bad("itemId is required")) }
                if (i < 0) bad("item '${op.itemId}' not found")
                array.remove(i)
            }
        }
    }

    private fun defaultArray(props: ObjectNode) = if (props.has("rows") && !props.has("items")) "rows" else "items"

    private fun array(props: ObjectNode, name: String): ArrayNode {
        checkSegment(name)
        return props.get(name) as? ArrayNode ?: bad("'$name' is not an array")
    }

    private fun findItem(props: ObjectNode, arrayName: String, itemId: String): ObjectNode =
        array(props, arrayName).firstOrNull { it.get("id")?.asString() == itemId } as? ObjectNode ?: bad("item '$itemId' not found")

    private fun checkSegment(s: String) {
        if (s in FORBIDDEN || !SEGMENT.matches(s)) bad("illegal property name '$s'")
    }

    private fun setPath(root: ObjectNode, path: String, value: JsonNode) {
        val parts = path.split(".")
        if (parts.size > 3) bad("path too deep")
        parts.forEach(::checkSegment)
        var node = root
        parts.dropLast(1).forEach { p -> node = node.get(p) as? ObjectNode ?: bad("path segment '$p' is not an object") }
        node.set(parts.last(), value)
    }

    private fun generateId(all: List<ArrayNode>, type: String): String {
        val base = type.lowercase().replace(Regex("[^a-z0-9]"), "")
        var n = 1
        while (all.any { arr -> arr.any { it.get("id")?.asString() == "$base-$n" } }) n++
        return "$base-$n"
    }
}
