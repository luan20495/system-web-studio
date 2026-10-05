package com.systemwebstudio.publish

import java.time.Instant
import java.util.UUID

object DeploymentStatus {
    const val QUEUED = "QUEUED"
    const val POLICY_CHECK = "POLICY_CHECK"
    const val SECURITY_CHECK = "SECURITY_CHECK"
    const val BUILDING = "BUILDING"
    const val DEPLOYING = "DEPLOYING"
    const val RUNNING = "RUNNING"
    const val FAILED = "FAILED"
    const val ROLLED_BACK = "ROLLED_BACK"

    /** Order of the happy path. A deployment only ever moves forward along it or to FAILED. */
    val pipeline = listOf(QUEUED, POLICY_CHECK, SECURITY_CHECK, BUILDING, DEPLOYING, RUNNING)
    val terminal = setOf(RUNNING, FAILED, ROLLED_BACK)

    fun allowed(from: String, to: String): Boolean = when {
        from in terminal -> to == ROLLED_BACK && from == RUNNING
        to == FAILED -> true
        else -> pipeline.indexOf(to) == pipeline.indexOf(from) + 1
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
