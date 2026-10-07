package com.systemwebstudio.wiring

import com.systemwebstudio.data.datasource.DataConnectorRegistry
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.test.context.TestPropertySource

/** B-C0-W-06 · with the data platform OFF an allow-list changes nothing: no connector registry, no policy, no Management route (fail closed). */
@TestPropertySource(properties = ["app.data-platform.enabled=false", "app.data-platform.postgres-targets.allowed-private=127.0.0.1:15440"])
class PostgresTargetFlagOffTests : IntegrationTestBase() {
    @Autowired lateinit var ctx: ApplicationContext

    @Test
    fun `nothing is built and no route answers, whatever the allow-list says`() {
        assertThat(ctx.getBeansOfType(DataConnectorRegistry::class.java)).isEmpty()
        val sc = scenario()
        val admin = sessionFor(fx.user("wsadmin").also { fx.member(sc.ws, it, "WORKSPACE_ADMIN") }.username)
        val r = admin.post("/api/v1/workspaces/${sc.ws}/data-sources", """{"name":"x","type":"postgres","config":{"host":"127.0.0.1","port":"15440","database":"d"}}""")
        assertThat(r.response.status).isEqualTo(404)
    }
}
