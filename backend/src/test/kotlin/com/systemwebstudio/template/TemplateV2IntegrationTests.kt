package com.systemwebstudio.template

import com.sun.net.httpserver.HttpServer
import com.systemwebstudio.access.AccessService
import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.version.SchemaCommitService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.net.InetSocketAddress
import java.util.UUID

/**
 * Template V2 end to end (PostgreSQL via Testcontainers): the 13 built-in templates, the portable copy made when a project is saved as a
 * template, and the SYSTEM sanitization at submit time. Not run in the C2 sandbox (B-004).
 */
class TemplateV2IntegrationTests : IntegrationTestBase() {
    companion object {
        /**
         * Stub render worker. Submitting a template runs the automated "render" check, which calls the render worker (`app.render.url`, default
         * http://127.0.0.1:18095). Without a stub this test depends on whatever happens to listen there: a real worker answers 401 (token mismatch),
         * nothing listening gives "not reachable", and either way `passed` is false. Same approach as LibraryCatalogTests. /preview answers 501
         * ("no browser"), exactly what the real worker does without one, so no preview is faked.
         */
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/render") { ex ->
                ex.requestBody.readBytes()
                val b = "<!doctype html><html><body><h1>ok</h1></body></html>".toByteArray()
                ex.responseHeaders.add("Content-Type", "text/html"); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            createContext("/preview") { ex -> ex.requestBody.readBytes(); ex.sendResponseHeaders(501, -1); ex.close() }
            start()
        }

        @JvmStatic @DynamicPropertySource
        fun render(registry: DynamicPropertyRegistry) { registry.add("app.render.url") { "http://127.0.0.1:${server.address.port}" } }
    }

    @Autowired lateinit var access: AccessService
    @Autowired lateinit var commits: SchemaCommitService

    private val v2Members = """{"schemaVersion":2,"kind":"PAGE_SCHEMA",
        "dataSources":[{"id":"crm","type":"rest","sourceRef":"0b8f2a3e-5c1d-4b7a-9e2f-3d4c5b6a7e80"}],
        "queries":[{"id":"q","dataSourceRef":"crm","operationKey":"orders.list"}],
        "viewModels":[{"id":"v","queryRef":"q","fields":[{"name":"name"}]}],
        "dataBindings":[{"id":"b","sectionId":"products-1","prop":"items","viewModelRef":"v"}],
        "publishConfig":{"mode":"DYNAMIC","visibility":"TENANT"}}"""

    private fun Scenario.commitV2() {
        val copy = schema().deepCopy() as ObjectNode
        val extra = json.readTree(v2Members)
        extra.propertyNames().forEach { copy.set(it, extra.get(it)) }
        commits.commit(access.forProject(user.id, ws, projectId), revision(), copy, "EDIT", "V2 members")
    }

    private fun ids(n: JsonNode) = n.toList().map { it.get("id").asString() }

    @Test
    fun `the library lists the 13 built-in business templates with their scope and data slots`() {
        val sc = scenario()
        val company = sc.s.body(sc.s.get("/api/v1/templates?scope=company"))
        val builtIns = company.toList().filter { it.get("builtIn").asBoolean() }
        assertThat(builtIns).hasSize(13)
        assertThat(builtIns.all { it.get("scope").asString() == "SYSTEM" && !it.get("canEdit").asBoolean() }).isTrue()
        assertThat(sc.s.body(sc.s.get("/api/v1/templates?scope=company&category=crm")).toList().map { it.get("category").asString() }.toSet()).containsExactly("crm")
        assertThat(sc.s.body(sc.s.get("/api/v1/templates?scope=mine")).toList().none { it.get("builtIn").asBoolean() }).isTrue()
        val crm = builtIns.first { it.get("category").asString() == "crm" }
        assertThat(crm.get("dataSlots").get(0).get("dataSourceId").asString()).isEqualTo("main")
    }

    @Test
    fun `a project can start from a built-in template and keeps the V2 part`() {
        val sc = scenario()
        val crm = sc.s.body(sc.s.get("/api/v1/templates?scope=company&category=crm")).get(0).get("id").asString()
        val created = sc.s.post(api(sc.ws), """{"name":"Từ mẫu CRM","templateId":"$crm"}""")
        assertThat(created.response.status).isEqualTo(201)
        val pid = UUID.fromString(sc.s.body(created).get("id").asString())
        val schema = sc.s.body(sc.s.get("${api(sc.ws, pid)}/schema")).get("schema")
        assertThat(schema.get("schemaVersion").asInt()).isEqualTo(2)
        assertThat(schema.get("dataBindings").size()).isEqualTo(1)
        assertThat(schema.get("dataSources").get(0).has("sourceRef")).isFalse()          // the slot is unbound until the project binds its own source
    }

    @Test
    fun `a built-in template cannot be edited or archived`() {
        val sc = scenario()
        val id = sc.s.body(sc.s.get("/api/v1/templates?scope=company&category=hr")).get(0).get("id").asString()
        assertThat(sc.s.delete("/api/v1/templates/$id").response.status).isEqualTo(403)
        assertThat(sc.s.patch("/api/v1/templates/$id", """{"name":"Đổi tên"}""").response.status).isEqualTo(403)
        assertThat(sc.s.post("/api/v1/templates/$id/submit").response.status).isEqualTo(404)
    }

    @Test
    fun `saving a project as a template drops the connector id and the publish draft`() {
        val sc = scenario()
        sc.commitV2()
        val saved = sc.s.body(sc.s.post("${sc.base}/templates", """{"name":"Mẫu có dữ liệu"}"""))
        val schema = saved.get("template").get("schema")
        assertThat(schema.has("publishConfig")).isFalse()
        assertThat(schema.get("dataSources").get(0).has("sourceRef")).isFalse()
        assertThat(schema.get("queries").get(0).get("operationKey").asString()).isEqualTo("orders.list")     // kept for PRIVATE
        assertThat(saved.get("removedPrivateData").size()).isEqualTo(2)
        assertThat(saved.get("template").get("scope").asString()).isEqualTo("PRIVATE")
        assertThat(saved.get("template").get("dataSlots").get(0).get("dataSourceId").asString()).isEqualTo("crm")
    }

    @Test
    fun `submitting for company review removes the operation key and the source project stays hidden from other users`() {
        val sc = scenario()
        sc.commitV2()
        val id = sc.s.body(sc.s.post("${sc.base}/templates", """{"name":"Mẫu chia sẻ"}""")).get("template").get("id").asString()
        val submitted = sc.s.body(sc.s.post("/api/v1/templates/$id/submit"))
        assertThat(submitted.get("passed").asBoolean()).isTrue()
        assertThat(submitted.get("template").get("schema").get("queries").get(0).has("operationKey")).isFalse()
        assertThat(submitted.get("checks").toList().any { it.get("check").asString() == "tenant-data" && it.get("ok").asBoolean() }).isTrue()

        val admin = sessionFor(fx.user("gov", systemAdmin = true).username)
        assertThat(admin.body(admin.post("/api/v1/admin/templates/$id/review", """{"decision":"APPROVE"}""")).get("scope").asString()).isEqualTo("SYSTEM")
        val other = scenario()
        val seen = other.s.body(other.s.get("/api/v1/templates/$id"))
        assertThat(seen.get("sourceProjectId").isNull).isTrue()
        assertThat(ids(other.s.body(other.s.get("/api/v1/templates?scope=company")))).contains(id)
    }
}
