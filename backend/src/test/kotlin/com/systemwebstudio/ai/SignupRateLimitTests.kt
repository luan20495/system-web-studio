package com.systemwebstudio.ai

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as B
import java.util.UUID

@TestPropertySource(properties = ["app.signup.enabled=true", "app.signup.ip-max-per-hour=3"])
class SignupRateLimitTests : IntegrationTestBase() {
    private fun attempt(ip: String) = session().also { it.initCsrf() }.perform(
        B.post("/api/v1/auth/register").contentType("application/json")
            .content("""{"username":"rl${UUID.randomUUID().toString().take(8)}","password":"correct-horse-battery"}""").with { it.remoteAddr = ip; it }).response.status

    @Test
    fun `the 4th sign-up from one IP in an hour is refused, another IP is unaffected`() {
        val ip = "203.0.113.${(1..250).random()}"; val other = "192.0.2.${(1..250).random()}"
        assertThat((1..3).map { attempt(ip) }).containsOnly(201)
        assertThat(attempt(ip)).isEqualTo(429)
        assertThat(attempt(other)).isEqualTo(201)
    }
}
