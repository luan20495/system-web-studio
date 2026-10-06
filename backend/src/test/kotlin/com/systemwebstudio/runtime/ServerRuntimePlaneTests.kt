package com.systemwebstudio.runtime

import com.systemwebstudio.integration.storage.ArtifactStore
import com.systemwebstudio.publish.RuntimeReleaseFailure
import com.systemwebstudio.publish.ServerRuntimePlane
import com.systemwebstudio.publish.StaticSiteBuilder
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import java.util.Base64
import java.util.UUID
import kotlin.concurrent.thread

/**
 * The server runtime as the second half of a release: the asynchronous runner (it reports STARTING / RUNNING / FAILED back) is hidden behind a call
 * that returns only when the state is final. The runner is simulated by calling the same report endpoint logic it uses.
 */
@TestPropertySource(properties = ["app.deploy.runtime-timeout-seconds=2", "app.deploy.runtime-poll-ms=20"])
class ServerRuntimePlaneTests : IntegrationTestBase() {
    companion object {
        @JvmStatic @DynamicPropertySource
        fun runtime(r: DynamicPropertyRegistry) {
            r.add("app.runtime.enabled") { "true" }
            r.add("app.secrets.master-key") { Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }) }
            r.add("app.runtime.appdb-url") { postgres.jdbcUrl }
            r.add("app.runtime.appdb-admin-user") { postgres.username }
            r.add("app.runtime.appdb-admin-password") { postgres.password }
            r.add("app.runtime.gateway-url") { "http://127.0.0.1:9" }
            r.add("app.runtime.gateway-token") { "gateway-test-token-0123456789abcdef0123" }
            r.add("app.build.runner-token") { "runner-test-token-123456" }
        }
    }

    @Autowired lateinit var runtime: ServerRuntimeService
    @Autowired lateinit var plane: ServerRuntimePlane
    @Autowired lateinit var store: ArtifactStore

    private fun serverApp(): Scenario {
        val sc = scenario()
        jdbc.update("UPDATE projects SET app_type = 'STATIC_APP', app_kind = 'SERVER_APP' WHERE id = ?", sc.projectId)
        runtime.provision(sc.projectId)
        return sc
    }

    private fun artifact(projectId: UUID): UUID {
        val files = mapOf("index.html" to "<!doctype html><title>x</title>", "server/server.cjs" to "require('http')", "server/openapi.json" to """{"openapi":"3.1.0","paths":{"/api/items":{"get":{}}}}""")
        val id = UUID.randomUUID(); val prefix = "$projectId/${id.toString().replace("-", "")}"
        val manifest = files.map { (p, c) -> store.putOnce("$prefix/$p", c.toByteArray(), "text/plain"); mapOf("path" to p, "size" to c.length, "sha256" to StaticSiteBuilder.sha256(c.toByteArray()), "contentType" to "text/plain") }
        jdbc.update("INSERT INTO artifacts (id, project_id, sha256, storage_prefix, file_count, total_bytes, manifest, kind) VALUES (?,?,?,?,?,?,CAST(? AS jsonb), 'STATIC_APP')",
            id, projectId, StaticSiteBuilder.sha256(id.toString().toByteArray()), prefix, files.size, 100, json.writeValueAsString(manifest))
        return id
    }

    /** the runner: once something is desired for the app, report [state] for it (after a short start-up) */
    private fun runnerReports(projectId: UUID, state: String, error: String? = null) = thread(isDaemon = true) {
        repeat(200) {
            runtime.desiredRelease(projectId)?.let { (id, _) ->
                Thread.sleep(60); runtime.report(id, RuntimeReport("STARTING")); Thread.sleep(60); runtime.report(id, RuntimeReport(state, error)); return@thread
            }
            Thread.sleep(20)
        }
    }

    /** serve [artifact] while the simulated runner answers; returns when serve has returned (or thrown) */
    private fun serveHealthy(projectId: UUID, artifact: UUID) { val r = runnerReports(projectId, "RUNNING"); try { plane.serve(projectId, artifact, anyRelease) } finally { r.join(5000) } }

    private fun serverDeployments(projectId: UUID) = jdbc.queryForObject("SELECT count(*) FROM server_deployments WHERE project_id = ?", Int::class.java, projectId)!!
    private val anyRelease = UUID.randomUUID()

    @Test
    fun `serve returns only when the runner reports the release healthy, and serving the same artifact again creates nothing`() {
        val sc = serverApp(); val a = artifact(sc.projectId)
        assertThat(plane.applies(sc.projectId)).isTrue(); assertThat(plane.currentArtifact(sc.projectId)).isNull()
        val runner = runnerReports(sc.projectId, "RUNNING")
        plane.serve(sc.projectId, a, anyRelease); runner.join(5000)
        assertThat(plane.currentArtifact(sc.projectId)).isEqualTo(a)
        assertThat(runtime.desiredRelease(sc.projectId)).isNull()
        val deployments = serverDeployments(sc.projectId)
        plane.serve(sc.projectId, a, anyRelease)                                               // already serving exactly this: no second server deployment
        assertThat(serverDeployments(sc.projectId)).isEqualTo(deployments)
    }

    @Test
    fun `a runner failure is a failed release - the serving release is unchanged and nothing stays desired`() {
        val sc = serverApp(); val first = artifact(sc.projectId); val second = artifact(sc.projectId)
        serveHealthy(sc.projectId, first)
        val runner = runnerReports(sc.projectId, "FAILED", "health check failed: port 8080 not open")
        assertThatThrownBy { plane.serve(sc.projectId, second, anyRelease) }.isInstanceOf(RuntimeReleaseFailure::class.java).hasMessageContaining("health check failed")
        runner.join(5000)
        assertThat(plane.currentArtifact(sc.projectId)).isEqualTo(first)
        assertThat(runtime.desiredRelease(sc.projectId)).isNull()
    }

    @Test
    fun `a runner that never answers is abandoned - the release fails, and a late RUNNING report cannot bring it up behind the platform's back`() {
        val sc = serverApp(); val first = artifact(sc.projectId); val second = artifact(sc.projectId)
        serveHealthy(sc.projectId, first)
        assertThatThrownBy { plane.serve(sc.projectId, second, anyRelease) }.isInstanceOf(RuntimeReleaseFailure::class.java).hasMessageContaining("did not become healthy")
        val abandoned = jdbc.queryForObject("SELECT id FROM server_deployments WHERE project_id = ? AND artifact_id = ?", UUID::class.java, sc.projectId, second)!!
        assertThat(runtime.deploymentState(abandoned)!!.first).isEqualTo("FAILED")
        assertThat(runtime.desiredRelease(sc.projectId)).isNull()
        runtime.report(abandoned, RuntimeReport("RUNNING"))                                    // the runner was only slow
        assertThat(plane.currentArtifact(sc.projectId)).isEqualTo(first)                       // still the previous release
    }

    @Test
    fun `a worker that died after asking for the release does not ask twice - the next attempt waits for the one that was asked for`() {
        val sc = serverApp(); val a = artifact(sc.projectId)
        runtime.deploy(sc.projectId, a, null, null)                                            // the first worker got this far
        assertThat(serverDeployments(sc.projectId)).isEqualTo(1)
        val runner = runnerReports(sc.projectId, "RUNNING")
        plane.serve(sc.projectId, a, anyRelease); runner.join(5000)
        assertThat(serverDeployments(sc.projectId)).isEqualTo(1)
        assertThat(plane.currentArtifact(sc.projectId)).isEqualTo(a)
    }

    @Test
    fun `stop takes the server part away and a project without a server part is not a runtime app`() {
        val sc = serverApp(); val a = artifact(sc.projectId)
        serveHealthy(sc.projectId, a)
        plane.stop(sc.projectId)
        assertThat(plane.currentArtifact(sc.projectId)).isNull()
        assertThat(plane.applies(scenario().projectId)).isFalse()
    }
}
