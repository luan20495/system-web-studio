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
    val index: Int? = null,
    /** multi-page sites: the page an ADD_SECTION goes to (default: home) and the page of ADD_PAGE / UPDATE_PAGE / REMOVE_PAGE */
    val pageId: String? = null,
    /**
     * AppDefinition V2 operations (see [OperationTypes.definitions]): the id of the definition an UPDATE_* / REMOVE_* works on, and the
     * definition (ADD_*: the whole object, UPDATE_*: the fields to change, null = clear the field). Plain JSON data like every other field:
     * the reader of the app definition validates it afterwards and rejects URLs, SQL, credentials and code.
     */
    val definitionId: String? = null,
    val definition: JsonNode? = null
)

object OperationTypes {
    val sections = setOf("ADD_SECTION", "REMOVE_SECTION", "MOVE_SECTION", "UPDATE_SECTION", "UPDATE_PROP", "ADD_ITEM", "REMOVE_ITEM")
    /** site structure (stage G): pages, navigation, site settings */
    val site = setOf("ADD_PAGE", "UPDATE_PAGE", "REMOVE_PAGE", "SET_NAVIGATION", "UPDATE_SITE")
    /** the operations of the Page Schema; unchanged (the AI editor, the Builder and old clients only know these) */
    val all = sections + site

    /**
     * AppDefinition V2 (D-C2-07): one ADD / UPDATE / REMOVE triple per typed collection, plus UPDATE of the two singletons.
     * Data sources (B-C0-W-07) are SLOT DECLARATIONS only: `{id, name, type, description}`. A slot never carries `sourceRef`, a credential or any
     * connection detail: which tenant data source fills a slot is granted by the data platform (Management API `data-bindings`), never by an edit.
     */
    val definitions = setOf(
        "ADD_DATA_SOURCE", "UPDATE_DATA_SOURCE", "REMOVE_DATA_SOURCE",
        "ADD_VIEW_MODEL", "UPDATE_VIEW_MODEL", "REMOVE_VIEW_MODEL",
        "ADD_QUERY", "UPDATE_QUERY", "REMOVE_QUERY",
        "ADD_MAPPING", "UPDATE_MAPPING", "REMOVE_MAPPING",
        "ADD_DATA_BINDING", "UPDATE_DATA_BINDING", "REMOVE_DATA_BINDING",
        "ADD_ACTION", "UPDATE_ACTION", "REMOVE_ACTION",
        "ADD_WORKFLOW_REF", "UPDATE_WORKFLOW_REF", "REMOVE_WORKFLOW_REF",
        "ADD_PERMISSION_REF", "UPDATE_PERMISSION_REF", "REMOVE_PERMISSION_REF",
        "UPDATE_THEME", "UPDATE_PUBLISH_CONFIG"
    )

    /** the data source SLOT operations: an author's decision, never an AI proposal (the planner keeps its pre-B-C0-W-07 vocabulary) */
    val dataSourceSlots = setOf("ADD_DATA_SOURCE", "UPDATE_DATA_SOURCE", "REMOVE_DATA_SOURCE")

    /** everything the patch engine and PATCH /schema accept */
    val allV2 = all + definitions

    /** what the AI planner may even name: everything except the data source slot operations */
    val planner = allV2 - dataSourceSlots
}
