package com.systemwebstudio.publish

import com.sun.net.httpserver.HttpServer
import com.systemwebstudio.integration.deploy.DeployVerification.State
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

/** The HTTP probe against a local stub server (there is no real public host to probe): what each answer means, and that "no answer" is never success. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReleaseHealthProbeTests {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        for (code in listOf(200, 204, 301, 401, 403, 404, 503)) createContext("/s$code") { ex ->
            if (code in 300..399) ex.responseHeaders.add("Location", "http://127.0.0.1:1/never-followed")
            ex.sendResponseHeaders(code, -1); ex.close()
        }
        createContext("/slow") { ex -> Thread.sleep(3_000); ex.sendResponseHeaders(200, -1); ex.close() }
        start()
    }
    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
    private val probe = HttpReleaseHealthProbe(enabled = true, timeoutSeconds = 1)

    @AfterAll fun stop() { server.stop(0) }

    @Test
    fun `an address that answers 2xx or 3xx is healthy, and a redirect is not followed anywhere`() {
        assertThat(probe.probe(url("/s200"), false).state).isEqualTo(State.HEALTHY)
        assertThat(probe.probe(url("/s204"), false).state).isEqualTo(State.HEALTHY)
        assertThat(probe.probe(url("/s301"), false).detail).contains("301")
        assertThat(probe.probe(url("/s301"), false).state).isEqualTo(State.HEALTHY)
    }

    @Test
    fun `an address that answers but is not serving the release is unhealthy`() {
        assertThat(probe.probe(url("/s404"), false)).extracting { it.state }.isEqualTo(State.UNHEALTHY)
        assertThat(probe.probe(url("/s503"), false)).extracting { it.state }.isEqualTo(State.UNHEALTHY)
        assertThat(probe.probe(url("/s401"), false).state).isEqualTo(State.UNHEALTHY)      // a public site must not ask a visitor to sign in
        assertThat(probe.probe(url("/s403"), false).state).isEqualTo(State.UNHEALTHY)
    }

    @Test
    fun `a private site answering an anonymous visitor with 401 or 403 is being served`() {
        assertThat(probe.probe(url("/s401"), true).state).isEqualTo(State.HEALTHY)
        assertThat(probe.probe(url("/s403"), true).state).isEqualTo(State.HEALTHY)
        assertThat(probe.probe(url("/s503"), true).state).isEqualTo(State.UNHEALTHY)
    }

    @Test
    fun `no answer is UNKNOWN, never success - refused connection and timeout`() {
        val closed = ServerSocket(0).use { it.localPort }
        assertThat(probe.probe("http://127.0.0.1:$closed/", false).state).isEqualTo(State.UNKNOWN)
        val slow = probe.probe(url("/slow"), false)
        assertThat(slow.state).isEqualTo(State.UNKNOWN); assertThat(slow.detail).contains("did not answer")
    }

    @Test
    fun `a probe that is not configured says so and never claims the address was checked`() {
        val off = HttpReleaseHealthProbe(enabled = false, timeoutSeconds = 1)
        assertThat(off.configured).isFalse()
        val r = off.probe(url("/s200"), false)
        assertThat(r.state).isEqualTo(State.UNKNOWN); assertThat(r.detail).contains("not configured")
    }
}
