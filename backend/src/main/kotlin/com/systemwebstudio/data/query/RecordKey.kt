package com.systemwebstudio.data.query

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.LongNode

/**
 * FQ-ACT-02 - the logical record identifier of UPDATE_RECORD / DELETE_RECORD.
 *
 * C4's actions name the row they change with the input `recordId`. That name is a LOGICAL identifier: it says "the key of the record", not which physical column holds
 * it. The physical key is already part of the approved mutation (the connector's reading of `target`, for PostgreSQL `shop.orders key=id`), so the mapping is
 * `recordId` -> the key parameter THAT DEFINITION names, resolved here before the parameters are validated. Nothing is invented:
 *
 *  - the key parameter is never taken from the request; it comes from the approved, connector-validated definition ([com.systemwebstudio.data.datasource.MutationExecutor.recordKey]),
 *    and the column it stands for is quoted and validated by the connector exactly as before (no identifier ever comes from a caller);
 *  - only UPDATE and DELETE, only when `recordId` is given, only when the definition does not itself declare a parameter named `recordId` (an operation that does keeps its
 *    own meaning: nothing that works today changes), only when the connector reports exactly ONE key that is a declared parameter;
 *  - otherwise nothing is changed and the usual validation answers (`recordId` is an unknown parameter: INVALID_PARAMS, definite, nothing executed);
 *  - giving the key twice (`recordId` and the key parameter itself) is ambiguous and refused;
 *  - a whole-number text identifier is converted for an INTEGER key (`"42"` -> 42): the logical identifier is a string, a bigint key is not.
 */
object RecordKey {
    const val LOGICAL = "recordId"
    private val INTEGER_TEXT = Regex("^-?[0-9]{1,18}$")

    fun resolve(def: MutationDefinition, keyParam: String?, given: Map<String, JsonNode>): Map<String, JsonNode> {
        if (LOGICAL !in given) return given
        if (def.kind != MutationKind.UPDATE && def.kind != MutationKind.DELETE) return given
        if (def.params.any { it.name == LOGICAL }) return given
        val key = keyParam ?: return given
        val spec = def.params.firstOrNull { it.name == key } ?: return given
        if (key in given) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "the record key was given twice")
        val raw = given.getValue(LOGICAL)
        val value = if (spec.type == ParamType.INTEGER && DataJson.isText(raw) && INTEGER_TEXT.matches(raw.asString())) LongNode.valueOf(raw.asString().toLong()) else raw
        return LinkedHashMap(given).also { it.remove(LOGICAL); it[key] = value }
    }
}
