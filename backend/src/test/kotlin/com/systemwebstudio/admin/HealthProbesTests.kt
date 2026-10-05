package com.systemwebstudio.admin

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Platform health reports only real probes in the five honest states; components that are not configured say so instead of "healthy". */
class HealthProbesTests : IntegrationTestBase() {
    @Test
    fun `health lists the added components with honest states`() {
        val admin = sessionFor(fx.user("healthadm", systemAdmin = true).username)
        val items = admin.body(admin.get("/api/v1/admin/system/health")).get("items").toList().associateBy { it.get("name").asString() }
        assertThat(items.keys).contains("PostgreSQL", "Redis", "Render worker", "Git server (Forgejo)", "Build runner", "Server runtime", "Backups")
        val allowed = setOf("HEALTHY", "DEGRADED", "UNAVAILABLE", "UNKNOWN", "NOT_CONFIGURED")
        assertThat(items.values.map { it.get("status").asString() }).allSatisfy { assertThat(it).isIn(allowed) }
        assertThat(items["Git server (Forgejo)"]!!.get("status").asString()).isEqualTo("NOT_CONFIGURED")      // no Git server in the test environment
        assertThat(items["Server runtime"]!!.get("status").asString()).isEqualTo("NOT_CONFIGURED")
        assertThat(items["Backups"]!!.get("status").asString()).isEqualTo("UNKNOWN")                          // no status directory: no claim either way
        assertThat(sessionFor(fx.user("healthusr").username).get("/api/v1/admin/system/health").response.status).isEqualTo(403)
    }
}
