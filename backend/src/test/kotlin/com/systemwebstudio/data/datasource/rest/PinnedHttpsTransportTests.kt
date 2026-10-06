package com.systemwebstudio.data.datasource.rest

import com.systemwebstudio.data.AllowAllAddresses
import com.systemwebstudio.data.FixedResolver
import com.systemwebstudio.data.LogCapture
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * The outbound transport against a real TLS server on loopback. Functional tests relax only the *address policy* (the stub lives on 127.0.0.1);
 * every SSRF test below runs with the platform's real policy ([com.systemwebstudio.data.datasource.PublicAddressPolicy], the default).
 */
class PinnedHttpsTransportTests {
    private val secret = "Bearer sk-live-TOPSECRET-9f8e7d"

    private fun request(host: String, port: Int, path: String = "/x", headers: Map<String, String> = emptyMap(), total: Long = 3_000, max: Int = 10_000) =
        HttpGetRequest(URI("https://$host:$port$path"), headers, connectTimeoutMillis = 1_000, totalTimeoutMillis = total, maxResponseBytes = max)

    private fun failure(block: () -> Unit): ConnectorFailure {
        try { block() } catch (e: ConnectorFailure) { return e }
        throw AssertionError("expected a ConnectorFailure")
    }

    private fun loopback(stub: TlsStub, host: String = TlsStub.HOST) = FixedResolver(mapOf(host to listOf("127.0.0.1")))
    private fun transport(stub: TlsStub, resolver: FixedResolver = loopback(stub)) = PinnedHttpsTransport(resolver, AllowAllAddresses, stub.clientContext)

    // ---------------------------------------------------------------- functional

    @Test fun `GET over verified TLS sends the credential header and returns the body`() {
        TlsStub(TlsStub.json("""{"ok":true}""")).use { stub ->
            val r = transport(stub).get(request(TlsStub.HOST, stub.port, "/items?x=1", mapOf("Authorization" to secret)))
            assertThat(r.status).isEqualTo(200)
            assertThat(String(r.body)).isEqualTo("""{"ok":true}""")
            val head = stub.heads.single()
            assertThat(head).startsWith("GET /items?x=1 HTTP/1.1")
            assertThat(head).contains("Host: ${TlsStub.HOST}:${stub.port}")
            assertThat(head).contains("Authorization: $secret")
            assertThat(head).contains("Connection: close")
            assertThat(head).contains("Accept-Encoding: identity")
        }
    }

    @Test fun `the connection goes to the address that was checked - no second lookup`() {
        TlsStub(TlsStub.json("{}")).use { stub ->
            val resolver = loopback(stub)
            transport(stub, resolver).get(request(TlsStub.HOST, stub.port))
            assertThat(resolver.lookups.get()).isEqualTo(1)
        }
    }

    @Test fun `chunked and until-close bodies are read`() {
        TlsStub { _, out -> out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n[1,2\r\n2;ext=1\r\n,3\r\n1\r\n]\r\n0\r\n\r\n".toByteArray()) }.use { stub ->
            assertThat(String(transport(stub).get(request(TlsStub.HOST, stub.port)).body)).isEqualTo("[1,2,3]")
        }
        TlsStub { _, out -> out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n[9]".toByteArray()) }.use { stub ->
            assertThat(String(transport(stub).get(request(TlsStub.HOST, stub.port)).body)).isEqualTo("[9]")
        }
    }

    // ---------------------------------------------------------------- SSRF (platform policy, not relaxed)

    @Test fun `loopback private link-local and metadata targets are refused before any socket is opened`() {
        TlsStub(TlsStub.json("{}")).use { stub ->
            // the stub really listens on 127.0.0.1:port — if the guard let it through, connections would be 1
            for (ip in listOf("127.0.0.1", "10.0.0.5", "172.16.3.4", "192.168.0.9", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1")) {
                val t = PinnedHttpsTransport(FixedResolver(mapOf(TlsStub.HOST to listOf(ip))), sslContext = stub.clientContext)   // default policy = PublicAddress
                val f = failure { t.get(request(TlsStub.HOST, stub.port)) }
                assertThat(f.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
                assertThat(f.message).doesNotContain(ip)
                assertThat(f.message).doesNotContain(TlsStub.HOST)
            }
            assertThat(stub.connections.get()).isEqualTo(0)
        }
    }

    @Test fun `a name that resolves to a public and an internal address is refused as a whole (DNS rebinding set)`() {
        TlsStub(TlsStub.json("{}")).use { stub ->
            val t = PinnedHttpsTransport(FixedResolver(mapOf(TlsStub.HOST to listOf("93.184.216.34", "127.0.0.1"))), sslContext = stub.clientContext)
            assertThat(failure { t.get(request(TlsStub.HOST, stub.port)) }.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
            assertThat(stub.connections.get()).isEqualTo(0)
        }
    }

    @Test fun `a redirect is returned and never followed, and the credential goes nowhere else`() {
        TlsStub { _, out -> out.write("HTTP/1.1 302 Found\r\nLocation: https://169.254.169.254/latest/meta-data/\r\nContent-Length: 0\r\n\r\n".toByteArray()) }.use { stub ->
            val r = transport(stub).get(request(TlsStub.HOST, stub.port, "/r", mapOf("Authorization" to secret)))
            assertThat(r.status).isEqualTo(302)
            assertThat(r.body.size).isEqualTo(0)
            assertThat(stub.connections.get()).isEqualTo(1)                                // exactly one request: nothing was followed
            for (code in listOf(301, 303, 307, 308)) {
                TlsStub { _, out -> out.write("HTTP/1.1 $code Moved\r\nLocation: https://127.0.0.1:1/\r\nContent-Length: 5\r\n\r\nhello".toByteArray()) }.use { s2 ->
                    val rr = transport(s2).get(request(TlsStub.HOST, s2.port))
                    assertThat(rr.status).isEqualTo(code); assertThat(rr.body.size).isEqualTo(0); assertThat(s2.connections.get()).isEqualTo(1)
                }
            }
        }
    }

    @Test fun `only https URLs without credentials are accepted`() {
        val t = PinnedHttpsTransport(FixedResolver(emptyMap()), AllowAllAddresses)
        for (u in listOf("http://api.example.test/x", "https://user:pw@api.example.test/x", "https://api.example.test/x#frag", "ftp://api.example.test/")) {
            val f = failure { t.get(HttpGetRequest(URI(u), emptyMap(), 500, 1_000, 1_000)) }
            assertThat(f.code).isEqualTo(FailureCodes.INVALID_CONFIG)
            assertThat(f.message).doesNotContain("pw")
        }
    }

    // ---------------------------------------------------------------- TLS

    @Test fun `the certificate must match the host name`() {
        TlsStub(TlsStub.json("{}")).use { stub ->
            val t = transport(stub, FixedResolver(mapOf("other.example.test" to listOf("127.0.0.1"))))
            assertThat(failure { t.get(request("other.example.test", stub.port)) }.code).isEqualTo(FailureCodes.TLS_FAILED)
        }
    }

    @Test fun `an untrusted certificate is refused`() {
        TlsStub(TlsStub.json("{}")).use { stub ->
            val t = PinnedHttpsTransport(loopback(stub), AllowAllAddresses)               // JVM default trust store: the self-signed stub is not in it
            assertThat(failure { t.get(request(TlsStub.HOST, stub.port)) }.code).isEqualTo(FailureCodes.TLS_FAILED)
        }
    }

    // ---------------------------------------------------------------- limits

    @Test fun `response size is capped for content-length, chunked and until-close bodies`() {
        TlsStub { _, out -> out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 5000\r\n\r\n".toByteArray()); out.write(ByteArray(5000) { 'a'.code.toByte() }) }.use { stub ->
            assertThat(failure { transport(stub).get(request(TlsStub.HOST, stub.port, max = 1_000)) }.code).isEqualTo(FailureCodes.RESPONSE_TOO_LARGE)
        }
        TlsStub { _, out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
            repeat(10) { out.write("200\r\n".toByteArray()); out.write(ByteArray(0x200) { 'a'.code.toByte() }); out.write("\r\n".toByteArray()) }
            out.write("0\r\n\r\n".toByteArray())
        }.use { stub ->
            assertThat(failure { transport(stub).get(request(TlsStub.HOST, stub.port, max = 1_000)) }.code).isEqualTo(FailureCodes.RESPONSE_TOO_LARGE)
        }
        TlsStub { _, out -> out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n".toByteArray()); out.write(ByteArray(50_000) { 'a'.code.toByte() }) }.use { stub ->
            assertThat(failure { transport(stub).get(request(TlsStub.HOST, stub.port, max = 1_000)) }.code).isEqualTo(FailureCodes.RESPONSE_TOO_LARGE)
        }
    }

    @Test fun `a server that never answers hits the deadline`() {
        TlsStub { _, _ -> Thread.sleep(5_000) }.use { stub ->
            val started = System.nanoTime()
            val f = failure { transport(stub).get(request(TlsStub.HOST, stub.port, total = 600)) }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertThat(f.code).isEqualTo(FailureCodes.TIMEOUT)
            assertThat(elapsedMs).isLessThan(3_000)
        }
    }

    @Test fun `a response dripped slowly cannot outlive the total deadline`() {
        TlsStub { _, out ->
            val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100000\r\n\r\n"
            head.forEach { out.write(it.code); out.flush(); Thread.sleep(40) }          // each read succeeds quickly; the total does not
            while (true) { out.write('a'.code); out.flush(); Thread.sleep(40) }
        }.use { stub ->
            val started = System.nanoTime()
            val f = failure { transport(stub).get(request(TlsStub.HOST, stub.port, total = 800, max = 1_000_000)) }
            assertThat(f.code).isEqualTo(FailureCodes.TIMEOUT)
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(3_000)
        }
    }

    @Test fun `nothing listening is a connect failure with a fixed message`() {
        val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val f = failure { PinnedHttpsTransport(FixedResolver(mapOf(TlsStub.HOST to listOf("127.0.0.1"))), AllowAllAddresses).get(request(TlsStub.HOST, closed)) }
        assertThat(f.code).isEqualTo(FailureCodes.CONNECT_FAILED)
        assertThat(f.message).doesNotContain("127.0.0.1")
    }

    // ---------------------------------------------------------------- malformed responses and request injection

    @Test fun `ambiguous or malformed responses are rejected`() {
        val bad = listOf(
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",            // request-smuggling shape
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\nabc",                              // conflicting lengths
            "HTTP/1.1 200 OK\r\nContent-Length: abc\r\n\r\n",
            "HTTP/1.1 200 OK\nContent-Length: 2\r\n\r\nab",                                                       // bare LF in the head
            "HTTP/1.1 200 OK\r\nX-A: b\r\n folded\r\nContent-Length: 0\r\n\r\n",                                 // obsolete folding
            "HTTP/1.1 100 Continue\r\n\r\n",
            "ICY 200 OK\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 0\r\n\r\n"
        )
        for (raw in bad) TlsStub { _, out -> out.write(raw.toByteArray()) }.use { stub ->
            assertThat(failure { transport(stub).get(request(TlsStub.HOST, stub.port)) }.code).isEqualTo(FailureCodes.RESPONSE_INVALID)
        }
    }

    @Test fun `a truncated body is an invalid response, not a short success`() {
        TlsStub { _, out -> out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 50\r\n\r\n[1,2".toByteArray()) }.use { stub ->
            assertThat(failure { transport(stub).get(request(TlsStub.HOST, stub.port)) }.code).isEqualTo(FailureCodes.RESPONSE_INVALID)
        }
    }

    @Test fun `header injection and forbidden headers are refused before anything is sent`() {
        TlsStub(TlsStub.json("{}")).use { stub ->
            val t = transport(stub)
            assertThat(failure { t.get(request(TlsStub.HOST, stub.port, headers = mapOf("X-Key" to "a\r\nX-Evil: 1"))) }.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
            assertThat(failure { t.get(request(TlsStub.HOST, stub.port, headers = mapOf("X-Key" to "a\nb"))) }.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
            for (name in listOf("Host", "Content-Length", "Transfer-Encoding", "Connection", "X Bad", "X:Bad", "Proxy-Authorization"))
                assertThat(failure { t.get(request(TlsStub.HOST, stub.port, headers = mapOf(name to "v"))) }.code).isEqualTo(FailureCodes.INVALID_CONFIG)
            assertThat(stub.connections.get()).isEqualTo(0)
        }
    }

    @Test fun `failures never reach the log with a host, an address or a credential`() {
        LogCapture().use { logs ->
            TlsStub { _, _ -> Thread.sleep(2_000) }.use { stub ->
                runCatching { transport(stub).get(request(TlsStub.HOST, stub.port, headers = mapOf("Authorization" to secret), total = 300)) }
                runCatching { PinnedHttpsTransport(FixedResolver(mapOf(TlsStub.HOST to listOf("10.9.9.9")))).get(request(TlsStub.HOST, stub.port, headers = mapOf("Authorization" to secret))) }
                runCatching { PinnedHttpsTransport(loopback(stub), AllowAllAddresses).get(request(TlsStub.HOST, stub.port, headers = mapOf("Authorization" to secret), total = 300)) }   // TLS failure
            }
            assertThat(logs.text).doesNotContain("TOPSECRET")
            assertThat(logs.text).doesNotContain("10.9.9.9")
        }
    }
}
