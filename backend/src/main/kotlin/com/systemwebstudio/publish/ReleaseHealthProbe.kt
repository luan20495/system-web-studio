package com.systemwebstudio.publish

import com.systemwebstudio.integration.deploy.DeployVerification
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Asks the address a release is published at whether it answers, from the outside, the way a visitor would. This is the one check that needs a
 * real public host, so it is a port: [configured] = false means nobody can probe yet (no public host is set up), and the verification then says
 * exactly that instead of pretending the address was checked.
 */
interface ReleaseHealthProbe {
    val configured: Boolean
    /** [protectedSite]: a private site legitimately answers an anonymous visitor with 401/403 / a redirect to sign-in; that still means it is being served */
    fun probe(url: String, protectedSite: Boolean): DeployVerification
}

/**
 * HTTP probe of the published address: 2xx/3xx = healthy; 404, 5xx and any other answer = UNHEALTHY (the address is reachable but is not serving
 * this release); no answer at all (refused, DNS, timeout) = UNKNOWN, never success. Redirects are not followed, so a probe cannot be sent
 * anywhere but the address it was asked about. Off unless `app.deploy.health-probe.enabled=true`: it needs the real public host, which this
 * code does not invent.
 */
@Component
class HttpReleaseHealthProbe(
    @Value("\${app.deploy.health-probe.enabled:false}") enabled: Boolean,
    @Value("\${app.deploy.health-probe.timeout-seconds:5}") timeoutSeconds: Long
) : ReleaseHealthProbe {
    override val configured = enabled
    private val timeout = Duration.ofSeconds(timeoutSeconds.coerceIn(1, 60))
    private val http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()

    override fun probe(url: String, protectedSite: Boolean): DeployVerification {
        if (!configured) return DeployVerification.unknown("the HTTP probe is not configured (no public host)")
        val status = try {
            http.send(HttpRequest.newBuilder(URI(url)).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()
        } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e
        } catch (e: Exception) { return DeployVerification.unknown("the address did not answer: " + FailureClassifier.safe(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""), 100)) }
        return when {
            status in 200..399 -> DeployVerification.healthy("the address answers HTTP $status")
            protectedSite && (status == 401 || status == 403) -> DeployVerification.healthy("the private site answers HTTP $status to an anonymous visitor")
            else -> DeployVerification.unhealthy("the address answers HTTP $status")
        }
    }
}
