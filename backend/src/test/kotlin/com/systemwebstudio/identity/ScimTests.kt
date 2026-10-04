package com.systemwebstudio.identity

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as B
import java.util.UUID

/** Stage I: SCIM 2.0 provisioning (users, groups, explicit workspace mappings), never touching local accounts or granting system admin. */
@TestPropertySource(properties = ["app.scim.enabled=true", "app.scim.token=scim-test-token-0123456789abcdef0123456789"])
class ScimTests : IntegrationTestBase() {
    private val token = "scim-test-token-0123456789abcdef0123456789"
    private fun scim(method: String, path: String, body: String? = null, auth: String? = token): MvcResult {
        val b = when (method) { "GET" -> B.get("/scim/v2$path"); "POST" -> B.post("/scim/v2$path"); "PUT" -> B.put("/scim/v2$path"); "PATCH" -> B.patch("/scim/v2$path"); else -> B.delete("/scim/v2$path") }
        if (body != null) b.contentType(MediaType.parseMediaType("application/scim+json")).content(body)
        if (auth != null) b.header("Authorization", "Bearer $auth")
        return mvc.perform(b).andReturn()
    }
    private fun j(r: MvcResult) = json.readTree(r.response.contentAsString)
    private fun uniq() = UUID.randomUUID().toString().take(8)

    @Test
    fun `users - bearer token required, create, filter, patch active (string form), replace, deprovision disables and revokes, local accounts are invisible`() {
        assertThat(scim("GET", "/Users", auth = null).response.status).isEqualTo(401)
        assertThat(scim("GET", "/Users", auth = "wrong-token-wrong-token-wrong-token-xx").response.status).isEqualTo(401)
        assertThat(scim("GET", "/ServiceProviderConfig").response.status).isEqualTo(200)
        val name = "alice.${uniq()}@corp.example"
        val created = scim("POST", "/Users", """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"$name","externalId":"ext-${uniq()}",
            "name":{"givenName":"Alice","familyName":"Nguyen"},"emails":[{"value":"$name","primary":true}],"active":true,"roles":[{"value":"admin"}]}""")
        assertThat(created.response.status).isEqualTo(201); assertThat(created.response.contentType).contains("application/scim+json")
        val id = j(created).get("id").asString()
        assertThat(j(created).get("displayName").asString()).isEqualTo("Alice Nguyen")
        assertThat(jdbc.queryForObject("SELECT system_admin FROM users WHERE id = ?::uuid", Boolean::class.java, id)).isFalse()       // roles never grant anything
        val dup = scim("POST", "/Users", """{"userName":"$name"}""")
        assertThat(dup.response.status).isEqualTo(409); assertThat(j(dup).get("scimType").asString()).isEqualTo("uniqueness")
        fun filtered(f: String) = mvc.perform(B.get("/scim/v2/Users").param("filter", f).header("Authorization", "Bearer $token")).andReturn()
        assertThat(j(filtered("userName eq \"$name\"")).get("totalResults").asInt()).isEqualTo(1)
        assertThat(filtered("userName co \"a\"").response.status).isEqualTo(400)
        // Entra ID style: replace active with the string "False"
        assertThat(scim("PATCH", "/Users/$id", """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[{"op":"Replace","path":"active","value":"False"}]}""").response.status).isEqualTo(200)
        assertThat(jdbc.queryForObject("SELECT enabled FROM users WHERE id = ?::uuid", Boolean::class.java, id)).isFalse()
        assertThat(scim("PATCH", "/Users/$id", """{"Operations":[{"op":"replace","value":{"active":true,"displayName":"Alice N."}}]}""").response.status).isEqualTo(200)
        assertThat(j(scim("GET", "/Users/$id")).get("displayName").asString()).isEqualTo("Alice N.")
        assertThat(scim("PUT", "/Users/$id", """{"userName":"$name","displayName":"Alice","active":true,"emails":[]}""").response.status).isEqualTo(200)
        // local accounts (and the bootstrap admin) are never reachable through SCIM
        val local = fx.user("localonly")
        assertThat(scim("GET", "/Users/${local.id}").response.status).isEqualTo(404)
        assertThat(scim("PATCH", "/Users/${local.id}", """{"Operations":[{"op":"replace","path":"active","value":false}]}""").response.status).isEqualTo(404)
        assertThat(scim("DELETE", "/Users/$id").response.status).isEqualTo(204)
        assertThat(jdbc.queryForObject("SELECT enabled FROM users WHERE id = ?::uuid", Boolean::class.java, id)).isFalse()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action LIKE 'SCIM_%'", Long::class.java, id)).isGreaterThanOrEqualTo(4)
    }

    @Test
    fun `groups become workspace membership only through an explicit admin mapping, SCIM never removes manual memberships`() {
        val u1 = j(scim("POST", "/Users", """{"userName":"g1.${uniq()}"}""")).get("id").asString()
        val u2 = j(scim("POST", "/Users", """{"userName":"g2.${uniq()}"}""")).get("id").asString()
        val local = fx.user("manual")
        assertThat(scim("POST", "/Groups", """{"displayName":"x","members":[{"value":"${local.id}"}]}""").response.status).isEqualTo(400)   // only SCIM users
        val g = j(scim("POST", "/Groups", """{"displayName":"Sales ${uniq()}","members":[{"value":"$u1"},{"value":"$u2"}]}""")).get("id").asString()
        val ws = fx.workspace(); fx.member(ws, local, "EDITOR")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id = ?", Long::class.java, ws)).isEqualTo(1)   // no mapping yet: nothing
        val admin = sessionFor(fx.user("scimadm", systemAdmin = true).username)
        assertThat(sessionFor(local.username).post("/api/v1/admin/scim/mappings", """{"groupId":"$g","workspaceId":"$ws","role":"EDITOR"}""").response.status).isEqualTo(403)
        assertThat(admin.post("/api/v1/admin/scim/mappings", """{"groupId":"$g","workspaceId":"$ws","role":"SYSTEM_ADMIN"}""").response.status).isEqualTo(400)
        assertThat(admin.post("/api/v1/admin/scim/mappings", """{"groupId":"$g","workspaceId":"$ws","role":"EDITOR"}""").response.status).isEqualTo(200)
        assertThat(jdbc.queryForList("SELECT user_id::text FROM workspace_members WHERE workspace_id = ? AND source = 'SCIM'", String::class.java, ws)).containsExactlyInAnyOrder(u1, u2)
        assertThat(scim("PATCH", "/Groups/$g", """{"Operations":[{"op":"remove","path":"members[value eq \"$u2\"]"}]}""").response.status).isEqualTo(200)
        assertThat(jdbc.queryForList("SELECT user_id::text FROM workspace_members WHERE workspace_id = ? AND source = 'SCIM'", String::class.java, ws)).containsExactly(u1)
        assertThat(scim("DELETE", "/Groups/$g").response.status).isEqualTo(204)
        assertThat(jdbc.queryForList("SELECT user_id FROM workspace_members WHERE workspace_id = ?", UUID::class.java, ws)).containsExactly(local.id)   // manual stays
        assertThat(admin.body(admin.get("/api/v1/admin/scim")).get("enabled").asBoolean()).isTrue()
    }
}

/** Off by default: every SCIM endpoint answers 404, even with a token. */
@TestPropertySource(properties = ["app.scim.token=scim-test-token-0123456789abcdef0123456789"])
class ScimDisabledTests : IntegrationTestBase() {
    @Test
    fun `scim is off unless enabled`() {
        val r = mvc.perform(B.get("/scim/v2/Users").header("Authorization", "Bearer scim-test-token-0123456789abcdef0123456789")).andReturn()
        assertThat(r.response.status).isEqualTo(404)
    }
}
