package com.systemwebstudio.data.datasource.rest

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory

/**
 * A throw-away HTTPS server on 127.0.0.1 with a self-signed certificate for `api.example.test` (generated with the JDK's own `keytool`; no
 * key material is stored in the repository). [handler] receives the raw request head and writes whatever bytes it likes, so tests can
 * produce malformed, oversized, slow or redirecting responses.
 */
class TlsStub(private val handler: (head: String, out: java.io.OutputStream) -> Unit) : AutoCloseable {
    val connections = AtomicInteger()
    val heads = CopyOnWriteArrayList<String>()
    private val dir: File = Files.createTempDirectory("tls-stub").toFile()
    private val store = File(dir, "stub.p12")
    private val server: SSLServerSocket
    val port: Int get() = server.localPort
    /** trust only this stub's certificate */
    val clientContext: SSLContext

    init {
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "stub", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=api.example.test",
            "-ext", "san=dns:api.example.test", "-validity", "2", "-storetype", "PKCS12", "-keystore", store.path, "-storepass", "changeit", "-keypass", "changeit")
            .redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); check(p.waitFor() == 0) { "keytool failed" }
        val ks = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val serverCtx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        clientContext = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        server = serverCtx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        Thread({
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                Thread({
                    try {
                        s.use { sock ->
                            sock.soTimeout = 10_000
                            val head = readHead(sock.getInputStream())
                            connections.incrementAndGet(); heads += head
                            handler(head, sock.getOutputStream())
                            sock.getOutputStream().flush()
                        }
                    } catch (_: Exception) { /* a client that hangs up or a handshake that fails is part of the tests */ }
                }, "tls-stub-conn").apply { isDaemon = true }.start()
            }
        }, "tls-stub-accept").apply { isDaemon = true }.start()
    }

    private fun readHead(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read(); if (b < 0) break
            out.write(b)
            val a = out.toByteArray()
            if (a.size >= 4 && a[a.size - 4] == '\r'.code.toByte() && a[a.size - 3] == '\n'.code.toByte() && a[a.size - 2] == '\r'.code.toByte() && a[a.size - 1] == '\n'.code.toByte()) break
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    override fun close() { runCatching { server.close() }; dir.deleteRecursively() }

    companion object {
        const val HOST = "api.example.test"
        fun json(body: String, status: String = "200 OK", extra: String = ""): (String, java.io.OutputStream) -> Unit = { _, out ->
            val b = body.toByteArray()
            out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\n$extra\r\n".toByteArray()); out.write(b)
        }
    }
}
