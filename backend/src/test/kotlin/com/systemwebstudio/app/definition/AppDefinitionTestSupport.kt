package com.systemwebstudio.app.definition

import com.systemwebstudio.component.ComponentDto
import com.systemwebstudio.component.ComponentRegistry
import com.systemwebstudio.component.ComponentVersionDto
import com.systemwebstudio.schema.PageSchemaValidator
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The registry as seeded by Flyway (V5__seed_component_registry.sql, plus the asset `image` props of V9) without a database,
 * so the validators can be unit-tested with their real rules. If a later migration changes a props schema, update this fixture.
 */
class SeededComponentRegistry(private val mapper: JsonMapper) : ComponentRegistry(JdbcTemplate(), mapper) {
    private val seed = listOf(
        Triple("Navbar", "UI", """{"required":["brand"],"properties":{"brand":{"type":"string","maxLength":80},"links":{"type":"array","maxItems":10}}}"""),
        Triple("Hero", "BLOCK", """{"required":["title"],"properties":{"eyebrow":{"type":"string","maxLength":120},"title":{"type":"string","maxLength":200},"description":{"type":"string","maxLength":600},"ctaLabel":{"type":"string","maxLength":60},"image":{"type":"string","format":"asset","maxLength":80}}}"""),
        Triple("ProductGrid", "BLOCK", """{"required":["heading","items"],"properties":{"heading":{"type":"string","maxLength":160},"items":{"type":"array","maxItems":24,"itemRequired":["id","name"],"itemProperties":{"id":{"type":"string","maxLength":64},"name":{"type":"string","maxLength":120},"description":{"type":"string","maxLength":400},"image":{"type":"string","format":"asset","maxLength":80}}}}}"""),
        Triple("TechnologySection", "BLOCK", """{"required":["heading"],"properties":{"heading":{"type":"string","maxLength":160},"body":{"type":"string","maxLength":1200}}}"""),
        Triple("ComparisonBlock", "BLOCK", """{"required":["heading","rows"],"properties":{"heading":{"type":"string","maxLength":160},"columns":{"type":"array","maxItems":6},"rows":{"type":"array","maxItems":24,"itemRequired":["id","label"],"itemProperties":{"id":{"type":"string","maxLength":64},"label":{"type":"string","maxLength":120},"values":{"type":"array","maxItems":6}}}}}"""),
        Triple("Testimonials", "BLOCK", """{"required":["heading","items"],"properties":{"heading":{"type":"string","maxLength":160},"visible":{"type":"boolean"},"items":{"type":"array","maxItems":24,"itemRequired":["id","quote","author"],"itemProperties":{"id":{"type":"string","maxLength":64},"quote":{"type":"string","maxLength":500},"author":{"type":"string","maxLength":120},"location":{"type":"string","maxLength":120},"rating":{"type":"number"}}}}}"""),
        Triple("ContactForm", "BUSINESS", """{"required":["heading"],"properties":{"heading":{"type":"string","maxLength":160},"submitLabel":{"type":"string","maxLength":60}}}"""),
        Triple("Footer", "UI", """{"required":["text"],"properties":{"text":{"type":"string","maxLength":300}}}""")
    )

    private val entries: Map<String, ComponentRegistry.Entry> = seed.associate { (id, _, props) ->
        "$id@1.0.0" to ComponentRegistry.Entry(id, ComponentVersionDto("1.0.0", "ACTIVE", mapper.readTree(props), mapper.readTree("[]"), mapper.readTree("""["PROJECT_EDIT"]""")))
    }

    override fun versions(): Map<String, ComponentRegistry.Entry> = entries

    override fun list(): List<ComponentDto> = seed.map { (id, category, _) -> ComponentDto(id, id, category, id, "1.0.0", "ACTIVE") }
}

/** Shared wiring for the app-definition unit tests: real PageSchemaValidator / codec / AppDefinitionValidator over the seeded registry. */
object AppDefinitionTestSupport {
    val json: JsonMapper = JsonMapper.builder().build()
    val registry = SeededComponentRegistry(json)
    val pageValidator = PageSchemaValidator(registry, json)
    val codec = AppDefinitionCodec(json)
    val validator = DefaultAppDefinitionValidator(pageValidator, registry, codec)

    fun resource(name: String): JsonNode =
        json.readTree(AppDefinitionTestSupport::class.java.getResourceAsStream("/app-definition/$name")!!.readBytes().toString(Charsets.UTF_8))

    /** two legacy sections (Hero hero-1, ProductGrid products-1, ContactForm contact-1) followed by the given V2 members, e.g. `"queries":[...]` */
    fun doc(v2: String): JsonNode = json.readTree(
        """{"page":"p","sections":[
            {"id":"hero-1","type":"Hero","componentVersion":"1.0.0","props":{"title":"T"}},
            {"id":"products-1","type":"ProductGrid","componentVersion":"1.0.0","props":{"heading":"H","items":[]}},
            {"id":"contact-1","type":"ContactForm","componentVersion":"1.0.0","props":{"heading":"C"}}]
            ${if (v2.isBlank()) "" else ",$v2"}}"""
    )

    fun check(v2: String): List<com.systemwebstudio.schema.Violation> = validator.validateDocument(doc(v2)).violations
}
