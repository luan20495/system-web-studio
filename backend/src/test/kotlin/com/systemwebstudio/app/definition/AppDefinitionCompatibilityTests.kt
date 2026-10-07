package com.systemwebstudio.app.definition

import com.systemwebstudio.app.definition.AppDefinitionTestSupport.codec
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.json
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.pageValidator
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.registry
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.resource
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.validator
import com.systemwebstudio.schema.DefaultPageSchema
import com.systemwebstudio.schema.OperationTypes
import com.systemwebstudio.schema.SchemaOperation
import com.systemwebstudio.schema.SchemaPatchEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Backward compatibility: every Page Schema that exists today keeps working exactly as before, reads as an AppDefinitionV2 with empty
 * extensions, and writes back unchanged; the existing operation engine keeps working on V2 documents without touching the V2 part.
 */
class AppDefinitionCompatibilityTests {
    @Test
    fun `the default page schema of a new project passes both validators and reads as legacy-only`() {
        val schema = DefaultPageSchema(json).create("Pure Living")
        assertThat(pageValidator.validate(schema)).isEmpty()               // unchanged behaviour of the existing validator
        assertThat(validator.validateDocument(schema).violations).isEmpty()
        val def = codec.fromJson(schema)
        assertThat(def.isLegacyOnly).isTrue()
        assertThat(def.schemaVersion).isNull()
        assertThat(def.kind).isNull()
        assertThat(def.dataSources).isEmpty()
        assertThat(def.queries).isEmpty()
        assertThat(def.publishConfig).isNull()
        assertThat(def.sections().map { it.id }).containsExactly("navbar-1", "hero-1", "products-1", "technology-1", "testimonials-1", "contact-1", "footer-1")
        assertThat(codec.toJson(def)).isEqualTo(schema)                    // lossless write back
    }

    @Test
    fun `a multi-page legacy schema with site settings and unknown keys is unchanged by the adapter`() {
        val schema = resource("legacy-multipage.json")
        assertThat(pageValidator.validate(schema)).isEmpty()
        assertThat(validator.validateDocument(schema).violations).isEmpty()
        val def = codec.fromJson(schema)
        assertThat(def.isLegacyOnly).isTrue()
        assertThat(def.pageSchema.keys).containsExactly("page", "seo", "sections", "pages", "site", "legacyUnknownKey")
        assertThat(def.sections().map { it.pageId + ":" + it.id }).containsExactly("home:hero-1", "home:products-1", "about:footer-1")
        assertThat(codec.toJson(def)).isEqualTo(schema)
    }

    @Test
    fun `legacy documents are not subject to any V2 rule`() {
        // top-level members that only look like V2 names but are not V2 keys, and arbitrary unknown keys, stay allowed as before
        val schema = json.readTree("""{"page":"p","sections":[],"somethingElse":{"url":"https://example.com","sql":"select 1"}}""")
        assertThat(AppDefinitionKeys.declaresV2(schema)).isFalse()
        assertThat(validator.validateExtensions(schema).violations).isEmpty()
        assertThat(validator.validateDocument(schema).violations).isEmpty()
        validator.requireValidExtensions(schema)
    }

    @Test
    fun `the sample V2 document round-trips exactly`() {
        val sample = resource("valid-v2-sample.json")
        val def = codec.fromJson(sample)
        assertThat(def.isLegacyOnly).isFalse()
        assertThat(def.schemaVersion).isEqualTo(2)
        assertThat(def.kind).isEqualTo(AppDefinitionKind.PAGE_SCHEMA)
        assertThat(def.dataSources.map { it.id }).containsExactly("crm", "erp-db")
        assertThat(def.queries.map { it.id }).containsExactly("orders-list", "order-create")
        assertThat(def.queries[1].mode).isEqualTo(QueryMode.WRITE)
        assertThat(def.actions.map { it.type }).containsExactly(ActionType.NAVIGATE, ActionType.CREATE_RECORD, ActionType.NOTIFY, ActionType.START_WORKFLOW)
        assertThat(def.publishConfig).isEqualTo(PublishConfigDef(PublishMode.DYNAMIC, PublishVisibility.TENANT, true, 60))
        assertThat(def.theme).isEqualTo(ThemeDef(mapOf("primary" to "#1A73E8", "background" to "#FFFFFF"), ThemeFont.SYSTEM, ThemeRadius.MD))
        assertThat(codec.toJson(def)).isEqualTo(sample)
        assertThat(codec.fromJson(codec.toJson(def))).isEqualTo(def)
    }

    @Test
    fun `defaults are omitted on write and restored on read`() {
        val def = AppDefinitionV2(
            schemaVersion = 2, pageSchema = mapOf("page" to json.readTree("\"p\""), "sections" to json.readTree("[]")),
            dataSources = listOf(DataSourceDef("d", type = "rest")), queries = listOf(QueryDef("q", dataSourceRef = "d")), theme = ThemeDef(),
            viewModels = listOf(ViewModelDef("v", fields = listOf(ViewModelFieldDef("a")))), publishConfig = PublishConfigDef()
        )
        val written = codec.toJson(def)
        assertThat(written.get("queries").get(0).has("mode")).isFalse()
        assertThat(written.get("viewModels").get(0).has("cardinality")).isFalse()
        assertThat(written.get("viewModels").get(0).get("fields").get(0).has("type")).isFalse()
        assertThat(written.get("publishConfig").size()).isEqualTo(0)
        assertThat(written.get("theme").size()).isEqualTo(0)
        assertThat(codec.fromJson(written)).isEqualTo(def)
    }

    @Test
    fun `fromJson refuses a malformed V2 part with every violation`() {
        val bad = json.readTree("""{"page":"p","sections":[],"queries":[{"id":"Bad Id","dataSourceRef":"d","sql":"x"}]}""")
        val e = assertThrows(AppDefinitionFormatException::class.java) { codec.fromJson(bad) }
        assertThat(e.violations.map { it.path }).contains("queries[0].id", "queries[0].sql")
    }

    @Test
    fun `existing operations still work on a V2 document and leave the V2 part untouched`() {
        val engine = SchemaPatchEngine(registry, json)
        val sample = resource("valid-v2-sample.json")
        val patched = engine.apply(sample, listOf(
            SchemaOperation("UPDATE_PROP", sectionId = "hero-1", path = "title", value = json.readTree("\"Đơn hàng hôm nay\"")),
            SchemaOperation("ADD_ITEM", sectionId = "products-1", item = json.readTree("""{"id":"p9","name":"Nine"}""")),
            SchemaOperation("ADD_SECTION", sectionType = "TechnologySection", props = json.readTree("""{"heading":"How"}"""))
        ))
        assertThat(patched.get("sections").get(1).get("props").get("title").asString()).isEqualTo("Đơn hàng hôm nay")
        for (key in AppDefinitionKeys.V2_KEYS) assertThat(patched.get(key)).describedAs(key).isEqualTo(sample.get(key))
        assertThat(sample.get("sections").get(1).get("props").get("title").asString()).isEqualTo("Đơn hàng")    // input copy not mutated
        assertThat(validator.validateDocument(patched).violations).isEmpty()
    }

    @Test
    fun `removing a section that a binding or trigger points at is caught by the V2 validator`() {
        val engine = SchemaPatchEngine(registry, json)
        val patched = engine.apply(resource("valid-v2-sample.json"), listOf(SchemaOperation("REMOVE_SECTION", sectionId = "products-1")))
        val violations = validator.validateDocument(patched).violations
        assertThat(violations.any { it.path == "dataBindings[0].sectionId" && it.message.contains("unknown section 'products-1'") }).isTrue()
    }

    @Test
    fun `the operation vocabulary of the page schema is unchanged`() {
        assertThat(OperationTypes.sections).containsExactlyInAnyOrder("ADD_SECTION", "REMOVE_SECTION", "MOVE_SECTION", "UPDATE_SECTION", "UPDATE_PROP", "ADD_ITEM", "REMOVE_ITEM")
        assertThat(OperationTypes.site).containsExactlyInAnyOrder("ADD_PAGE", "UPDATE_PAGE", "REMOVE_PAGE", "SET_NAVIGATION", "UPDATE_SITE")
        assertThat(OperationTypes.all.size).isEqualTo(12)
    }

    @Test
    fun `the V2 document fits the size limit of the page validator`() {
        // the 256 KB limit of PageSchemaValidator applies to the whole document, V2 part included
        val big = "x".repeat(300_000)
        val schema = json.readTree("""{"page":"p","sections":[],"extensions":{"acme":{"blob":"$big"}}}""")
        assertThat(validator.validateDocument(schema).violations.map { it.message }.any { it.contains("exceeds") }).isTrue()
    }
}

/** The canonical views (pages / navigation / components / assets / actionRefs) are computed from the legacy part and never change the stored form. */
class AppDefinitionViewTests {
    @Test
    fun `a multi-page legacy schema reads as pages navigation components and assets`() {
        val def = codec.fromJson(resource("legacy-multipage.json"))
        assertThat(def.pages().map { it.id + ":" + it.home + ":" + it.sections.size }).containsExactly("home:true:2", "about:false:1")
        assertThat(def.pages()[0].title).isEqualTo("Home")
        assertThat(def.pages()[1].slug).isEqualTo("about")
        assertThat(def.navigation().map { it.id + ":" + it.pageId + ":" + it.anchor }).containsExactly("n1:about:null", "n2:null:#products-1")
        assertThat(def.components().map { it.type + "@" + it.version + "=" + it.sectionIds }).containsExactly("Hero@1.0.0=[hero-1]", "ProductGrid@1.0.0=[products-1]", "Footer@1.0.0=[footer-1]")
        assertThat(def.assets().map { it.assetId + "=" + it.sectionIds }).containsExactly("11111111-2222-3333-4444-555555555555=[hero-1]")
        assertThat(codec.toJson(def)).isEqualTo(resource("legacy-multipage.json"))       // views read, they never write
    }

    @Test
    fun `the default page of a new project has a home page and no navigation or assets`() {
        val def = codec.fromJson(DefaultPageSchema(json).create("Pure Living"))
        assertThat(def.pages()).hasSize(1)
        assertThat(def.pages()[0].home).isTrue()
        assertThat(def.pages()[0].sections).hasSize(7)
        assertThat(def.navigation()).isEmpty()
        assertThat(def.assets()).isEmpty()
        assertThat(def.components().map { it.type }).contains("Hero", "Footer")
    }

    @Test
    fun `declared actions expose C4 style action refs`() {
        val def = codec.fromJson(resource("valid-v2-sample.json"))
        assertThat(def.actionRefs().map { it.trigger + "->" + it.actionId }).containsExactly("hero-1.onClick->go-home", "contact-1.onSubmit->create-order")
    }
}
