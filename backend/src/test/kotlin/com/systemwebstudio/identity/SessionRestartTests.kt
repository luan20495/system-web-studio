package com.systemwebstudio.identity

import com.systemwebstudio.SystemWebStudioApplication
import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.support.TestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Two real application processes' worth of lifecycle: the session must live in Redis, not in the JVM. */
class SessionRestartTests : IntegrationTestBase() {
    private fun start(): ConfigurableApplicationContext = SpringApplicationBuilder(SystemWebStudioApplication::class.java)
        .profiles("local").run(
            "--server.port=0", "--spring.main.banner-mode=off",
            "--spring.datasource.url=${postgres.jdbcUrl}", "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}", "--spring.data.redis.host=${redis.host}",
            "--spring.data.redis.port=${redis.getMappedPort(6379)}", "--LOCAL_ADMIN_PASSWORD=$ADMIN_PASSWORD",
            "--server.servlet.session.cookie.secure=false",
            "--app.storage.endpoint=http://${minio.host}:${minio.getMappedPort(9000)}",
            "--app.storage.public-endpoint=http://${minio.host}:${minio.getMappedPort(9000)}",
            "--app.storage.access-key=$MINIO_USER", "--app.storage.secret-key=$MINIO_PASSWORD",
            "--spring.rabbitmq.host=${rabbit.host}", "--spring.rabbitmq.port=${rabbit.getMappedPort(5672)}",
            "--spring.rabbitmq.username=studio", "--spring.rabbitmq.password=test-rabbit-123"
        )

    private fun port(c: ConfigurableApplicationContext) = c.environment.getProperty("local.server.port")!!.toInt()

    @Test
    fun `session survives a full application restart while Redis stays up`() {
        val user = fx.user("restart")
        val cookies = CookieManager()
        val client = HttpClient.newBuilder().cookieHandler(cookies).build()
        var ctx = start()
        try {
            val base = "http://localhost:${port(ctx)}"
            val csrf = client.send(HttpRequest.newBuilder(URI("$base/api/v1/auth/csrf")).build(), HttpResponse.BodyHandlers.ofString())
            val token = Regex("\"token\":\"([^\"]+)\"").find(csrf.body())!!.groupValues[1]
            val login = client.send(
                HttpRequest.newBuilder(URI("$base/api/v1/auth/login")).header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString("""{"username":"${user.username}","password":"${TestFixtures.PASSWORD}"}""")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertThat(login.statusCode()).isEqualTo(200)
        } finally { ctx.close() }

        ctx = start()                                               // new JVM-side state, same Redis
        try {
            val me = client.send(HttpRequest.newBuilder(URI("http://localhost:${port(ctx)}/api/v1/auth/me")).build(), HttpResponse.BodyHandlers.ofString())
            assertThat(me.statusCode()).isEqualTo(200)
            assertThat(me.body()).contains(user.username)
        } finally { ctx.close() }
    }
}
