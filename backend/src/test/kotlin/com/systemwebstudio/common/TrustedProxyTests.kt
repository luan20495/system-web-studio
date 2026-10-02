package com.systemwebstudio.common

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as B
import java.util.UUID

/** Same app, but configured as if it sat behind a proxy in 10.0.0.0/8. */
@TestPropertySource(properties = [
    "app.proxy.trust=true", "app.proxy.trusted-cidrs=10.0.0.0/8",
    "app.metrics.token=scrape-token-0123456789", "management.endpoints.web.exposure.include=health,prometheus"
])
class TrustedProxyTests : IntegrationTestBase() {
    private fun failedLoginIp(peer: String, forwarded: String?): String? {
        val name = "ghost-" + UUID.randomUUID().toString().take(8)
        val s = session().also { it.initCsrf() }
        s.perform(B.post("/api/v1/auth/login").contentType("application/json").content("""{"username":"$name","password":"wrong-password-1"}""")
            .with { it.remoteAddr = peer; it }.also { b -> forwarded?.let { b.header("X-Forwarded-For", it) } })
        return jdbc.queryForObject("SELECT ip_address FROM audit_events WHERE action='LOGIN_FAILURE' AND resource_id=?", String::class.java, name)
    }

    @Test
    fun `behind a trusted proxy the audit IP is the real client, and spoofed leading entries are ignored`() {
        assertThat(failedLoginIp("10.1.2.3", "203.0.113.9")).isEqualTo("203.0.113.9")
        assertThat(failedLoginIp("10.1.2.3", "6.6.6.6, 203.0.113.9")).isEqualTo("203.0.113.9")           // client-prepended 6.6.6.6 is never believed
        assertThat(failedLoginIp("10.1.2.3", "203.0.113.9, 10.9.9.9")).isEqualTo("203.0.113.9")          // trailing trusted hop skipped
        assertThat(failedLoginIp("10.1.2.3", "not-an-ip")).isEqualTo("10.1.2.3")
        assertThat(failedLoginIp("10.1.2.3", null)).isEqualTo("10.1.2.3")
    }

    @Test
    fun `a peer outside the trusted CIDRs cannot inject forwarded headers`() {
        assertThat(failedLoginIp("198.51.100.7", "1.2.3.4")).isEqualTo("198.51.100.7")
    }

    @Test
    fun `rate limiting uses the forwarded client, not the proxy address`() {
        val name = "victim-" + UUID.randomUUID().toString().take(8)
        fun attempt(client: String) = session().also { it.initCsrf() }.perform(B.post("/api/v1/auth/login").contentType("application/json")
            .content("""{"username":"$name","password":"wrong-password-1"}""").with { it.remoteAddr = "10.0.0.5"; it }.header("X-Forwarded-For", client)).response.status
        repeat(6) { attempt("203.0.113.${it + 10}") }                                                   // 6 failures for ONE username, six different IPs
        assertThat(attempt("203.0.113.99")).isEqualTo(429)                                              // username limit (5) still applies
        val other = "other-" + UUID.randomUUID().toString().take(8)
        val ok = session().also { it.initCsrf() }.perform(B.post("/api/v1/auth/login").contentType("application/json")
            .content("""{"username":"$other","password":"wrong-password-1"}""").with { it.remoteAddr = "10.0.0.5"; it }.header("X-Forwarded-For", "203.0.113.200")).response.status
        assertThat(ok).isEqualTo(401)                                                                   // an unrelated client behind the same proxy is not throttled
    }

    @Test
    fun `X-Forwarded-Proto is honoured only from a trusted proxy`() {
        val r = mvc.perform(B.get("/api/v1/auth/csrf").with { it.remoteAddr = "10.0.0.5"; it }.header("X-Forwarded-Proto", "https")).andReturn().response
        assertThat(r.getHeader("Strict-Transport-Security")).contains("max-age=63072000")
        val untrusted = mvc.perform(B.get("/api/v1/auth/csrf").with { it.remoteAddr = "198.51.100.7"; it }.header("X-Forwarded-Proto", "https")).andReturn().response
        assertThat(untrusted.getHeader("Strict-Transport-Security")).isNull()
    }

    @Test
    fun `prometheus endpoint needs the scrape token, sessions are not enough`() {
        assertThat(mvc.perform(B.get("/actuator/prometheus")).andReturn().response.status).isEqualTo(401)
        assertThat(mvc.perform(B.get("/actuator/prometheus").header("Authorization", "Bearer wrong-token-0123456789")).andReturn().response.status).isEqualTo(401)
        val ok = mvc.perform(B.get("/actuator/prometheus").header("Authorization", "Bearer scrape-token-0123456789")).andReturn().response
        assertThat(ok.status).isEqualTo(200); assertThat(ok.contentAsString).contains("jvm_memory")
        assertThat(sessionFor(fx.user("sess").username).get("/actuator/prometheus").response.status).isIn(401, 403)
    }
}
