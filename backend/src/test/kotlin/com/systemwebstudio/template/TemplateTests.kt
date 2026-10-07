package com.systemwebstudio.template

import com.systemwebstudio.app.definition.AppDefinitionTestSupport.codec
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.json
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.pageValidator
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.resource
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.validator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/** Template V2: the 13 built-in business templates and the sanitizer that keeps tenant / project data out of shared templates. */
class BusinessTemplateTests {
    private val all = BusinessTemplates.all(json)

    @Test
    fun `there is exactly one built-in template for each of the 13 business categories`() {
        assertThat(all).hasSize(13)
        assertThat(all.map { it.category }).isEqualTo(listOf("crm", "sales", "hr", "project", "helpdesk", "inventory", "approval", "marketing", "cms", "ecommerce", "portal", "dashboard", "form"))
        assertThat(all.map { it.category }).isEqualTo(BusinessTemplates.categories.keys.toList())
        assertThat(all.map { it.id }.toSet()).hasSize(13)
        assertThat(all.all { it.category.matches(Regex("^[a-z0-9-]{1,40}$")) }).isTrue()   // the category rule of the existing library
        assertThat(all.all { it.name.isNotBlank() && it.description.length <= 500 && it.tags.size <= 8 }).isTrue()
    }

    @Test
    fun `ids are stable between runs and recognised as built-in`() {
        assertThat(BusinessTemplates.all(json).map { it.id }).isEqualTo(all.map { it.id })
        assertThat(all.all { BusinessTemplates.isBuiltIn(it.id) }).isTrue()
        assertThat(BusinessTemplates.isBuiltIn(java.util.UUID.randomUUID())).isFalse()
        assertThat(BusinessTemplates.find(json, all[3].id)?.key).isEqualTo("project")
    }

    @Test
    fun `every built-in template passes the page validator and the V2 validator`() {
        for (t in all) {
            assertThat(pageValidator.validate(t.schema)).describedAs(t.key).isEmpty()
            assertThat(validator.validateDocument(t.schema).violations.map { it.path + ": " + it.message }).describedAs(t.key).isEmpty()
        }
    }

    @Test
    fun `every built-in template is a typed V2 document that round-trips`() {
        for (t in all) {
            val def = codec.fromJson(t.schema)
            assertThat(def.schemaVersion).describedAs(t.key).isEqualTo(2)
            assertThat(def.isLegacyOnly).isFalse()
            assertThat(def.dataBindings).describedAs(t.key).hasSize(1)
            assertThat(codec.toJson(def)).describedAs(t.key).isEqualTo(t.schema)
        }
    }

    @Test
    fun `built-in templates only use approved components and carry no tenant or project data`() {
        val approved = setOf("Navbar", "Hero", "ProductGrid", "TechnologySection", "ComparisonBlock", "Testimonials", "ContactForm", "Footer")
        val sanitizer = TemplateSanitizer(json)
        for (t in all) {
            val sectionsNode: JsonNode = t.schema.get("sections")
            assertThat(sectionsNode.isArray).describedAs("${t.key} sections must be an array").isTrue()
            // Not `sectionsNode.map { ... }`: Jackson 3's JsonNode has its own member map(Function) that wins over Kotlin's Iterable.map
            // and would hand the lambda the whole array node. Iterate the elements explicitly.
            val types: MutableSet<String> = linkedSetOf()
            for (section in sectionsNode) types.add(section.get("type").asString())
            val unapproved: Set<String> = types - approved
            assertThat(unapproved).describedAs("${t.key} uses components outside the approved set (uses $types)").isEmpty()
            val text = json.writeValueAsString(t.schema)
            for (forbidden in listOf("://", "asset://", "\"sourceRef\"", "\"operationKey\"", "\"publishConfig\"", "password", "secret", "apiKey")) {
                assertThat(text.contains(forbidden)).describedAs("${t.key} contains $forbidden").isFalse()
            }
            assertThat(sanitizer.issues(t.schema, TemplateScope.SYSTEM)).describedAs(t.key).isEmpty()
        }
    }

    @Test
    fun `a built-in template declares its data contract as slots`() {
        val crm = BusinessTemplates.find(json, BusinessTemplates.idOf("crm"))!!
        val slots = TemplateSanitizer(json).dataSlots(crm.schema)
        assertThat(slots).hasSize(1)
        assertThat(slots[0].dataSourceId).isEqualTo("main")
        assertThat(slots[0].queries.map { it.id + ":" + it.mode }).containsExactly("list-crm:READ", "create-crm:WRITE")
        assertThat(slots[0].queries[0].params).containsExactly("search")
        assertThat(slots[0].viewModels.map { it.id + ":" + it.fields }).containsExactly("crm-vm:[name, description]")
    }

    @Test
    fun `sample data fits the view model and is limited to text numbers and booleans`() {
        val sanitizer = TemplateSanitizer(json)
        for (t in all) {
            val rows = sanitizer.sampleData(t.schema)!!
            assertThat(rows.propertyNames().toList()).containsExactly(t.key + "-vm")
            assertThat(rows.get(t.key + "-vm").size() in 1..TemplateSanitizer.MAX_SAMPLE_ROWS).isTrue()
        }
    }

    @Test
    fun `workflow templates start from an action and stay acyclic`() {
        for (key in listOf("crm", "hr", "helpdesk", "approval")) {
            val t = BusinessTemplates.find(json, BusinessTemplates.idOf(key))!!
            assertThat(t.schema.get("workflows")).describedAs(key).isNotNull()
            assertThat(validator.validateDocument(t.schema).violations).describedAs(key).isEmpty()
        }
    }
}

class TemplateSanitizerTests {
    private val sanitizer = TemplateSanitizer(json)

    private fun v2(extra: String = ""): JsonNode = json.readTree(
        """{"schemaVersion":2,"kind":"PAGE_SCHEMA","page":"p","sections":[
            {"id":"hero-1","type":"Hero","componentVersion":"1.0.0","props":{"title":"T"}},
            {"id":"products-1","type":"ProductGrid","componentVersion":"1.0.0","props":{"heading":"H","items":[]}}],
          "dataSources":[{"id":"crm","type":"rest","sourceRef":"0b8f2a3e-5c1d-4b7a-9e2f-3d4c5b6a7e80"}],
          "queries":[{"id":"q","dataSourceRef":"crm","operationKey":"orders.list"}],
          "viewModels":[{"id":"vm","queryRef":"q","fields":[{"name":"name"},{"name":"note"}]}],
          "dataBindings":[{"id":"b","sectionId":"products-1","prop":"items","viewModelRef":"vm"}],
          "publishConfig":{"mode":"DYNAMIC","visibility":"PUBLIC"}$extra}"""
    )

    @Test
    fun `scope follows the visibility of the template`() {
        assertThat(TemplateScope.of("PRIVATE")).isEqualTo(TemplateScope.PRIVATE)
        assertThat(TemplateScope.of("COMPANY")).isEqualTo(TemplateScope.SYSTEM)
        assertThat(TemplateScope.of("PRIVATE", tenantScoped = true)).isEqualTo(TemplateScope.TENANT)
        assertThat(TemplateScope.of("COMPANY", tenantScoped = true)).isEqualTo(TemplateScope.SYSTEM)
    }

    @Test
    fun `every scope drops the publish draft and the connector id but keeps the slot`() {
        for (scope in TemplateScope.values()) {
            val out = sanitizer.sanitize(v2(), scope)
            assertThat(out.schema.has("publishConfig")).describedAs("$scope").isFalse()
            assertThat(out.schema.get("dataSources").get(0).has("sourceRef")).describedAs("$scope").isFalse()
            assertThat(out.schema.get("dataSources").get(0).get("id").asString()).isEqualTo("crm")
            assertThat(out.removed.any { it.startsWith("publishConfig") }).isTrue()
            assertThat(out.removed.any { it.startsWith("dataSources[0].sourceRef") }).isTrue()
            assertThat(validator.validateDocument(out.schema).violations).describedAs("$scope").isEmpty()
        }
    }

    @Test
    fun `operation keys of a tenant connector are removed only from platform-wide templates`() {
        assertThat(sanitizer.sanitize(v2(), TemplateScope.PRIVATE).schema.get("queries").get(0).has("operationKey")).isTrue()
        assertThat(sanitizer.sanitize(v2(), TemplateScope.TENANT).schema.get("queries").get(0).has("operationKey")).isTrue()
        val system = sanitizer.sanitize(v2(), TemplateScope.SYSTEM)
        assertThat(system.schema.get("queries").get(0).has("operationKey")).isFalse()
        assertThat(system.removed.any { it.startsWith("queries[0].operationKey") }).isTrue()
        assertThat(validator.validateDocument(system.schema).violations).isEmpty()
    }

    @Test
    fun `third-party extension namespaces never travel in a SYSTEM template`() {
        val doc = v2(""","extensions":{"acme.crm":{"segment":"smb"},"xweb.note":{"hint":"ok"}}""")
        assertThat(sanitizer.sanitize(doc, TemplateScope.PRIVATE).schema.get("extensions").has("acme.crm")).isTrue()
        val system = sanitizer.sanitize(doc, TemplateScope.SYSTEM)
        assertThat(system.schema.get("extensions").has("acme.crm")).isFalse()
        assertThat(system.schema.get("extensions").has("xweb.note")).isTrue()
    }

    @Test
    fun `credential-like keys and URLs in an extension remove that namespace in every scope`() {
        for (bad in listOf("""{"apiKey":"abc"}""", """{"nested":{"Authorization":"Bearer x"}}""", """{"link":"https://example.com/x"}""", """{"sql":"select 1"}""")) {
            val out = sanitizer.sanitize(v2(""","extensions":{"acme.crm":$bad}"""), TemplateScope.PRIVATE)
            assertThat(out.schema.has("extensions")).describedAs(bad).isFalse()
            assertThat(out.removed.any { it.startsWith("extensions.acme.crm") }).describedAs(bad).isTrue()
        }
    }

    @Test
    fun `sample data with unknown fields, URLs, credentials or too many rows is dropped`() {
        fun sample(rows: String) = v2(""","extensions":{"xweb.template":{"contractVersion":1,"sampleData":{"vm":$rows}}}""")
        val ok = sanitizer.sanitize(sample("""[{"name":"A","note":"x"}]"""), TemplateScope.SYSTEM)
        assertThat(ok.removed.filter { it.startsWith("extensions") }).isEmpty()
        assertThat(sanitizer.sampleData(ok.schema)!!.get("vm").size()).isEqualTo(1)

        for (bad in listOf("""[{"other":"A"}]""", """[{"name":"https://evil.example/x"}]""", """[{"name":{"deep":1}}]""", "[" + (1..21).joinToString(",") { """{"name":"n$it"}""" } + "]", """[{"password":"x"}]""")) {
            val out = sanitizer.sanitize(sample(bad), TemplateScope.SYSTEM)
            assertThat(sanitizer.sampleData(out.schema)?.has("vm") ?: false).describedAs(bad).isFalse()
            assertThat(out.removed).describedAs(bad).isNotEmpty()
        }
        val unknownVm = sanitizer.sanitize(v2(""","extensions":{"xweb.template":{"sampleData":{"nope":[{"name":"A"}]}}}"""), TemplateScope.SYSTEM)
        assertThat(sanitizer.sampleData(unknownVm.schema)).isNull()
    }

    @Test
    fun `sanitizing does not change its input and a legacy page is returned as it was`() {
        val input = v2()
        val before = input.deepCopy() as JsonNode
        sanitizer.sanitize(input, TemplateScope.SYSTEM)
        assertThat(input).isEqualTo(before)

        val legacy = resource("legacy-multipage.json")
        for (scope in TemplateScope.values()) {
            val out = sanitizer.sanitize(legacy, scope)
            assertThat(out.schema).isEqualTo(legacy)
            assertThat(out.removed).isEmpty()
        }
    }

    @Test
    fun `a document is safe exactly when sanitizing removes nothing`() {
        assertThat(sanitizer.issues(v2(), TemplateScope.SYSTEM)).hasSize(3)       // publishConfig, sourceRef, operationKey
        assertThat(sanitizer.issues(v2(), TemplateScope.PRIVATE)).hasSize(2)      // publishConfig, sourceRef
        val clean = sanitizer.sanitize(v2(), TemplateScope.SYSTEM).schema
        assertThat(sanitizer.issues(clean, TemplateScope.SYSTEM)).isEmpty()
    }

    @Test
    fun `the sample V2 document becomes a valid platform template`() {
        val out = sanitizer.sanitize(resource("valid-v2-sample.json"), TemplateScope.SYSTEM)
        assertThat(validator.validateDocument(out.schema).violations.map { it.path + ": " + it.message }).isEmpty()
        assertThat(out.schema.has("publishConfig")).isFalse()
        assertThat(out.schema.get("extensions")).isNull()
        assertThat(sanitizer.dataSlots(out.schema).map { it.dataSourceId }).containsExactly("crm", "erp-db")
    }
}
