package com.systemwebstudio.schema

import tools.jackson.databind.JsonNode

/**
 * Structured, declarative edit. There is deliberately no field that can carry code:
 * values are plain JSON data validated against the component registry afterwards.
 */
data class SchemaOperation(
    val type: String,
    val sectionId: String? = null,
    val sectionType: String? = null,
    val itemId: String? = null,
    val arrayPath: String? = null,
    val path: String? = null,
    val value: JsonNode? = null,
    val item: JsonNode? = null,
    val props: JsonNode? = null,
    val beforeSectionId: String? = null,
    val afterSectionId: String? = null,
    val index: Int? = null
)

object OperationTypes {
    val all = setOf("ADD_SECTION", "REMOVE_SECTION", "MOVE_SECTION", "UPDATE_SECTION", "UPDATE_PROP", "ADD_ITEM", "REMOVE_ITEM")
}
