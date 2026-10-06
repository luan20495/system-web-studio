package com.systemwebstudio.publish

import com.systemwebstudio.integration.deploy.DeployProvider
import com.systemwebstudio.integration.deploy.DeployRequest
import com.systemwebstudio.integration.deploy.DeployVerification
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ArtifactCheck(val ok: Boolean, val reason: String? = null)

/** Proves that an artifact can still be served: the record exists, was not removed by retention, and its files are all in the store. */
fun interface ArtifactVerifier { fun verify(artifactId: UUID): ArtifactCheck }

/** The release bookkeeping the deployer needs (JDBC in production, a fake in unit tests). */
interface ReleaseStore {
    /** the deployment the project's site points at, null = nothing is served */
    fun activeDeployment(projectId: UUID): UUID?
    /** the release that was active when this deployment started switching, if one was recorded (survives a worker restart) */
    fun recordedPrevious(deploymentId: UUID): UUID?
    fun recordPrevious(deploymentId: UUID, previous: UUID)
    fun artifactOf(deploymentId: UUID): UUID?
    /** short human label of a deployment, e.g. `v3 (1a2b3c4d)` */
    fun label(deploymentId: UUID): String
    /** release / deployment history: one row in deployment_events */
    fun event(deploymentId: UUID, status: String, message: String)
    /**
     * DEPLOYING -> ROLLING_BACK, durably, before any undo runs (so a crash resumes the undo and never rolls forward). true = the deployment is
     * ROLLING_BACK now (it already was, or it just became so); false = it is in some other status and must not be undone.
     */
    fun beginRollback(deploymentId: UUID, reason: String): Boolean = true
    /**
     * RUNNING -> ROLLED_BACK of the release that was active, only when it is NEWER than the one restored (a roll-forward leaves it RUNNING). Called by
     * the pointer commit, in the same transaction as the pointer change.
     */
    fun markRolledBackIfNewer(left: UUID, target: UUID) {}
    /** the operation that holds the release scope of this project right now, null = free (used to tell "I was replaced by the same operation" from "a newer one won") */
    fun leaseOperation(projectId: UUID): UUID? = null
}

sealed interface RollbackResult {
    val summary: String
    /** the site was not pointing at this deployment, so nothing was touched (never clobber a newer release) */
    data object NotSwitched : RollbackResult { override val summary = "no rollback needed: the site was never switched to this deployment" }
    /** the requested release is already the active one: repeating a rollback changes nothing */
    data class AlreadyActive(val deploymentId: UUID, val label: String) : RollbackResult { override val summary = "release $label is already active" }
    data class Restored(val deploymentId: UUID, val label: String) : RollbackResult { override val summary = "restored release $label (no rebuild)" }
    data object TookOffline : RollbackResult { override val summary = "no earlier release existed; the site serves nothing" }
    /**
     * The earlier release could not be made active again, and was not reported as success. Automatic rollback: the site pointer is NULL (fail
     * closed) and the deployment that triggered it is FAILED. Manual rollback: the release that was active is still active, unchanged.
     * [inconsistent] = the static site and the server runtime could not be put on the same release; the site was taken offline instead of
     * serving a mix, and an operator has to look at it.
     */
    data class Failed(val reason: String, val inconsistent: Boolean = false, val tookOffline: Boolean = false) : RollbackResult { override val summary = "ROLLBACK FAILED: $reason" }
    /** this operation lost the release scope (its pointer write was refused): nothing was written by it from then on, whoever holds the scope now decides */
    data object ScopeLost : RollbackResult { override val summary = "the release scope was lost; the active release was not changed by this operation" }
}

sealed interface DeployOutcome {
    data class Live(val url: String) : DeployOutcome
    /** [rollingBack] = the deployment is ROLLING_BACK (the switch was attempted), so it leaves that state to FAILED, not from DEPLOYING */
    data class Failed(val failure: StepFailure, val rollback: RollbackResult, val rollingBack: Boolean = false) : DeployOutcome
    /** the scope was taken over by the SAME operation (a redelivery after a crash): the resumed run decides, this one writes nothing more */
    data object Lost : DeployOutcome
    /** another operation owns the release scope: nothing was done, the publish goes back to the queue and waits */
    data class Busy(val holder: ScopeHolder?) : DeployOutcome
}

/**
 * The DEPLOYING step. Order, always: verify the artifact -> stage -> server runtime healthy (server apps) -> switch the site -> confirm.
 * A release is never switched in before it was verified, and the static site and the server runtime end on the SAME release or the
 * operation fails and says so:
 *  - a failure before the switch leaves the serving release untouched (the server runtime too: it keeps the previous release);
 *  - a failure after it puts the previous release back from its immutable artifact (never a rebuild), static site and server runtime both;
 *  - if that is impossible the site is taken offline (pointer NULL, the deployment ends FAILED): nothing mixed is ever served.
 * The runtime is moved first and the site pointer last because the pointer is the one instant, local and reversible step.
 *
 * Why the pointer is checked before restoring: the rollback only acts when the site still points at THIS deployment. If another deployment
 * has switched since, its pointer is left alone.
 */
class ReleaseDeployer(
    private val provider: DeployProvider, private val releases: ReleaseStore, private val artifacts: ArtifactVerifier,
    private val runner: StepRunner = StepRunner(), private val deployTimeoutMs: Long = 120_000, private val verifyTimeoutMs: Long = 60_000,
    private val runtime: RuntimePlane? = null
) {
    /** what must be undone on the server runtime if the site cannot be switched or confirmed */
    private class RuntimeUndo(val plane: RuntimePlane, val before: UUID?, val restoreFor: UUID)

    /** the pointer as it was when this operation took the scope (read under the lease); an unguarded lease (unit tests) reads it now */
    private fun activeNow(projectId: UUID, lease: ScopeLease): UUID? =
        if (lease.fenceToken == ScopeLease.NO_FENCE) releases.activeDeployment(projectId) else lease.activeDeploymentId

    /**
     * Runs under [lease]: every pointer move below goes through its fence (compare-and-set), so if the scope is lost half way this operation stops
     * writing at once and reports [DeployOutcome.Lost] (the same operation took over: it decides) or STALE_PUBLISH (someone else did).
     */
    fun deploy(request: DeployRequest, lease: ScopeLease = ScopeLease.unguarded()): DeployOutcome {
        val projectId = request.projectId ?: return DeployOutcome.Failed(StepFailure(FailureCode.DEPLOY_FAILED, "Missing project"), RollbackResult.NotSwitched)
        val fenced = request.copy(fence = lease.fence)
        val recorded = releases.recordedPrevious(request.deploymentId)
        val previous = recorded ?: activeNow(projectId, lease)?.takeIf { it != request.deploymentId }

        // 1. verify BEFORE anything can serve it
        request.artifactId?.let { artifact ->
            val check = try { runner.bounded(verifyTimeoutMs) { artifacts.verify(artifact) } } catch (e: Exception) { return notServed(FailureClassifier.classify("VERIFYING", e)) }
            if (!check.ok) return notServed(StepFailure(FailureCode.VERIFICATION_FAILED, "The release cannot be served: " + FailureClassifier.safe(check.reason)))
        }
        // 2. stage (prepare without serving)
        val staged = try { runner.bounded(deployTimeoutMs) { provider.stage(fenced) } } catch (e: Exception) { return notServed(FailureClassifier.classify("STAGING", e)) }
        staged.error?.let { return notServed(StepFailure(FailureCode.DEPLOY_FAILED, FailureClassifier.safe(it))) }
        if (lease.lost) return scopeLost(request, projectId)

        // 3. server runtime: the same release must be serving there before the site points at it
        var undo: RuntimeUndo? = null
        val plane = runtime?.takeIf { request.artifactId != null && it.applies(projectId) }
        if (plane != null) {
            val before = try { plane.currentArtifact(projectId) } catch (e: Exception) { return notServed(runtimeFailure(e)) }
            try { plane.serve(projectId, request.artifactId!!, request.deploymentId) } catch (e: Exception) { return notServed(runtimeFailure(e)) }
            if (before != request.artifactId) undo = RuntimeUndo(plane, before, previous ?: request.deploymentId)
            if (lease.lost) return scopeLost(request, projectId)
        }

        // 4. the switch: a compare-and-set through the fence, never a blind write
        if (recorded == null && previous != null) releases.recordPrevious(request.deploymentId, previous)
        val switched = try {
            runner.bounded(deployTimeoutMs) { provider.deploy(fenced) }
        } catch (e: Exception) {
            return failedAfterSwitch(request, previous, FailureClassifier.classify("DEPLOYING", e), undo, lease)
        }
        if (switched.error != null) return failedAfterSwitch(request, previous, StepFailure(FailureCode.DEPLOY_FAILED, FailureClassifier.safe(switched.error)), undo, lease)
        val url = switched.url ?: return failedAfterSwitch(request, previous, StepFailure(FailureCode.DEPLOY_FAILED, "The provider returned no address"), undo, lease)

        // 5. confirm after the switch (the artifact could have gone between the check and the switch)
        val verification = try {
            runner.bounded(verifyTimeoutMs) { provider.verify(fenced) }
        } catch (e: Exception) {
            DeployVerification.unknown("verification did not complete: " + FailureClassifier.classify("VERIFYING", e).message)
        }
        return when (verification.state) {
            DeployVerification.State.HEALTHY -> if (lease.lost) scopeLost(request, projectId) else DeployOutcome.Live(url)
            DeployVerification.State.UNHEALTHY -> failedAfterSwitch(request, previous,
                StepFailure(FailureCode.VERIFICATION_FAILED, "The new release is unhealthy: " + FailureClassifier.safe(verification.detail)), undo, lease)
            // an answer we cannot trust is never success
            DeployVerification.State.UNKNOWN -> failedAfterSwitch(request, previous,
                StepFailure(FailureCode.DEPLOY_STATE_UNKNOWN, "The state of the new release could not be confirmed: " + FailureClassifier.safe(verification.detail), ambiguous = true), undo, lease)
        }
    }

    private fun notServed(failure: StepFailure) = DeployOutcome.Failed(failure, RollbackResult.NotSwitched)

    /** this operation no longer owns the scope. Same operation took over (a redelivery) -> it decides, this run is silent; another operation -> this publish lost to it */
    private fun scopeLost(request: DeployRequest, projectId: UUID): DeployOutcome =
        if (releases.leaseOperation(projectId) == request.deploymentId) DeployOutcome.Lost
        else DeployOutcome.Failed(StepFailure(FailureCode.STALE_PUBLISH, "The release scope was taken over by another operation before this release could be activated"), RollbackResult.ScopeLost)

    private fun runtimeFailure(e: Exception): StepFailure {
        val message = if (e is RuntimeReleaseFailure) FailureClassifier.safe(e.message, 300) else FailureClassifier.classify("DEPLOYING", e).message
        return StepFailure(FailureCode.RUNTIME_DEPLOY_FAILED, message)
    }

    /** From here only the undo may run: ROLLING_BACK is written first, so a crash resumes the undo and never rolls forward. */
    private fun failedAfterSwitch(request: DeployRequest, previous: UUID?, failure: StepFailure, undo: RuntimeUndo?, lease: ScopeLease): DeployOutcome {
        val projectId = request.projectId ?: return notServed(failure)
        if (lease.lost) return scopeLost(request, projectId)
        if (!releases.beginRollback(request.deploymentId, failure.reason())) return DeployOutcome.Lost            // not DEPLOYING any more: someone else settled it
        return DeployOutcome.Failed(failure, compensate(request, previous, undo, lease), rollingBack = true)
    }

    /** Idempotent: safe to run again after a crash in the middle of it. */
    fun rollbackFailedDeployment(request: DeployRequest, previous: UUID?, lease: ScopeLease = ScopeLease.unguarded()): RollbackResult = compensate(request, previous, null, lease)

    /**
     * Finish the undo of a deployment that is ROLLING_BACK after a crash: the previous release is the typed one recorded at the switch, the server
     * runtime goes back to that release's artifact (or stops when there was none). Never re-verifies the failed release, never rolls forward.
     */
    fun resumeRollback(request: DeployRequest, lease: ScopeLease = ScopeLease.unguarded()): RollbackResult {
        val projectId = request.projectId ?: return RollbackResult.NotSwitched
        val previous = releases.recordedPrevious(request.deploymentId)
        val plane = runtime?.takeIf { it.applies(projectId) }
        val before = previous?.let { releases.artifactOf(it) }
        val undo = plane?.let { p -> if (runCatching { p.currentArtifact(projectId) }.getOrNull() != before) RuntimeUndo(p, before, previous ?: request.deploymentId) else null }
        return compensate(request, previous, undo, lease)
    }

    /**
     * The static site back to [previous], then the server runtime back to what it served; both or (fail closed) neither is served. Only the
     * FINAL outcome is written to the history: a site that went back while the runtime could not follow is one failed rollback, not a success.
     */
    private fun compensate(request: DeployRequest, previous: UUID?, undo: RuntimeUndo?, lease: ScopeLease): RollbackResult {
        val projectId = request.projectId ?: return RollbackResult.NotSwitched
        val site = staticRollback(request, projectId, previous, lease)
        if (site is RollbackResult.ScopeLost) return site                                       // the scope is someone else's now: they reconcile the runtime too
        val runtimeProblem = undo?.let { undoRuntime(projectId, it) } ?: return recorded(request.deploymentId, site)
        // the server part could not follow: the old site against a different server release is a mixed release, which is never served
        val offline = takeOffline(projectId, lease)
        return recorded(request.deploymentId, RollbackResult.Failed(
            "the server runtime could not be put back on the previous release ($runtimeProblem); the site " +
                (if (offline == null) "was taken offline" else "could NOT be taken offline ($offline)") + " so that no mixed release is served", inconsistent = true, tookOffline = offline == null))
    }

    private fun staticRollback(request: DeployRequest, projectId: UUID, previous: UUID?, lease: ScopeLease): RollbackResult {
        val active = try { releases.activeDeployment(projectId) } catch (e: Exception) {
            return RollbackResult.Failed("could not read the active release: " + FailureClassifier.safe(e.message, 120))
        }
        if (active != request.deploymentId) return RollbackResult.NotSwitched
        val problem = switchStatic(projectId, previous, lease)
        if (problem == null) return if (previous == null) RollbackResult.TookOffline else RollbackResult.Restored(previous, releases.label(previous))
        if (lease.lost) return RollbackResult.ScopeLost
        // the earlier release cannot be served: fail closed, the site points at nothing rather than at the release that just failed
        val offline = takeOffline(projectId, lease)
        if (lease.lost) return RollbackResult.ScopeLost
        return RollbackResult.Failed(problem + if (offline == null) "; the site was taken offline" else "; the site could NOT be taken offline ($offline)", tookOffline = offline == null)
    }

    /** null = the server runtime serves its previous release again (or never changed) */
    private fun undoRuntime(projectId: UUID, undo: RuntimeUndo): String? = try {
        if (undo.before == null) undo.plane.stop(projectId) else undo.plane.serve(projectId, undo.before, undo.restoreFor)
        null
    } catch (e: Exception) { if (e is RuntimeReleaseFailure) FailureClassifier.safe(e.message, 200) else FailureClassifier.classify("DEPLOYING", e).message }

    /**
     * Manual rollback / roll-forward to an earlier successful release. No build. Order: verify the artifact, move the server runtime, switch the
     * site, confirm. If any step fails everything is put back exactly as it was and the result is [RollbackResult.Failed]: the release that was
     * active stays active. In the pointer's own transaction the release that was left becomes ROLLED_BACK, only when it is newer than the target.
     * Repeating it is safe (already active -> AlreadyActive, nothing changes), and two requests of the same operation converge on one result.
     */
    fun restoreRelease(projectId: UUID, target: UUID, lease: ScopeLease = ScopeLease.unguarded()): RollbackResult {
        val current = activeNow(projectId, lease)
        if (current == target) return RollbackResult.AlreadyActive(target, releases.label(target))
        val result = restoreManually(projectId, target, current, lease)
        // refused because the scope was lost, yet the release is the active one: somebody (the same operation, retried) already did exactly this
        if (result is RollbackResult.ScopeLost && releases.activeDeployment(projectId) == target) return RollbackResult.AlreadyActive(target, releases.label(target))
        return recorded(target, result)
    }

    private fun restoreManually(projectId: UUID, target: UUID, current: UUID?, lease: ScopeLease): RollbackResult {
        val artifact = releases.artifactOf(target) ?: return RollbackResult.Failed("release ${releases.label(target)} has no artifact")
        artifactProblem(target, artifact)?.let { return RollbackResult.Failed(it) }
        val plane = runtime?.takeIf { it.applies(projectId) }
        val before = plane?.let { try { it.currentArtifact(projectId) } catch (e: Exception) { return RollbackResult.Failed("the server runtime could not be read: " + FailureClassifier.classify("DEPLOYING", e).message) } }
        if (plane != null) {
            try { plane.serve(projectId, artifact, target) } catch (e: Exception) {
                return RollbackResult.Failed("the server part of release ${releases.label(target)} could not be restored: " + runtimeFailure(e).message)     // nothing else was touched
            }
        }
        // the left release becomes ROLLED_BACK in the same transaction as the pointer change, and only for the switch to the target (not for a revert below)
        val armed = AtomicBoolean(current != null)
        if (current != null) lease.fence.withNextCommit { if (armed.get()) releases.markRolledBackIfNewer(current, target) }
        val problem = try { switchStatic(projectId, target, lease, precheck = false) } finally { armed.set(false) }
        if (problem == null) return RollbackResult.Restored(target, releases.label(target))
        if (lease.lost) return RollbackResult.ScopeLost
        // put it all back: the site on the release that was active, the server runtime on what it served
        val siteBack = if (releases.activeDeployment(projectId) == current) null else switchStaticBack(projectId, current, lease)
        val runtimeBack = if (plane != null && before != artifact) undoRuntime(projectId, RuntimeUndo(plane, before, current ?: target)) else null
        if (siteBack == null && runtimeBack == null) return RollbackResult.Failed("$problem; the active release was not changed")
        val offline = takeOffline(projectId, lease)
        return RollbackResult.Failed("$problem; and the previous state could not be restored (${listOfNotNull(siteBack, runtimeBack).joinToString("; ")}); the site " +
            (if (offline == null) "was taken offline" else "could NOT be taken offline ($offline)") + " so that no mixed release is served", inconsistent = true, tookOffline = offline == null)
    }

    private fun switchStaticBack(projectId: UUID, target: UUID?, lease: ScopeLease): String? = try {
        runner.bounded(deployTimeoutMs) { provider.restore(projectId, target, lease.fence) }.error?.let { FailureClassifier.safe(it) }
    } catch (e: Exception) { FailureClassifier.classify("DEPLOYING", e).message }

    /** The static site on [target] (null = nothing): verify its artifact, switch (compare-and-set), confirm. null = done and confirmed, else why not. */
    private fun switchStatic(projectId: UUID, target: UUID?, lease: ScopeLease, precheck: Boolean = true): String? {
        val artifact = target?.let { releases.artifactOf(it) ?: return "release ${releases.label(it)} has no artifact" }
        if (precheck && target != null && artifact != null) artifactProblem(target, artifact)?.let { return it }
        val result = try { runner.bounded(deployTimeoutMs) { provider.restore(projectId, target, lease.fence) } } catch (e: Exception) {
            return "the provider did not restore the release: " + FailureClassifier.classify("DEPLOYING", e).message
        }
        result.error?.let { return FailureClassifier.safe(it) }
        if (target != null && artifact != null) {
            // the artifact was checked before the switch; it may have been removed or changed since
            artifactProblem(target, artifact)?.let { return "$it (found after the switch)" }
            if (releases.activeDeployment(projectId) != target) return "the site does not point at release ${releases.label(target)} after the switch"
        }
        return null
    }

    private fun artifactProblem(target: UUID, artifact: UUID): String? {
        val check = try { runner.bounded(verifyTimeoutMs) { artifacts.verify(artifact) } } catch (e: Exception) { ArtifactCheck(false, "verification failed: " + FailureClassifier.safe(e.message, 120)) }
        return if (check.ok) null else "release ${releases.label(target)} cannot be served again: ${check.reason}"
    }

    /** null = the site points at nothing now */
    private fun takeOffline(projectId: UUID, lease: ScopeLease): String? = try {
        runner.bounded(deployTimeoutMs) { provider.restore(projectId, null, lease.fence) }.error?.let { FailureClassifier.safe(it) }
    } catch (e: Exception) { FailureClassifier.classify("DEPLOYING", e).message }

    private fun recorded(onDeployment: UUID, result: RollbackResult): RollbackResult {
        val status = when (result) {
            is RollbackResult.Failed -> "ROLLBACK_FAILED"
            is RollbackResult.TookOffline -> "ROLLBACK_OFFLINE"
            is RollbackResult.Restored -> "ROLLBACK_OK"
            // nothing was done by this operation: nothing to record
            is RollbackResult.AlreadyActive, is RollbackResult.NotSwitched, is RollbackResult.ScopeLost -> return result
        }
        runCatching { releases.event(onDeployment, status, result.summary.take(StepFailure.MAX_LENGTH)) }
        // fail-closed: the failed restore is one event, the pointer that ended up NULL is another (contract 3.1 step 4)
        if (result is RollbackResult.Failed && result.tookOffline) runCatching { releases.event(onDeployment, "ROLLBACK_OFFLINE", "fail-closed after a failed restore: the site serves nothing") }
        return result
    }
}
