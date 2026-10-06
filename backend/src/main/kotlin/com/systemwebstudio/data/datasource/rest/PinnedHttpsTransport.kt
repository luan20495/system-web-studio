package com.systemwebstudio.data.datasource.rest

import com.systemwebstudio.data.datasource.AddressPolicy
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.HostResolver
import com.systemwebstudio.data.datasource.PinnedResolution
import com.systemwebstudio.data.datasource.PublicAddressPolicy
import com.systemwebstudio.data.datasource.SystemHostResolver
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

class HttpGetRequest(
    val url: URI,
    /** request headers; may carry a credential, which is why this class has no readable `toString` */
    val headers: Map<String, String>,
    val connectTimeoutMillis: Int,
    /** deadline for the whole exchange: connect + TLS + request + response */
    val totalTimeoutMillis: Long,
    val maxResponseBytes: Int
) {
    override fun toString() = "HttpGetRequest(***)"
}

/** Redirect bodies are never read; [location] is exposed only so callers can tell "moved" from "broken", never followed. */
class HttpGetResponse(val status: Int, val contentType: String?, val body: ByteArray, val location: String? = null)

/** Seam for the connector tests; production is [PinnedHttpsTransport]. Implementations throw only [ConnectorFailure]. */
interface RestTransport {
    fun get(request: HttpGetRequest): HttpGetResponse
}

/**
 * Outbound HTTPS GET for data sources, written around three guarantees:
 *
 * 1. **SSRF**: the host is resolved once, **every** address is checked by [AddressPolicy] (default: the platform's [PublicAddressPolicy] →
 *    `PublicAddress`), and the socket is opened to one of *those* addresses. There is no second lookup, so DNS rebinding between check and
 *    use cannot redirect the connection. TLS still validates the certificate against the original host name (and sends it as SNI).
 * 2. **No redirects**: a 3xx is returned as-is and never followed, so a redirect cannot move the request (or its credential header) to
 *    another host or into the private network. HTTPS only; no proxy.
 * 3. **Bounded**: connect timeout, a total deadline enforced on every read (so a slow-drip response cannot outlive it), header size cap and
 *    body size cap checked while reading.
 *
 * It speaks a deliberately small HTTP/1.1 subset (GET, `Connection: close`, identity encoding, Content-Length / chunked / until-close) and
 * rejects anything ambiguous (both Content-Length and Transfer-Encoding, conflicting lengths, folded headers, 1xx).
 */
class PinnedHttpsTransport(
    private val resolver: HostResolver = SystemHostResolver,
    private val addressPolicy: AddressPolicy = PublicAddressPolicy,
    private val sslContext: SSLContext = SSLContext.getDefault(),
    private val nanoTime: () -> Long = System::nanoTime
) : RestTransport {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun get(request: HttpGetRequest): HttpGetResponse {
        val url = request.url
        val host = url.host?.lowercase()
        if (url.scheme != "https" || host.isNullOrEmpty() || url.userInfo != null || url.rawFragment != null)
            throw ConnectorFailure(FailureCodes.INVALID_CONFIG, "only plain https URLs are allowed")
        val port = if (url.port == -1) 443 else url.port
        if (port !in 1..65535) throw ConnectorFailure(FailureCodes.INVALID_CONFIG, "invalid port")
        val target = (url.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (url.rawQuery?.let { "?$it" } ?: "")
        if (target.any { it.code <= 0x20 || it.code == 0x7f } || !target.startsWith("/")) throw ConnectorFailure(FailureCodes.INVALID_CONFIG, "invalid request target")
        val headerBlock = headerBlock(host, port, target, request.headers)

        val deadline = nanoTime() + request.totalTimeoutMillis * 1_000_000
        val addresses = PinnedResolution.resolve(host, resolver, addressPolicy)          // before any socket exists
        var socket: Socket? = null
        try {
            socket = connect(addresses, port, request.connectTimeoutMillis, deadline)
            val tls = tlsHandshake(socket, host, port, deadline)
            socket = tls
            tls.outputStream.apply { write(headerBlock.toByteArray(Charsets.US_ASCII)); flush() }
            return readResponse(tls, tls.inputStream, request.maxResponseBytes, deadline)
        } catch (e: ConnectorFailure) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ConnectorFailure(FailureCodes.TIMEOUT, "the data source did not answer in time")
        } catch (e: SSLException) {
            log.debug("tls failure: {}", e.javaClass.simpleName)
            throw ConnectorFailure(FailureCodes.TLS_FAILED, "secure connection could not be established")
        } catch (e: IOException) {
            log.debug("io failure: {}", e.javaClass.simpleName)
            throw ConnectorFailure(FailureCodes.CONNECT_FAILED, "the data source could not be reached")
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun remainingMillis(deadline: Long): Int {
        val left = (deadline - nanoTime()) / 1_000_000
        if (left <= 0) throw ConnectorFailure(FailureCodes.TIMEOUT, "the data source did not answer in time")
        return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun connect(addresses: List<java.net.InetAddress>, port: Int, connectTimeout: Int, deadline: Long): Socket {
        var failure: IOException? = null
        for (address in addresses.take(MAX_ADDRESS_ATTEMPTS)) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(address, port), minOf(connectTimeout, remainingMillis(deadline)))
                return s
            } catch (e: ConnectorFailure) { runCatching { s.close() }; throw e }
            catch (e: IOException) { runCatching { s.close() }; failure = e }
        }
        throw if (failure is SocketTimeoutException) ConnectorFailure(FailureCodes.TIMEOUT, "the data source did not answer in time")
        else ConnectorFailure(FailureCodes.CONNECT_FAILED, "the data source could not be reached")
    }

    private fun tlsHandshake(plain: Socket, host: String, port: Int, deadline: Long): SSLSocket {
        val tls = sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        val p = tls.sslParameters
        p.protocols = tls.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        p.endpointIdentificationAlgorithm = "HTTPS"                       // verify the certificate against `host`
        p.serverNames = listOf(SNIHostName(host))
        tls.sslParameters = p
        tls.soTimeout = remainingMillis(deadline)
        tls.startHandshake()
        return tls
    }

    private fun headerBlock(host: String, port: Int, target: String, headers: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("GET ").append(target).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(host).append(if (port == 443) "" else ":$port").append("\r\n")
        sb.append("User-Agent: xweb-data-connector/1\r\n")
        sb.append("Accept: application/json\r\n")
        sb.append("Accept-Encoding: identity\r\n")
        sb.append("Connection: close\r\n")
        for ((name, value) in headers) {
            if (!HEADER_NAME.matches(name) || name.lowercase() in FORBIDDEN_HEADERS) throw ConnectorFailure(FailureCodes.INVALID_CONFIG, "invalid request header")
            if (value.any { it == '\r' || it == '\n' || it == '\u0000' } || value.length > MAX_HEADER_VALUE) throw ConnectorFailure(FailureCodes.INVALID_CREDENTIAL, "invalid header value")
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        return sb.append("\r\n").toString()
    }

    // ---------------------------------------------------------------- response

    private fun readResponse(socket: Socket, input: InputStream, maxBody: Int, deadline: Long): HttpGetResponse {
        val reader = Reader(socket, input, deadline)
        val head = reader.readHead()
        val lines = head.split("\r\n")
        val m = STATUS_LINE.matchEntire(lines[0]) ?: throw invalid()
        val status = m.groupValues[2].toInt()
        if (status < 200) throw invalid()                                  // 1xx never requested (no Expect/Upgrade)
        val headers = HashMap<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            if (line[0] == ' ' || line[0] == '\t') throw invalid()         // obsolete line folding
            val i = line.indexOf(':')
            if (i <= 0) throw invalid()
            headers.getOrPut(line.substring(0, i).lowercase()) { mutableListOf() }.add(line.substring(i + 1).trim())
        }
        val contentType = headers["content-type"]?.firstOrNull()?.take(200)
        val location = headers["location"]?.firstOrNull()?.take(200)
        if (status in 300..399 || status == 204 || status == 304) return HttpGetResponse(status, contentType, ByteArray(0), location)

        val te = headers["transfer-encoding"]
        val cl = headers["content-length"]
        if (te != null && cl != null) throw invalid()
        val body = when {
            te != null -> { if (te.size != 1 || !te[0].equals("chunked", ignoreCase = true)) throw invalid(); reader.readChunked(maxBody) }
            cl != null -> {
                val lengths = cl.flatMap { it.split(',') }.map { it.trim() }.distinct()
                if (lengths.size != 1 || !DIGITS.matches(lengths[0]) || lengths[0].length > 10) throw invalid()
                val n = lengths[0].toLong()
                if (n > maxBody) throw tooLarge()
                reader.readExactly(n.toInt())
            }
            else -> reader.readToEof(maxBody)
        }
        val encoding = headers["content-encoding"]?.firstOrNull()
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) throw invalid()
        return HttpGetResponse(status, contentType, body, location)
    }

    private inner class Reader(private val socket: Socket, private val input: InputStream, private val deadline: Long) {
        private val buf = ByteArray(8192); private var pos = 0; private var lim = 0

        private fun fill(): Boolean {
            socket.soTimeout = remainingMillis(deadline)
            val n = input.read(buf, 0, buf.size)
            if (n < 0) return false
            pos = 0; lim = n; return true
        }
        private fun readByte(): Int { if (pos >= lim && !fill()) return -1; return buf[pos++].toInt() and 0xff }

        fun readHead(): String {
            val out = ByteArrayOutputStream(); var state = 0
            while (true) {
                val b = readByte()
                if (b < 0) throw invalid()
                if (out.size() >= MAX_HEAD) throw invalid()
                out.write(b)
                state = when {
                    b == '\r'.code -> if (state == 0 || state == 2) state + 1 else 1
                    b == '\n'.code && (state == 1 || state == 3) -> state + 1
                    else -> 0
                }
                if (state == 4) break
            }
            val s = String(out.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
            if (BARE_LF.containsMatchIn(s) || s.contains('\u0000')) throw invalid()     // header lines end in CRLF only
            return s
        }

        fun readExactly(n: Int): ByteArray {
            val out = ByteArray(n); var got = 0
            while (got < n) {
                if (pos >= lim && !fill()) throw invalid()
                val c = minOf(n - got, lim - pos); System.arraycopy(buf, pos, out, got, c); pos += c; got += c
            }
            return out
        }

        fun readToEof(max: Int): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                if (pos >= lim && !fill()) return out.toByteArray()
                if (out.size() + (lim - pos) > max) throw tooLarge()
                out.write(buf, pos, lim - pos); pos = lim
            }
        }

        private fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val b = readByte()
                if (b < 0 || sb.length > MAX_LINE) throw invalid()
                if (b == '\n'.code) return sb.toString().removeSuffix("\r")
                sb.append(b.toChar())
            }
        }

        fun readChunked(max: Int): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = readLine().substringBefore(';').trim()
                if (!HEX.matches(size) || size.length > 8) throw invalid()
                val n = size.toLong(16)
                if (n == 0L) {
                    var trailers = 0
                    while (readLine().isNotEmpty()) { if (++trailers > MAX_TRAILERS) throw invalid() }
                    return out.toByteArray()
                }
                if (out.size() + n > max) throw tooLarge()
                out.write(readExactly(n.toInt()))
                if (readLine().isNotEmpty()) throw invalid()
            }
        }
    }

    private fun invalid() = ConnectorFailure(FailureCodes.RESPONSE_INVALID, "the data source sent an invalid response")
    private fun tooLarge() = ConnectorFailure(FailureCodes.RESPONSE_TOO_LARGE, "the response is larger than allowed")

    private companion object {
        const val MAX_HEAD = 16 * 1024
        const val MAX_LINE = 4 * 1024
        const val MAX_TRAILERS = 20
        const val MAX_HEADER_VALUE = 4_000
        const val MAX_ADDRESS_ATTEMPTS = 3
        val HEADER_NAME = Regex("^[A-Za-z][A-Za-z0-9-]{0,63}$")
        val FORBIDDEN_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "upgrade", "te", "trailer", "expect", "proxy-authorization",
            "proxy-connection", "keep-alive", "accept-encoding", "user-agent", "accept")
        val STATUS_LINE = Regex("^(HTTP/1\\.[01]) ([1-5][0-9]{2})(?: .*)?$")
        val DIGITS = Regex("^[0-9]+$")
        val HEX = Regex("^[0-9a-fA-F]+$")
        val BARE_LF = Regex("(?<!\r)\n")
    }
}
