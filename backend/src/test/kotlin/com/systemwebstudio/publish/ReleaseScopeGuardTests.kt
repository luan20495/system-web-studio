package com.systemwebstudio.publish

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.integration.deploy.DeployProvider
import com.systemwebstudio.integration.deploy.DeployRequest
import com.systemwebstudio.integration.deploy.DeployResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * publish, rollback and unpublish are one family of operations on one scope (tenant, app, PRODUCTION). Pure: no database, no lease storage
 * (that needs a migration C0 has not reserved) — these tests pin the shape the lease will plug into.
 */
class ReleaseScopeGuardTests {
    private val tenant = UUID.randomUUID(); private val app = UUID.randomUUID()
    private val scope = ReleaseScope(tenant, app)
    private val releaseN = UUID.randomUUID(); private val releaseN1 = UUID.randomUUID()

    private class Releases : ReleaseStore {
        var active: UUID? = null
        override fun activeDeployment(projectId: UUID) = active
        override fun recordedPrevious(deploymentId: UUID): UUID? = null
        override fun recordPrevious(deploymentId: UUID, previous: UUID) {}
        override fun artifactOf(deploymentId: UUID): UUID? = UUID.nameUUIDFromBytes(deploymentId.toString().toByteArray())
        override fun label(deploymentId: UUID) = "rel"
        override fun event(deploymentId: UUID, status: String, message: String) {}
    }

    private class Provider(val releases: Releases) : DeployProvider {
        override val name = "fake"
        var failAfterSwitch = false
        override fun deploy(request: DeployRequest): DeployResult { releases.active = request.deploymentId; return if (failAfterSwitch) DeployResult(null, "boom") else DeployResult("https://x/", null) }
        override fun verify(request: DeployRequest) = com.systemwebstudio.integration.deploy.DeployVerification.healthy()
        override fun restore(projectId: UUID, previousDeploymentId: UUID?): DeployResult { releases.active = previousDeploymentId; return DeployResult(null, null) }
    }

    /** records every acquisition; [busyFor] simulates a scope that someone else holds */
    private class RecordingGuard(val busyFor: Set<ReleaseOperation> = emptySet()) : ReleaseScopeGuard {
        val acquired = ArrayList<Pair<ReleaseScope, ReleaseOperation>>()
        var tokens = ArrayList<Long>()
        override fun <T> run(scope: ReleaseScope, operation: ReleaseOperation, body: (ScopeLease) -> T): T {
            acquired += scope to operation
            if (operation in busyFor) throw ReleaseScopeGuard.busy(scope)
            return body(ScopeLease(scope, operation, fencingToken = 41L + acquired.size).also { tokens += it.fencingToken })
        }
    }

    private val noRuntime = object : RuntimePlane {
        override fun applies(projectId: UUID) = false
        override fun currentArtifact(projectId: UUID): UUID? = null
        override fun serve(projectId: UUID, artifactId: UUID, deploymentId: UUID) {}
        override fun stop(projectId: UUID) {}
    }
    private val releases = Releases().also { it.active = releaseN }
    private val provider = Provider(releases)
    private fun service(guard: ReleaseScopeGuard) = ReleaseService(provider, releases, { ArtifactCheck(true) }, noRuntime, guard, 5, 5)
    private fun request() = DeployRequest(releaseN1, "Demo", 2, "PUBLIC", null, "sha", app, UUID.randomUUID())

    @Test
    fun `publish, rollback and unpublish each run inside the guard, on the same scope`() {
        val guard = RecordingGuard(); val service = service(guard)
        assertThat(service.publish(scope, request())).isInstanceOf(DeployOutcome.Live::class.java)
        assertThat(service.rollback(scope, releaseN)).isInstanceOf(RollbackResult.Restored::class.java)
        var offline = false
        service.unpublish(scope) { offline = true }
        assertThat(offline).isTrue()
        assertThat(guard.acquired.map { it.second }).containsExactly(ReleaseOperation.PUBLISH, ReleaseOperation.ROLLBACK, ReleaseOperation.UNPUBLISH)
        assertThat(guard.acquired.map { it.first }.toSet()).containsExactly(scope)
        assertThat(scope.environment).isEqualTo("PRODUCTION")
    }

    @Test
    fun `the automatic rollback of a failed publish runs under the publish's own scope and does not ask for it again`() {
        val guard = RecordingGuard(); provider.failAfterSwitch = true
        val outcome = service(guard).publish(scope, request()) as DeployOutcome.Failed
        assertThat(outcome.rollback).isInstanceOf(RollbackResult.Restored::class.java)
        assertThat(guard.acquired).hasSize(1)
        assertThat(releases.active).isEqualTo(releaseN)
    }

    @Test
    fun `a busy scope stops the operation before anything is touched - 409 SCOPE_BUSY`() {
        val busy = RecordingGuard(busyFor = ReleaseOperation.entries.toSet()); val service = service(busy)
        var offline = false
        listOf({ service.rollback(scope, releaseN1) }, { service.unpublish(scope) { offline = true } }, { service.publish(scope, request()) }).forEach { op ->
            assertThatThrownBy { op() }.isInstanceOf(ApiException::class.java).hasMessageContaining("Another release operation")
                .satisfies({ assertThat((it as ApiException).code).isEqualTo("SCOPE_BUSY"); assertThat(it.status.value()).isEqualTo(409) })
        }
        assertThat(offline).isFalse()
        assertThat(releases.active).isEqualTo(releaseN)                    // not switched, not rolled back, not unpublished
        assertThat(provider.let { releases.active }).isNotEqualTo(releaseN1)
    }

    @Test
    fun `the lease carries the fencing token the guard issued, so a later compare-and-set can use it`() {
        val guard = RecordingGuard()
        val seen = ArrayList<Long>()
        guard.run(scope, ReleaseOperation.PUBLISH) { seen += it.fencingToken }
        guard.run(scope, ReleaseOperation.ROLLBACK) { seen += it.fencingToken }
        assertThat(seen).isEqualTo(listOf(42L, 43L))
    }

    @Test
    fun `until leases exist the guard changes nothing - same result as calling the deployer directly, and it never blocks`() {
        val guard = PassThroughScopeGuard()
        val lease = guard.run(scope, ReleaseOperation.PUBLISH) { it }
        assertThat(lease.fencingToken).isEqualTo(ScopeLease.NO_FENCE)
        assertThat(lease.scope).isEqualTo(scope); assertThat(lease.operation).isEqualTo(ReleaseOperation.PUBLISH)
        // the guard does not serialise yet: a nested / concurrent acquisition is not refused
        assertThat(guard.run(scope, ReleaseOperation.PUBLISH) { guard.run(scope, ReleaseOperation.ROLLBACK) { "ok" } }).isEqualTo("ok")
    }

    @Test
    fun `scopes of different apps or tenants are different scopes`() {
        assertThat(ReleaseScope(tenant, app)).isEqualTo(ReleaseScope(tenant, app, "PRODUCTION"))
        assertThat(ReleaseScope(tenant, app)).isNotEqualTo(ReleaseScope(tenant, UUID.randomUUID()))
        assertThat(ReleaseScope(tenant, app)).isNotEqualTo(ReleaseScope(UUID.randomUUID(), app))
    }
}
