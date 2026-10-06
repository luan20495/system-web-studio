package com.systemwebstudio.wiring

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** C0 · three exact, distinct origins (docs/parallel/WEB_SECURITY_CONFIG.md §2). Pure unit test; not run when written (no Gradle). */
class WebOriginsTests {
    private val dev = WebOrigins("http://localhost:3001", "http://localhost:3002", "http://localhost:3003")

    @Test fun `cors is exactly the three origins and never a wildcard`() {
        assertThat(dev.cors()).containsExactly("http://localhost:3001", "http://localhost:3002", "http://localhost:3003")
        assertThat(dev.cors()).noneMatch { it.contains("*") }
    }

    @Test fun `every portal gets its own OIDC redirect and logout URI`() {
        assertThat(dev.oidcRedirectUris()).containsExactly(
            "http://localhost:3001/login/oauth2/code/oidc", "http://localhost:3002/login/oauth2/code/oidc", "http://localhost:3003/login/oauth2/code/oidc")
        assertThat(dev.oidcPostLogoutUris()).containsExactly("http://localhost:3001/login", "http://localhost:3002/login", "http://localhost:3003/login")
    }

    @Test fun `wildcards paths and duplicates are refused`() {
        assertThatThrownBy { WebOrigins("*", "http://localhost:3002", "http://localhost:3003") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WebOrigins("https://*.example.com", "https://admin.example.com", "https://studio.example.com") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WebOrigins("https://platform.example.com/app", "https://admin.example.com", "https://studio.example.com") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WebOrigins("https://a.example.com", "https://a.example.com", "https://studio.example.com") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
