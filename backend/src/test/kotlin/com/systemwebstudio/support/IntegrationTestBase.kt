package com.systemwebstudio.support

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Real PostgreSQL + Redis. Flyway runs normally and Hibernate validates the schema;
 * nothing here relies on H2 or create-drop.
 */
@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Import(TestFixtures::class)
abstract class IntegrationTestBase {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var fx: TestFixtures
    @Autowired lateinit var jdbc: JdbcTemplate

    fun session() = ApiSession(mvc, json)
    fun sessionFor(username: String): ApiSession = session().also { it.login(username) }
    fun api(w: java.util.UUID, p: java.util.UUID? = null) = "/api/v1/workspaces/$w/projects" + (p?.let { "/$it" } ?: "")

    class Scenario(val user: com.systemwebstudio.identity.UserEntity, val ws: java.util.UUID, val projectId: java.util.UUID, val s: ApiSession, val base: String)

    /** An EDITOR-level owner with a fresh project whose schema is initialised through the API. */
    fun scenario(): Scenario {
        val u = fx.user("owner"); val ws = fx.workspace(); fx.member(ws, u, "EDITOR")
        val s = sessionFor(u.username)
        val created = s.body(s.post(api(ws), """{"name":"Scenario"}"""))
        val id = java.util.UUID.fromString(created.get("id").asString())
        return Scenario(u, ws, id, s, api(ws, id))
    }

    fun Scenario.revision(): Long = s.body(s.get(base)).get("revision").asLong()
    fun Scenario.schema() = s.body(s.get("$base/schema")).get("schema")
    fun Scenario.versionCount(): Int = s.body(s.get("$base/versions")).size()
    fun Scenario.auditCount(action: String): Long = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE project_id=? AND action=?", Long::class.java, projectId, action)!!
    fun Scenario.section(type: String) = schema().get("sections").firstOrNull { it.get("type").asString() == type }
    fun Scenario.prompt(text: String, rev: Long = revision()) = s.post("$base/prompts", """{"prompt":${json.writeValueAsString(text)},"expectedRevision":$rev}""")

    companion object {
        const val ADMIN_PASSWORD = "integration-test-pass-123"

        val postgres: PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:17.6")).apply { start() }
        val redis: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:8.2.1-alpine"))
            .withExposedPorts(6379).apply { start() }

        const val MINIO_USER = "testminio"
        const val MINIO_PASSWORD = "testminio-secret-123"
        val minio: GenericContainer<*> = GenericContainer(DockerImageName.parse("bitnamilegacy/minio:2025.7.23-debian-12-r5"))
            .withEnv("MINIO_ROOT_USER", MINIO_USER).withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
            .withExposedPorts(9000)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/minio/health/ready").forPort(9000))
            .apply { start() }
        val rabbit: GenericContainer<*> = GenericContainer(DockerImageName.parse("rabbitmq:4-management-alpine"))
            .withEnv("RABBITMQ_DEFAULT_USER", "studio").withEnv("RABBITMQ_DEFAULT_PASS", "test-rabbit-123")
            .withExposedPorts(5672, 15672)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Server startup complete.*\\n", 1))
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            val minioUrl = "http://${minio.host}:${minio.getMappedPort(9000)}"
            registry.add("app.storage.endpoint") { minioUrl }
            registry.add("app.storage.public-endpoint") { minioUrl }
            registry.add("app.storage.access-key") { MINIO_USER }
            registry.add("app.storage.secret-key") { MINIO_PASSWORD }
            registry.add("spring.rabbitmq.host") { rabbit.host }
            registry.add("spring.rabbitmq.port") { rabbit.getMappedPort(5672) }
            registry.add("spring.rabbitmq.username") { "studio" }
            registry.add("spring.rabbitmq.password") { "test-rabbit-123" }
            registry.add("app.cors.allowed-origins") { "http://localhost:3000" }   // hermetic: never inherit the developer shell's env
            registry.add("LOCAL_ADMIN_PASSWORD") { ADMIN_PASSWORD }
            registry.add("server.servlet.session.cookie.secure") { false }
        }
    }
}
