package com.systemwebstudio.runtime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * C0 · canonical `PublicAddress` (docs/parallel/INTEGRATION_V2.md §8). Not compiled or run when written (no Gradle in the authoring environment):
 * the Mac gate in docs/parallel/MAC_INTEGRATION_CHECKLIST.md runs it. C3 keeps its own `AddressRangeSpecTests` against the same object.
 * Only IP literals are used, so nothing here touches DNS, except the one test for a name that cannot resolve (reserved `.invalid` TLD, RFC 6761).
 */
class PublicAddressTests {
    private fun pub(ip: String) = PublicAddress.isPublicAddress(InetAddress.getByName(ip))
    private fun refused(vararg ips: String) { for (ip in ips) assertThat(pub(ip)).withFailMessage("$ip must be refused").isFalse() }
    private fun allowed(vararg ips: String) { for (ip in ips) assertThat(pub(ip)).withFailMessage("$ip must be allowed").isTrue() }

    @Test fun `private loopback link-local multicast and any-local are refused`() = refused(
        "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1", "127.0.0.1", "169.254.169.254", "224.0.0.1", "0.0.0.0",
        "::1", "fe80::1", "ff02::1", "fc00::1", "fd12:3456::1")

    @Test fun `240 slash 4 reserved and the limited broadcast`() = refused("240.0.0.0", "247.1.2.3", "255.255.255.254", "255.255.255.255")

    @Test fun `192 0 0 slash 24 and the documentation and benchmarking blocks`() {
        refused("192.0.0.0", "192.0.0.8", "192.0.0.170", "192.0.0.255", "192.0.2.5", "192.88.99.1", "198.51.100.9", "203.0.113.9")
        refused("198.18.0.0", "198.18.0.1", "198.19.0.1", "198.19.255.255")
        allowed("192.0.1.1", "191.255.255.255", "198.17.255.255", "198.20.0.0", "192.0.3.1")
    }

    @Test fun `this-network and carrier-grade NAT`() {
        refused("0.1.2.3", "100.64.0.1", "100.127.255.255")
        allowed("100.63.255.255", "100.128.0.1")
    }

    @Test fun `NAT64 is judged by the embedded IPv4`() {
        refused("64:ff9b::a00:1", "64:ff9b::7f00:1", "64:ff9b::c0a8:101", "64:ff9b::a9fe:a9fe")   // 10.0.0.1, 127.0.0.1, 192.168.1.1, 169.254.169.254
        allowed("64:ff9b::808:808")                                                                 // 8.8.8.8
        refused("64:ff9b:1::1")                                                                     // local-use NAT64 is never public
    }

    @Test fun `6to4 Teredo documentation discard and IPv4-compatible IPv6`() = refused(
        "2002:808:808::1", "2002::1", "2001::1", "2001:0:4136:e378:8000:63bf:3fff:fdd2", "2001:db8::1", "3fff::1", "100::1", "5f00::1", "::", "::1.2.3.4", "::ffff:10.0.0.1")

    @Test fun `public addresses stay public`() = allowed("8.8.8.8", "8.8.4.4", "1.1.1.1", "93.184.216.34", "2606:4700:4700::1111", "2a00:1450::1", "2001:200::1")

    @Test fun `an IPv6 zone id does not change the verdict`() {
        assertThat(PublicAddress.isPublicAddress(InetAddress.getByName("fe80::1%1"))).isFalse()
        assertThat(PublicAddress.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111%1"))).isTrue()
    }

    @Test fun `a literal that is private is not public and a name that cannot resolve is not public`() {
        assertThat(PublicAddress.isPublic("10.1.2.3")).isFalse()
        assertThat(PublicAddress.isPublic("name.that.does.not.resolve.invalid")).isFalse()
    }
}
