package com.systemwebstudio.wiring

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Contract §1.4: with `app.data-platform.enabled` off (the default) no Management API route exists: every one answers 404, whoever asks. */
class ManagementFlagOffTests : IntegrationTestBase() {
    @Test
    fun `no management route is mounted by default - 404 for a workspace administrator too`() {
        val sc = scenario()
        val rowsBefore = count("SELECT count(*) FROM data_sources")
        val admin = sessionFor(fx.user("wsadmin").also { fx.member(sc.ws, it, "WORKSPACE_ADMIN") }.username)
        val base = "/api/v1/workspaces/${sc.ws}/data-sources"; val id = UUID.randomUUID(); val project = "/api/v1/workspaces/${sc.ws}/projects/${sc.projectId}/data-bindings"
        val calls = listOf(
            admin.get("$base/connectors"), admin.get(base), admin.post(base, """{"name":"n","type":"fake","config":{}}"""), admin.get("$base/$id"), admin.patch("$base/$id", """{"name":"x"}"""), admin.delete("$base/$id"),
            admin.get("$base/$id/credential"), admin.put("$base/$id/credential", """{"credential":{"a":"b"}}"""), admin.delete("$base/$id/credential"), admin.post("$base/$id/test", "{}"),
            admin.post("$base/$id/schema/discover", "{}"), admin.get("$base/$id/schema"),
            admin.get("$base/$id/queries"), admin.post("$base/$id/queries", "{}"), admin.get("$base/$id/queries/q"), admin.patch("$base/$id/queries/q", "{}"), admin.delete("$base/$id/queries/q"),
            admin.get("$base/$id/mutations"), admin.post("$base/$id/mutations", "{}"), admin.get("$base/$id/mutations/m"), admin.patch("$base/$id/mutations/m", "{}"), admin.delete("$base/$id/mutations/m"),
            admin.get(project), admin.put("$project/LIVE/slot", """{"dataSourceId":"$id"}"""), admin.delete("$project/LIVE/slot"))
        for (r in calls) assertThat(r.response.status).describedAs("${r.request.method} ${r.request.requestURI}").isEqualTo(404)
        assertThat(count("SELECT count(*) FROM data_sources")).describedAs("the create call changed nothing").isEqualTo(rowsBefore)
    }

    private fun count(sql: String) = jdbc.queryForObject(sql, Long::class.java)!!
}
