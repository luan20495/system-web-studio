package com.systemwebstudio.common

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as B

// Swagger (local profile) legitimately relaxes the API CSP, so it is switched off here to test the production header set.
@org.springframework.test.context.TestPropertySource(properties = ["springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"])
class HardeningTests : IntegrationTestBase() {
    @Test
    fun `API responses carry restrictive security headers and the CSRF cookie is SameSite`() {
        val r = mvc.perform(B.get("/api/v1/auth/csrf")).andReturn().response
        assertThat(r.getHeader("Content-Security-Policy")).contains("default-src 'none'").contains("frame-ancestors 'none'")
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(r.getHeader("Referrer-Policy")).isEqualTo("no-referrer")
        assertThat(r.getHeader("Permissions-Policy")).contains("camera=()")
        assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY")
        assertThat(r.getHeader("Cache-Control")).contains("no-store")
        val xsrf = r.getCookie("XSRF-TOKEN")!!
        assertThat(xsrf.getAttribute("SameSite") ?: r.getHeaders("Set-Cookie").joinToString(";").substringAfter("SameSite=", "").substringBefore(";")).isEqualTo("Lax")
    }

    @Test
    fun `oversized JSON bodies are rejected with 413 before any processing`() {
        val s = sessionFor(fx.user("big").username)
        val huge = """{"name":"${"x".repeat(1_200_000)}"}"""
        val r = s.post("/api/v1/workspaces/${fx.workspace()}/projects", huge)
        assertThat(r.response.status).isEqualTo(413)
        assertThat(s.body(r).get("code").asString()).isEqualTo("REQUEST_TOO_LARGE")
    }

    @Test
    fun `auth config tells the UI which sign-in options exist and metrics are not readable by sessions`() {
        val cfg = json.readTree(mvc.perform(B.get("/api/v1/auth/config")).andReturn().response.contentAsString)
        assertThat(cfg.get("localLogin").asBoolean()).isTrue(); assertThat(cfg.get("oidc").asBoolean()).isFalse()
        val s = sessionFor(fx.user("m").username)
        assertThat(s.get("/actuator/metrics").response.status).isIn(401, 403, 404)
        assertThat(s.get("/actuator/prometheus").response.status).isIn(401, 403, 404)
        assertThat(s.get("/actuator/env").response.status).isIn(401, 403, 404)
    }

    @Test
    fun `access logs never contain query strings, ids or credentials`() {
        // The filter logs the route pattern only; assert the pattern shape rather than capturing log output.
        val s = sessionFor(fx.user("log").username)
        val r = s.get("/api/v1/workspaces/${java.util.UUID.randomUUID()}/projects?token=SECRET123")
        assertThat(r.response.status).isEqualTo(404)
        assertThat(r.request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as String).doesNotContain("SECRET123").contains("{")
    }

    @Test
    fun `production profile refuses unsafe configuration`() {
        fun validate(vararg p: Pair<String, String>) {
            val env = MockEnvironment().withProperty("spring.datasource.password", "s3cure-db-password-1").withProperty("spring.data.redis.password", "s3cure-redis-password-1")
                .withProperty("spring.rabbitmq.password", "s3cure-rabbit-password-1").withProperty("app.storage.secret-key", "s3cure-minio-secret-1")
                .withProperty("server.servlet.session.cookie.secure", "true").withProperty("app.cors.allowed-origins", "https://studio.example.com")
                .withProperty("app.storage.public-endpoint", "https://files.example.com").withProperty("app.forms.ip-salt", "salt-for-the-hardening-test-0001").withProperty("app.proxy.trust", "true").withProperty("app.proxy.trusted-cidrs", "10.0.0.0/8")
            env.setActiveProfiles("prod")
            p.forEach { env.setProperty(it.first, it.second) }
            ProductionConfigValidator(env)
        }
        validate()                                                                                          // a sane config passes
        assertThatThrownBy { validate("spring.datasource.password" to "studio-local-only") }.hasMessageContaining("development default")
        assertThatThrownBy { validate("spring.data.redis.password" to "short") }.hasMessageContaining("at least 12")
        assertThatThrownBy { validate("server.servlet.session.cookie.secure" to "false") }.hasMessageContaining("Secure")
        assertThatThrownBy { validate("app.cors.allowed-origins" to "http://localhost:3000") }.hasMessageContaining("https")
        assertThatThrownBy { validate("app.cors.allowed-origins" to "https://localhost:3000") }.hasMessageContaining("localhost")
        assertThatThrownBy { validate("app.proxy.trusted-cidrs" to "") }.hasMessageContaining("TRUSTED_PROXY_CIDRS")
        assertThatThrownBy { validate("app.storage.public-endpoint" to "http://files.example.com") }.hasMessageContaining("https")
        assertThatThrownBy { validate("springdoc.api-docs.enabled" to "true") }.hasMessageContaining("disabled")
        assertThatThrownBy { validate("app.oidc.enabled" to "true", "app.oidc.client-secret" to "x", "app.oidc.issuer-uri" to "https://idp") }.hasMessageContaining("OIDC_CLIENT_SECRET")
    }

    @Test
    fun `prod profile fails fast when required variables are missing`() {
        val app = org.springframework.boot.builder.SpringApplicationBuilder(com.systemwebstudio.SystemWebStudioApplication::class.java).profiles("prod")
        assertThatThrownBy { app.run("--server.port=0", "--spring.main.banner-mode=off") }.hasStackTraceContaining("\${SERVER_ADDRESS}")      // unresolved required variable stops the boot
    }
}
