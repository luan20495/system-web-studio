package com.systemwebstudio.data.datasource

import com.systemwebstudio.runtime.PublicAddress
import java.net.InetAddress
import java.net.UnknownHostException

/** Resolves a host name to addresses. Seam so tests (and a future caching resolver) can replace DNS; production uses the system resolver. */
fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>
}

object SystemHostResolver : HostResolver {
    override fun resolve(host: String): List<InetAddress> =
        try { InetAddress.getAllByName(host).toList() } catch (e: UnknownHostException) { emptyList() }
}

/** Decides whether an outbound connection to one *resolved* address is acceptable. */
fun interface AddressPolicy {
    fun isAllowed(address: InetAddress): Boolean
}

/**
 * The platform's one SSRF guard: every address is put through [PublicAddress] (`runtime/Gateway.kt`), fed the literal IP of an address that was already
 * resolved, so it does no DNS and the check is on exactly the address we then connect to. C3 has **no second list of ranges**.
 *
 * The ranges this policy relies on — loopback, private (10/8, 172.16/12, 192.168/16), link-local (incl. 169.254.169.254), CGNAT 100.64/10, 0/8,
 * 240.0.0.0/4, 192.0.0.0/24, 198.18.0.0/15, the TEST-NETs, multicast, unique-local fc00::/7, NAT64 (embedded IPv4 judged; `64:ff9b:1::/48` blocked), 6to4
 * `2002::/16`, Teredo `2001::/32`, `2001:db8::/32`, `100::/64`, IPv4-compatible `::a.b.c.d` — are the canonical `PublicAddress` of
 * `docs/parallel/INTEGRATION_V2.md` §8 (a C0 patch; C3's copy for review is `docs/parallel/agents/C3_PUBLIC_ADDRESS_PATCH.md`). They are specified by
 * `AddressRangeSpecTests` in this module. **Until C0 applies that patch the base `PublicAddress` still answers "public" for the reserved and embedding forms,
 * and those tests fail: that failure is the gate, not something to work around here.** (The former C3 stop-gap `SupplementaryRanges` was deleted.)
 */
object PublicAddressPolicy : AddressPolicy {
    override fun isAllowed(address: InetAddress): Boolean = PublicAddress.isPublic(address.hostAddress.substringBefore('%'))
}

/**
 * Resolve once, check **every** returned address, and hand back the checked addresses to connect to. Connecting to these (and not
 * resolving again) is what closes the DNS-rebinding window: the answer that was validated is the answer that is used. One bad address in
 * the set refuses the whole host (an attacker can publish a public and a private A record side by side).
 */
object PinnedResolution {
    fun resolve(host: String, resolver: HostResolver, policy: AddressPolicy): List<InetAddress> {
        val addresses = try { resolver.resolve(host) } catch (e: Exception) { emptyList() }
        if (addresses.isEmpty()) throw ConnectorFailure(FailureCodes.HOST_UNRESOLVED, "host name could not be resolved")
        if (!addresses.all { policy.isAllowed(it) }) throw ConnectorFailure(FailureCodes.ADDRESS_BLOCKED, "target is not a public internet address")
        // IPv4 first: the usual dual-stack default, and the more commonly routable one from server networks
        return addresses.sortedBy { if (it is java.net.Inet4Address) 0 else 1 }.distinctBy { it.hostAddress }
    }
}

/** Syntactic rules on a data-source host name that hold before any DNS lookup (same intent as the existing admin connector validation). */
object HostNamePolicy {
    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val IPV4_LITERAL = Regex("^[0-9.]+$")

    /** @return null when acceptable, otherwise a fixed reason (never the host itself) */
    fun reject(rawHost: String?, allowIpLiteral: Boolean = false): String? {
        val host = rawHost?.trim()?.lowercase()?.trimEnd('.')
        if (host.isNullOrEmpty() || host.length > 253) return "host is missing or too long"
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".internal") || host.endsWith(".local") || host.endsWith(".localdomain") || host.endsWith(".lan"))
            return "host must be a public name"
        if (IPV4_LITERAL.matches(host) || host.contains(':') || host.startsWith('[')) return if (allowIpLiteral) null else "host must be a name, not an IP address"
        if (!host.split('.').all { LABEL.matches(it) }) return "host contains invalid characters"
        if (!host.contains('.')) return "host must be a fully qualified public name"   // single-label names are compose/k8s service names
        return null
    }
}
