package com.systemwebstudio.data.mapping

import com.systemwebstudio.data.query.DataJson
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The transform DSL (T10). Declarative JSON in, a closed set of operations out — nothing here evaluates user code except
 * [Formula], whose language is the allowlisted [Expression]. Operations: `toString`, `toNumber`, `toBoolean`, `date`, `enumMap`,
 * `join`, `split`, `formula` and the three text helpers `trim`, `lower`, `upper`.
 * A transform that does not apply to a null value leaves it null; [Join] and [Formula] always run (they read the whole row).
 */
sealed interface Transform {
    val name: String

    data object ToStringT : Transform { override val name = "toString" }
    data object ToNumber : Transform { override val name = "toNumber" }
    data object Trim : Transform { override val name = "trim" }
    data object Lower : Transform { override val name = "lower" }
    data object Upper : Transform { override val name = "upper" }

    data class ToBoolean(val trueValues: Set<String>, val falseValues: Set<String>) : Transform { override val name = "toBoolean" }

    /** [input]: null/"iso" (auto-detect ISO forms), "epochSeconds", "epochMillis" or a java.time pattern; [output]: "iso", "date", "epochSeconds", "epochMillis" or a pattern */
    data class DateT(val input: String?, val output: String, val zone: String) : Transform { override val name = "date" }

    data class EnumMap(val map: Map<String, JsonNode>, val hasDefault: Boolean, val default: JsonNode?, val strict: Boolean, val ignoreCase: Boolean) : Transform { override val name = "enumMap" }

    data class Join(val fields: List<String>, val separator: String, val skipNull: Boolean) : Transform { override val name = "join" }

    /** [index] null = return all parts as an array */
    data class Split(val separator: String, val index: Int?) : Transform { override val name = "split" }

    data class Formula(val expression: Expression) : Transform { override val name = "formula" }
}

/** a value that cannot be mapped: carries a fixed reason, never the value */
internal class ValueError(val reason: String) : RuntimeException(reason, null, false, false)

object Transforms {
    private val DATE_PATTERN_CHARS = Regex("^[yMdHhmsSAnaEXxZzVOKkGuQqLecW'.,:/ T-]{1,40}$")
    private val KEYS = mapOf(
        "toString" to setOf("type"), "toNumber" to setOf("type"), "trim" to setOf("type"), "lower" to setOf("type"), "upper" to setOf("type"),
        "toBoolean" to setOf("type", "trueValues", "falseValues"), "date" to setOf("type", "input", "output", "zone"),
        "enumMap" to setOf("type", "map", "default", "strict", "ignoreCase"), "join" to setOf("type", "fields", "separator", "skipNull"),
        "split" to setOf("type", "separator", "index"), "formula" to setOf("type", "expression")
    )
    val types: Set<String> get() = KEYS.keys

    /** @throws IllegalArgumentException with a fixed message (the callers turn it into INVALID_MAPPING naming the field) */
    fun parse(node: JsonNode): Transform {
        require(node.isObject) { "a transform must be an object" }
        val type = node.get("type")?.takeIf { DataJson.isText(it) }?.let { DataJson.text(it) } ?: throw IllegalArgumentException("a transform needs a type")
        val allowed = KEYS[type] ?: throw IllegalArgumentException("unknown transform '${type.take(30)}'")
        require(DataJson.keys(node).all { it in allowed }) { "transform '$type' has an unknown key" }
        return when (type) {
            "toString" -> Transform.ToStringT
            "toNumber" -> Transform.ToNumber
            "trim" -> Transform.Trim
            "lower" -> Transform.Lower
            "upper" -> Transform.Upper
            "toBoolean" -> Transform.ToBoolean(
                words(node.get("trueValues"), setOf("true", "yes", "y", "1", "on")), words(node.get("falseValues"), setOf("false", "no", "n", "0", "off"))
            ).also { require(it.trueValues.intersect(it.falseValues).isEmpty()) { "toBoolean true and false values overlap" } }
            "date" -> {
                val input = node.get("input")?.let { text(it, "date input") }
                val output = node.get("output")?.let { text(it, "date output") } ?: "iso"
                val zone = node.get("zone")?.let { text(it, "date zone") } ?: "UTC"
                require(runCatching { ZoneId.of(zone) }.isSuccess) { "date zone is not a valid zone id" }
                if (input != null && input !in setOf("iso", "epochSeconds", "epochMillis")) pattern(input)
                if (output !in setOf("iso", "date", "epochSeconds", "epochMillis")) pattern(output)
                Transform.DateT(input?.takeIf { it != "iso" }, output, zone)
            }
            "enumMap" -> {
                val m = node.get("map")
                require(m != null && m.isObject && DataJson.keys(m).size in 1..200) { "enumMap needs a map of 1..200 entries" }
                val map = LinkedHashMap<String, JsonNode>()
                for (k in DataJson.keys(m)) { require(k.length <= 100 && m.get(k).isValueNode) { "enumMap values must be plain values" }; map[k] = m.get(k) }
                val hasDefault = node.has("default")
                val default = node.get("default")?.also { require(it.isValueNode) { "enumMap default must be a plain value" } }
                Transform.EnumMap(map, hasDefault, default, flag(node, "strict"), flag(node, "ignoreCase"))
            }
            "join" -> {
                val f = node.get("fields")
                require(f != null && f.isArray && f.size() in 1..10 && DataJson.elements(f).all { DataJson.isText(it) && FieldMapping.FROM.matches(DataJson.text(it)) }) { "join needs 1..10 source paths" }
                val sep = node.get("separator")?.let { text(it, "join separator") } ?: ""
                require(sep.length <= 10) { "join separator is too long" }
                Transform.Join(DataJson.elements(f).map { DataJson.text(it) }, sep, node.get("skipNull")?.let { it.isBoolean && it.asBoolean() } ?: true)
            }
            "split" -> {
                val sep = node.get("separator")?.let { text(it, "split separator") } ?: throw IllegalArgumentException("split needs a separator")
                require(sep.length in 1..5) { "split separator must be 1..5 characters" }
                val idx = node.get("index")?.let { require(it.isIntegralNumber && it.asInt() in 0..99) { "split index must be 0..99" }; it.asInt() }
                Transform.Split(sep, idx)
            }
            "formula" -> {
                val e = node.get("expression")?.takeIf { DataJson.isText(it) }?.let { DataJson.text(it) } ?: throw IllegalArgumentException("formula needs an expression")
                try { Transform.Formula(Expression.compile(e)) } catch (x: ExpressionException) { throw IllegalArgumentException("formula: ${x.message}") }
            }
            else -> throw IllegalArgumentException("unknown transform")
        }
    }

    /**
     * Canonical JSON of a parsed transform (the writer side of [parse]; `parse(toNode(t)) == t`). A definition is always WRITTEN as
     * `transforms: [ … ]` of these objects — never under the legacy `transform` key (contract v2, `app-definition.md` §3).
     */
    fun toNode(t: Transform): JsonNode {
        val m = LinkedHashMap<String, Any?>(); m["type"] = t.name
        when (t) {
            is Transform.ToStringT, Transform.ToNumber, Transform.Trim, Transform.Lower, Transform.Upper -> {}
            is Transform.ToBoolean -> { m["trueValues"] = t.trueValues.toList(); m["falseValues"] = t.falseValues.toList() }
            is Transform.DateT -> { t.input?.let { m["input"] = it }; m["output"] = t.output; m["zone"] = t.zone }
            is Transform.EnumMap -> { m["map"] = t.map; if (t.hasDefault) m["default"] = t.default; if (t.strict) m["strict"] = true; if (t.ignoreCase) m["ignoreCase"] = true }
            is Transform.Join -> { m["fields"] = t.fields; m["separator"] = t.separator; m["skipNull"] = t.skipNull }
            is Transform.Split -> { m["separator"] = t.separator; t.index?.let { m["index"] = it } }
            is Transform.Formula -> m["expression"] = t.expression.source
        }
        return DataJson.toNode(m)
    }

    fun toNodes(list: List<Transform>): JsonNode = DataJson.toNode(list.map { toNode(it) })

    /** a single transform object or an array of them (applied in order) */
    fun parseList(node: JsonNode?): List<Transform> = when {
        node == null || node.isNull -> emptyList()
        node.isArray -> { require(node.size() <= 8) { "too many transforms" }; DataJson.elements(node).map { parse(it) } }
        else -> listOf(parse(node))
    }

    private fun text(n: JsonNode, what: String): String { require(DataJson.isText(n)) { "$what must be text" }; return DataJson.text(n) }
    private fun flag(n: JsonNode, key: String) = n.get(key)?.let { it.isBoolean && it.asBoolean() } ?: false
    private fun words(n: JsonNode?, default: Set<String>): Set<String> {
        if (n == null) return default
        require(n.isArray && n.size() in 1..20 && DataJson.elements(n).all { DataJson.isText(it) && DataJson.text(it).length <= 20 }) { "toBoolean values must be a short list of text" }
        return DataJson.elements(n).map { DataJson.text(it).trim().lowercase() }.toSet()
    }
    private fun pattern(p: String): DateTimeFormatter {
        require(DATE_PATTERN_CHARS.matches(p)) { "date pattern has characters that are not allowed" }
        return try { DateTimeFormatter.ofPattern(p, Locale.ENGLISH) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("date pattern is invalid") }
    }

    // ------------------------------------------------------------------------------------------------ execution

    /** @return null, Boolean, BigDecimal, String or (array) JsonNode */
    internal fun apply(t: Transform, v: Any?, row: Map<String, JsonNode>): Any? = when (t) {
        is Transform.Join -> join(t, row)
        is Transform.Formula -> try { t.expression.evaluate(row, v) } catch (e: ExpressionException) { throw ValueError("formula: ${e.message}") }
        else -> if (v == null) (if (t is Transform.EnumMap) enumMap(t, null) else null) else applyToValue(t, v)
    }

    private fun applyToValue(t: Transform, v: Any): Any? = when (t) {
        is Transform.ToStringT -> Expression.text(v) ?: throw ValueError("toString: not a plain value")
        is Transform.ToNumber -> Expression.toNum(v) ?: throw ValueError("toNumber: not a number")
        is Transform.Trim -> textOf(v, "trim").trim()
        is Transform.Lower -> textOf(v, "lower").lowercase()
        is Transform.Upper -> textOf(v, "upper").uppercase()
        is Transform.ToBoolean -> toBoolean(t, v)
        is Transform.DateT -> date(t, v)
        is Transform.EnumMap -> enumMap(t, v)
        is Transform.Split -> split(t, v)
        is Transform.Join, is Transform.Formula -> throw IllegalStateException()
    }

    private fun textOf(v: Any, op: String): String = Expression.text(v) ?: throw ValueError("$op: not text")

    private fun toBoolean(t: Transform.ToBoolean, v: Any): Boolean = when (v) {
        is Boolean -> v
        is BigDecimal -> when { v.compareTo(BigDecimal.ZERO) == 0 -> false; v.compareTo(BigDecimal.ONE) == 0 -> true; else -> throw ValueError("toBoolean: not a boolean") }
        is String -> v.trim().lowercase().let { s -> when (s) { in t.trueValues -> true; in t.falseValues -> false; else -> throw ValueError("toBoolean: not a boolean") } }
        else -> throw ValueError("toBoolean: not a boolean")
    }

    private fun enumMap(t: Transform.EnumMap, v: Any?): Any? {
        val key = v?.let { Expression.text(it) }
        val hit = key?.let { k -> t.map[k] ?: if (t.ignoreCase) t.map.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value else null }
        return when {
            hit != null -> Expression.scalar(hit)
            t.hasDefault -> Expression.scalar(t.default)
            v == null -> null
            t.strict -> throw ValueError("enumMap: value is not in the map")
            else -> v
        }
    }

    private fun join(t: Transform.Join, row: Map<String, JsonNode>): Any? {
        val parts = t.fields.map { p -> Paths.resolve(row, p)?.let { node -> if (DataJson.isContainer(node)) throw ValueError("join: not a plain value") else Expression.scalar(node) }?.let { Expression.text(it) } }
        val used = if (t.skipNull) parts.filterNotNull() else parts.map { it ?: "" }
        if (used.isEmpty()) return null
        val out = used.joinToString(t.separator)
        if (out.length > ExpressionLimits.MAX_STRING) throw ValueError("join: result is too long")
        return out
    }

    private fun split(t: Transform.Split, v: Any): Any? {
        val parts = textOf(v, "split").split(t.separator)
        if (t.index != null) return parts.getOrNull(t.index)
        if (parts.size > 100) throw ValueError("split: too many parts")
        return DataJson.toNode(parts)
    }

    private fun date(t: Transform.DateT, v: Any): Any? {
        val zone = ZoneId.of(t.zone)
        val instant: Instant = when (t.input) {
            "epochSeconds", "epochMillis" -> {
                val n = Expression.toNum(v) ?: throw ValueError("date: not an epoch number")
                val l = runCatching { n.longValueExact() }.getOrNull() ?: throw ValueError("date: not an epoch number")
                runCatching { if (t.input == "epochSeconds") Instant.ofEpochSecond(l) else Instant.ofEpochMilli(l) }.getOrNull() ?: throw ValueError("date: out of range")
            }
            null -> parseIso(textOf(v, "date"), zone)
            else -> {
                val s = textOf(v, "date")
                val f = pattern(t.input)
                val parsed = runCatching { f.parseBest(s, OffsetDateTime::from, LocalDateTime::from, LocalDate::from) }.getOrNull() ?: throw ValueError("date: does not match the pattern")
                when (parsed) {
                    is OffsetDateTime -> parsed.toInstant()
                    is LocalDateTime -> parsed.atZone(zone).toInstant()
                    else -> (parsed as LocalDate).atStartOfDay(zone).toInstant()
                }
            }
        }
        return when (t.output) {
            "iso" -> instant.toString()
            "date" -> instant.atZone(zone).toLocalDate().toString()
            "epochSeconds" -> BigDecimal(instant.epochSecond)
            "epochMillis" -> BigDecimal(instant.toEpochMilli())
            else -> runCatching { instant.atZone(zone).format(pattern(t.output)) }.getOrNull() ?: throw ValueError("date: cannot format")
        }
    }

    internal fun parseIso(s: String, zone: ZoneId): Instant {
        val str = s.trim()
        if (str.length > 40) throw ValueError("date: not a date")
        return runCatching { OffsetDateTime.parse(str).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(str) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(str).atZone(zone).toInstant() }.getOrNull()
            ?: runCatching { LocalDate.parse(str).atStartOfDay(zone).toInstant() }.getOrNull()
            ?: throw ValueError("date: not a date")
    }
}

/** column / dotted-path lookup in a result row; an exact column name wins over path splitting (CSV headers may contain dots) */
internal object Paths {
    fun resolve(row: Map<String, JsonNode>, path: String): JsonNode? {
        row[path]?.let { return it }
        val segs = path.split('.')
        var node: JsonNode? = row[segs[0]]
        for (seg in segs.drop(1)) {
            node = when {
                node == null || node.isNull || node.isMissingNode -> return null
                node.isObject -> node.get(seg)
                node.isArray -> seg.toIntOrNull()?.let { node!!.get(it) }
                else -> return null
            }
        }
        return node
    }
}
