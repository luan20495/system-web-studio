package com.systemwebstudio.data.datasource

import com.systemwebstudio.data.FixedResolver
import com.systemwebstudio.data.datasource.postgres.PostgresTargetPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * The address ranges every outbound data connection must refuse — the specification of the canonical `PublicAddress` (INTEGRATION_V2 §8, a C0 patch to
 * `runtime/Gateway.kt`), exercised through the policy C3 actually uses ([PublicAddressPolicy]). C3 keeps no list of its own (the former stop-gap
 * `SupplementaryRanges` was removed), so **these tests pass only with the canonical `PublicAddress`**; against the base implementation the reserved and the
 * IPv4-embedding forms fail them, which is how a missing patch shows up. Addresses are IP literals: nothing here resolves a name.
 */
class AddressRangeSpecTests {
    private fun allowed(ip: String) = PublicAddressPolicy.isAllowed(InetAddress.getByName(ip))
    private fun blocked(vararg ips: String) { for (ip in ips) { if (allowed(ip)) throw AssertionError("$ip must be refused") } }
    private fun public(vararg ips: String) { for (ip in ips) { if (!allowed(ip)) throw AssertionError("$ip must be allowed") } }

    // ---------------------------------------------------------------- IPv4

    @Test fun `240 slash 4 reserved and the limited broadcast`() = blocked("240.0.0.0", "240.0.0.1", "247.1.2.3", "255.255.255.254", "255.255.255.255")

    @Test fun `192 0 0 slash 24 IETF protocol assignments incl DS-Lite and PCP anycast`() {
        blocked("192.0.0.0", "192.0.0.1", "192.0.0.8", "192.0.0.170", "192.0.0.171", "192.0.0.255")
        public("192.0.1.1", "191.255.255.255")
    }

    @Test fun `198 18 slash 15 benchmarking`() {
        blocked("198.18.0.0", "198.18.0.1", "198.19.0.1", "198.19.255.255")
        public("198.17.255.255", "198.20.0.0")                                           // just outside on both sides
    }

    @Test fun `documentation and 6to4 relay blocks`() {
        blocked("192.0.2.1", "192.0.2.255", "198.51.100.7", "203.0.113.9", "192.88.99.1")
        public("192.0.3.1", "198.51.99.1", "198.51.101.1", "203.0.112.1", "203.0.114.1", "192.88.98.1", "192.88.100.1")
    }

    @Test fun `private loopback link-local CGNAT and this-network`() {
        blocked("10.0.0.0", "10.1.2.3", "10.255.255.255", "172.16.0.0", "172.20.1.1", "172.31.255.255", "192.168.0.0", "192.168.1.1", "192.168.255.255",
            "127.0.0.1", "127.8.8.8", "127.255.255.254", "169.254.0.1", "169.254.169.254", "169.254.255.254",
            "100.64.0.0", "100.100.100.100", "100.127.255.255", "0.0.0.0", "0.1.2.3", "0.255.255.255", "224.0.0.1", "239.255.255.255")
        public("9.255.255.255", "11.0.0.1", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "126.255.255.255", "128.0.0.1",
            "169.253.255.255", "169.255.0.1", "100.63.255.255", "100.128.0.0", "1.0.0.1")
    }

    @Test fun `ordinary public IPv4 stays reachable`() = public("8.8.8.8", "1.1.1.1", "9.9.9.9", "93.184.216.34", "151.101.1.69", "172.217.14.206")

    // ---------------------------------------------------------------- IPv6

    @Test fun `loopback unspecified link-local site-local unique-local multicast`() {
        blocked("::1", "::", "fe80::1", "fe80::abcd:1234", "febf::1", "fec0::1", "fc00::1", "fd12:3456:789a::1", "fdff:ffff:ffff::1", "ff02::1", "ff0e::1", "ff05::2")
    }

    @Test fun `IPv4-compatible and IPv4-mapped forms are judged as IPv4`() {
        blocked("::127.0.0.1", "::10.0.0.1", "::192.168.0.1", "::8.8.8.8")                    // ::a.b.c.d is deprecated and refused whatever it embeds
        blocked("::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "::ffff:192.168.0.1", "::ffff:240.0.0.1", "::ffff:198.18.0.1")
        public("::ffff:8.8.8.8")                                                               // mapped public IPv4 is just that public IPv4
    }

    @Test fun `NAT64 well-known prefix is judged by the IPv4 address it embeds`() {
        blocked("64:ff9b::7f00:1",       // 127.0.0.1
            "64:ff9b::a00:1",            // 10.0.0.1
            "64:ff9b::c0a8:1",           // 192.168.0.1
            "64:ff9b::a9fe:a9fe",        // 169.254.169.254
            "64:ff9b::6440:1",           // 100.64.0.1
            "64:ff9b::f000:1",           // 240.0.0.1
            "64:ff9b::c612:1",           // 198.18.0.1
            "64:ff9b::c000:8")           // 192.0.0.8
        public("64:ff9b::808:808", "64:ff9b::101:101")                                         // 8.8.8.8 and 1.1.1.1 via NAT64 are public
    }

    @Test fun `NAT64 local-use prefix is refused whatever it embeds`() = blocked("64:ff9b:1::1", "64:ff9b:1::808:808", "64:ff9b:1:ffff::1")

    @Test fun `6to4 is refused as a whole, even when the embedded IPv4 is public`() {
        blocked("2002:7f00:1::1", "2002:a00:1::", "2002:c0a8:1::1", "2002:a9fe:a9fe::1", "2002:808:808::1", "2002:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
        public("2003::1", "2001:ffff::1")                                                      // neighbours of 2002::/16
    }

    @Test fun `Teredo and the rest of the IETF protocol block 2001 slash 23`() {
        blocked("2001:0:4136:e378:8000:63bf:3fff:fdd2", "2001::1", "2001:0:0:0:0:0:0:1", "2001:1::1", "2001:2::1", "2001:10::1", "2001:1ff:ffff::1")
        public("2001:200::1", "2001:4860:4860::8888")                                          // 2001:200::/23 onwards is ordinary registry space
    }

    @Test fun `documentation discard-only SRv6 and the other reserved blocks`() {
        blocked("2001:db8::1", "2001:db8:ffff::1", "100::1", "100::ffff:ffff:ffff:ffff", "3fff::1", "3fff:fff:ffff::1", "5f00::1", "::ffff:0:808:808")
        public("2001:db7::1", "2001:db9::1", "100:0:0:1::1", "3ffe::1", "5eff::1", "5f01::1")
    }

    @Test fun `ordinary public IPv6 stays reachable`() = public("2606:4700:4700::1111", "2a00:1450:4001:81b::200e", "2001:4860:4860::8888", "2620:fe::fe", "2400:cb00::1")

    @Test fun `a zone identifier does not change the verdict`() {
        fun scoped(ip: String, scope: Int) = java.net.Inet6Address.getByAddress(null, InetAddress.getByName(ip).address, scope)      // numeric scope: no interface lookup
        assertThat(scoped("fe80::1", 1).hostAddress).contains("%")
        assertThat(PublicAddressPolicy.isAllowed(scoped("fe80::1", 1))).isFalse()
        assertThat(PublicAddressPolicy.isAllowed(scoped("fd00::1", 2))).isFalse()
        assertThat(PublicAddressPolicy.isAllowed(scoped("2606:4700:4700::1111", 2))).isTrue()
    }

    // ---------------------------------------------------------------- through the connector-facing policies

    @Test fun `a host with one embedded-internal address among public ones is refused whole - DNS rebinding with NAT64 and 6to4 forms`() {
        for (evil in listOf("64:ff9b::7f00:1", "2002:7f00:1::1", "::127.0.0.1", "240.0.0.1", "198.18.0.1", "192.0.0.8", "2001:db8::1", "64:ff9b::a9fe:a9fe")) {
            val resolver = FixedResolver(mapOf("rebind.example.com" to listOf("93.184.216.34", evil)))
            val f = try { PinnedResolution.resolve("rebind.example.com", resolver, PublicAddressPolicy); null } catch (e: ConnectorFailure) { e }
            assertThat(f?.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
        }
        val ok = PinnedResolution.resolve("ok.example.com", FixedResolver(mapOf("ok.example.com" to listOf("93.184.216.34", "2606:4700:4700::1111"))), PublicAddressPolicy)
        assertThat(ok.map { it.hostAddress }.first()).isEqualTo("93.184.216.34")
    }

    @Test fun `a database host given as a reserved literal is refused when the data source is saved`() {
        val policy = PostgresTargetPolicy()
        for (host in listOf("240.0.0.1", "198.18.0.1", "192.0.0.8", "192.0.2.10", "100.64.0.1", "169.254.169.254", "127.0.0.1", "10.0.0.5", "0.0.0.0"))
            assertThat(try { policy.checkSyntax(host); null } catch (e: ConnectorFailure) { e.code }).isEqualTo(FailureCodes.INVALID_CONFIG)
        policy.checkSyntax("93.184.216.34")
    }
}
