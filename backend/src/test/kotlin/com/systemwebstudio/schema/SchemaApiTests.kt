package com.systemwebstudio.schema

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class SchemaApiTests : IntegrationTestBase() {
    @Autowired lateinit var validator: PageSchemaValidator

    private fun patch(sc: Scenario, ops: String, rev: Long = sc.revision()) =
        sc.s.patch("${sc.base}/schema", """{"expectedRevision":$rev,"operations":$ops}""")

    @Test
    fun `new project gets default schema and version 1 from the registry`() {
        val sc = scenario()
        val schema = sc.schema()
        assertThat(schema.get("sections").toList().map { it.get("type").asString() })
            .containsExactly("Navbar", "Hero", "ProductGrid", "TechnologySection", "Testimonials", "ContactForm", "Footer")
        assertThat(validator.validate(schema)).isEmpty()
        assertThat(sc.versionCount()).isEqualTo(1)
    }

    @Test
    fun `UPDATE_PROP on an item creates a version and persists`() {
        val sc = scenario()
        val r = patch(sc, """[{"type":"UPDATE_PROP","sectionId":"products-1","itemId":"p1","path":"name","value":"New Product"}]""")
        assertThat(r.response.status).isEqualTo(200)
        assertThat(sc.s.body(r).get("revision").asLong()).isEqualTo(1L)
        assertThat(sc.section("ProductGrid")!!.get("props").get("items").get(0).get("name").asString()).isEqualTo("New Product")
        assertThat(sc.versionCount()).isEqualTo(2)
        assertThat(sc.auditCount("CREATE_VERSION")).isEqualTo(1L)
    }

    @Test
    fun `ADD_ITEM REMOVE_ITEM ADD_SECTION MOVE_SECTION and REMOVE_SECTION work`() {
        val sc = scenario()
        assertThat(patch(sc, """[{"type":"ADD_ITEM","sectionId":"products-1","item":{"id":"p9","name":"Nine"}}]""").response.status).isEqualTo(200)
        assertThat(sc.section("ProductGrid")!!.get("props").get("items").size()).isEqualTo(4)
        assertThat(patch(sc, """[{"type":"REMOVE_ITEM","sectionId":"products-1","itemId":"p9"}]""").response.status).isEqualTo(200)
        assertThat(sc.section("ProductGrid")!!.get("props").get("items").size()).isEqualTo(3)
        assertThat(patch(sc, """[{"type":"ADD_SECTION","sectionType":"ComparisonBlock","beforeSectionId":"testimonials-1","props":{"heading":"Compare","rows":[]}}]""").response.status).isEqualTo(200)
        var types = sc.schema().get("sections").toList().map { it.get("type").asString() }
        assertThat(types.indexOf("ComparisonBlock")).isEqualTo(types.indexOf("Testimonials") - 1)
        assertThat(patch(sc, """[{"type":"MOVE_SECTION","sectionId":"comparisonblock-1","afterSectionId":"hero-1"}]""").response.status).isEqualTo(200)
        types = sc.schema().get("sections").toList().map { it.get("type").asString() }
        assertThat(types[2]).isEqualTo("ComparisonBlock")
        assertThat(patch(sc, """[{"type":"REMOVE_SECTION","sectionId":"comparisonblock-1"}]""").response.status).isEqualTo(200)
        assertThat(sc.versionCount()).isEqualTo(6)
    }

    @Test
    fun `invalid operations and invalid schemas are rejected without creating a version`() {
        val sc = scenario()
        val rev = sc.revision()
        val bad = listOf(
            """[{"type":"ADD_SECTION","sectionType":"EvilScript","props":{}}]""" to 400,                          // not in registry
            """[{"type":"DROP_TABLE"}]""" to 400,                                                               // unknown operation
            """[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"__proto__","value":"x"}]""" to 400,           // forbidden path
            """[{"type":"UPDATE_PROP","sectionId":"nope","path":"title","value":"x"}]""" to 400,                 // unknown section
            """[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":123}]""" to 422,               // wrong prop type
            """[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"surprise","value":"x"}]""" to 422,            // unknown prop
            """[{"type":"ADD_SECTION","sectionType":"Hero","sectionId":"hero-1","props":{"title":"x"}}]""" to 400,   // duplicate id
            """[{"type":"ADD_SECTION","sectionType":"ComparisonBlock","props":{}}]""" to 422,                    // required props missing
            """[{"type":"ADD_ITEM","sectionId":"products-1","item":{"id":"p1","name":"dup"}}]""" to 400          // duplicate item id
        )
        bad.forEach { (ops, status) ->
            val r = patch(sc, ops, rev)
            assertThat(r.response.status).describedAs(ops).isEqualTo(status)
            assertThat(sc.s.body(r).get("requestId").asString()).isNotBlank()
        }
        assertThat(sc.versionCount()).isEqualTo(1)
        assertThat(sc.revision()).isEqualTo(rev)
        assertThat(patch(sc, "[]").response.status).isEqualTo(400)
    }

    @Test
    fun `validator rejects duplicate ids unknown component version and missing required props`() {
        val dup = json.readTree("""{"page":"p","sections":[
            {"id":"a","type":"Footer","componentVersion":"1.0.0","props":{"text":"x"}},
            {"id":"a","type":"Footer","componentVersion":"1.0.0","props":{"text":"y"}}]}""")
        assertThat(validator.validate(dup).map { it.message }).anyMatch { it.contains("duplicate section id") }
        val badVersion = json.readTree("""{"page":"p","sections":[{"id":"a","type":"Footer","componentVersion":"9.9.9","props":{"text":"x"}}]}""")
        assertThat(validator.validate(badVersion).map { it.message }).anyMatch { it.contains("unknown component") }
        val missing = json.readTree("""{"page":"p","sections":[{"id":"a","type":"Hero","componentVersion":"1.0.0","props":{}}]}""")
        assertThat(validator.validate(missing).map { it.message }).anyMatch { it.contains("required prop 'title'") }
        val wrongType = json.readTree("""{"page":"p","sections":[{"id":"a","type":"Testimonials","componentVersion":"1.0.0","props":{"heading":"h","visible":"yes","items":[]}}]}""")
        assertThat(validator.validate(wrongType).map { it.message }).anyMatch { it.contains("must be a boolean") }
    }

    @Test
    fun `viewer cannot edit and stale revision is 409`() {
        val sc = scenario()
        val viewer = fx.user("viewer"); fx.member(sc.ws, viewer, "VIEWER")
        fx.projectRole(fx.projects.findById(sc.projectId).get(), viewer, "VIEWER")
        val v = sessionFor(viewer.username)
        assertThat(v.get("${sc.base}/schema").response.status).isEqualTo(200)
        assertThat(v.patch("${sc.base}/schema", """{"expectedRevision":0,"operations":[{"type":"REMOVE_SECTION","sectionId":"footer-1"}]}""").response.status).isEqualTo(403)
        assertThat(patch(sc, """[{"type":"REMOVE_SECTION","sectionId":"footer-1"}]""", 99).response.status).isEqualTo(409)
    }

    @Test
    fun `two schema writes with the same revision let exactly one win`() {
        val sc = scenario()
        val rev = sc.revision()
        val pool = Executors.newFixedThreadPool(2); val gate = CountDownLatch(1)
        val futures = (1..2).map { n -> pool.submit(Callable { gate.await()
            sc.s.patch("${sc.base}/schema", """{"expectedRevision":$rev,"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"writer $n"}]}""").response.status }) }
        gate.countDown()
        assertThat(futures.map { it.get() }.sorted()).isEqualTo(listOf(200, 409))
        pool.shutdown()
        assertThat(sc.versionCount()).isEqualTo(2)
    }

    @Test
    fun `foreign users cannot read or patch the schema`() {
        val sc = scenario()
        val other = fx.user("other"); val ws2 = fx.workspace(); fx.member(ws2, other, "EDITOR")
        val o = sessionFor(other.username)
        assertThat(o.get("/api/v1/workspaces/${sc.ws}/projects/${sc.projectId}/schema").response.status).isEqualTo(404)
        assertThat(o.get("/api/v1/workspaces/$ws2/projects/${sc.projectId}/schema").response.status).isEqualTo(404)
    }

    @Test
    fun `registry endpoints list seeded components`() {
        val sc = scenario()
        val list = sc.s.body(sc.s.get("/api/v1/components"))
        assertThat(list.toList().map { it.get("id").asString() }).contains("Navbar", "Hero", "ProductGrid", "ProductCard", "TechnologySection", "ComparisonBlock", "Testimonials", "ContactForm", "Footer")
        assertThat(sc.s.body(sc.s.get("/api/v1/components/Hero")).get("versions").get(0).get("propsSchema").get("required").get(0).asString()).isEqualTo("title")
        assertThat(sc.s.get("/api/v1/components/Nope").response.status).isEqualTo(404)
    }
}
