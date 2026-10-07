package com.systemwebstudio.project.publishconfig

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.util.UUID

/** D-C0-37 · the publish-configuration API on the real repository: the first PUT used to fail with a 500 (the INSERT never set the NOT NULL tenant_id of V27). */
@TestPropertySource(properties = ["app.publish-configs.enabled=true"])
class PublishConfigApiIntegrationTests : IntegrationTestBase() {
    @Test
    fun `the first PUT creates the row with the tenant of the project's workspace, the second updates it by revision`() {
        val sc = scenario(); val url = "${sc.base}/publish-config"
        val created = sc.s.put(url, """{"mode":"STATIC","visibility":"PUBLIC","requiresAuth":false,"acknowledgePublicData":true}""")
        assertThat(created.response.status).describedAs(created.response.contentAsString).isEqualTo(200)
        val body = sc.s.body(created); assertThat(body.get("config").get("publicDataApproved").asBoolean()).isTrue()
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, sc.ws)
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM publish_configs WHERE project_id = ?", UUID::class.java, sc.projectId)).isEqualTo(tenant)
        val again = sc.s.put(url, """{"mode":"STATIC","visibility":"PUBLIC","requiresAuth":false,"acknowledgePublicData":false,"expectedRevision":${body.get("config").get("revision").asLong()}}""")
        assertThat(again.response.status).describedAs(again.response.contentAsString).isEqualTo(200)
        assertThat(sc.s.body(again).get("config").get("publicDataApproved").asBoolean()).isFalse()
        assertThat(sc.s.get(url).response.status).isEqualTo(200)
        // another workspace's project is a 404, nothing is written for it
        val other = scenario()
        val foreign = sc.s.put("${other.base}/publish-config", """{"mode":"STATIC","visibility":"PUBLIC","requiresAuth":false,"acknowledgePublicData":true}""")
        assertThat(foreign.response.status).describedAs(foreign.response.contentAsString).isEqualTo(404)
    }
}
