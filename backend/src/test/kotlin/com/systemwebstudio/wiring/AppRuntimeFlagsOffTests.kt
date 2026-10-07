package com.systemwebstudio.wiring

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Default configuration: both flags are false, so none of the runtime routes exists (and none of the runtime beans). */
class AppRuntimeFlagsOffTests : IntegrationTestBase() {
    @Test
    fun `no runtime route is mounted by default`() {
        val sc = scenario()
        val base = "${sc.base}/app-runtime"
        assertThat(sc.s.post("$base/actions/go-home/execute", "{}").response.status).isIn(404, 405)
        assertThat(sc.s.post("$base/queries/q/run", "{}").response.status).isIn(404, 405)
        assertThat(sc.s.post("$base/workflows/w/runs", """{"idempotencyKey":"k-12345678"}""").response.status).isIn(404, 405)
        assertThat(sc.s.get("$base/workflow-runs/${UUID.randomUUID()}").response.status).isIn(404, 405)
    }

    @Test
    fun `the data platform administration family is not mounted either`() {
        val sc = scenario()
        assertThat(sc.s.post("/api/v1/data/query", "{}").response.status).isIn(404, 405)
        assertThat(sc.s.post("/api/v1/data/mutate", "{}").response.status).isIn(404, 405)
    }
}
