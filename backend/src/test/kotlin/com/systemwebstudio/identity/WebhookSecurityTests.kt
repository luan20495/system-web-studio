package com.systemwebstudio.identity

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*

/**
 * C0 · exact security of POST /api/v1/webhooks/data/{endpointId} (docs/parallel/WEB_SECURITY_CONFIG.md §1). Written without a Gradle run:
 * the Mac gate (MAC_INTEGRATION_CHECKLIST) executes it. Until C3 is imported there is no controller, so the one open route answers 404
 * (reached the dispatcher) while every neighbour stays 401: that difference is what these tests pin. Signature checks are C3's tests.
 */
class WebhookSecurityTests : IntegrationTestBase() {
    private fun status(r: org.springframework.test.web.servlet.MvcResult) = r.response.status

    @Test fun `the ingest route is anonymous and needs no CSRF token`() {
        val r = mvc.perform(post("/api/v1/webhooks/data/6f1d2c0e-0000-4000-8000-000000000001").content("{}").contentType("application/json")).andReturn()
        assertThat(status(r)).isNotIn(401, 403)          // not stopped by authentication or CSRF (404 until C3's controller exists)
    }

    @Test fun `neighbouring routes stay authenticated`() {
        for (path in listOf("/api/v1/webhooks/other", "/api/v1/webhooks/data", "/api/v1/webhooks/data/a/b", "/api/v1/webhooks/data/a/replay", "/api/v1/webhooks"))
            assertThat(status(mvc.perform(post(path).content("{}").contentType("application/json")).andReturn())).withFailMessage("$path must not be open").isIn(401, 403)
    }

    @Test fun `only POST is open on the ingest path`() {
        for (m in listOf(get("/api/v1/webhooks/data/abc"), put("/api/v1/webhooks/data/abc"), delete("/api/v1/webhooks/data/abc"), patch("/api/v1/webhooks/data/abc")))
            assertThat(status(mvc.perform(m).andReturn())).isIn(401, 403)
    }

    @Test fun `an unknown route elsewhere still needs a CSRF token and a session`() {
        assertThat(status(mvc.perform(post("/api/v1/projects").content("{}").contentType("application/json")).andReturn())).isIn(401, 403)
    }

    @Test fun `CORS origins must be exact`() {
        assertThat(SecurityConfiguration.requireExactOrigins(listOf("http://localhost:3001", " https://studio.example.com "))).containsExactly("http://localhost:3001", "https://studio.example.com")
        for (bad in listOf("*", "https://*.example.com", "null", "http://localhost:3001/", "https://studio.example.com/app", "localhost:3001"))
            assertThatThrownBy { SecurityConfiguration.requireExactOrigins(listOf(bad)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SecurityConfiguration.requireExactOrigins(emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
