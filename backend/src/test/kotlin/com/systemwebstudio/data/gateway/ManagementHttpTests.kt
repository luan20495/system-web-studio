package com.systemwebstudio.data.gateway

import com.systemwebstudio.data.datasource.ConnectorCapability
import com.systemwebstudio.data.datasource.ConnectorDescriptor
import com.systemwebstudio.data.datasource.ConnectorStatus
import com.systemwebstudio.data.datasource.CredentialInfo
import com.systemwebstudio.data.datasource.DataSourceStatus
import com.systemwebstudio.data.datasource.DataSourceView
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.query.DataJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The strict request parsers and safe response projections of the Data Source Management API. */
class ManagementHttpTests {
    private val secret = "sk-live-HTTP-SECRET-4242"
    private fun json(s: String) = DataJson.parse(s.toByteArray())
    private fun code(block: () -> Unit): String { try { block() } catch (e: com.systemwebstudio.data.datasource.ConnectorFailure) { assertThat(e.toString() + e.message).doesNotContain(secret); return e.code }; throw AssertionError("expected a ConnectorFailure") }

    @Test fun `create takes name type config and an optional credential and nothing else`() {
        val spec = ManagementRequests.create(json("""{"name":"billing","type":"postgres","config":{"host":"db.example.com","port":5432,"sslmode":"verify-full","writable":true},"credential":{"user":"u","password":"$secret"}}"""))
        assertThat(spec.name).isEqualTo("billing"); assertThat(spec.type).isEqualTo("postgres")
        assertThat(spec.config["port"]).isEqualTo("5432"); assertThat(spec.config["writable"]).isEqualTo("true"); assertThat(spec.config["host"]).isEqualTo("db.example.com")
        assertThat(spec.credential!!["password"]).isEqualTo(secret)
        assertThat(spec.toString()).doesNotContain(secret)
        assertThat(ManagementRequests.create(json("""{"name":"n","type":"postgres"}""")).config).isEmpty()
        for (body in listOf(
            """{"name":"n","type":"t","tenantId":"$secret"}""", """{"name":"n","type":"t","workspaceId":"x"}""", """{"name":"n","type":"t","credentialRef":"x"}""", """{"name":"n","type":"t","status":"ACTIVE"}""",
            """{"type":"t"}""", """{"name":"n"}""", """{"name":1,"type":"t"}""", """{"name":"n","type":"t","config":[]}""", """{"name":"n","type":"t","config":{"a":{"b":1}}}""",
            """{"name":"n","type":"t","config":{"a":1.5}}""", """{"name":"n","type":"t","config":{"a":null}}""", """{"name":"n","type":"t","credential":"$secret"}""", """{"name":"n","type":"t","credential":{}}""",
            """{"name":"n","type":"t","credential":{"a":1}}""", """{"name":"n","type":"t","credential":{"a":{"b":"$secret"}}}""", """[]""", """"text""""))
            assertThat(code { ManagementRequests.create(json(body)) }).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(code { ManagementRequests.create(null) }).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(code { ManagementRequests.create(json("""{"name":"n","type":"t","credential":{${(1..9).joinToString(",") { "\"k$it\":\"v\"" }}}}""")) }).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `update accepts name config and status and needs at least one`() {
        val c = ManagementRequests.update(json("""{"name":"x","config":{"host":"h"},"status":"DISABLED"}"""))
        assertThat(c.name).isEqualTo("x"); assertThat(c.config!!["host"]).isEqualTo("h"); assertThat(c.status).isEqualTo(DataSourceStatus.DISABLED)
        assertThat(ManagementRequests.update(json("""{"status":"ACTIVE"}""")).status).isEqualTo(DataSourceStatus.ACTIVE)
        for (body in listOf("""{}""", """{"status":"DELETED"}""", """{"status":"active"}""", """{"credential":{"a":"$secret"}}""", """{"name":null}""", """{"config":null}""", """{"type":"mysql"}""", """{"tenantId":"x"}"""))
            assertThat(code { ManagementRequests.update(json(body)) }).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `the credential body is write only and strict`() {
        assertThat(ManagementRequests.credential(json("""{"credential":{"user":"u","password":"$secret"}}"""))["password"]).isEqualTo(secret)
        for (body in listOf("""{}""", """{"credential":{}}""", """{"credential":"$secret"}""", """{"credential":{"a":"b"},"extra":1}""", """{"password":"$secret"}"""))
            assertThat(code { ManagementRequests.credential(json(body)) }).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `binding targets slots and ids are validated and a bad id looks like a missing one`() {
        val id = UUID.randomUUID()
        assertThat(ManagementRequests.bindingTarget(json("""{"dataSourceId":"$id"}"""))).isEqualTo(id)
        for (body in listOf("""{}""", """{"dataSourceId":"nope"}""", """{"dataSourceId":1}""", """{"dataSourceId":"$id","mode":"LIVE"}"""))
            assertThat(code { ManagementRequests.bindingTarget(json(body)) }).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(ManagementRequests.id(id.toString())).isEqualTo(id)
        assertThat(code { ManagementRequests.id("../etc/passwd") }).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(ManagementRequests.slot("main")).isEqualTo("main"); assertThat(ManagementRequests.slot("crm.v2-a_b")).isEqualTo("crm.v2-a_b")
        for (bad in listOf("", " ", "-x", "a/b", "a b", "a;b", "x".repeat(129), "é")) assertThat(code { ManagementRequests.slot(bad) }).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `responses carry only the safe projection`() {
        val id = UUID.randomUUID(); val ws = UUID.randomUUID(); val now = Instant.parse("2026-10-05T10:00:00Z")
        val view = DataSourceView(id, UUID.randomUUID(), ws, "billing", "postgres", mapOf("host" to "db.example.com"), true, DataSourceStatus.ACTIVE, UUID.randomUUID(), now, now, 3)
        val node = ManagementResponses.dataSource(view)
        assertThat(DataJson.keys(node)).containsExactlyInAnyOrder("id", "workspaceId", "name", "type", "config", "hasCredential", "status", "version", "createdBy", "createdAt", "updatedAt")
        assertThat(node.get("hasCredential").asBoolean()).isTrue(); assertThat(node.get("version").asLong()).isEqualTo(3L)
        assertThat(node.toString()).doesNotContain("tenantId").doesNotContain("credentialRef")
        assertThat(DataJson.elements(ManagementResponses.dataSources(listOf(view)).get("items"))).hasSize(1)

        val by = UUID.randomUUID()
        val cred = ManagementResponses.credential(CredentialInfo(true, "postgres", listOf("user", "password"), now, by))
        assertThat(DataJson.keys(cred)).containsExactlyInAnyOrder("configured", "type", "keys", "updatedAt", "updatedBy")
        assertThat(cred.get("configured").asBoolean()).isTrue(); assertThat(cred.get("type").asString()).isEqualTo("postgres")
        assertThat(cred.get("updatedBy").asString()).isEqualTo(by.toString()); assertThat(cred.get("updatedAt").asString()).isEqualTo(now.toString())
        assertThat(DataJson.elements(cred.get("keys")).map { it.asString() }).containsExactly("user", "password")
        val none = ManagementResponses.credential(CredentialInfo(false, "postgres", listOf("user", "password"), null, null))
        assertThat(none.get("configured").asBoolean()).isFalse(); assertThat(none.get("updatedAt").isNull).isTrue(); assertThat(none.get("updatedBy").isNull).isTrue()

        val b = ManagementResponses.binding("LIVE", "main", id, now)
        assertThat(DataJson.keys(b)).containsExactlyInAnyOrder("mode", "slotId", "dataSourceId", "updatedAt")
        val catalog = ManagementResponses.connectors(listOf(ConnectorDescriptor("postgres", "PostgreSQL", ConnectorStatus.AVAILABLE, setOf(ConnectorCapability.QUERY, ConnectorCapability.MUTATION), credentialKeys = listOf("user", "password"))))
        assertThat(DataJson.elements(catalog.get("items"))[0].get("capabilities").toString()).contains("MUTATION")
    }
}
