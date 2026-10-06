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
 * - [deniedHosts]: hosts that are refused even if they would otherwise pass or be allow-listed (the platform DB and apps DB hosts;
 *   build them with [hostsOfJdbcUrls] from the platform's own JDBC URLs).
 * - [allowedPrivateHosts]: the **explicit, server-side allow-list** for a private host that an operator has decided to expose (for example a
 *   read replica on the internal network). Empty by default and settable only in code/configuration — never through a data source's own config.
 */
class PostgresTargetPolicy(
    private val addressPolicy: AddressPolicy = PublicAddressPolicy,
    allowedPrivateHosts: Set<String> = emptySet(),
    deniedHosts: Set<String> = emptySet()
) {
    private val allowed = allowedPrivateHosts.map { it.lowercase() }.toSet()
    private val denied = deniedHosts.map { it.lowercase() }.toSet()

    /** @return the checked addresses to connect to (connect to these; never resolve again) */
    fun resolve(host: String, resolver: HostResolver = SystemHostResolver): List<InetAddress> {
        val h = host.lowercase().trimEnd('.')
        if (h in denied) throw blocked()
        val addresses = if (h in allowed) resolveAllowListed(h, resolver) else {
            HostNamePolicy.reject(h, allowIpLiteral = true)?.let { throw blocked() }
            PinnedResolution.resolve(h, resolver, addressPolicy)
        }
        // a different alias (or a literal) for a denied host is refused by address as well: compare with what the denied names resolve to now.
        // FAIL CLOSED: a denied entry whose address cannot be determined right now (DNS error, empty answer) means the comparison cannot be made,
        // so the connection is refused rather than allowed through unchecked.
        val deniedIps = HashSet<String>()
        for (d in denied) {
            val ips = if (IP_LITERAL.matches(d)) listOf(InetAddress.getByName(d)) else try { resolver.resolve(d) } catch (e: Exception) { emptyList() }
            if (ips.isEmpty()) throw blocked()
            ips.forEach { deniedIps += it.hostAddress.substringBefore('%') }
        }
        if (addresses.any { it.hostAddress.substringBefore('%') in deniedIps }) throw blocked()
        return addresses
    }

    /**
     * Static part of the policy, for `validateConfig` when a data source is saved (no DNS): refuses a denied host, a non-public name
     * (`localhost`, single-label compose/k8s service names, `.internal`/`.local`) and a private/loopback IPv4 literal.
     */
    fun checkSyntax(host: String) {
        val h = host.lowercase().trimEnd('.')
        fun bad(): Nothing = throw ConnectorFailure(FailureCodes.INVALID_CONFIG, "invalid configuration: host is not an allowed database address")
        if (h in denied) bad()
        if (h in allowed) return
        if (HostNamePolicy.reject(h, allowIpLiteral = true) != null) bad()
        if (h.all { it.isDigit() || it == '.' }) {                                                    // numeric host: must be a strict dotted quad, and a public one
            val octets = h.split('.')
            if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || it.toInt() > 255 }) bad()
            if (!addressPolicy.isAllowed(InetAddress.getByAddress(ByteArray(4) { octets[it].toInt().toByte() }))) bad()   // from bytes: no DNS involved
        }
    }

    private fun resolveAllowListed(host: String, resolver: HostResolver): List<InetAddress> {
        val addresses = try { resolver.resolve(host) } catch (e: Exception) { emptyList() }
        if (addresses.isEmpty()) throw ConnectorFailure(FailureCodes.HOST_UNRESOLVED, "host name could not be resolved")
        return addresses.sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
    }

    private fun blocked() = ConnectorFailure(FailureCodes.ADDRESS_BLOCKED, "target is not an allowed database address")

    companion object {
        private val IP_LITERAL = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$|^[0-9a-fA-F:.]*:[0-9a-fA-F:.]*$")

        /**
         * Every host named by JDBC URLs such as `jdbc:postgresql://appdb:5432/x?ssl=true` or a failover list `jdbc:postgresql://a:5432,b:5432/x`
         * (userinfo and `[ipv6]` brackets handled); `jdbc:postgresql:db` means localhost. Lenient: null or unparsable URLs contribute nothing — use
         * [denyingPlatformDatabases] to build the production policy, which refuses to start on those.
         */
        fun hostsOfJdbcUrls(vararg urls: String?): Set<String> = urls.filterNotNull().flatMap { hostsOf(it) ?: emptyList() }.toSet()

        /** The production policy: public addresses only, plus every host of the platform's own JDBC URLs denied. Throws when a URL is missing or cannot be read — a deny-list that silently came out empty would be fail-open. */
        fun denyingPlatformDatabases(vararg platformJdbcUrls: String?, allowedPrivateHosts: Set<String> = emptySet()): PostgresTargetPolicy {
            require(platformJdbcUrls.isNotEmpty()) { "at least one platform JDBC URL is required" }
            val hosts = platformJdbcUrls.flatMap { u -> requireNotNull(u?.takeIf { it.isNotBlank() }?.let { hostsOf(it) }?.takeIf { it.isNotEmpty() }) { "platform JDBC URL missing or unreadable" } }
            return PostgresTargetPolicy(allowedPrivateHosts = allowedPrivateHosts, deniedHosts = hosts.toSet())
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
