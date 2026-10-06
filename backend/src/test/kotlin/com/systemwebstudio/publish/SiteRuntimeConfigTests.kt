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
}
