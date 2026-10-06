package com.systemwebstudio.publish

import com.systemwebstudio.integration.deploy.DeployProvider
import com.systemwebstudio.integration.deploy.DeployRequest
import com.systemwebstudio.integration.deploy.DeployResult
import com.systemwebstudio.integration.deploy.DeployVerification
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Release N is active -> deploy N+1 -> N+1 fails or is unhealthy -> N is active again, from its own immutable artifact, with no rebuild.
 * Pure: the provider, the release bookkeeping and the artifact check are fakes, so every failure point can be forced.
 */
class ReleaseDeployerTests {
    private val project = UUID.randomUUID()
    private val releaseN = UUID.randomUUID()
    private val releaseN1 = UUID.randomUUID()
    private val artifactN = UUID.randomUUID()
    private val artifactN1 = UUID.randomUUID()

    private class FakeReleases : ReleaseStore {
        var active: UUID? = null
        val previous = HashMap<UUID, UUID>()
        val artifacts = HashMap<UUID, UUID>()
        val events = ArrayList<Pair<UUID, String>>()          // deployment -> "STATUS: message"
        override fun activeDeployment(projectId: UUID) = active
        override fun recordedPrevious(deploymentId: UUID) = previous[deploymentId]
        override fun recordPrevious(deploymentId: UUID, previous: UUID) { this.previous[deploymentId] = previous }
        override fun artifactOf(deploymentId: UUID) = artifacts[deploymentId]
        override fun label(deploymentId: UUID) = "rel-" + deploymentId.toString().take(4)
        override fun event(deploymentId: UUID, status: String, message: String) { events += deploymentId to "$status: $message" }
        fun statuses(dep: UUID) = events.filter { it.first == dep }.map { it.second.substringBefore(':') }
    }

    private class FakeProvider(val store: FakeReleases) : DeployProvider {
        override val name = "fake"
        override val buildsArtifacts = true
        var switchThenFail: String? = null      // switches the pointer, then reports an error (partial failure)
        var failBeforeSwitch: Throwable? = null
        var sleepMs = 0L                        // how long deploy takes
        var verification: () -> DeployVerification = { DeployVerification.healthy() }
        var restoreError: String? = null
        var deployCalls = 0; var restoreCalls = 0
        override fun deploy(request: DeployRequest): DeployResult {
            deployCalls++
            failBeforeSwitch?.let { throw it }
            store.active = request.deploymentId
            if (sleepMs > 0) Thread.sleep(sleepMs)
            return switchThenFail?.let { DeployResult(null, it) } ?: DeployResult("https://sites.example/x/", null)
        }
        override fun verify(request: DeployRequest) = verification()
        override fun restore(projectId: UUID, previousDeploymentId: UUID?): DeployResult {
            restoreCalls++
            restoreError?.let { return DeployResult(null, it) }
            store.active = previousDeploymentId
            return DeployResult(null, null)
        }
    }

    private val store = FakeReleases()
    private val provider = FakeProvider(store)
    private var artifactOk: (UUID) -> ArtifactCheck = { ArtifactCheck(true) }
    private fun deployer(deployTimeoutMs: Long = 5_000) = ReleaseDeployer(provider, store, { artifactOk(it) }, StepRunner(), deployTimeoutMs, 5_000)
    private fun request(id: UUID = releaseN1, artifact: UUID = artifactN1) = DeployRequest(id, "Demo", 2, "PUBLIC", null, "sha", project, artifact)

    @BeforeEach fun releaseNIsActive() { store.active = releaseN; store.artifacts[releaseN] = artifactN; store.artifacts[releaseN1] = artifactN1 }

    private fun failed(o: DeployOutcome) = o as DeployOutcome.Failed

    @Test
    fun `a healthy new release becomes the live one and the happy path writes no extra history`() {
        val o = deployer().deploy(request())
        assertThat(o).isInstanceOf(DeployOutcome.Live::class.java)
        assertThat(store.active).isEqualTo(releaseN1)
        assertThat(store.previous[releaseN1]).isEqualTo(releaseN)     // remembered, so a restart can still roll back
        assertThat(store.events).isEmpty()
        assertThat(provider.restoreCalls).isZero()
    }

    @Test
    fun `N+1 switches then fails - N is active again from its own artifact and nothing is rebuilt`() {
        provider.switchThenFail = "control plane rejected the release"
        val f = failed(deployer().deploy(request()))
        assertThat(f.failure.code).isEqualTo(FailureCode.DEPLOY_FAILED)
        assertThat(f.failure.reason()).contains("control plane rejected the release")      // the reason is not lost
        assertThat(f.rollback).isEqualTo(RollbackResult.Restored(releaseN, "rel-" + releaseN.toString().take(4)))
        assertThat(store.active).isEqualTo(releaseN)
        assertThat(store.statuses(releaseN1)).containsExactly("ROLLBACK_OK")
        assertThat(provider.deployCalls).isEqualTo(1)                                       // restore is not a second deploy / build
    }

    @Test
    fun `N+1 is unhealthy after the switch - rolled back, with the health detail as the reason`() {
        provider.verification = { DeployVerification.unhealthy("HTTP 502 from the site") }
        val f = failed(deployer().deploy(request()))
        assertThat(f.failure.code).isEqualTo(FailureCode.VERIFICATION_FAILED)
        assertThat(f.failure.message).contains("HTTP 502")
        assertThat(f.rollback).isInstanceOf(RollbackResult.Restored::class.java)
        assertThat(store.active).isEqualTo(releaseN)
    }

    @Test
    fun `an answer that cannot be trusted is not success - unknown verification rolls back and is marked ambiguous`() {
        provider.verification = { DeployVerification.unknown("control plane unreachable") }
        val f = failed(deployer().deploy(request()))
        assertThat(f.failure.code).isEqualTo(FailureCode.DEPLOY_STATE_UNKNOWN); assertThat(f.failure.ambiguous).isTrue()
        assertThat(store.active).isEqualTo(releaseN)
        provider.verification = { throw IllegalStateException("verify crashed") }
        store.active = releaseN
        assertThat(failed(deployer().deploy(request())).failure.code).isEqualTo(FailureCode.DEPLOY_STATE_UNKNOWN)   // a throwing verifier is also UNKNOWN
        assertThat(store.active).isEqualTo(releaseN)
    }

    @Test
    fun `a deploy timeout is ambiguous - not success, not retried, previous release restored`() {
        provider.sleepMs = 2_000
        val f = failed(deployer(deployTimeoutMs = 100).deploy(request()))
        assertThat(f.failure.code).isEqualTo(FailureCode.DEPLOY_TIMEOUT)
        assertThat(f.failure.ambiguous).isTrue(); assertThat(f.failure.transient).isFalse()
        assertThat(f.rollback).isInstanceOf(RollbackResult.Restored::class.java)
        assertThat(store.active).isEqualTo(releaseN)
    }

    @Test
    fun `the server runtime step failing after the switch rolls the site back too`() {
        val f = failed(deployer().deploy(request()) { throw IllegalStateException("runtime refused the artifact") })
        assertThat(f.failure.code).isEqualTo(FailureCode.RUNTIME_DEPLOY_FAILED)
        assertThat(f.failure.message).contains("runtime refused the artifact")
        assertThat(store.active).isEqualTo(releaseN)
    }

    @Test
    fun `a provider that fails before it switched anything leaves the active release alone`() {
        provider.failBeforeSwitch = IllegalStateException("cannot reach the provider")
        val f = failed(deployer().deploy(request()))
        assertThat(f.rollback).isEqualTo(RollbackResult.NotSwitched)
        assertThat(store.active).isEqualTo(releaseN)
        assertThat(provider.restoreCalls).isZero()
        assertThat(store.events).isEmpty()
    }

    @Test
    fun `the first release has nothing to go back to - a failure leaves the site serving nothing, said explicitly`() {
        store.active = null
        provider.switchThenFail = "boom"
        val f = failed(deployer().deploy(request()))
        assertThat(f.rollback).isEqualTo(RollbackResult.TookOffline)
        assertThat(store.active).isNull()
        assertThat(store.statuses(releaseN1)).containsExactly("ROLLBACK_OFFLINE")
    }

    @Test
    fun `rollback failure is its own recorded state - the previous artifact is gone, the pointer is not pointed at a missing artifact`() {
        provider.switchThenFail = "boom"
        artifactOk = { ArtifactCheck(it != artifactN, "files missing in the store: index.html") }
        val f = failed(deployer().deploy(request()))
        val rb = f.rollback as RollbackResult.Failed
        assertThat(rb.reason).contains("cannot be served again").contains("index.html")
        assertThat(provider.restoreCalls).isZero()                                  // never switched to an artifact that does not exist
        assertThat(store.statuses(releaseN1)).containsExactly("ROLLBACK_FAILED")
        assertThat(store.events.single().second).contains("ROLLBACK FAILED")
    }

    @Test
    fun `a provider that cannot restore is a recorded rollback failure, not a silent one`() {
        provider.switchThenFail = "boom"; provider.restoreError = "restore API returned 500"
        val rb = failed(deployer().deploy(request())).rollback as RollbackResult.Failed
        assertThat(rb.reason).contains("restore API returned 500")
        assertThat(store.statuses(releaseN1)).containsExactly("ROLLBACK_FAILED")
    }

    @Test
    fun `repeating the rollback is safe - the second run finds the pointer is no longer ours and touches nothing`() {
        provider.switchThenFail = "boom"
        val d = deployer()
        assertThat(failed(d.deploy(request())).rollback).isInstanceOf(RollbackResult.Restored::class.java)
        val again = d.rollbackFailedDeployment(request(), releaseN)
        assertThat(again).isEqualTo(RollbackResult.NotSwitched)
        assertThat(store.active).isEqualTo(releaseN)
        assertThat(provider.restoreCalls).isEqualTo(1)
        assertThat(store.statuses(releaseN1)).containsExactly("ROLLBACK_OK")        // not recorded twice
    }

    @Test
    fun `a rollback never clobbers a newer release that was switched in the meantime`() {
        val releaseN2 = UUID.randomUUID()
        store.active = releaseN2                                                     // someone else already switched to N+2
        val rb = deployer().rollbackFailedDeployment(request(), releaseN)
        assertThat(rb).isEqualTo(RollbackResult.NotSwitched)
        assertThat(store.active).isEqualTo(releaseN2)
    }

    @Test
    fun `after a worker restart the release that was active BEFORE the switch is still the rollback target`() {
        // run 1: switched (pointer is N+1), recorded N, then the worker died. Run 2 resumes: the pointer is already N+1.
        store.previous[releaseN1] = releaseN
        store.active = releaseN1
        provider.verification = { DeployVerification.unhealthy("still unhealthy") }
        val f = failed(deployer().deploy(request()))
        assertThat(f.rollback).isInstanceOf(RollbackResult.Restored::class.java)
        assertThat(store.active).isEqualTo(releaseN)                                  // not "N+1 is the previous release of itself"
    }

    @Test
    fun `manual rollback verifies the artifact first, needs no build, and repeating it changes nothing`() {
        store.active = releaseN1
        val d = deployer()
        assertThat(d.restoreRelease(project, releaseN)).isInstanceOf(RollbackResult.Restored::class.java)
        assertThat(store.active).isEqualTo(releaseN)
        assertThat(d.restoreRelease(project, releaseN)).isInstanceOf(RollbackResult.AlreadyActive::class.java)
        assertThat(provider.restoreCalls).isEqualTo(1); assertThat(provider.deployCalls).isZero()
        // an artifact that retention removed must not be switched to
        store.active = releaseN1
        artifactOk = { ArtifactCheck(false, "artifact was removed by retention") }
        val rb = d.restoreRelease(project, releaseN) as RollbackResult.Failed
        assertThat(rb.reason).contains("removed by retention")
        assertThat(store.active).isEqualTo(releaseN1)
    }

    @Test
    fun `a release without any artifact cannot be restored`() {
        store.active = releaseN1; store.artifacts.remove(releaseN)
        val rb = deployer().restoreRelease(project, releaseN) as RollbackResult.Failed
        assertThat(rb.reason).contains("has no artifact")
        assertThat(store.active).isEqualTo(releaseN1)
    }
}
