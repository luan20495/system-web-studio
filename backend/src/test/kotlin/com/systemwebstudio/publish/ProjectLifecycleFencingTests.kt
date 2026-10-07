package com.systemwebstudio.publish

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.util.UUID

/**
 * D-C0-33 · archive and delete of an application, over HTTP, take the site offline through the release scope (fenced, bounded wait). A live release operation
 * makes them a `409 SCOPE_BUSY` and the web transaction rolls back: the project is NOT archived / deleted. Once the scope is free they succeed and the site is offline.
 */
@TestPropertySource(properties = ["app.deploy.scope-lifecycle-wait-seconds=1"])
class ProjectLifecycleFencingTests : IntegrationTestBase() {
    @Autowired lateinit var guard: JdbcScopeGuard
    @Autowired lateinit var sites: SiteService
    private val held = mutableListOf<ScopeLease>()
    @AfterEach fun tidy() { held.forEach { it.release() }; held.clear() }

    private fun online(sc: Scenario): UUID {
        sites.ensureSlug(sc.projectId, "Life")
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, sc.projectId)
        val art = UUID.randomUUID(); val dep = UUID.randomUUID()
        jdbc.update("INSERT INTO artifacts (id, project_id, sha256, storage_prefix, file_count, total_bytes, manifest) VALUES (?,?,?,?,1,5,'[]'::jsonb)", art, sc.projectId, UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""), "${sc.projectId}/x")
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, artifact_id) VALUES (?,?,?,?,?,'PUBLIC','RUNNING','static',?)", dep, sc.ws, sc.projectId, version, sc.user.id, art)
        val scope = ReleaseScope(jdbc.queryForObject("SELECT tenant_id FROM projects WHERE id = ?", UUID::class.java, sc.projectId)!!, sc.projectId)
        val l = (guard.acquire(ScopeRequest(scope, ReleaseOperation.ROLLBACK, UUID.randomUUID())) as ScopeAcquisition.Acquired).lease
        check(l.fence.commit(dep)); l.release()
        return dep
    }
    private fun holdScope(sc: Scenario): ScopeLease {
        val scope = ReleaseScope(jdbc.queryForObject("SELECT tenant_id FROM projects WHERE id = ?", UUID::class.java, sc.projectId)!!, sc.projectId)
        return (guard.acquire(ScopeRequest(scope, ReleaseOperation.ROLLBACK, UUID.randomUUID())) as ScopeAcquisition.Acquired).lease.also { held += it }
    }
    private fun pointer(sc: Scenario) = jdbc.queryForObject("SELECT current_deployment_id FROM sites WHERE project_id = ?", UUID::class.java, sc.projectId)
    private fun lifecycle(sc: Scenario) = jdbc.queryForObject("SELECT lifecycle FROM projects WHERE id = ?", String::class.java, sc.projectId)

    @Test
    fun `archive is refused while a release operation is alive, changes nothing, and works once the scope is free`() {
        val sc = scenario(); val dep = online(sc)
        val owner = sc.s
        fx.member(sc.ws, sc.user, "WORKSPACE_ADMIN")                                            // may delete / archive
        val lease = holdScope(sc)
        val busy = owner.post("${sc.base}/archive", "{}")
        assertThat(busy.response.status).describedAs(busy.response.contentAsString).isEqualTo(409)
        assertThat(owner.body(busy).get("code").asString()).isEqualTo("SCOPE_BUSY")
        assertThat(lifecycle(sc)).describedAs("the web transaction rolled back").isEqualTo("ACTIVE")
        assertThat(pointer(sc)).isEqualTo(dep)
        assertThat(lease.lost).isFalse()
        lease.release()
        val ok = owner.post("${sc.base}/archive", "{}")
        assertThat(ok.response.status).describedAs(ok.response.contentAsString).isEqualTo(200)
        assertThat(lifecycle(sc)).isEqualTo("ARCHIVED"); assertThat(pointer(sc)).isNull()
    }

    @Test
    fun `delete of an application is refused while a release operation is alive and takes the site offline otherwise`() {
        val sc = scenario(); val dep = online(sc)
        fx.member(sc.ws, sc.user, "WORKSPACE_ADMIN")
        val lease = holdScope(sc)
        val rev = sc.revision()
        val busy = sc.s.delete("${sc.base}?expectedRevision=$rev")
        assertThat(busy.response.status).describedAs(busy.response.contentAsString).isEqualTo(409)
        assertThat(jdbc.queryForObject("SELECT active FROM projects WHERE id = ?", Boolean::class.java, sc.projectId)).isTrue()
        assertThat(pointer(sc)).isEqualTo(dep)
        lease.release()
        val ok = sc.s.delete("${sc.base}?expectedRevision=$rev")
        assertThat(ok.response.status).describedAs(ok.response.contentAsString).isIn(200, 204)
        assertThat(jdbc.queryForObject("SELECT active FROM projects WHERE id = ?", Boolean::class.java, sc.projectId)).isFalse()
        assertThat(pointer(sc)).isNull()
    }
}
