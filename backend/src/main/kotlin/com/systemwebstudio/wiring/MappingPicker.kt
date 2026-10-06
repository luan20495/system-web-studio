package com.systemwebstudio.wiring

import tools.jackson.databind.JsonNode

/**
 * C0 · R1 helper. A browser names a query by its LOCAL id; the mapping that shapes its rows is the one whose `queryRef` is that id. Exactly one such mapping
 * is unambiguous; none or several -> `null` and the caller answers `422 MAPPING_REF_REQUIRED` (the client must then say which one). Pure.
 */
object MappingPicker {
    fun pick(document: JsonNode, queryId: String): String? {
        val mappings = document.get("mappings") ?: return null
        if (!mappings.isArray) return null
        var found: String? = null
        for (i in 0 until mappings.size()) {
            val m = mappings.get(i) ?: continue
            val ref = m.get("queryRef")
            val id = m.get("id")
            if (ref == null || !ref.isString || ref.asString() != queryId) continue
            if (id == null || !id.isString) continue
            if (found != null) return null            // ambiguous
            found = id.asString()
        }
        return found
    }
}
