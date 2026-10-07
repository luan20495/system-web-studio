package com.systemwebstudio.publish

import java.time.Instant
import java.util.UUID

object DeploymentStatus {
    const val QUEUED = "QUEUED"
    const val POLICY_CHECK = "POLICY_CHECK"
    const val SECURITY_CHECK = "SECURITY_CHECK"
    const val BUILDING = "BUILDING"
    const val DEPLOYING = "DEPLOYING"
    /** non-terminal and never served: the decision to undo this deployment is durable; whoever holds the scope (or the recovery sweeper) finishes the undo and never rolls forward */
    const val ROLLING_BACK = "ROLLING_BACK"
    const val RUNNING = "RUNNING"
    const val FAILED = "FAILED"
    const val ROLLED_BACK = "ROLLED_BACK"

    /** Order of the happy path. A deployment only ever moves forward along it or to FAILED (ROLLING_BACK is a side path, see [allowed]). */
    val pipeline = listOf(QUEUED, POLICY_CHECK, SECURITY_CHECK, BUILDING, DEPLOYING, RUNNING)
    val terminal = setOf(RUNNING, FAILED, ROLLED_BACK)
    /** statuses the recovery sweeper must resume if they stop moving */
    val inProgress = setOf(QUEUED, POLICY_CHECK, SECURITY_CHECK, BUILDING, DEPLOYING, ROLLING_BACK)

    /**
     * The only legal edges (C2_DEPLOY_CONTRACT.md §1.1):
     *  - along [pipeline], one step at a time; any in-progress status -> FAILED;
     *  - DEPLOYING -> ROLLING_BACK (verification failed / unknown / deploy error after the switch was attempted);
     *  - ROLLING_BACK -> FAILED, and nothing else (the undo finished: previous release restored, or the site taken offline);
     *  - RUNNING -> ROLLED_BACK (a manual rollback moved away from this release);
     *  - nothing leaves RUNNING, FAILED or ROLLED_BACK otherwise, and nothing goes back to a pipeline status.
     */
    fun allowed(from: String, to: String): Boolean = when {
        from in terminal -> to == ROLLED_BACK && from == RUNNING
        from == ROLLING_BACK -> to == FAILED
        to == ROLLING_BACK -> from == DEPLOYING
        to == FAILED -> true
        else -> pipeline.indexOf(to) == pipeline.indexOf(from) + 1 && pipeline.indexOf(from) >= 0
    }
}

data class DeploymentEventDto(val status: String, val message: String?, val createdAt: Instant)

data class DeploymentDto(
    val id: UUID, val projectId: UUID, val versionId: UUID, val versionNumber: Int, val visibility: String,
    val status: String, val url: String?, val error: String?, val provider: String,
    val createdAt: Instant, val updatedAt: Instant, val finishedAt: Instant?,
    val events: List<DeploymentEventDto> = emptyList(),
    val mock: Boolean = true
)
