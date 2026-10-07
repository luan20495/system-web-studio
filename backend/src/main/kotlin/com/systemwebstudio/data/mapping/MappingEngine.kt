package com.systemwebstudio.data.mapping

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.QueryResult
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Checks a mapping against the view model it feeds, before any data flows. Messages carry field names only.
 * Rules: target names unique; with a view model, mapped targets == view model fields (no hole, no stray column — the UI never sees a column
 * the view model did not declare), and a NUMBER/… field is not fed by a transform that can only produce something else.
 */
object MappingValidator {
    fun problems(mapping: MappingDefinition, vm: ViewModelDefinition?): List<String> {
        val out = ArrayList<String>()
        val to = mapping.fields.map { it.to }
        to.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { out += "target field '$it' is mapped more than once" }
        if (vm != null) {
            val declared = vm.fields.associateBy { it.name }
            to.filter { it !in declared }.distinct().forEach { out += "target field '$it' is not declared by view model '${vm.id}'" }
            vm.fields.filter { it.name !in to }.forEach { out += "view model field '${it.name}' has no mapping" }
            if (vm.mappingRef != null && vm.mappingRef != mapping.id) out += "view model '${vm.id}' refers to mapping '${vm.mappingRef.take(64)}', not '${mapping.id}'"
            if (vm.queryRef != null && mapping.queryRef != null && vm.queryRef != mapping.queryRef) out += "view model and mapping refer to different queries"
        }
        return out
    }

    fun require(mapping: MappingDefinition, vm: ViewModelDefinition?) {
        val p = problems(mapping, vm)
        if (p.isNotEmpty()) throw ConnectorFailure(FailureCodes.INVALID_MAPPING, p.take(5).joinToString("; ").take(500))
    }
}

/**
 * Turns the raw rows of a query into a [ViewModelData]. Pure and stateless (thread-safe): the formulas were compiled when the mapping was
 * parsed, nothing is evaluated that the closed [Transform] set does not describe, and every outcome that involves a value is reported by
 * field name and count only.
 */
class MappingEngine {

    fun apply(result: QueryResult, mapping: MappingDefinition, vm: ViewModelDefinition? = null): ViewModelData {
        MappingValidator.require(mapping, vm)
        val infoOrder: List<Pair<String, FieldType?>> = vm?.fields?.map { it.name to it.type } ?: mapping.fields.map { it.to to null }
        val byTarget = mapping.fields.associateBy { it.to }
        val ordered = infoOrder.map { (name, type) -> Triple(byTarget.getValue(name), type, name) }
        val warnings = LinkedHashMap<String, Int>()   // "field: reason" -> count
        fun warn(field: String, reason: String) { warnings.merge("$field: $reason", 1, Int::plus) }

        val known = result.columns.map { it.name }.toSet()
        if (result.columns.isNotEmpty()) {
            for ((fm, _, name) in ordered) {
                val root = fm.from?.let { f -> if (f in known) f else f.substringBefore('.') }
                if (root != null && root !in known) warn(name, "source column is not in the result")
            }
        }

        val sourceRows = if (vm?.cardinality == Cardinality.SINGLE) result.rows.take(1) else result.rows
        if (vm?.cardinality == Cardinality.SINGLE && result.rows.size > 1) warn("(result)", "more than one row, the first was used")

        val out = ArrayList<Map<String, JsonNode>>(sourceRows.size.coerceAtMost(1_000))
        var skipped = 0
        rows@ for (row in sourceRows) {
            if (out.size >= MAX_ROWS) { warn("(result)", "output was cut at $MAX_ROWS rows"); break }
            val mapped = LinkedHashMap<String, JsonNode>(ordered.size * 2)
            for ((fm, type, name) in ordered) {
                val v = try { field(fm, type, row) } catch (e: ValueError) {
                    when (mapping.errorPolicy) {
                        MappingErrorPolicy.FAIL -> throw ConnectorFailure(FailureCodes.MAPPING_FAILED, "mapping failed for field '$name': ${e.reason}")
                        MappingErrorPolicy.SKIP_ROW -> { warn(name, e.reason); skipped++; continue@rows }
                        MappingErrorPolicy.NULL_FIELD -> {
                            warn(name, e.reason)
                            if (!fm.nullable) { skipped++; continue@rows }
                            DataJson.NULL
                        }
                    }
                }
                mapped[name] = v
            }
            out += mapped
        }
        val cardinality = vm?.cardinality ?: Cardinality.LIST
        val info = ordered.map { (fm, type, name) -> ViewModelFieldInfo(name, type, fm.nullable) }
        val texts = warnings.map { (k, n) -> if (n == 1) k else "$k ($n rows)" }.take(MAX_WARNINGS)
        return ViewModelData(vm?.id, cardinality, info, out, result.truncated, texts, skipped)
    }

    private fun field(fm: FieldMapping, type: FieldType?, row: Map<String, JsonNode>): JsonNode {
        val raw: JsonNode? = fm.from?.let { Paths.resolve(row, it) }?.takeUnless { it.isNull || it.isMissingNode }
        var current: Any? = null
        var passthrough: JsonNode? = null
        if (raw != null) {
            if (DataJson.isContainer(raw)) {
                if (fm.transforms.isNotEmpty()) throw ValueError("transform input is not a plain value")
                passthrough = raw
            } else current = try { Expression.scalar(raw) } catch (e: ExpressionException) { throw ValueError("source value out of range") }
        }
        if (passthrough == null) {
            for (t in fm.transforms) {
                current = try { Transforms.apply(t, current, row) } catch (e: ExpressionException) { throw ValueError("${t.name}: ${e.message}") }
                if (current is JsonNode) { // an array produced by split: only legal as the last step
                    if (t !== fm.transforms.last()) throw ValueError("${t.name}: result is not a plain value")
                    passthrough = current as JsonNode; break
                }
            }
        }
        if (passthrough == null && current == null && fm.default != null) current = Expression.scalar(fm.default)
        val node = if (passthrough != null) typed(passthrough, type) else typed(current, type)
        if (node.isNull) { if (!fm.nullable) throw ValueError("value is required"); return node }
        fm.validation?.let { validate(it, node) }
        return node
    }

    /** coerces to the declared type (when there is one) and builds the JSON value; a value that does not fit is a [ValueError] */
    private fun typed(v: Any?, type: FieldType?): JsonNode {
        if (v == null) return DataJson.NULL
        if (v is JsonNode) {
            return when (type) {
                null -> v
                FieldType.OBJECT -> if (v.isObject) v else throw ValueError("expected an object")
                FieldType.ARRAY -> if (v.isArray) v else throw ValueError("expected an array")
                else -> throw ValueError("expected a ${type.name.lowercase()}")
            }
        }
        return when (type) {
            null -> plain(v)
            FieldType.STRING -> DataJson.toNode(Expression.text(v) ?: throw ValueError("expected text"))
            FieldType.NUMBER -> number(Expression.toNum(v) ?: throw ValueError("expected a number"))
            FieldType.BOOLEAN -> DataJson.toNode(Expression.toBool(v) ?: throw ValueError("expected true or false"))
            FieldType.DATE -> DataJson.toNode(date(v))
            FieldType.DATETIME -> DataJson.toNode(Transforms.parseIso(Expression.text(v) ?: throw ValueError("expected a date and time"), ZoneOffset.UTC).toString())
            FieldType.OBJECT, FieldType.ARRAY -> throw ValueError("expected ${type.name.lowercase()}")
        }
    }

    private fun date(v: Any): String {
        val s = Expression.text(v) ?: throw ValueError("expected a date")
        return runCatching { LocalDate.parse(s.trim()) }.getOrNull()?.toString()
            ?: Transforms.parseIso(s, ZoneOffset.UTC).atZone(ZoneOffset.UTC).toLocalDate().toString()
    }

    private fun plain(v: Any): JsonNode = when (v) {
        is BigDecimal -> number(v)
        is Boolean, is String -> DataJson.toNode(v)
        else -> throw ValueError("not a plain value")
    }

    /** integral values become JSON integers, the rest keep their exact decimal */
    private fun number(n: BigDecimal): JsonNode {
        val s = n.stripTrailingZeros()
        return if (s.scale() <= 0 && s.precision() <= 18) DataJson.toNode(s.toLong()) else DataJson.toNode(s)
    }

    private fun validate(v: FieldValidation, node: JsonNode) {
        if (v.oneOf != null && v.oneOf.none { same(it, node) }) throw ValueError("value is not one of the allowed values")
        if (DataJson.isText(node)) {
            val s = DataJson.text(node)
            if (v.minLength != null && s.length < v.minLength) throw ValueError("value is too short")
            if (v.maxLength != null && s.length > v.maxLength) throw ValueError("value is too long")
            v.format?.let { f -> if (!Formats.matches(f, s)) throw ValueError("value does not have the expected format") }
        } else if (v.format == ValueFormat.INTEGER) {
            if (!(node.isNumber && node.decimalValue().stripTrailingZeros().scale() <= 0)) throw ValueError("value does not have the expected format")
        } else if (v.format != null) throw ValueError("value does not have the expected format")
        if (node.isNumber) {
            val d = node.decimalValue()
            if (v.min != null && d < v.min) throw ValueError("value is below the minimum")
            if (v.max != null && d > v.max) throw ValueError("value is above the maximum")
        }
    }

    private fun same(a: JsonNode, b: JsonNode): Boolean = when {
        a.isNumber && b.isNumber -> a.decimalValue().compareTo(b.decimalValue()) == 0
        else -> a == b
    }

    companion object { const val MAX_ROWS = 10_000; const val MAX_WARNINGS = 50 }
}

/** format checks written without backtracking-prone patterns; every input is length-bounded first */
internal object Formats {
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(\\.[A-Za-z0-9-]{1,63}){1,5}$")
    fun matches(f: ValueFormat, s: String): Boolean {
        if (s.length > 2_000) return false
        return when (f) {
            ValueFormat.UUID -> UUID.matches(s)
            ValueFormat.EMAIL -> s.length <= 254 && EMAIL.matches(s)
            ValueFormat.URL -> s.length <= 2_000 && (s.startsWith("https://") || s.startsWith("http://")) && runCatching { java.net.URI(s).host != null }.getOrDefault(false)
            ValueFormat.DATE -> runCatching { LocalDate.parse(s) }.isSuccess
            ValueFormat.DATETIME -> runCatching { Transforms.parseIso(s, ZoneOffset.UTC) }.isSuccess
            ValueFormat.INTEGER -> s.length <= 40 && s.matches(Regex("^-?[0-9]{1,38}$"))
        }
    }
}
