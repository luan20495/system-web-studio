package com.systemwebstudio.schema

import com.systemwebstudio.common.ApiException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Applies the AppDefinition V2 operations ([OperationTypes.definitions]) to the document, in place on the copy the patch engine works on.
 *
 * Same trust model as the section operations: the operation is plain data, this class only does the structural part (right collection,
 * unique / existing id, definition is an object); what a definition may contain and how definitions refer to each other is judged
 * afterwards by the app definition reader and validator, so the rules exist once and an AI proposal, a Design UI edit and an API call
 * are held to the same ones.
 *
 * What is deliberately NOT done here: setting `schemaVersion` / `kind` (that would make a legacy document with unknown top-level keys
 * strict by a side effect), cascading deletes (removing a query that something still uses is reported by the validator with the path of
 * the dangling reference), and creating data sources (granted by the data platform, not by an edit).
 */
class DefinitionPatch(private val json: JsonMapper) {
    private companion object {
        /** operation family → the collection key of the document */
        val COLLECTIONS = mapOf(
            "VIEW_MODEL" to "viewModels", "QUERY" to "queries", "MAPPING" to "mappings", "DATA_BINDING" to "dataBindings",
            "ACTION" to "actions", "WORKFLOW_REF" to "workflows", "PERMISSION_REF" to "permissions"
        )
        val SINGLETONS = mapOf("THEME" to "theme", "PUBLISH_CONFIG" to "publishConfig")
        val FORBIDDEN_KEYS = setOf("__proto__", "constructor", "prototype")
    }

    private fun bad(msg: String): Nothing = throw ApiException.badRequest("INVALID_OPERATION", msg)

    fun apply(root: ObjectNode, op: SchemaOperation) {
        val verb = op.type.substringBefore('_')
        val family = op.type.substringAfter('_')
        SINGLETONS[family]?.let { key -> if (verb == "UPDATE") return updateSingleton(root, key, op) }
        val key = COLLECTIONS[family] ?: bad("unsupported operation type '${op.type}'")
        when (verb) {
            "ADD" -> add(root, key, op)
            "UPDATE" -> update(root, key, op)
            "REMOVE" -> remove(root, key, op)
            else -> bad("unsupported operation type '${op.type}'")
        }
    }

    private fun definition(op: SchemaOperation): ObjectNode {
        val d = op.definition ?: bad("definition is required")
        if (!d.isObject) bad("definition must be an object")
        d.propertyNames().forEach { if (it in FORBIDDEN_KEYS) bad("illegal field name '$it'") }
        return d as ObjectNode
    }

    private fun collection(root: ObjectNode, key: String, create: Boolean): ArrayNode? {
        val existing = root.get(key)
        if (existing != null && existing !is ArrayNode) bad("'$key' is not an array")
        if (existing != null) return existing as ArrayNode
        if (!create) return null
        val created = json.createArrayNode()
        root.set(key, created)
        return created
    }

    private fun indexOfId(items: ArrayNode?, id: String): Int = items?.indexOfFirst { it.get("id")?.takeIf { n -> n.isString }?.asString() == id } ?: -1

    private fun add(root: ObjectNode, key: String, op: SchemaOperation) {
        val def = definition(op)
        val id = def.get("id")?.takeIf { it.isString }?.asString() ?: bad("definition.id is required")
        if (op.definitionId != null && op.definitionId != id) bad("definitionId does not match definition.id")
        val items = collection(root, key, create = true)!!
        if (indexOfId(items, id) >= 0) bad("'$id' already exists in $key")
        items.add(def.deepCopy())
    }

    private fun update(root: ObjectNode, key: String, op: SchemaOperation) {
        val id = op.definitionId ?: bad("definitionId is required")
        val patch = definition(op)
        val items = collection(root, key, create = false)
        val i = indexOfId(items, id)
        if (i < 0) bad("'$id' not found in $key")
        val target = items!!.get(i) as ObjectNode
        patch.propertyNames().toList().forEach { field ->
            val value = patch.get(field)
            if (field == "id") { if (!(value.isString && value.asString() == id)) bad("the id of a definition is immutable"); return@forEach }
            if (value == null || value.isNull) target.remove(field) else target.set(field, value.deepCopy())
        }
    }

    private fun remove(root: ObjectNode, key: String, op: SchemaOperation) {
        val id = op.definitionId ?: bad("definitionId is required")
        val items = collection(root, key, create = false)
        val i = indexOfId(items, id)
        if (i < 0) bad("'$id' not found in $key")
        items!!.remove(i)
        // the canonical form has no empty collections: removing the last definition returns the document to what it was before
        if (items.size() == 0) root.remove(key)
    }

    /** UPDATE_THEME / UPDATE_PUBLISH_CONFIG: merge the given fields into the object (created when absent), null clears a field */
    private fun updateSingleton(root: ObjectNode, key: String, op: SchemaOperation) {
        val patch = definition(op)
        val existing = root.get(key)
        if (existing != null && existing !is ObjectNode) bad("'$key' is not an object")
        val target = (existing as ObjectNode?) ?: json.createObjectNode().also { root.set(key, it) }
        patch.propertyNames().toList().forEach { field ->
            val value = patch.get(field)
            if (value == null || value.isNull) target.remove(field) else target.set(field, value.deepCopy())
        }
    }
}
