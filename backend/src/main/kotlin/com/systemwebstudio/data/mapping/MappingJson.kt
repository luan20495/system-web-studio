package com.systemwebstudio.data.mapping

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.query.DataJson
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.UUID

/** Where a mapping/view model is looked up: always inside one tenant, optionally narrowed to a project / published app version. */
data class AppScope(val tenantId: UUID, val projectId: UUID? = null, val appVersionId: String? = null)

/**
 * Port to the definitions that live in C2's AppDefinition JSON (`mappings`, `viewModels`). The gateway never reads an app definition
 * itself: C2's adapter implements this, returns parsed objects (via [MappingJson]) and must scope the lookup by [AppScope.tenantId].
 */
interface MappingCatalog {
    fun findMapping(scope: AppScope, ref: String): MappingDefinition?
    fun findViewModel(scope: AppScope, ref: String): ViewModelDefinition?
}

/**
 * Strict reader for the C2 shapes (see [MappingModel.kt]). Unknown keys are rejected rather than ignored: a silently ignored `transform` or
 * `validation` would let data through that the author believed was constrained. Errors name the JSON path, never a value.
 */
object MappingJson {
    private const val MAX_BYTES = 256 * 1024
    private val MAPPING_KEYS = setOf("id", "name", "description", "queryRef", "fields", "errorPolicy", "version")
    private val FIELD_KEYS = setOf("from", "to", "transforms", "transform", "default", "nullable", "validation", "description")
    private val VM_KEYS = setOf("id", "name", "description", "queryRef", "mappingRef", "cardinality", "fields")
    private val VMF_KEYS = setOf("name", "type", "label", "description")
    private val VALIDATION_KEYS = setOf("minLength", "maxLength", "min", "max", "oneOf", "format")

    fun parseMapping(bytes: ByteArray): MappingDefinition = mapping(guarded("mapping") { readTree(bytes) })
    fun parseViewModel(bytes: ByteArray): ViewModelDefinition = viewModel(guarded("view model") { readTree(bytes) })

    private fun readTree(bytes: ByteArray): JsonNode {
        if (bytes.size > MAX_BYTES) throw bad("$", "definition is too large")
        return try { DataJson.parse(bytes) } catch (e: Exception) { throw bad("$", "not valid JSON") }
    }

    fun mapping(n: JsonNode): MappingDefinition = guarded("mapping") {
        obj(n, "$", MAPPING_KEYS)
        val fields = array(n, "fields", "$.fields", 1, 200)
        MappingDefinition(
            id = str(n, "id", "$.id") ?: throw bad("$.id", "is required"), name = str(n, "name", "$.name"), queryRef = str(n, "queryRef", "$.queryRef"),
            fields = DataJson.elements(fields).mapIndexed { i, f -> field(f, "$.fields[$i]") },
            errorPolicy = n.get("errorPolicy")?.let { enumOf<MappingErrorPolicy>(it, "$.errorPolicy") } ?: MappingErrorPolicy.NULL_FIELD,
            version = n.get("version")?.let { if (!it.isIntegralNumber || it.asLong() < 1) throw bad("$.version", "must be a positive integer"); it.asLong() } ?: 1
        )
    }

    fun viewModel(n: JsonNode): ViewModelDefinition = guarded("view model") {
        obj(n, "$", VM_KEYS)
        val fields = array(n, "fields", "$.fields", 1, 200)
        ViewModelDefinition(
            id = str(n, "id", "$.id") ?: throw bad("$.id", "is required"), name = str(n, "name", "$.name"), queryRef = str(n, "queryRef", "$.queryRef"),
            mappingRef = str(n, "mappingRef", "$.mappingRef"),
            cardinality = n.get("cardinality")?.let { enumOf<Cardinality>(it, "$.cardinality") } ?: Cardinality.LIST,
            fields = DataJson.elements(fields).mapIndexed { i, f ->
                val p = "$.fields[$i]"; obj(f, p, VMF_KEYS)
                ViewModelField(str(f, "name", "$p.name") ?: throw bad("$p.name", "is required"), f.get("type")?.let { enumOf<FieldType>(it, "$p.type") } ?: FieldType.STRING, str(f, "label", "$p.label"))
            }
        )
    }

    private fun field(f: JsonNode, p: String): FieldMapping {
        obj(f, p, FIELD_KEYS)
        val transforms = transforms(f, p)
        val default = f.get("default")?.takeUnless { it.isNull }
        val nullable = f.get("nullable")?.let { if (!it.isBoolean) throw bad("$p.nullable", "must be true or false"); it.asBoolean() } ?: true
        return FieldMapping(str(f, "from", "$p.from"), str(f, "to", "$p.to") ?: throw bad("$p.to", "is required"), transforms, default, nullable, f.get("validation")?.let { validation(it, "$p.validation") })
    }

    /**
     * Canonical key `transforms` = an array of at most 8 transform objects, applied in order. The LEGACY key `transform` (C3's first reader: one
     * object, or an array) is still read and normalised to the same list; a field that carries both keys, or an explicit `null` for either, is
     * rejected as ambiguous. Same rules as C2's reader (`AppDefinitionReader.transforms`), so a document that passes one passes the other.
     */
    private fun transforms(f: JsonNode, p: String): List<Transform> {
        val canonical = f.get("transforms"); val legacy = f.get("transform")
        if (canonical != null && legacy != null) throw bad("$p.transform", "ambiguous: use either 'transforms' (canonical) or the legacy 'transform', not both")
        val key = if (canonical != null) "transforms" else "transform"
        val node = canonical ?: legacy ?: return emptyList()
        if (node.isNull) throw bad("$p.$key", "must not be null")
        if (key == "transforms" && !node.isArray) throw bad("$p.transforms", "must be an array of transform objects")
        if (!node.isArray && !node.isObject) throw bad("$p.$key", "must be a transform object or an array of them")
        if (node.isArray && node.size() > FieldMapping.MAX_TRANSFORMS) throw bad("$p.$key", "at most ${FieldMapping.MAX_TRANSFORMS} transforms")
        val items = if (node.isArray) DataJson.elements(node) else listOf(node)
        return items.mapIndexed { i, t ->
            val tp = if (node.isArray) "$p.$key[$i]" else "$p.$key"
            try { Transforms.parse(t) } catch (e: IllegalArgumentException) { throw bad(tp, e.message ?: "is invalid") }
        }
    }

    // ---------------------------------------------------------------------------------------------- writer (canonical shape only)

    /** Canonical JSON of a mapping: fields carry `transforms[]` (never the legacy `transform`); `from`/`default`/`validation` only when set. */
    fun toNode(m: MappingDefinition): JsonNode {
        val o = LinkedHashMap<String, Any?>()
        o["id"] = m.id; m.name?.let { o["name"] = it }; m.queryRef?.let { o["queryRef"] = it }
        o["errorPolicy"] = m.errorPolicy.name; o["version"] = m.version
        o["fields"] = m.fields.map { f ->
            val fo = LinkedHashMap<String, Any?>()
            f.from?.let { fo["from"] = it }; fo["to"] = f.to
            if (f.transforms.isNotEmpty()) fo["transforms"] = Transforms.toNodes(f.transforms)
            f.default?.let { fo["default"] = it }
            fo["nullable"] = f.nullable
            f.validation?.let { v -> fo["validation"] = validationNode(v) }
            fo
        }
        return DataJson.toNode(o)
    }

    fun toNode(v: ViewModelDefinition): JsonNode {
        val o = LinkedHashMap<String, Any?>()
        o["id"] = v.id; v.name?.let { o["name"] = it }; v.queryRef?.let { o["queryRef"] = it }; v.mappingRef?.let { o["mappingRef"] = it }
        o["cardinality"] = v.cardinality.name
        o["fields"] = v.fields.map { f -> linkedMapOf<String, Any?>("name" to f.name, "type" to f.type.name).also { m -> f.label?.let { m["label"] = it } } }
        return DataJson.toNode(o)
    }

    private fun validationNode(v: FieldValidation): Map<String, Any?> {
        val o = LinkedHashMap<String, Any?>()
        v.minLength?.let { o["minLength"] = it }; v.maxLength?.let { o["maxLength"] = it }; v.min?.let { o["min"] = it }; v.max?.let { o["max"] = it }
        v.oneOf?.let { o["oneOf"] = it }; v.format?.let { o["format"] = it.name }
        return o
    }

    private fun validation(v: JsonNode, p: String): FieldValidation {
        obj(v, p, VALIDATION_KEYS)
        fun int(k: String) = v.get(k)?.let { if (!it.isIntegralNumber) throw bad("$p.$k", "must be an integer"); it.asInt() }
        fun dec(k: String): BigDecimal? = v.get(k)?.let { if (!it.isNumber) throw bad("$p.$k", "must be a number"); it.decimalValue() }
        val oneOf = v.get("oneOf")?.let { if (!it.isArray) throw bad("$p.oneOf", "must be an array"); DataJson.elements(it) }
        val format = v.get("format")?.let { enumOf<ValueFormat>(it, "$p.format") }
        return FieldValidation(int("minLength"), int("maxLength"), dec("min"), dec("max"), oneOf, format)
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private inline fun <T> guarded(what: String, block: () -> T): T = try { block() } catch (e: IllegalArgumentException) {
        if (e is BadDef) throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "invalid $what: ${e.message}".take(300))
        throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "invalid $what: ${e.message ?: "rejected"}".take(300))
    }

    private class BadDef(msg: String) : IllegalArgumentException(msg)
    private fun bad(path: String, msg: String): IllegalArgumentException = BadDef("$path $msg")

    private fun obj(n: JsonNode?, path: String, allowed: Set<String>) {
        if (n == null || !n.isObject) throw bad(path, "must be an object")
        DataJson.keys(n).firstOrNull { it !in allowed }?.let { throw bad("$path.${it.take(40)}", "is not a known key") }
    }
    private fun array(n: JsonNode, key: String, path: String, min: Int, max: Int): JsonNode {
        val a = n.get(key)
        if (a == null || !a.isArray || a.size() !in min..max) throw bad(path, "must be an array of $min..$max items")
        return a
    }
    private fun str(n: JsonNode, key: String, path: String): String? {
        val v = n.get(key) ?: return null
        if (v.isNull) return null
        if (!DataJson.isText(v)) throw bad(path, "must be text")
        return DataJson.text(v).also { if (it.length > 2_000) throw bad(path, "is too long") }
    }
    private inline fun <reified E : Enum<E>> enumOf(n: JsonNode, path: String): E {
        if (!DataJson.isText(n)) throw bad(path, "must be text")
        val s = DataJson.text(n)
        return enumValues<E>().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: throw bad(path, "is not one of ${enumValues<E>().joinToString("/") { it.name }}")
    }
}
