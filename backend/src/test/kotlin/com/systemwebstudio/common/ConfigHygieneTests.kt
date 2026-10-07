package com.systemwebstudio.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * D-C0-34 · L-2. Production-shaped local: the code and the base / prod configuration know no development address. Local values live in `application-local.yml`
 * and `scripts/_env.sh`; production states its own (application-prod.yml has no default for them), so nothing silently falls back to loopback.
 */
class ConfigHygieneTests {
    private val loop = Regex("""127\.0\.0\.1|localhost|host\.docker\.internal""", RegexOption.IGNORE_CASE)
    private fun res(name: String) = File("src/main/resources/$name").readLines()
    private fun code(l: String) = l.substringBefore(" #").trimEnd()          // the comment is not configuration

    /** accepted, with the reason: tracing is off by default (sampling 0) and the mock deploy provider is refused in production */
    private val baseExceptions = listOf("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "MOCK_DEPLOY_BASE_URL")

    @Test fun `the base configuration carries no loopback address except the two documented ones`() {
        val hits = res("application.yml").filter { !it.trimStart().startsWith("#") }.filter { loop.containsMatchIn(code(it)) }.filter { l -> baseExceptions.none { it in l } }
        assertThat(hits).describedAs("loopback defaults in application.yml").isEmpty()
    }

    @Test fun `the production profile has no loopback and no default for the sites, render and infrastructure addresses`() {
        val lines = res("application-prod.yml").filter { !it.trimStart().startsWith("#") }
        assertThat(lines.filter { loop.containsMatchIn(code(it)) }).isEmpty()
        for (key in listOf("DATABASE_URL", "REDIS_HOST", "RABBITMQ_HOST", "SERVER_ADDRESS", "CORS_ALLOWED_ORIGINS", "MINIO_ENDPOINT", "MINIO_PUBLIC_ENDPOINT", "SITES_ORIGIN", "STUDIO_ORIGIN", "RENDER_URL"))
            assertThat(lines.firstOrNull { "\${$key}" in it }).describedAs("$key must be a required placeholder (no default) in prod").isNotNull()
    }

    @Test fun `the base placeholders of the addresses have no default, so a profile that names none stops the start`() {
        val lines = res("application.yml")
        for (key in listOf("DATABASE_URL", "REDIS_HOST", "RABBITMQ_HOST", "MINIO_ENDPOINT")) assertThat(lines.firstOrNull { "\${$key}" in it }).describedAs(key).isNotNull()
        for (key in listOf("SITES_ORIGIN", "STUDIO_ORIGIN", "RENDER_URL", "BUILD_API_BASE", "SITES_DATA_API_BASE", "CORS_ALLOWED_ORIGINS"))
            assertThat(lines.firstOrNull { "\${$key:}" in it }).describedAs("$key: blank in base").isNotNull()
    }

    @Test fun `no product code carries a loopback default of a property`() {
        val allowed = setOf("WorkflowQueueConfiguration.kt")                  // C4 file; spring.rabbitmq.* is always defined by the profiles
        val hits = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in allowed }.flatMap { f ->
            f.readLines().filter { l -> Regex("""@Value\(".*\$\{[^}]*:[^}]*(127\.0\.0\.1|localhost)""").containsMatchIn(l) }.map { "${f.name}: ${it.trim().take(120)}" }
        }.toList()
        assertThat(hits).describedAs("@Value defaults naming a development address").isEmpty()
    }

    @Test fun `the local profile and the local script carry the local values`() {
        val local = res("application-local.yml").joinToString("\n")
        for (key in listOf("DATABASE_URL", "REDIS_HOST", "RABBITMQ_HOST", "MINIO_ENDPOINT", "SITES_ORIGIN", "STUDIO_ORIGIN", "RENDER_URL", "BUILD_API_BASE", "CORS_ALLOWED_ORIGINS"))
            assertThat(local).describedAs("$key in application-local.yml").contains("\${$key:")
    }
}
