package com.systemwebstudio.data.datasource.postgres

import com.systemwebstudio.data.datasource.AddressPolicy
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.HostNamePolicy
import com.systemwebstudio.data.datasource.HostResolver
import com.systemwebstudio.data.datasource.PinnedResolution
import com.systemwebstudio.data.datasource.PublicAddressPolicy
import com.systemwebstudio.data.datasource.SystemHostResolver
import java.net.InetAddress

/**
 * Where a PostgreSQL data source may point. Default: **public internet addresses only**, through the same [PublicAddressPolicy] (→
 * `PublicAddress`) as every other outbound call, so the platform's own databases — the platform DB, the apps DB server, anything on the
 * compose/k8s network — are unreachable simply because they are not public.
 *
 * Both lists take **targets**: `host` (every port of that host) or `host:port` / `[v6]:port` (that endpoint only). The port matters because one host
 * routinely runs several unrelated services (a developer machine: platform DB, apps DB, a data target, all on `127.0.0.1`).
 *
 * - [deniedHosts]: targets that are refused even if they would otherwise pass or be allow-listed — **deny always wins over allow** (the platform DB and
 *   apps DB; build them with [denyingPlatformDatabases] from the platform's own JDBC URLs). A host-only entry denies every port.
 * - [allowedPrivateHosts]: the **explicit, server-side allow-list** for a private target that an operator has decided to expose (a read replica on the
 *   internal network, the V1 local data target). Empty by default and settable only in code/configuration — never through a data source's own config.
 *   Every entry **must name a port** (no "this whole host"), must be a plain host name or an exact address (no `*`, no CIDR, no `0.0.0.0` / `::`, no
 *   link-local / multicast / broadcast, so not the cloud metadata address) and at most [MAX_ALLOWED] entries; a bad entry fails construction (startup),
 *   it is never ignored.
 */
class PostgresTargetPolicy(
    private val addressPolicy: AddressPolicy = PublicAddressPolicy,
    allowedPrivateHosts: Set<String> = emptySet(),
    deniedHosts: Set<String> = emptySet()
) {
    private class Target(val host: String, val port: Int?) {
        /** [p] null = the caller does not know the port (legacy call): only a host-wide entry can then be matched as "any port" */
        fun covers(h: String, p: Int?) = host == h && (port == null || port == p)
        fun overlaps(p: Int?) = port == null || p == null || port == p
    }

    private val allowed = allowedPrivateHosts.map { parseTarget(it) }.also { list ->
        require(list.size <= MAX_ALLOWED) { "too many allowed private database targets" }
        list.forEach { requireExactAllowedTarget(it.host, it.port) }
    }
    private val denied = deniedHosts.map { parseTarget(it) }

    /** @return the checked addresses to connect to (connect to these; never resolve again). [port] null = unknown (legacy callers): scoped allow entries do not match, every deny entry applies */
    fun resolve(host: String, port: Int?, resolver: HostResolver = SystemHostResolver): List<InetAddress> {
        val h = host.lowercase().trimEnd('.')
        if (denied.any { it.covers(h, port) || (it.port == null && it.host == h) }) throw blocked()
        val addresses = if (allowed.any { it.host == h && it.port != null && it.port == port }) resolveAllowListed(h, resolver) else {
            HostNamePolicy.reject(h, allowIpLiteral = true)?.let { throw blocked() }
            PinnedResolution.resolve(h, resolver, addressPolicy)
        }
        // a different alias (or a literal) for a denied target is refused by address as well: compare with what the denied names resolve to now, for the
        // entries that apply to this port. FAIL CLOSED: a relevant denied entry whose address cannot be determined right now (DNS error, empty answer)
        // means the comparison cannot be made, so the connection is refused rather than allowed through unchecked.
        val deniedIps = HashSet<String>()
        for (d in denied.filter { it.overlaps(port) }) {
            val ips = if (IP_LITERAL.matches(d.host)) listOf(InetAddress.getByName(d.host)) else try { resolver.resolve(d.host) } catch (e: Exception) { emptyList() }
            if (ips.isEmpty()) throw blocked()
            ips.forEach { deniedIps += it.hostAddress.substringBefore('%') }
        }
        if (addresses.any { it.hostAddress.substringBefore('%') in deniedIps }) throw blocked()
        return addresses
    }

    /** Port unknown (the pre-port API): every deny entry applies and only host-wide allow entries could match, and none can exist, so it is the strict reading */
    fun resolve(host: String, resolver: HostResolver = SystemHostResolver): List<InetAddress> = resolve(host, null, resolver)

    /**
     * Static part of the policy, for `validateConfig` when a data source is saved (no DNS): refuses a denied target, a non-public name
     * (`localhost`, single-label compose/k8s service names, `.internal`/`.local`) and a private/loopback IPv4 literal, unless that exact `host:port`
     * is allow-listed.
     */
    fun checkSyntax(host: String, port: Int?) {
        val h = host.lowercase().trimEnd('.')
        fun bad(): Nothing = throw ConnectorFailure(FailureCodes.INVALID_CONFIG, "invalid configuration: host is not an allowed database address")
        if (denied.any { it.covers(h, port) || (it.port == null && it.host == h) }) bad()
        if (allowed.any { it.host == h && it.port != null && it.port == port }) return
        if (HostNamePolicy.reject(h, allowIpLiteral = true) != null) bad()
        if (h.all { it.isDigit() || it == '.' }) {                                                    // numeric host: must be a strict dotted quad, and a public one
            val octets = h.split('.')
            if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || it.toInt() > 255 }) bad()
            if (!addressPolicy.isAllowed(InetAddress.getByAddress(ByteArray(4) { octets[it].toInt().toByte() }))) bad()   // from bytes: no DNS involved
        }
    }

    fun checkSyntax(host: String) = checkSyntax(host, null)

    private fun resolveAllowListed(host: String, resolver: HostResolver): List<InetAddress> {
        val addresses = try { resolver.resolve(host) } catch (e: Exception) { emptyList() }
        if (addresses.isEmpty()) throw ConnectorFailure(FailureCodes.HOST_UNRESOLVED, "host name could not be resolved")
        return addresses.sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
    }

    private fun blocked() = ConnectorFailure(FailureCodes.ADDRESS_BLOCKED, "target is not an allowed database address")

    companion object {
        const val MAX_ALLOWED = 20

        /** `host`, `host:port`, `[v6]`, `[v6]:port`, or a bare IPv6 literal (two or more colons, host only). Anything else is rejected, never guessed. */
        private fun parseTarget(raw: String): Target {
            val t = raw.trim().lowercase().trimEnd('.')
            require(t.isNotEmpty() && t.length <= 260 && t.none { it.isWhitespace() || it == '/' || it == '*' || it == ',' || it == '@' || it == '?' }) { "invalid database target" }
            fun port(p: String) = p.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("invalid database target port")
            val (host, port) = when {
                t.startsWith("[") -> {
                    val close = t.indexOf(']'); require(close > 1) { "invalid database target" }
                    val rest = t.substring(close + 1)
                    require(rest.isEmpty() || rest.startsWith(":")) { "invalid database target" }
                    t.substring(1, close) to rest.removePrefix(":").takeIf { it.isNotEmpty() }?.let { port(it) }
                }
                t.count { it == ':' } == 1 -> t.substringBefore(':') to port(t.substringAfter(':'))
                else -> t to null
            }
            require(host.isNotEmpty()) { "invalid database target" }
            return Target(host, port)
        }

        /** an allow-list entry is an exact endpoint, and never an address class that is dangerous to expose (see the class comment) */
        private fun requireExactAllowedTarget(host: String, port: Int?) {
            requireNotNull(port) { "an allowed private database target must name a port (host:port)" }
            if (IP_LITERAL.matches(host)) {
                val a = InetAddress.getByName(host)
                require(!(a.isAnyLocalAddress || a.isLinkLocalAddress || a.isMulticastAddress || host == "255.255.255.255" || host.startsWith("0."))) { "this address cannot be an allowed database target" }
            } else {
                require(Regex("^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$").matches(host)) { "invalid allowed database target host" }
            }
        }

        private val LOOPBACK = Regex("^127(\\.[0-9]{1,3}){3}$|^::1$|^0:0:0:0:0:0:0:1$|^localhost$")

        /** the endpoints (`host` and port, 5432 when the URL has none) named by JDBC URLs; same lenient parsing as [hostsOfJdbcUrls] */
        fun endpointsOfJdbcUrls(vararg urls: String?): Set<Pair<String, Int>> = urls.filterNotNull().flatMap { endpointsOf(it) ?: emptyList() }.toSet()

        private val IP_LITERAL = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$|^[0-9a-fA-F:.]*:[0-9a-fA-F:.]*$")

        /**
         * Every host named by JDBC URLs such as `jdbc:postgresql://appdb:5432/x?ssl=true` or a failover list `jdbc:postgresql://a:5432,b:5432/x`
         * (userinfo and `[ipv6]` brackets handled); `jdbc:postgresql:db` means localhost. Lenient: null or unparsable URLs contribute nothing — use
         * [denyingPlatformDatabases] to build the production policy, which refuses to start on those.
         */
        fun hostsOfJdbcUrls(vararg urls: String?): Set<String> = urls.filterNotNull().flatMap { hostsOf(it) ?: emptyList() }.toSet()

        /**
         * The production policy: public addresses only, plus the platform's own databases denied. A platform database on a **loopback** address is denied as
         * `host:port` (a developer machine runs the platform DB, the apps DB and any data target on the same `127.0.0.1`; only the platform endpoints are
         * denied, a distinct explicitly allow-listed endpoint stays reachable); on any other host the **whole host** is denied, every port, exactly as before.
         * Throws when a URL is missing or cannot be read — a deny-list that silently came out empty would be fail-open.
         */
        fun denyingPlatformDatabases(vararg platformJdbcUrls: String?, allowedPrivateHosts: Set<String> = emptySet(), extraDenied: Set<String> = emptySet()): PostgresTargetPolicy {
            require(platformJdbcUrls.isNotEmpty()) { "at least one platform JDBC URL is required" }
            val endpoints = platformJdbcUrls.flatMap { u -> requireNotNull(u?.takeIf { it.isNotBlank() }?.let { endpointsOf(it) }?.takeIf { it.isNotEmpty() }) { "platform JDBC URL missing or unreadable" } }
            val denied = endpoints.map { (h, p) -> if (LOOPBACK.matches(h)) "${if (':' in h) "[$h]" else h}:$p" else h }.toSet() + extraDenied
            return PostgresTargetPolicy(allowedPrivateHosts = allowedPrivateHosts, deniedHosts = denied)
        }

        private fun endpointsOf(url: String): List<Pair<String, Int>>? {
            val u = url.trim()
            val prefix = "jdbc:postgresql:"
            if (!u.startsWith(prefix, ignoreCase = true)) return null
            val rest = u.substring(prefix.length)
            if (!rest.startsWith("//")) return listOf("localhost" to 5432)
            val authority = rest.substring(2).substringBefore('/').substringBefore('?')
            val out = authority.split(',').map { entry ->
                val e = entry.substringAfterLast('@')
                val host: String; val port: Int
                if (e.startsWith("[")) { host = e.substringAfter('[').substringBefore(']').lowercase(); port = e.substringAfter(']', "").removePrefix(":").toIntOrNull() ?: 5432 }
                else { host = e.substringBefore(':').lowercase().trimEnd('.'); port = e.substringAfter(':', "").toIntOrNull() ?: 5432 }
                host to port
            }
            return out.takeIf { o -> o.isNotEmpty() && o.all { it.first.isNotEmpty() } } ?: if (authority.isEmpty()) listOf("localhost" to 5432) else null
        }

        private fun hostsOf(url: String): List<String>? {
            val u = url.trim()
            val prefix = "jdbc:postgresql:"
            if (!u.startsWith(prefix, ignoreCase = true)) return null
            val rest = u.substring(prefix.length)
            if (!rest.startsWith("//")) return listOf("localhost")                       // jdbc:postgresql:db — the driver's default host
            val authority = rest.substring(2).substringBefore('/').substringBefore('?')
            val hosts = authority.split(',').map { entry ->
                val e = entry.substringAfterLast('@')
                (if (e.startsWith("[")) e.substringAfter('[').substringBefore(']') else e.substringBefore(':')).lowercase().trimEnd('.')
            }
            return hosts.takeIf { h -> h.isNotEmpty() && h.all { it.isNotEmpty() } } ?: if (authority.isEmpty()) listOf("localhost") else null
        }
    }
}
