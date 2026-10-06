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
    /** the earlier release could not be made active again. The deployment that triggered it is FAILED and this is recorded as ROLLBACK_FAILED */
    data class Failed(val reason: String) : RollbackResult { override val summary = "ROLLBACK FAILED: $reason" }
}

sealed interface DeployOutcome {
    data class Live(val url: String) : DeployOutcome
    data class Failed(val failure: StepFailure, val rollback: RollbackResult) : DeployOutcome
}

/**
 * The DEPLOYING step: switch the site to the new release, run the optional follow-up (server runtime), verify it, and if ANY of that fails
 * after the switch was attempted, put the previous release back from its immutable artifact (never a rebuild). Outcome is deterministic:
 * either the new release is live and verified, or the previous release is active again (or the failure says why that was impossible).
 *
 * Why the pointer is checked before restoring: the rollback only acts when the site still points at THIS deployment. If another deployment
 * has switched since, its pointer is left alone.
 */
class ReleaseDeployer(
    private val provider: DeployProvider, private val releases: ReleaseStore, private val artifacts: ArtifactVerifier,
    private val runner: StepRunner = StepRunner(), private val deployTimeoutMs: Long = 120_000, private val verifyTimeoutMs: Long = 60_000
) {
    fun deploy(request: DeployRequest, afterSwitch: (() -> Unit)? = null): DeployOutcome {
        val projectId = request.projectId ?: return DeployOutcome.Failed(StepFailure(FailureCode.DEPLOY_FAILED, "Missing project"), RollbackResult.NotSwitched)
        val previous = releases.recordedPrevious(request.deploymentId)
            ?: releases.activeDeployment(projectId)?.takeIf { it != request.deploymentId }?.also { releases.recordPrevious(request.deploymentId, it) }

        val switched = try {
            runner.bounded(deployTimeoutMs) { provider.deploy(request) }
        } catch (e: Exception) {
            return failedAfterSwitch(request, previous, FailureClassifier.classify("DEPLOYING", e))
        }
        if (switched.error != null) return failedAfterSwitch(request, previous, StepFailure(FailureCode.DEPLOY_FAILED, FailureClassifier.safe(switched.error)))
        val url = switched.url ?: return failedAfterSwitch(request, previous, StepFailure(FailureCode.DEPLOY_FAILED, "The provider returned no address"))

        if (afterSwitch != null) {
            try { afterSwitch() } catch (e: Exception) {
                val c = FailureClassifier.classify("DEPLOYING", e)
                return failedAfterSwitch(request, previous, StepFailure(FailureCode.RUNTIME_DEPLOY_FAILED, c.message, ambiguous = c.ambiguous))
            }
        }

        val verification = try {
            runner.bounded(verifyTimeoutMs) { provider.verify(request) }
        } catch (e: Exception) {
            DeployVerification.unknown("verification did not complete: " + FailureClassifier.classify("VERIFYING", e).message)
        }
        return when (verification.state) {
            DeployVerification.State.HEALTHY -> DeployOutcome.Live(url)
            DeployVerification.State.UNHEALTHY -> failedAfterSwitch(request, previous,
                StepFailure(FailureCode.VERIFICATION_FAILED, "The new release is unhealthy: " + FailureClassifier.safe(verification.detail)))
            // an answer we cannot trust is never success
            DeployVerification.State.UNKNOWN -> failedAfterSwitch(request, previous,
                StepFailure(FailureCode.DEPLOY_STATE_UNKNOWN, "The state of the new release could not be confirmed: " + FailureClassifier.safe(verification.detail), ambiguous = true))
        }
    }

    private fun failedAfterSwitch(request: DeployRequest, previous: UUID?, failure: StepFailure) =
        DeployOutcome.Failed(failure, rollbackFailedDeployment(request, previous))

    /** Idempotent: safe to run again after a crash in the middle of it. */
    fun rollbackFailedDeployment(request: DeployRequest, previous: UUID?): RollbackResult {
        val projectId = request.projectId ?: return RollbackResult.NotSwitched
        val active = try { releases.activeDeployment(projectId) } catch (e: Exception) {
            return recorded(request.deploymentId, RollbackResult.Failed("could not read the active release: " + FailureClassifier.safe(e.message, 120)))
        }
        if (active != request.deploymentId) return RollbackResult.NotSwitched
        return recorded(request.deploymentId, switchTo(projectId, previous))
    }

    /**
     * Manual rollback / roll-forward to an earlier successful release. No build; the artifact is verified first. Repeating it is safe
     * (already active -> AlreadyActive, nothing changes).
     */
    fun restoreRelease(projectId: UUID, target: UUID): RollbackResult {
        if (releases.activeDeployment(projectId) == target) return RollbackResult.AlreadyActive(target, releases.label(target))
        val result = switchTo(projectId, target)
        return recorded(target, result)
    }

    private fun switchTo(projectId: UUID, target: UUID?): RollbackResult {
        if (target != null) {
            val artifact = releases.artifactOf(target)
                ?: return RollbackResult.Failed("release ${releases.label(target)} has no artifact")
            val check = try { artifacts.verify(artifact) } catch (e: Exception) { ArtifactCheck(false, "verification failed: " + FailureClassifier.safe(e.message, 120)) }
            if (!check.ok) return RollbackResult.Failed("release ${releases.label(target)} cannot be served again: ${check.reason}")
        }
        val result = try { runner.bounded(deployTimeoutMs) { provider.restore(projectId, target) } } catch (e: Exception) {
            return RollbackResult.Failed("the provider did not restore the release: " + FailureClassifier.classify("DEPLOYING", e).message)
        }
        if (result.error != null) return RollbackResult.Failed(FailureClassifier.safe(result.error))
        return if (target == null) RollbackResult.TookOffline else RollbackResult.Restored(target, releases.label(target))
    }

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
