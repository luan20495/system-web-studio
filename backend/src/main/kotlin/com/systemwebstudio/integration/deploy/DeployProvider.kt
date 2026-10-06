package com.systemwebstudio.integration.deploy

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The authority to move the active pointer, issued to the one operation that holds the release scope. The pointer is only ever changed through
 * [commit], a compare-and-set that also proves the writer still holds its lease with its own fencing token: a writer that lost the scope (stalled,
 * lease expired, someone took over) cannot move the pointer, whatever it was doing.
 */
interface PointerFence {
    /** identity of the operation this fence belongs to (publish = the deployment id) */
    val operationId: UUID
    /** true once a commit was refused or the lease was found lost: the owner must stop writing */
    val fencedOut: Boolean
    /**
     * Point the site at [deploymentId] (null = serve nothing). true = committed; false = refused and NOTHING was written (the lease is lost or the
     * pointer moved since it was read). [inSameTransaction] runs only on success and in the same transaction as the pointer change.
     */
    fun commit(deploymentId: UUID?, inSameTransaction: (() -> Unit)? = null): Boolean
    /** a one-shot step that [commit] runs, in the same transaction, together with the next successful pointer change (for example the status of the release left behind) */
    fun withNextCommit(step: () -> Unit)
}

data class DeployRequest(
    val deploymentId: UUID, val projectName: String, val versionNumber: Int, val visibility: String,
    val deploymentTarget: String?, val artifactHash: String,
    val projectId: UUID? = null, val artifactId: UUID? = null,
    /** set by the release scope guard: the only way a provider may move the active pointer. null = unguarded (tests, the mock) */
    val fence: PointerFence? = null
)
data class DeployResult(val url: String?, val error: String?)

/**
 * What a provider can say about a deployment it was asked to switch to. UNKNOWN is an explicit third state: the caller must NOT treat it
 * as success (a timeout, an unreachable control plane, a provider with no way to check).
 */
data class DeployVerification(val state: State, val detail: String? = null) {
    enum class State { HEALTHY, UNHEALTHY, UNKNOWN }
    companion object {
        fun healthy(detail: String? = null) = DeployVerification(State.HEALTHY, detail)
        fun unhealthy(detail: String) = DeployVerification(State.UNHEALTHY, detail)
        fun unknown(detail: String) = DeployVerification(State.UNKNOWN, detail)
    }
}

/**
 * Port for whatever actually serves the site (self-host, AWS, Azure, GCP).
 *
 * Order of a release (the caller enforces it): artifact verified -> [stage] -> (server runtime healthy) -> [deploy] = the switch that makes it
 * serve -> [verify] as the confirmation after the switch. A release is never switched in before it was verified.
 *
 * Contract (production providers MUST honour it):
 *  - [stage] prepares the release WITHOUT serving it (provision, upload, warm up). Default: nothing to prepare. Anything it leaves behind on
 *    failure must be harmless, because the previous release keeps serving.
 *  - [deploy] is idempotent: calling it again for the same deploymentId converges on the same state. A non-null `error` means the new
 *    release is not (or not fully) active; the caller still asks for [restore], which only acts if the provider points at this deployment.
 *  - [verify] is the post-deploy health / readiness check. A deployment becomes RUNNING only after HEALTHY.
 *  - [restore] makes an EARLIER release the active one again WITHOUT building anything (its artifact is immutable); `null` = serve nothing.
 */
interface DeployProvider {
    val name: String
    /** true: BUILDING produces a real artifact (StaticSiteBuilder) that DEPLOYING serves; false: the mock hash only */
    val buildsArtifacts: Boolean get() = false
    fun stage(request: DeployRequest): DeployResult = DeployResult(null, null)
    /** The switch: after this the release is what the address serves. Only called for a release that was verified and staged. */
    fun deploy(request: DeployRequest): DeployResult
    /** Default is UNKNOWN on purpose: a provider that cannot verify must say so, never imply success. */
    fun verify(request: DeployRequest): DeployVerification = DeployVerification.unknown("Provider ${name} does not implement post-deploy verification")
    fun restore(projectId: UUID, previousDeploymentId: UUID?): DeployResult = DeployResult(null, "Provider ${name} does not support rollback")
    /**
     * [restore] under a fence. A provider that moves the platform's active pointer MUST do it through [PointerFence.commit]. The default delegates to the
     * unfenced [restore] so existing providers keep working; one that can fence must override it.
     */
    fun restore(projectId: UUID, previousDeploymentId: UUID?, fence: PointerFence?): DeployResult = restore(projectId, previousDeploymentId)
}

/**
 * Controlled stand-in: no site is really served. It returns an obviously local/mock URL and fails on demand
 * (deploymentTarget = "fail") so the failure path can be exercised.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = ["app.deploy.provider"], havingValue = "mock", matchIfMissing = true)
class MockDeployProvider(@Value("\${app.deploy.mock.base-url}") private val baseUrl: String) : DeployProvider {
    override val name = "mock"
    override fun deploy(request: DeployRequest): DeployResult {
        if (request.deploymentTarget == "fail") return DeployResult(null, "Mock provider simulated a deployment failure")
        val slug = request.projectName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "site" }
        return DeployResult("$baseUrl/$slug-${request.deploymentId.toString().take(8)}", null)
    }
    /** nothing is served, so there is nothing to check: stated as such (the deployment is labelled mock everywhere it is shown) */
    override fun verify(request: DeployRequest) = DeployVerification.healthy("mock provider: nothing is served")
    override fun restore(projectId: UUID, previousDeploymentId: UUID?) = DeployResult(null, null)
}
