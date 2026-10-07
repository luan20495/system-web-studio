package com.systemwebstudio.publish

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * `app.sites.data-api-base`: where a published app finds the Data Runtime API. It is environment configuration read at request time, never baked into an
 * artifact, so one artifact serves DEV / STAGING / PROD against different hosts and a rollback needs no rebuild.
 */
class SiteRuntimeConfigTests : ScopeIntegrationTestBase() {
    @Autowired lateinit var sites: SiteService

    @Test
    fun `the configured base and the release being served reach the app at request time`() {
        val sc = scenario(); val release = UUID.randomUUID()
        val cfg = sites.runtimeConfig(sc.projectId, "production", "PUBLIC", null, release)
        assertThat(cfg["apiBase"]).isEqualTo("https://data.dev.example.test/api/v1")
        assertThat(cfg["releaseId"]).isEqualTo(release.toString())
        assertThat(cfg["environment"]).isEqualTo("production")                         // the vocabulary of @company/app-sdk is unchanged
        assertThat(cfg["appId"]).isEqualTo(sc.projectId.toString())
        // a rollback changes only which release is served: the same configuration answers for the other release, no rebuild
        val other = UUID.randomUUID()
        assertThat(sites.runtimeConfig(sc.projectId, "production", "PUBLIC", null, other)).containsEntry("releaseId", other.toString()).containsEntry("apiBase", "https://data.dev.example.test/api/v1")
        assertThat(sites.runtimeConfig(sc.projectId, "preview", "PUBLIC", null)["releaseId"]).isNull()          // a preview serves no release
    }

    @Test
    fun `nothing usable configured means not configured - never a guess, never a made-up address`() {
        for (bad in listOf(null, "", "   ", "data.example.test", "/api/v1", "ftp://data.example.test", "javascript:alert(1)", "https://user:pw@data.example.test", "https://", "https://data.example.test/#x", "http://exa mple.test"))
            assertThat(SiteService.resolveDataApiBase(bad)).describedAs(bad).isNull()
        assertThat(SiteService.resolveDataApiBase(" https://data.example.test/api ")).isEqualTo("https://data.example.test/api")
        assertThat(SiteService.resolveDataApiBase("http://127.0.0.1:8080")).isEqualTo("http://127.0.0.1:8080")
    }

    @Test
    fun `the base is not part of any artifact - the builder has no access to it`() {
        // the artifact is content-addressed from the rendered files only: the configuration key is not an input of the build
        assertThat(StaticSiteBuilder::class.java.declaredFields.map { it.name }).noneMatch { it.contains("apiBase", ignoreCase = true) || it.contains("dataApi", ignoreCase = true) }
        assertThat(StaticSiteBuilder::class.java.declaredConstructors.flatMap { it.parameters.toList() }.map { it.name }).noneMatch { it.contains("apiBase", ignoreCase = true) }
    }

    @Test
    fun `version is the version of the release being served, so a rollback shows the older one - a preview shows the latest`() {
        val sc = scenario()
        val versions = jdbc.queryForList("SELECT id FROM project_versions WHERE project_id = ? ORDER BY version_number", UUID::class.java, sc.projectId)
        // a second version, as if the project had moved on
        val v1 = versions.first()
        val v2 = UUID.randomUUID()
        jdbc.update("""INSERT INTO project_versions (id, workspace_id, project_id, version_number, schema_snapshot, kind, summary, created_by)
            SELECT ?, workspace_id, project_id, version_number + 1, schema_snapshot, 'EDIT', 'next', created_by FROM project_versions WHERE id = ?""", v2, v1)
        fun release(version: UUID): UUID {
            val id = UUID.randomUUID()
            jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?,?,?,?,?,'PUBLIC','RUNNING','static')", id, sc.ws, sc.projectId, version, sc.user.id)
            return id
        }
        val old = release(v1); val newest = release(v2)
        val oldNumber = jdbc.queryForObject("SELECT version_number FROM project_versions WHERE id = ?", Int::class.java, v1).toString()
        val newNumber = jdbc.queryForObject("SELECT version_number FROM project_versions WHERE id = ?", Int::class.java, v2).toString()
        assertThat(oldNumber).isNotEqualTo(newNumber)
        assertThat(sites.runtimeConfig(sc.projectId, "production", "PUBLIC", null, newest)["version"]).isEqualTo(newNumber)
        assertThat(sites.runtimeConfig(sc.projectId, "production", "PUBLIC", null, old)["version"]).describedAs("after a rollback to the older release").isEqualTo(oldNumber)
        assertThat(sites.runtimeConfig(sc.projectId, "preview", "PUBLIC", null)["version"]).isEqualTo(newNumber)                 // a preview has no release: the latest
        assertThat(sites.runtimeConfig(sc.projectId, "production", "PUBLIC", null, UUID.randomUUID())["version"]).isEqualTo(newNumber)   // an unknown release falls back, it never fails
    }

    @Test
    fun `the key can be set from the environment as APP_SITES_DATA_API_BASE`() {
        // how Spring resolves `${app.sites.data-api-base}` against the process environment (relaxed binding of system environment variables)
        val env = org.springframework.core.env.SystemEnvironmentPropertySource("test-env", mapOf("APP_SITES_DATA_API_BASE" to "https://data.staging.example.test/api"))
        assertThat(env.getProperty("app.sites.data-api-base")).isEqualTo("https://data.staging.example.test/api")
        assertThat(SiteService.resolveDataApiBase(env.getProperty("app.sites.data-api-base") as String)).isEqualTo("https://data.staging.example.test/api")
    }
}
