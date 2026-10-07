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
        var restoreErrorOnce: String? = null          // the next restore fails without touching the pointer (a provider that refuses)
        var stageError: String? = null
        var deployCalls = 0; var restoreCalls = 0
        val calls = ArrayList<String>()               // stage / deploy / verify / restore, in the order the deployer made them
        override fun stage(request: DeployRequest): DeployResult { calls += "stage"; return stageError?.let { DeployResult(null, it) } ?: DeployResult(null, null) }
        override fun deploy(request: DeployRequest): DeployResult {
            calls += "deploy"; deployCalls++
            failBeforeSwitch?.let { throw it }
            store.active = request.deploymentId
            if (sleepMs > 0) Thread.sleep(sleepMs)
            return switchThenFail?.let { DeployResult(null, it) } ?: DeployResult("https://sites.example/x/", null)
        }
        override fun verify(request: DeployRequest): DeployVerification { calls += "verify"; return verification() }
        override fun restore(projectId: UUID, previousDeploymentId: UUID?): DeployResult {
            calls += "restore"; restoreCalls++
            restoreErrorOnce?.let { restoreErrorOnce = null; return DeployResult(null, it) }
            restoreError?.let { return DeployResult(null, it) }
            store.active = previousDeploymentId
            return DeployResult(null, null)
        }
    }

    /** the server runtime: serves one artifact at a time, can be told to refuse an artifact, and remembers every call in order */
    private class FakePlane(val log: MutableList<String>) : RuntimePlane {
        var current: UUID? = null
        var failFor: Set<UUID> = emptySet()
        var stopFails = false
        val served = ArrayList<UUID>(); var stops = 0
        override fun applies(projectId: UUID) = true
        override fun currentArtifact(projectId: UUID) = current
        override fun serve(projectId: UUID, artifactId: UUID, deploymentId: UUID) {
            log += "runtime"
            if (artifactId in failFor) throw RuntimeReleaseFailure("the server part did not start")
            served += artifactId; current = artifactId
        }
        override fun stop(projectId: UUID) { log += "runtime-stop"; if (stopFails) throw RuntimeReleaseFailure("runner unreachable"); stops++; current = null }
    }

    private val store = FakeReleases()
    private val provider = FakeProvider(store)
    private var artifactOk: (UUID) -> ArtifactCheck = { ArtifactCheck(true) }
    private val plane = FakePlane(provider.calls)
    private fun deployer(deployTimeoutMs: Long = 5_000, runtime: RuntimePlane? = null) = ReleaseDeployer(provider, store, { artifactOk(it) }, StepRunner(), deployTimeoutMs, 5_000, runtime)
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
    fun `automatic rollback failure fails closed - the pointer is NULL, never at the release that just failed or at a missing artifact`() {
        provider.switchThenFail = "boom"
        artifactOk = { ArtifactCheck(it != artifactN, "files missing in the store: index.html") }
        val f = failed(deployer().deploy(request()))
        val rb = f.rollback as RollbackResult.Failed
        assertThat(rb.reason).contains("cannot be served again").contains("index.html").contains("taken offline")
        assertThat(store.active).isNull()
        assertThat(provider.calls.filter { it == "restore" }).hasSize(1)             // the only restore is "serve nothing", never a switch to N
        assertThat(store.statuses(releaseN1)).containsExactly("ROLLBACK_FAILED", "ROLLBACK_OFFLINE")        // the failed restore, then the pointer that ended up NULL
        assertThat(store.events.first().second).contains("ROLLBACK FAILED")
    }

    @Test
    fun `a provider that cannot restore is a recorded rollback failure, not a silent one - and it says the site could not be taken offline either`() {
        provider.switchThenFail = "boom"; provider.restoreError = "restore API returned 500"
        val rb = failed(deployer().deploy(request())).rollback as RollbackResult.Failed
        assertThat(rb.reason).contains("restore API returned 500").contains("could NOT be taken offline")
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

    // ------------------------------------------------------------------ verify before serve

    @Test
    fun `the order is verify the artifact, stage, switch, confirm - nothing serves before it was verified`() {
        val order = ArrayList<String>()
        val verified = ReleaseDeployer(provider, store, { order += "artifact-check"; ArtifactCheck(true) }, StepRunner(), 5_000, 5_000)
        verified.deploy(request())
        // the artifact check comes before the provider is asked for anything, and the confirmation comes after the switch
        assertThat(order.first()).isEqualTo("artifact-check")
        assertThat(provider.calls).containsExactly("stage", "deploy", "verify")
    }

    @Test
    fun `an artifact that cannot be verified is never switched in - the serving release is untouched and nothing is recorded as switched`() {
        artifactOk = { ArtifactCheck(it != artifactN1, "file index.html is missing in the artifact store") }
        val f = failed(deployer().deploy(request()))
        assertThat(f.failure.code).isEqualTo(FailureCode.VERIFICATION_FAILED); assertThat(f.failure.message).contains("index.html")
        assertThat(f.rollback).isEqualTo(RollbackResult.NotSwitched)
        assertThat(provider.calls).isEmpty()                                     // not staged, not switched, not even asked
        assertThat(store.active).isEqualTo(releaseN)
        assertThat(store.previous).isEmpty(); assertThat(store.events).isEmpty()
    }

    @Test
    fun `a release that cannot be verified because the check itself failed is not served either`() {
        val broken = ReleaseDeployer(provider, store, { throw IllegalStateException("store unreachable") }, StepRunner(), 5_000, 5_000)
        val f = failed(broken.deploy(request()))
        assertThat(f.rollback).isEqualTo(RollbackResult.NotSwitched)
        assertThat(provider.deployCalls).isZero(); assertThat(store.active).isEqualTo(releaseN)
    }

    @Test
    fun `a first release that cannot be verified never serves anything - no switch, so nothing to take offline`() {
        store.active = null
        artifactOk = { ArtifactCheck(false, "nothing is in the store") }
        val f = failed(deployer().deploy(request()))
        assertThat(f.rollback).isEqualTo(RollbackResult.NotSwitched)
        assertThat(store.active).isNull(); assertThat(provider.calls).isEmpty(); assertThat(store.events).isEmpty()
    }

    @Test
    fun `a provider that cannot stage the release leaves the serving release untouched`() {
        provider.stageError = "could not prepare the release"
        val f = failed(deployer().deploy(request()))
        assertThat(f.failure.code).isEqualTo(FailureCode.DEPLOY_FAILED); assertThat(f.rollback).isEqualTo(RollbackResult.NotSwitched)
        assertThat(provider.deployCalls).isZero(); assertThat(store.active).isEqualTo(releaseN)
    }

    // ------------------------------------------------------------------ TOCTOU: verified, then gone before it is used

    @Test
    fun `manual rollback - the artifact is verified, then disappears before the switch is confirmed - not success, the active release is unchanged`() {
        store.active = releaseN1
        var calls = 0
        artifactOk = { if (++calls == 1) ArtifactCheck(true) else ArtifactCheck(false, "file index.html is missing in the artifact store") }
        val rb = deployer().restoreRelease(project, releaseN) as RollbackResult.Failed
        assertThat(rb.reason).contains("found after the switch").contains("the active release was not changed")
        assertThat(store.active).isEqualTo(releaseN1)                                        // the pointer went back to where it was
        assertThat(store.statuses(releaseN)).containsExactly("ROLLBACK_FAILED")
        assertThat(store.events.none { it.second.startsWith("ROLLBACK_OK") }).isTrue()
    }

    @Test
    fun `automatic rollback - the previous artifact disappears between the check and the confirmation - fail closed, not a pointer at nothing`() {
        provider.switchThenFail = "boom"
        var restoreChecks = 0
        artifactOk = { if (it == artifactN) (if (++restoreChecks == 1) ArtifactCheck(true) else ArtifactCheck(false, "file index.html is missing")) else ArtifactCheck(true) }
        val rb = failed(deployer().deploy(request())).rollback as RollbackResult.Failed
        assertThat(rb.reason).contains("found after the switch").contains("taken offline")
        assertThat(store.active).isNull()
        assertThat(store.statuses(releaseN1)).containsExactly("ROLLBACK_FAILED", "ROLLBACK_OFFLINE")
    }

    @Test
    fun `manual rollback that the provider refuses changes nothing and says so`() {
        store.active = releaseN1
        provider.restoreErrorOnce = "restore API returned 503"
        val rb = deployer().restoreRelease(project, releaseN) as RollbackResult.Failed
        assertThat(rb.reason).contains("503").contains("the active release was not changed"); assertThat(rb.inconsistent).isFalse()
        assertThat(store.active).isEqualTo(releaseN1)
        assertThat(store.statuses(releaseN)).containsExactly("ROLLBACK_FAILED")
    }

    // ------------------------------------------------------------------ static site + server runtime are one release

    @Test
    fun `server app publish - the server runtime serves the new release before the site pointer moves`() {
        plane.current = artifactN
        val o = deployer(runtime = plane).deploy(request())
        assertThat(o).isInstanceOf(DeployOutcome.Live::class.java)
        assertThat(provider.calls).containsExactly("stage", "runtime", "deploy", "verify")     // runtime strictly between stage and the switch
        assertThat(plane.current).isEqualTo(artifactN1); assertThat(store.active).isEqualTo(releaseN1)
    }

    @Test
    fun `server app publish - a runtime that does not start leaves the site and the runtime on the previous release`() {
        plane.current = artifactN; plane.failFor = setOf(artifactN1)
        val f = failed(deployer(runtime = plane).deploy(request()))
        assertThat(f.failure.code).isEqualTo(FailureCode.RUNTIME_DEPLOY_FAILED); assertThat(f.failure.message).contains("did not start")
        assertThat(f.rollback).isEqualTo(RollbackResult.NotSwitched)
        assertThat(provider.deployCalls).isZero()                                               // the site was never pointed at it
        assertThat(store.active).isEqualTo(releaseN); assertThat(plane.current).isEqualTo(artifactN)
    }

    @Test
    fun `server app publish - the switch fails after the runtime moved - both go back to the previous release`() {
        plane.current = artifactN
        provider.switchThenFail = "control plane rejected the release"
        val f = failed(deployer(runtime = plane).deploy(request()))
        assertThat(f.rollback).isInstanceOf(RollbackResult.Restored::class.java)
        assertThat(store.active).isEqualTo(releaseN); assertThat(plane.current).isEqualTo(artifactN)
        assertThat(plane.served).containsExactly(artifactN1, artifactN)                         // forward, then compensated
    }

    @Test
    fun `server app publish - unhealthy after the switch also puts the runtime back, even when the pointer had not moved`() {
        plane.current = artifactN
        provider.failBeforeSwitch = IllegalStateException("cannot reach the provider")         // the runtime already moved, the pointer did not
        val f = failed(deployer(runtime = plane).deploy(request()))
        assertThat(f.rollback).isEqualTo(RollbackResult.NotSwitched)
        assertThat(store.active).isEqualTo(releaseN); assertThat(plane.current).isEqualTo(artifactN)
    }

    @Test
    fun `server app first release - a failure after the runtime started stops it again`() {
        store.active = null; plane.current = null
        provider.switchThenFail = "boom"
        val f = failed(deployer(runtime = plane).deploy(request()))
        assertThat(f.rollback).isEqualTo(RollbackResult.TookOffline)
        assertThat(plane.current).isNull(); assertThat(plane.stops).isEqualTo(1)
    }

    @Test
    fun `server app - when the runtime cannot be put back the site is taken offline, never a mixed release, and it is not reported as a rollback`() {
        plane.current = artifactN
        provider.switchThenFail = "boom"
        plane.failFor = setOf(artifactN)                   // the previous server release will not start again
        val f = failed(deployer(runtime = plane).deploy(request()))
        val rb = f.rollback as RollbackResult.Failed
        assertThat(rb.inconsistent).isTrue(); assertThat(rb.reason).contains("server runtime could not be put back").contains("taken offline")
        assertThat(store.active).isNull()
        assertThat(store.statuses(releaseN1)).containsExactly("ROLLBACK_FAILED", "ROLLBACK_OFFLINE")
    }

    @Test
    fun `server app manual rollback - the runtime goes to the target first, then the site, and both end on the same release`() {
        store.active = releaseN1; plane.current = artifactN1
        val rb = deployer(runtime = plane).restoreRelease(project, releaseN)
        assertThat(rb).isInstanceOf(RollbackResult.Restored::class.java)
        assertThat(provider.calls).containsExactly("runtime", "restore")
        assertThat(store.active).isEqualTo(releaseN); assertThat(plane.current).isEqualTo(artifactN)
    }

    @Test
    fun `server app manual rollback - a runtime that will not start leaves everything as it was`() {
        store.active = releaseN1; plane.current = artifactN1; plane.failFor = setOf(artifactN)
        val rb = deployer(runtime = plane).restoreRelease(project, releaseN) as RollbackResult.Failed
        assertThat(rb.reason).contains("server part of release").contains("could not be restored")
        assertThat(store.active).isEqualTo(releaseN1); assertThat(plane.current).isEqualTo(artifactN1)
        assertThat(provider.restoreCalls).isZero()
    }

    @Test
    fun `server app manual rollback - the site switch fails after the runtime moved - the runtime goes back, the active release is unchanged`() {
        store.active = releaseN1; plane.current = artifactN1
        provider.restoreErrorOnce = "restore API returned 503"
        val rb = deployer(runtime = plane).restoreRelease(project, releaseN) as RollbackResult.Failed
        assertThat(rb.inconsistent).isFalse(); assertThat(rb.reason).contains("the active release was not changed")
        assertThat(store.active).isEqualTo(releaseN1); assertThat(plane.current).isEqualTo(artifactN1)
    }

    @Test
    fun `server app manual rollback - static and runtime cannot be reconciled - offline and inconsistent, never success`() {
        store.active = releaseN1; plane.current = artifactN1
        provider.restoreErrorOnce = "restore API returned 503"
        plane.failFor = setOf(artifactN1)                  // after moving to N, the runtime cannot go back to N+1
        val rb = deployer(runtime = plane).restoreRelease(project, releaseN) as RollbackResult.Failed
        assertThat(rb.inconsistent).isTrue()
        assertThat(store.active).isNull()
        assertThat(store.statuses(releaseN)).containsExactly("ROLLBACK_FAILED", "ROLLBACK_OFFLINE")
    }

    @Test
    fun `an app without a server part never touches the runtime`() {
        val none = object : RuntimePlane {
            override fun applies(projectId: UUID) = false
            override fun currentArtifact(projectId: UUID): UUID? = error("must not be asked")
            override fun serve(projectId: UUID, artifactId: UUID, deploymentId: UUID) = error("must not be asked")
            override fun stop(projectId: UUID) = error("must not be asked")
        }
        assertThat(deployer(runtime = none).deploy(request())).isInstanceOf(DeployOutcome.Live::class.java)
        store.active = releaseN1
        assertThat(deployer(runtime = none).restoreRelease(project, releaseN)).isInstanceOf(RollbackResult.Restored::class.java)
    }

    // ------------------------------------------------------------------ content check before activation

    private class CountingVerifier(val contentOk: Boolean = true) : ArtifactVerifier {
        var cheap = 0; var content = 0
        override fun verify(artifactId: UUID): ArtifactCheck { cheap++; return ArtifactCheck(true) }
        override fun verifyContent(artifactId: UUID): ArtifactCheck { content++; return if (contentOk) ArtifactCheck(true) else ArtifactCheck(false, "file index.html was altered: its checksum differs from the manifest") }
    }

    @Test
    fun `the bytes are verified once before the switch and the cheap check is used after it`() {
        val v = CountingVerifier()
        val live = ReleaseDeployer(provider, store, v, StepRunner(), 5_000, 5_000).deploy(request())
        assertThat(live).isInstanceOf(DeployOutcome.Live::class.java)
        assertThat(v.content).isEqualTo(1); assertThat(v.cheap).isZero()                     // the fake provider does its own confirmation
    }

    @Test
    fun `an artifact whose bytes were altered is never switched in`() {
        val v = CountingVerifier(contentOk = false)
        val f = failed(ReleaseDeployer(provider, store, v, StepRunner(), 5_000, 5_000).deploy(request()))
        assertThat(f.failure.code).isEqualTo(FailureCode.VERIFICATION_FAILED); assertThat(f.failure.message).contains("altered")
        assertThat(f.rollback).isEqualTo(RollbackResult.NotSwitched); assertThat(provider.calls).isEmpty(); assertThat(store.active).isEqualTo(releaseN)
    }

    @Test
    fun `a rollback checks the target's bytes before the switch and only the cheap check after it`() {
        store.active = releaseN1
        val v = CountingVerifier()
        assertThat(ReleaseDeployer(provider, store, v, StepRunner(), 5_000, 5_000).restoreRelease(project, releaseN)).isInstanceOf(RollbackResult.Restored::class.java)
        assertThat(v.content).isEqualTo(1); assertThat(v.cheap).isEqualTo(1)
        store.active = releaseN1
        val bad = CountingVerifier(contentOk = false)
        val rb = ReleaseDeployer(provider, store, bad, StepRunner(), 5_000, 5_000).restoreRelease(project, releaseN) as RollbackResult.Failed
        assertThat(rb.reason).contains("altered"); assertThat(store.active).isEqualTo(releaseN1)
    }
}
