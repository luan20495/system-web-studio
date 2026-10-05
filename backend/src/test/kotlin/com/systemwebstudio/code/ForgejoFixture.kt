package com.systemwebstudio.code

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/** One real Forgejo container for all code-project test classes (bot token for repositories, admin token for IDE access). */
object ForgejoFixture {
    val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("codeberg.org/forgejo/forgejo:11-rootless"))
        .withEnv(mapOf("FORGEJO__database__DB_TYPE" to "sqlite3", "FORGEJO__security__INSTALL_LOCK" to "true", "FORGEJO__server__HTTP_PORT" to "3000",
            "FORGEJO__server__DISABLE_SSH" to "true", "FORGEJO__service__DISABLE_REGISTRATION" to "true", "FORGEJO__repository__DEFAULT_BRANCH" to "main",
            "FORGEJO__actions__ENABLED" to "false", "FORGEJO__security__SECRET_KEY" to "test-secret-key-0123456789",
            "FORGEJO__security__INTERNAL_TOKEN" to "eyJhbGciOiJIUzI1NiJ9.test-internal-token-0123456789"))
        .withExposedPorts(3000).waitingFor(Wait.forHttp("/api/healthz").forPort(3000).withStartupTimeout(Duration.ofMinutes(2)))
        .apply { start() }
    val url: String get() = "http://${container.host}:${container.getMappedPort(3000)}"
    private fun fx(vararg a: String) = container.execInContainer("forgejo", *a).also { check(it.exitCode == 0) { it.stderr } }.stdout.trim()
    val botToken: String by lazy {
        fx("admin", "user", "create", "--username", "factory-bot", "--password", "bot-password-123456", "--email", "bot@test.local", "--must-change-password=false")
        val t = fx("admin", "user", "generate-access-token", "--username", "factory-bot", "--token-name", "t", "--scopes", "write:repository,write:organization,read:user", "--raw")
        java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI("$url/api/v1/orgs")).header("Authorization", "token $t")
            .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("""{"username":"factory","visibility":"private"}""")).build(),
            java.net.http.HttpResponse.BodyHandlers.discarding())
        t
    }
    val adminToken: String by lazy {
        botToken
        fx("admin", "user", "create", "--admin", "--username", "factory-admin", "--password", "admin-password-123456", "--email", "adm@test.local", "--must-change-password=false")
        fx("admin", "user", "generate-access-token", "--username", "factory-admin", "--token-name", "a", "--scopes", "write:admin,write:repository,read:user", "--raw")
    }
}
