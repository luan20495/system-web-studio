package com.systemwebstudio.integration.deploy

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

data class DeployRequest(
    val deploymentId: UUID, val projectName: String, val versionNumber: Int, val visibility: String,
    val deploymentTarget: String?, val artifactHash: String
)
data class DeployResult(val url: String?, val error: String?)

/** Port for whatever actually serves the site (self-host, AWS, Azure, GCP). */
interface DeployProvider {
    val name: String
    fun deploy(request: DeployRequest): DeployResult
}

/**
 * Controlled stand-in: no site is really served. It returns an obviously local/mock URL and fails on demand
 * (deploymentTarget = "fail") so the failure path can be exercised.
 */
@Component
class MockDeployProvider(@Value("\${app.deploy.mock.base-url}") private val baseUrl: String) : DeployProvider {
    override val name = "mock"
    override fun deploy(request: DeployRequest): DeployResult {
        if (request.deploymentTarget == "fail") return DeployResult(null, "Mock provider simulated a deployment failure")
        val slug = request.projectName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "site" }
        return DeployResult("$baseUrl/$slug-${request.deploymentId.toString().take(8)}", null)
    }
}
