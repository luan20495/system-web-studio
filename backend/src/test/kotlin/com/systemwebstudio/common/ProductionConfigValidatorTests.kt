package com.systemwebstudio.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/** The prod profile must refuse unsafe values, including those of features added after the first hardening pass. */
class ProductionConfigValidatorTests {
    private val strong = "Aa1-very-strong-secret-value-0001"
    private fun base() = MockEnvironment().withProperty("spring.datasource.password", strong).withProperty("spring.data.redis.password", strong)
        .withProperty("spring.rabbitmq.password", strong).withProperty("app.storage.secret-key", strong).withProperty("server.servlet.session.cookie.secure", "true")
        .withProperty("app.cors.allowed-origins", "https://studio.example.com").withProperty("app.storage.public-endpoint", "https://files.example.com")
        .withProperty("app.forms.ip-salt", strong).withProperty("app.deploy.provider", "static")
        .withProperty("app.sites.origin", "https://sites.example.com").withProperty("app.sites.studio-origin", "https://studio.example.com").withProperty("app.render.url", "http://render.internal:18095")
    private fun refuses(env: MockEnvironment, contains: String) = assertThatThrownBy { ProductionConfigValidator(env) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining(contains)

    @Test fun `a safe configuration starts`() { ProductionConfigValidator(base()) }
    @Test fun `public sign-up needs an explicit acknowledgement`() {
        refuses(base().withProperty("app.signup.enabled", "true"), "sign-up")
        ProductionConfigValidator(base().withProperty("app.signup.enabled", "true").withProperty("app.signup.allow-in-prod", "true"))
    }
    @Test fun `mock deploy provider, weak bootstrap admin and missing forms salt are refused`() {
        refuses(base().withProperty("app.deploy.provider", "mock"), "DEPLOY_PROVIDER=mock")
        refuses(base().withProperty("app.bootstrap.admin-username", "op").withProperty("app.bootstrap.admin-password", "short"), "BOOTSTRAP_ADMIN_PASSWORD")
        refuses(base().withProperty("app.forms.ip-salt", ""), "FORMS_IP_SALT")
    }
    @Test fun `wildcard or localhost CORS and an unsafe cookie are refused`() {
        refuses(base().withProperty("app.cors.allowed-origins", "*"), "https")
        refuses(base().withProperty("app.cors.allowed-origins", "https://localhost:3000"), "localhost")
        refuses(base().withProperty("server.servlet.session.cookie.secure", "false"), "Secure")
    }
    @Test fun `scim saml and server runtime need their secrets when enabled`() {
        refuses(base().withProperty("app.scim.enabled", "true").withProperty("app.scim.token", "short"), "SCIM_TOKEN")
        refuses(base().withProperty("app.saml.enabled", "true"), "OIDC_ENABLED")
        refuses(base().withProperty("app.runtime.enabled", "true"), "SECRETS_MASTER_KEY")
        assertThat(runCatching { ProductionConfigValidator(base().withProperty("app.runtime.enabled", "true").withProperty("app.secrets.master-key", java.util.Base64.getEncoder().encodeToString(ByteArray(32)))
            .withProperty("app.runtime.gateway-token", strong + strong).withProperty("app.runtime.appdb-admin-password", strong)) }.isSuccess).isTrue()
    }

    @Test fun `sites and render addresses are required and the browser-facing ones are public https (D-C0-34)`() {
        refuses(base().withProperty("app.sites.origin", ""), "app.sites.origin is required")
        refuses(base().withProperty("app.sites.studio-origin", ""), "app.sites.studio-origin is required")
        refuses(base().withProperty("app.render.url", ""), "app.render.url is required")
        refuses(base().withProperty("app.sites.origin", "http://sites.example.com"), "must be https")
        refuses(base().withProperty("app.sites.origin", "https://localhost:18088"), "public address")
        refuses(base().withProperty("app.sites.studio-origin", "https://host.docker.internal:3003"), "public address")
    }

    @Test fun `apiBase is optional, and when set it is a public https address a browser can reach`() {
        ProductionConfigValidator(base())
        ProductionConfigValidator(base().withProperty("app.sites.data-api-base", "https://data.example.com/api/v1"))
        for (bad in listOf("http://data.example.com/api", "https://localhost:8080", "https://127.0.0.1:8080/api", "https://host.docker.internal:8080", "https://10.1.2.3/api", "https://192.168.1.5/api", "https://172.20.0.4/api", "https://api.svc.cluster.local/x"))
            refuses(base().withProperty("app.sites.data-api-base", bad), "SITES_DATA_API_BASE")
    }
}
