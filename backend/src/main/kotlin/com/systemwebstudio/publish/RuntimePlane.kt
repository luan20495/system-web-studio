package com.systemwebstudio.publish

import com.systemwebstudio.code.CodeProjectService
import com.systemwebstudio.runtime.ServerRuntimeService
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/** The server part of a release did not become healthy (the runner reported a failure, or did not answer in time). The serving release is untouched. */
class RuntimeReleaseFailure(message: String) : RuntimeException(message)

/**
 * The server runtime of an app, seen as the second half of a release. A server app is only released when its static site AND its server
 * runtime are the same release, so the two are moved in a fixed order by [ReleaseDeployer]: the runtime first (slow, can fail, reversible),
 * the site pointer last (one local, instant switch). Underneath, the runtime is asynchronous (it is "desired" until the runner reports it
 * healthy); these calls hide that: they return when the state is final.
 */
interface RuntimePlane {
    /** does this app have a server runtime that must follow its releases */
    fun applies(projectId: UUID): Boolean
    /** the artifact of the server release serving now, null = none */
    fun currentArtifact(projectId: UUID): UUID?
    /**
     * Make [artifactId] the serving server release and return only when it is healthy. Repeating it for the artifact that already serves (or is
     * already being started) creates nothing new. On failure the serving release is unchanged and nothing keeps starting in the background:
     * throws [RuntimeReleaseFailure].
     */
    fun serve(projectId: UUID, artifactId: UUID, deploymentId: UUID)
    /** stop serving the server part (the app had none before this release) */
    fun stop(projectId: UUID)
}

@Component
class ServerRuntimePlane(
    private val runtime: ServerRuntimeService, private val jdbc: JdbcTemplate,
    @Value("\${app.deploy.runtime-timeout-seconds:120}") private val timeoutSeconds: Long,
    @Value("\${app.deploy.runtime-poll-ms:500}") private val pollMs: Long
) : RuntimePlane {
    override fun applies(projectId: UUID) =
        jdbc.query("SELECT app_kind FROM projects WHERE id = ?", { rs, _ -> rs.getString(1) }, projectId).firstOrNull() in CodeProjectService.SERVER_KINDS

    override fun currentArtifact(projectId: UUID) = runtime.currentArtifact(projectId)

    override fun serve(projectId: UUID, artifactId: UUID, deploymentId: UUID) {
        val desired = runtime.desiredRelease(projectId)
        val id = when {
            desired != null && desired.second == artifactId -> desired.first                        // a worker died after asking for it: wait for that one
            runtime.currentArtifact(projectId) == artifactId -> return                              // already serving exactly this
            else -> {
                val commit = jdbc.query("SELECT commit_sha FROM deployments WHERE id = ?", { rs, _ -> rs.getString(1) }, deploymentId).firstOrNull()
                val rollbackOf = runtime.lastDeploymentOf(projectId, artifactId)
                runtime.deploy(projectId, artifactId, commit, null, rollbackOf)
            }
        }
        await(id)
    }

    private fun await(id: UUID) {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (true) {
            val (status, error) = runtime.deploymentState(id) ?: throw RuntimeReleaseFailure("the server release disappeared")
            when (status) {
                "RUNNING" -> return
                "FAILED" -> throw RuntimeReleaseFailure("the server part did not start: " + FailureClassifier.safe(error ?: "failed", 200))
                "STOPPED", "SUPERSEDED" -> throw RuntimeReleaseFailure("the server release was replaced before it became healthy")
            }
            if (System.nanoTime() >= deadline) {
                runtime.abandon(id, "did not become healthy within ${timeoutSeconds}s")
                throw RuntimeReleaseFailure("the server part did not become healthy within ${timeoutSeconds}s")
            }
            try { Thread.sleep(pollMs.coerceAtLeast(10)) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); runtime.abandon(id, "interrupted"); throw e }
        }
    }

    override fun stop(projectId: UUID) = runtime.stop(projectId, null)
}
