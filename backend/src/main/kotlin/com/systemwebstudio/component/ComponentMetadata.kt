package com.systemwebstudio.component

import com.systemwebstudio.app.definition.ActionType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode

/*
 * Component Registry V2 metadata (D-C2-12). The registry tables keep what they have (id, category, versions, props schema); this adds, as
 * DECLARATIONS, what a designer, the AI planner and the validator need to know about a component:
 *   - which props can be bound to data (and whether they take a list or a single value),
 *   - which UI events it emits and which action types each event supports,
 *   - which prop controls its visibility,
 *   - how to preview it (layout, sample props).
 * A component never contains business logic: events and bindings are names, the runtime (C4 actions, C3 data) does the work.
 *
 * The overlay for the 8 seeded components is code (no migration). A component without an overlay (added later through the registry) gets a
 * DERIVED description from its props schema: array props are bindable lists, text props are bindable values, no events, no actions;
 * validators do not enforce anything for it. When C0 stores richer metadata in the registry tables the DB value takes precedence
 * (supportedActions already reads `component_versions.actions` when it holds action names).
 */

/** An event a component emits (the UI side of an action trigger) and the action types an author may attach to it. */
data class ComponentEventDef(val name: String, val label: String, val supportedActions: List<String>)

/** [cardinality] LIST (an array prop; [itemFields] are the fields of one item) or SINGLE (one value). */
data class BindablePropDef(val prop: String, val cardinality: String, val itemFields: List<String> = emptyList())

/** A boolean prop that shows / hides the component. */
data class VisibilityConditionDef(val prop: String, val kind: String = "BOOLEAN_PROP", val description: String = "")

/** [layout] FULL_WIDTH | CONTAINED; [sampleProps] are props that pass the component's own props schema, for galleries and thumbnails. */
data class ComponentPreviewDef(val layout: String = "FULL_WIDTH", val sampleProps: JsonNode? = null)

data class ComponentMetadataV2(
    val id: String, val version: String, val category: String, val propsSchema: JsonNode,
    val bindableProps: List<BindablePropDef>, val events: List<ComponentEventDef>, val supportedActions: List<String>,
    val visibilityConditions: List<VisibilityConditionDef>, val preview: ComponentPreviewDef,
    /** OVERLAY (declared in code) | DERIVED (from the props schema) */
    val source: String
)

/** What the overlay declares for one seeded component. */
class ComponentOverlay(val bindable: List<BindablePropDef> = emptyList(), val events: List<ComponentEventDef> = emptyList(), val layout: String = "FULL_WIDTH",
                       val sampleProps: String = "{}")

object ComponentOverlays {
    private val NAVIGATE_LIKE = listOf("NAVIGATE", "START_WORKFLOW", "NOTIFY")
    private val CLICK = ComponentEventDef("onClick", "Khi nhấn", NAVIGATE_LIKE + listOf("REFRESH_QUERY", "CALL_API"))
    /** a grid / list: the click is on an item (the item is the event payload); event names are the canonical EventType wire names (contract v2) */
    private val ITEM_CLICK = ComponentEventDef("onClick", "Khi nhấn một mục", NAVIGATE_LIKE + listOf("REFRESH_QUERY", "UPDATE_RECORD", "DELETE_RECORD", "CALL_API"))
    private val SUBMIT = ComponentEventDef("onSubmit", "Khi gửi biểu mẫu",
        listOf("SUBMIT_FORM", "CREATE_RECORD", "UPDATE_RECORD", "CALL_API") + NAVIGATE_LIKE)
    private fun single(vararg props: String) = props.map { BindablePropDef(it, "SINGLE") }

    val byId: Map<String, ComponentOverlay> = mapOf(
        "Navbar" to ComponentOverlay(events = listOf(CLICK), sampleProps = """{"brand":"Xweb"}"""),
        "Hero" to ComponentOverlay(single("eyebrow", "title", "description", "ctaLabel"), listOf(CLICK),
            sampleProps = """{"title":"Tiêu đề","description":"Mô tả ngắn","ctaLabel":"Bắt đầu"}"""),
        "ProductGrid" to ComponentOverlay(listOf(BindablePropDef("items", "LIST", listOf("id", "name", "description", "image"))) + single("heading"), listOf(ITEM_CLICK),
            sampleProps = """{"heading":"Sản phẩm","items":[{"id":"p1","name":"Mục 1"}]}"""),
        "TechnologySection" to ComponentOverlay(single("heading", "body"), sampleProps = """{"heading":"Công nghệ","body":"Nội dung"}"""),
        "ComparisonBlock" to ComponentOverlay(listOf(BindablePropDef("rows", "LIST", listOf("id", "label", "values"))) + single("heading"),
            sampleProps = """{"heading":"So sánh","columns":["A","B"],"rows":[{"id":"r1","label":"Tiêu chí","values":["x","y"]}]}"""),
        "Testimonials" to ComponentOverlay(listOf(BindablePropDef("items", "LIST", listOf("id", "quote", "author", "location", "rating"))) + single("heading"),
            sampleProps = """{"heading":"Đánh giá","items":[{"id":"t1","quote":"Rất tốt","author":"Khách"}]}"""),
        "ContactForm" to ComponentOverlay(single("heading", "submitLabel"), listOf(SUBMIT), "CONTAINED", """{"heading":"Liên hệ","submitLabel":"Gửi"}"""),
        "Footer" to ComponentOverlay(single("text"), sampleProps = """{"text":"© Xweb"}""")
    )

    /** the declared overlay of a component type, or null when none (validators then enforce nothing) */
    fun of(type: String?): ComponentOverlay? = type?.let { byId[it] }

    fun event(type: String?, name: String): ComponentEventDef? = of(type)?.events?.firstOrNull { it.name == name }
    fun bindable(type: String?, prop: String): BindablePropDef? = of(type)?.bindable?.firstOrNull { it.prop == prop }
}

/** Builds the metadata of one registry component (pure). */
class ComponentMetadataBuilder(private val json: JsonMapper) {
    private val actionNames = ActionType.entries.map { it.name }.toSet()

    /** [dbActions] = `component_versions.actions` (names of action types the registry itself declares; empty today) */
    fun build(id: String, version: String, category: String, propsSchema: JsonNode, dbActions: JsonNode?): ComponentMetadataV2 {
        val overlay = ComponentOverlays.of(id)
        val props = propsSchema.get("properties")
        val bindable = overlay?.bindable ?: derivedBindable(props)
        val events = overlay?.events.orEmpty()
        val declared = (dbActions as? ArrayNode)?.mapNotNull { it.takeIf { n -> n.isString }?.asString() }?.filter { it in actionNames }.orEmpty()
        val actions = declared.ifEmpty { events.flatMap { it.supportedActions }.distinct() }
        val visibility = if (props?.get("visible")?.get("type")?.asString() == "boolean")
            listOf(VisibilityConditionDef("visible", description = "false ẩn thành phần khỏi trang")) else emptyList()
        return ComponentMetadataV2(id, version, category, propsSchema, bindable, events, actions, visibility,
            ComponentPreviewDef(overlay?.layout ?: "FULL_WIDTH", overlay?.sampleProps?.let { json.readTree(it) }), if (overlay != null) "OVERLAY" else "DERIVED")
    }

    private fun derivedBindable(props: JsonNode?): List<BindablePropDef> {
        if (props == null || !props.isObject) return emptyList()
        return props.propertyNames().toList().mapNotNull { name ->
            val p = props.get(name)
            when {
                p.get("type")?.asString() == "array" -> BindablePropDef(name, "LIST", p.get("itemProperties")?.propertyNames()?.toList().orEmpty())
                p.get("type")?.asString() == "string" && p.get("format") == null -> BindablePropDef(name, "SINGLE")
                else -> null
            }
        }
    }
}
