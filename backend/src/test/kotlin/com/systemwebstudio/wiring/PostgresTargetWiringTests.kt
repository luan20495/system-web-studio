package com.systemwebstudio.wiring

import com.systemwebstudio.data.datasource.DataConnectorRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.context.TestPropertySource

/**
 * B-C0-W-06 · the real Spring wiring: the allow-list key reaches the policy of the `postgres` connector through the Management API. The test database of this
 * run is the "platform database" (`spring.datasource.url`, a loopback endpoint on a random port); the configured allowed target is another loopback endpoint.
 */
@Import(ManagementTestBeans::class)
@TestPropertySource(properties = ["app.data-platform.postgres-targets.allowed-private=127.0.0.1:15440"])
class PostgresTargetWiringTests : ManagementApiTestBase() {
    @Autowired lateinit var env: Environment

    private fun pg(w: W, host: String, port: Int) = w.s.post(w.base, """{"name":"${uniq("pg")}","type":"postgres","config":{"host":"$host","port":"$port","database":"shop","schemas":"shop"}}""")

    @Test
    fun `the configured private target can be saved and tested, any other loopback endpoint is refused`() {
        val w = admin()
        val ok = pg(w, "127.0.0.1", 15440)
        assertThat(ok.response.status).describedAs(ok.response.contentAsString).isEqualTo(201)
        val id = w.s.body(ok).get("id").asString()
        // nothing listens on 15440 in this run: the policy let the call through (not ADDRESS_BLOCKED), the connection itself fails
        w.s.put("${w.base}/$id/credential", """{"credential":{"username":"ro_user","password":"$secret"}}""")
        val test = w.s.body(w.s.post("${w.base}/$id/test", "{}"))
        assertThat(test.get("ok").asBoolean()).isFalse()
        assertThat(test.get("code").asString()).describedAs("not blocked by the target policy").isIn("CONNECT_FAILED", "TIMEOUT", "TLS_FAILED", "AUTH_REJECTED")
        assertNoSecretAnywhere(secret)
        for (port in listOf(15441, 5432, 15432, 15434)) {
            val r = pg(w, "127.0.0.1", port)
            assertThat(r.response.status).describedAs("127.0.0.1:$port").isEqualTo(400)
            assertThat(w.s.body(r).get("code").asString()).isEqualTo("INVALID_CONFIG")
        }
    }

    @Test
    fun `the platform database of this very run cannot be made a data source, by any name`() {
        val w = admin()
        val url = env.getProperty("spring.datasource.url")!!                       // jdbc:postgresql://localhost:<port>/test?...
        val port = Regex("//[^:/]+:(\\d+)/").find(url)!!.groupValues[1].toInt()
        for (host in listOf("localhost", "127.0.0.1")) {
            val r = pg(w, host, port)
            assertThat(r.response.status).describedAs("$host:$port").isEqualTo(400)
            assertThat(w.s.body(r).get("code").asString()).isEqualTo("INVALID_CONFIG")
        }
        assertThat(count("SELECT count(*) FROM data_sources WHERE workspace_id = ? AND type = 'postgres' AND config_nonsecret->>'port' = ?", w.ws, port.toString())).isZero()
    }

    @Test
    fun `the connector registry is built with the policy only when the data platform is on`() {
        assertThat(ctx.getBeansOfType(DataConnectorRegistry::class.java)).hasSize(1)
    }

    @Autowired lateinit var ctx: ApplicationContext
}
