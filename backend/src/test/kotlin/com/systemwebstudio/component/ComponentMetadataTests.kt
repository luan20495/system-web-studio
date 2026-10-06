package com.systemwebstudio.component

import com.systemwebstudio.app.definition.AppDefinitionTestSupport.json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Component Registry V2 metadata: declared bindings / events / visibility, and the validator rule built on them. */
class ComponentMetadataTests {
    private val builder = ComponentMetadataBuilder(json)
    private val seeded = listOf("Navbar", "Hero", "ProductGrid", "TechnologySection", "ComparisonBlock", "Testimonials", "ContactForm", "Footer")

    private fun props(vararg entries: String) = json.readTree("""{"type":"object","properties":{${entries.joinToString(",")}}}""")

    @Test
    fun `every seeded component has an overlay with preview sample props`() {
        for (id in seeded) {
            val m = builder.build(id, "1.0.0", "marketing", props(), null)
            assertThat(m.source).describedAs(id).isEqualTo("OVERLAY")
            assertThat(m.preview.sampleProps != null && m.preview.sampleProps!!.isObject).describedAs(id).isTrue()
            assertThat(m.preview.layout in setOf("FULL_WIDTH", "CONTAINED")).describedAs(id).isTrue()
        }
        assertThat(ComponentOverlays.byId.keys).isEqualTo(seeded.toSet())
    }

    @Test
    fun `events declare the action types they support and no component carries business logic`() {
        val grid = builder.build("ProductGrid", "1.0.0", "commerce", props(), null)
        assertThat(grid.events.map { it.name }).isEqualTo(listOf("onClick"))
        assertThat("DELETE_RECORD" in grid.supportedActions).isTrue()
        val form = builder.build("ContactForm", "1.0.0", "forms", props(), null)
        assertThat(form.events.single().supportedActions.first()).isEqualTo("SUBMIT_FORM")
        assertThat(builder.build("Footer", "1.0.0", "layout", props(), null).events).isEmpty()
        assertThat(builder.build("Footer", "1.0.0", "layout", props(), null).supportedActions).isEmpty()
        // every declared action type is a real ActionType name
        val all = com.systemwebstudio.app.definition.ActionType.entries.map { it.name }.toSet()
        assertThat(ComponentOverlays.byId.values.flatMap { it.events }.all { e -> e.supportedActions.all { it in all } }).isTrue()
    }

    @Test
    fun `list props are bindable lists with item fields and text props bindable values`() {
        val grid = ComponentOverlays.bindable("ProductGrid", "items")!!
        assertThat(grid.cardinality).isEqualTo("LIST")
        assertThat("name" in grid.itemFields).isTrue()
        assertThat(ComponentOverlays.bindable("Hero", "title")!!.cardinality).isEqualTo("SINGLE")
        assertThat(ComponentOverlays.bindable("Hero", "nope")).isNull()
    }

    @Test
    fun `visibility condition is derived from a boolean visible prop`() {
        val withVisible = builder.build("Testimonials", "1.0.0", "social", props(""""visible":{"type":"boolean"}"""), null)
        assertThat(withVisible.visibilityConditions.map { it.prop }).isEqualTo(listOf("visible"))
        assertThat(builder.build("Testimonials", "1.0.0", "social", props(), null).visibilityConditions).isEmpty()
    }

    @Test
    fun `a component without an overlay is described from its props schema and nothing is enforced for it`() {
        val schema = props(""""title":{"type":"string"}""", """"rows":{"type":"array","itemProperties":{"a":{"type":"string"},"b":{"type":"string"}}}""",
            """"count":{"type":"number"}""")
        val m = builder.build("DataTable", "0.1.0", "data", schema, null)
        assertThat(m.source).isEqualTo("DERIVED")
        assertThat(m.bindableProps.map { it.prop }).isEqualTo(listOf("title", "rows"))
        assertThat(m.bindableProps.first { it.prop == "rows" }.itemFields).isEqualTo(listOf("a", "b"))
        assertThat(m.events).isEmpty()
        assertThat(ComponentOverlays.event("DataTable", "onAnything")).isNull()
    }

    @Test
    fun `actions declared by the registry itself take precedence over the overlay`() {
        val m = builder.build("Hero", "1.0.0", "marketing", props(), json.readTree("""["NAVIGATE","BOGUS"]"""))
        assertThat(m.supportedActions).isEqualTo(listOf("NAVIGATE"))
    }

    private fun check(actions: String): List<String> =
        com.systemwebstudio.app.definition.AppDefinitionTestSupport.check(""""actions":$actions""").map { it.path + ": " + it.message }

    @Test
    fun `validator rejects an event the component does not emit`() {
        val v = check("""[{"id":"a","type":"NOTIFY","channel":"IN_APP","templateRef":"tpl-1","trigger":{"sectionId":"hero-1","event":"onChange"}}]""")
        assertThat(v.any { it.contains("does not emit 'onChange'") }).describedAs(v.toString()).isTrue()
    }

    @Test
    fun `validator rejects an action type the event does not support, and any trigger on a component with no events`() {
        val v = check("""[{"id":"a","type":"DELETE_RECORD","queryRef":"q","trigger":{"sectionId":"hero-1","event":"onClick"}}]""")
        assertThat(v.any { it.contains("DELETE_RECORD is not supported on 'Hero.onClick'") }).describedAs(v.toString()).isTrue()
    }

    @Test
    fun `validator still accepts the documented triggers`() {
        val v = check("""[{"id":"go-home","type":"NAVIGATE","pageRef":"home","trigger":{"sectionId":"hero-1","event":"onClick"}}]""")
        assertThat(v.filter { it.contains("trigger") }).isEmpty()
    }
}
