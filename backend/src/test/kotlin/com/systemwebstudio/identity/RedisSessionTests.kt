package com.systemwebstudio.identity

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate

class RedisSessionTests : IntegrationTestBase() {
    @Autowired lateinit var redis: StringRedisTemplate

    @Test
    fun `authenticated session is stored in Redis and cookie is HttpOnly`() {
        val user = fx.user()
        val s = session()
        val login = s.login(user.username)
        assertThat(login.response.status).isEqualTo(200)
        val header = login.response.getHeaders("Set-Cookie").joinToString()
        assertThat(header).contains("STUDIO_SESSION=").contains("HttpOnly").containsIgnoringCase("SameSite=Lax")
        assertThat(redis.keys("spring:session:*")).isNotEmpty
        assertThat(s.get("/api/v1/auth/me").response.status).isEqualTo(200)
    }
}
