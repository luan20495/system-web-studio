package com.systemwebstudio.data.datasource.postgres

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.Properties
import javax.net.SocketFactory

/**
 * Plugged into the PostgreSQL JDBC driver (`socketFactory` property; the driver instantiates it with the connection `Properties`).
 * Whatever host the driver asks for, the socket is connected to the address list that [PostgresTargetPolicy] already checked and passed in
 * `xweb.pinnedAddress` — so the driver's own DNS lookup can never produce a different (rebound, internal) address. TLS is layered on top
 * by the driver against the original host name, as usual. If the property is missing the factory refuses to exist: **fail closed**.
 *
 * Must stay a public class with a public `Properties` constructor: the driver loads it reflectively.
 */
class PinnedSocketFactory(props: Properties) : SocketFactory() {
    private val pinned: List<InetAddress> = (props.getProperty(PROP) ?: throw IllegalArgumentException("pinned address missing"))
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .map { literal -> require(isLiteral(literal)) { "not an IP literal" }; InetAddress.getByName(literal) }    // literal only: no DNS happens
        .also { require(it.isNotEmpty()) { "pinned address missing" } }

    /** driver fallback form (`socketFactoryArg`) */
    constructor(arg: String) : this(Properties().also { it.setProperty(PROP, arg) })

    override fun createSocket(): Socket = PinnedSocket(pinned)
    override fun createSocket(host: String?, port: Int): Socket = createSocket().also { it.connect(InetSocketAddress(pinned[0], port)) }
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
    override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket(null as String?, port)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket(null as String?, port)

    /** a [Socket] whose `connect` ignores the requested address and uses the pinned one */
    private class PinnedSocket(private val pinned: List<InetAddress>) : Socket() {
        override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val port = (endpoint as? InetSocketAddress)?.port ?: throw IOException("unsupported address")
            // a failed connect closes a java.net.Socket, so only the first (IPv4-preferred) pinned address is tried
            super.connect(InetSocketAddress(pinned[0], port), timeout)
        }
    }

    companion object {
        const val PROP = "xweb.pinnedAddress"
        /** IPv4 with four octets 0..255, or an IPv6 form (contains ':'); anything else would make `InetAddress.getByName` do a DNS lookup */
        private fun isLiteral(s: String) = (V4.matches(s) && s.split('.').all { it.toInt() in 0..255 }) || V6.matches(s)
        private val V4 = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
        private val V6 = Regex("^[0-9a-fA-F:.]*:[0-9a-fA-F:.]*$")          // must contain ':' — a hex-looking host name such as "dead.beef" is not a literal
    }
}
