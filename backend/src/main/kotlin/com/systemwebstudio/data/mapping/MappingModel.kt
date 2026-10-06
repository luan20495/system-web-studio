package com.systemwebstudio.data.mapping

import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/*
 * T10 canonical contract, reconciled with C2's AppDefinitionV2 (`app/definition/AppDefinitionModel.kt`, branch agent/c2-app-model):
 *
 *   C2 `FieldType`        = this `FieldType`          (same seven names)
 *   C2 `Cardinality`      = this `Cardinality`        (SINGLE | LIST)
 *   C2 `ViewModelFieldDef(name, type, label)`         = [ViewModelField]      (identical shape)
 *   C2 `ViewModelDef(id, name, queryRef, mappingRef, cardinality, fields)` = [ViewModelDefinition] (identical shape)
 *   C2 `MappingDef(id, name, queryRef, fields)`       = [MappingDefinition]   (+ optional `errorPolicy`, `version`)
 *   C2 `FieldMappingDef(from, to)`                    = [FieldMapping]        (+ optional `transforms[]`, `default`, `nullable`, `validation`; legacy key `transform` is read, never written)
 *
 * Everything C3 adds is OPTIONAL and additive, so a mapping written against C2's minimal shape (`{from, to}`) is valid here unchanged.
 * The one thing C2 must do is let the five extra keys through its reader (BLOCKERS B-C3-06); `MappingJson` parses the whole shape.
 * Declarative only: nothing here holds code; the only computed values are the allowlisted `formula` expressions of [Transform].
 */
enum class FieldType { STRING, NUMBER, BOOLEAN, DATE, DATETIME, OBJECT, ARRAY }

enum class Cardinality { SINGLE, LIST }

/**
 * What to do with a value that cannot be mapped (cannot be converted, fails validation, or is missing though not nullable):
 * [FAIL] stops the whole mapping; [NULL_FIELD] (default) sets a nullable field to null and drops the row when the field is not nullable;
 * [SKIP_ROW] drops the row. Every case is counted in [ViewModelData.warnings] (field names and counts, never values).
 */
enum class MappingErrorPolicy { FAIL, NULL_FIELD, SKIP_ROW }

enum class ValueFormat { EMAIL, URL, UUID, DATE, DATETIME, INTEGER }

/** Closed set of checks — deliberately no free-form regex (a user-supplied pattern is a ReDoS vector). */
data class FieldValidation(
    val minLength: Int? = null, val maxLength: Int? = null, val min: BigDecimal? = null, val max: BigDecimal? = null,
    val oneOf: List<JsonNode>? = null, val format: ValueFormat? = null
) {
    init {
        require(minLength == null || minLength in 0..100_000) { "invalid minLength" }
        require(maxLength == null || maxLength in 0..100_000) { "invalid maxLength" }
        require(oneOf == null || (oneOf.size in 1..200 && oneOf.all { it.isValueNode })) { "invalid oneOf" }
    }
}

/**
 * One column of the view model: where the value comes from ([from]: a column or dotted path of the query result, absent for a constant or a
 * computed value), the [transforms] applied in order, the [default] used when the result is null, whether null is acceptable, and the checks.
 */
data class FieldMapping(
    val from: String?, val to: String, val transforms: List<Transform> = emptyList(), val default: JsonNode? = null,
    val nullable: Boolean = true, val validation: FieldValidation? = null
) {
    init {
        require(TO.matches(to)) { "invalid target field name" }
        require(from == null || FROM.matches(from)) { "invalid source path" }
        require(transforms.size <= MAX_TRANSFORMS) { "too many transforms" }
        require(from != null || transforms.isNotEmpty() || default != null) { "a field needs a source, a transform or a default" }
        require(default == null || default.isValueNode) { "default must be a plain value" }
    }
    companion object {
        const val MAX_TRANSFORMS = 8
        val TO = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")
        /** a column name (letters, digits, `_`, `-`, space) optionally followed by `.segments` (object keys or array indexes) */
        val FROM = Regex("^[A-Za-z0-9_][A-Za-z0-9_ -]{0,63}(\\.[A-Za-z0-9_][A-Za-z0-9_ -]{0,63}){0,7}$")
    }
}

data class MappingDefinition(
    val id: String, val name: String? = null, val queryRef: String? = null, val fields: List<FieldMapping>,
    val errorPolicy: MappingErrorPolicy = MappingErrorPolicy.NULL_FIELD, val version: Long = 1
) {
    init {
        require(ID.matches(id)) { "invalid mapping id" }
        require(fields.size in 1..200) { "a mapping needs 1..200 fields" }
    }
    companion object { val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$") }
}

data class ViewModelField(val name: String, val type: FieldType = FieldType.STRING, val label: String? = null) {
    init { require(FieldMapping.TO.matches(name)) { "invalid view model field name" }; require(label == null || label.length <= 120) { "label too long" } }
}

/** The UI-facing shape. A binding/section talks to this and never to the raw column names of a data source. */
data class ViewModelDefinition(
    val id: String, val name: String? = null, val queryRef: String? = null, val mappingRef: String? = null,
    val cardinality: Cardinality = Cardinality.LIST, val fields: List<ViewModelField>
) {
    init {
        require(MappingDefinition.ID.matches(id)) { "invalid view model id" }
        require(fields.size in 1..200 && fields.map { it.name }.toSet().size == fields.size) { "a view model needs 1..200 distinct fields" }
    }
}

/** [nullable] comes from the mapping, which is the single place that decides it. [type] is null when no view model constrained the field. */
data class ViewModelFieldInfo(val name: String, val type: FieldType?, val nullable: Boolean)

/**
 * What the UI receives (contract: `ViewModelData`). Keys of every row are the view model's field names; nothing of the raw source row is
 * carried along. [truncated] = the source result was cut by a row/byte cap; [skippedRows] = rows dropped by the error policy.
 */
data class ViewModelData(
    val viewModelId: String?, val cardinality: Cardinality, val fields: List<ViewModelFieldInfo>, val rows: List<Map<String, JsonNode>>,
    val truncated: Boolean, val warnings: List<String> = emptyList(), val skippedRows: Int = 0
)
