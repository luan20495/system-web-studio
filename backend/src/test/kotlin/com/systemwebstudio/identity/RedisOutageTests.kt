package com.systemwebstudio.identity

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A Redis outage must be a fast, structured 503 and must heal by itself; never a hang or an opaque 500. */
class RedisOutageTests : IntegrationTestBase() {
    @Test
    fun `redis down gives a structured 503 quickly and the same session works again afterwards`() {
        val u = fx.user("outage")
        val s = sessionFor(u.username)
        assertThat(s.get("/api/v1/auth/me").response.status).isEqualTo(200)

        val docker = redis.dockerClient
        docker.pauseContainerCmd(redis.containerId).exec()
        try {
            val started = System.nanoTime()
            val r = s.get("/api/v1/auth/me")
            val seconds = (System.nanoTime() - started) / 1_000_000_000.0
            assertThat(r.response.status).isEqualTo(503)
            assertThat(s.body(r).get("code").asString()).isEqualTo("DEPENDENCY_UNAVAILABLE")
            assertThat(s.body(r).get("requestId").asString()).isNotBlank()
            assertThat(r.response.getHeader("Retry-After")).isEqualTo("5")
            assertThat(seconds).describedAs("fail fast").isLessThan(10.0)
        } finally {
            docker.unpauseContainerCmd(redis.containerId).exec()
        }
        assertThat(s.get("/api/v1/auth/me").response.status).isEqualTo(200)      // session was never lost
    }
}
