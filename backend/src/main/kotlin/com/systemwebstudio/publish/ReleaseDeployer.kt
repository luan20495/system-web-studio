package com.systemwebstudio.publish

import com.systemwebstudio.integration.deploy.DeployProvider
import com.systemwebstudio.integration.deploy.DeployRequest
import com.systemwebstudio.integration.deploy.DeployVerification
import java.util.UUID

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
    data class Failed(val reason: String, val inconsistent: Boolean = false) : RollbackResult { override val summary = "ROLLBACK FAILED: $reason" }
}

sealed interface DeployOutcome {
    data class Live(val url: String) : DeployOutcome
    data class Failed(val failure: StepFailure, val rollback: RollbackResult) : DeployOutcome
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

    fun deploy(request: DeployRequest): DeployOutcome {
        val projectId = request.projectId ?: return DeployOutcome.Failed(StepFailure(FailureCode.DEPLOY_FAILED, "Missing project"), RollbackResult.NotSwitched)
        val recorded = releases.recordedPrevious(request.deploymentId)
        val previous = recorded ?: releases.activeDeployment(projectId)?.takeIf { it != request.deploymentId }

        // 1. verify BEFORE anything can serve it
        request.artifactId?.let { artifact ->
            val check = try { runner.bounded(verifyTimeoutMs) { artifacts.verify(artifact) } } catch (e: Exception) { return notServed(FailureClassifier.classify("VERIFYING", e)) }
            if (!check.ok) return notServed(StepFailure(FailureCode.VERIFICATION_FAILED, "The release cannot be served: " + FailureClassifier.safe(check.reason)))
        }
        // 2. stage (prepare without serving)
        val staged = try { runner.bounded(deployTimeoutMs) { provider.stage(request) } } catch (e: Exception) { return notServed(FailureClassifier.classify("STAGING", e)) }
        staged.error?.let { return notServed(StepFailure(FailureCode.DEPLOY_FAILED, FailureClassifier.safe(it))) }

        // 3. server runtime: the same release must be serving there before the site points at it
        var undo: RuntimeUndo? = null
        val plane = runtime?.takeIf { request.artifactId != null && it.applies(projectId) }
        if (plane != null) {
            val before = try { plane.currentArtifact(projectId) } catch (e: Exception) { return notServed(runtimeFailure(e)) }
            try { plane.serve(projectId, request.artifactId!!, request.deploymentId) } catch (e: Exception) { return notServed(runtimeFailure(e)) }
            if (before != request.artifactId) undo = RuntimeUndo(plane, before, previous ?: request.deploymentId)
        }

        // 4. the switch
        if (recorded == null && previous != null) releases.recordPrevious(request.deploymentId, previous)
        val switched = try {
            runner.bounded(deployTimeoutMs) { provider.deploy(request) }
        } catch (e: Exception) {
            return failedAfterSwitch(request, previous, FailureClassifier.classify("DEPLOYING", e), undo)
        }
        if (switched.error != null) return failedAfterSwitch(request, previous, StepFailure(FailureCode.DEPLOY_FAILED, FailureClassifier.safe(switched.error)), undo)
        val url = switched.url ?: return failedAfterSwitch(request, previous, StepFailure(FailureCode.DEPLOY_FAILED, "The provider returned no address"), undo)

        // 5. confirm after the switch (the artifact could have gone between the check and the switch)
        val verification = try {
            runner.bounded(verifyTimeoutMs) { provider.verify(request) }
        } catch (e: Exception) {
            DeployVerification.unknown("verification did not complete: " + FailureClassifier.classify("VERIFYING", e).message)
        }
        return when (verification.state) {
            DeployVerification.State.HEALTHY -> DeployOutcome.Live(url)
            DeployVerification.State.UNHEALTHY -> failedAfterSwitch(request, previous,
                StepFailure(FailureCode.VERIFICATION_FAILED, "The new release is unhealthy: " + FailureClassifier.safe(verification.detail)), undo)
            // an answer we cannot trust is never success
            DeployVerification.State.UNKNOWN -> failedAfterSwitch(request, previous,
                StepFailure(FailureCode.DEPLOY_STATE_UNKNOWN, "The state of the new release could not be confirmed: " + FailureClassifier.safe(verification.detail), ambiguous = true), undo)
        }
    }

    private fun notServed(failure: StepFailure) = DeployOutcome.Failed(failure, RollbackResult.NotSwitched)

    private fun runtimeFailure(e: Exception): StepFailure {
        val message = if (e is RuntimeReleaseFailure) FailureClassifier.safe(e.message, 300) else FailureClassifier.classify("DEPLOYING", e).message
        return StepFailure(FailureCode.RUNTIME_DEPLOY_FAILED, message)
    }

    private fun failedAfterSwitch(request: DeployRequest, previous: UUID?, failure: StepFailure, undo: RuntimeUndo?) =
        DeployOutcome.Failed(failure, compensate(request, previous, undo))

    /** Idempotent: safe to run again after a crash in the middle of it. */
    fun rollbackFailedDeployment(request: DeployRequest, previous: UUID?): RollbackResult = compensate(request, previous, null)

    /**
     * The static site back to [previous], then the server runtime back to what it served; both or (fail closed) neither is served. Only the
     * FINAL outcome is written to the history: a site that went back while the runtime could not follow is one failed rollback, not a success.
     */
    private fun compensate(request: DeployRequest, previous: UUID?, undo: RuntimeUndo?): RollbackResult {
        val projectId = request.projectId ?: return RollbackResult.NotSwitched
        val site = staticRollback(request, projectId, previous)
        val runtimeProblem = undo?.let { undoRuntime(projectId, it) } ?: return recorded(request.deploymentId, site)
        // the server part could not follow: the old site against a different server release is a mixed release, which is never served
        val offline = takeOffline(projectId)
        return recorded(request.deploymentId, RollbackResult.Failed(
            "the server runtime could not be put back on the previous release ($runtimeProblem); the site " +
                (if (offline == null) "was taken offline" else "could NOT be taken offline ($offline)") + " so that no mixed release is served", inconsistent = true))
    }

    private fun staticRollback(request: DeployRequest, projectId: UUID, previous: UUID?): RollbackResult {
        val active = try { releases.activeDeployment(projectId) } catch (e: Exception) {
            return RollbackResult.Failed("could not read the active release: " + FailureClassifier.safe(e.message, 120))
        }
        if (active != request.deploymentId) return RollbackResult.NotSwitched
        val problem = switchStatic(projectId, previous)
        if (problem == null) return if (previous == null) RollbackResult.TookOffline else RollbackResult.Restored(previous, releases.label(previous))
        // the earlier release cannot be served: fail closed, the site points at nothing rather than at the release that just failed
        val offline = takeOffline(projectId)
        return RollbackResult.Failed(problem + if (offline == null) "; the site was taken offline" else "; the site could NOT be taken offline ($offline)")
    }

    /** null = the server runtime serves its previous release again (or never changed) */
    private fun undoRuntime(projectId: UUID, undo: RuntimeUndo): String? = try {
        if (undo.before == null) undo.plane.stop(projectId) else undo.plane.serve(projectId, undo.before, undo.restoreFor)
        null
    } catch (e: Exception) { if (e is RuntimeReleaseFailure) FailureClassifier.safe(e.message, 200) else FailureClassifier.classify("DEPLOYING", e).message }

    /**
     * Manual rollback / roll-forward to an earlier successful release. No build. Order: verify the artifact, move the server runtime, switch the
     * site, confirm. If any step fails everything is put back exactly as it was and the result is [RollbackResult.Failed]: the release that was
     * active stays active. Repeating it is safe (already active -> AlreadyActive, nothing changes).
     */
    fun restoreRelease(projectId: UUID, target: UUID): RollbackResult {
        val current = releases.activeDeployment(projectId)
        if (current == target) return RollbackResult.AlreadyActive(target, releases.label(target))
        return recorded(target, restoreManually(projectId, target, current))
    }

    private fun restoreManually(projectId: UUID, target: UUID, current: UUID?): RollbackResult {
        val artifact = releases.artifactOf(target) ?: return RollbackResult.Failed("release ${releases.label(target)} has no artifact")
        artifactProblem(target, artifact)?.let { return RollbackResult.Failed(it) }
        val plane = runtime?.takeIf { it.applies(projectId) }
        val before = plane?.let { try { it.currentArtifact(projectId) } catch (e: Exception) { return RollbackResult.Failed("the server runtime could not be read: " + FailureClassifier.classify("DEPLOYING", e).message) } }
        if (plane != null) {
            try { plane.serve(projectId, artifact, target) } catch (e: Exception) {
                return RollbackResult.Failed("the server part of release ${releases.label(target)} could not be restored: " + runtimeFailure(e).message)     // nothing else was touched
            }
        }
        val problem = switchStatic(projectId, target, precheck = false) ?: return RollbackResult.Restored(target, releases.label(target))
        // put it all back: the site on the release that was active, the server runtime on what it served
        val siteBack = if (releases.activeDeployment(projectId) == current) null else switchStaticBack(projectId, current)
        val runtimeBack = if (plane != null && before != artifact) undoRuntime(projectId, RuntimeUndo(plane, before, current ?: target)) else null
        if (siteBack == null && runtimeBack == null) return RollbackResult.Failed("$problem; the active release was not changed")
        val offline = takeOffline(projectId)
        return RollbackResult.Failed("$problem; and the previous state could not be restored (${listOfNotNull(siteBack, runtimeBack).joinToString("; ")}); the site " +
            (if (offline == null) "was taken offline" else "could NOT be taken offline ($offline)") + " so that no mixed release is served", inconsistent = true)
    }

    private fun switchStaticBack(projectId: UUID, target: UUID?): String? = try {
        runner.bounded(deployTimeoutMs) { provider.restore(projectId, target) }.error?.let { FailureClassifier.safe(it) }
    } catch (e: Exception) { FailureClassifier.classify("DEPLOYING", e).message }

    /** The static site on [target] (null = nothing): verify its artifact, switch, confirm. null = done and confirmed, else why not. */
    private fun switchStatic(projectId: UUID, target: UUID?, precheck: Boolean = true): String? {
        val artifact = target?.let { releases.artifactOf(it) ?: return "release ${releases.label(it)} has no artifact" }
        if (precheck && target != null && artifact != null) artifactProblem(target, artifact)?.let { return it }
        val result = try { runner.bounded(deployTimeoutMs) { provider.restore(projectId, target) } } catch (e: Exception) {
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
    private fun takeOffline(projectId: UUID): String? = try {
        runner.bounded(deployTimeoutMs) { provider.restore(projectId, null) }.error?.let { FailureClassifier.safe(it) }
    } catch (e: Exception) { FailureClassifier.classify("DEPLOYING", e).message }

    private fun recorded(onDeployment: UUID, result: RollbackResult): RollbackResult {
        val status = when (result) {
            is RollbackResult.Failed -> "ROLLBACK_FAILED"
            is RollbackResult.TookOffline -> "ROLLBACK_OFFLINE"
            is RollbackResult.Restored -> "ROLLBACK_OK"
            is RollbackResult.AlreadyActive, is RollbackResult.NotSwitched -> return result
        }
        runCatching { releases.event(onDeployment, status, result.summary.take(StepFailure.MAX_LENGTH)) }
        return result
    }
}
