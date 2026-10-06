# C3 → C0: `PublicAddress` patch (INTEGRATION_V2 §8) — proposal, C0 applies

Status: PROPOSED · C3 does **not** edit `runtime/Gateway.kt` (C0-gated, B-002/D-003/B-C3-04). This note is what C0 needs to apply the canonical `PublicAddress` from `docs/parallel/INTEGRATION_V2.md` §8 and to turn on C3's tests.

## What C3 did

- Deleted C3's stop-gap `SupplementaryRanges`. `PublicAddressPolicy.isAllowed(a)` is now `PublicAddress.isPublic(a.hostAddress.substringBefore('%'))` — it **relies on the canonical object** and adds no ranges of its own.
- Added `datasource/AddressRangeSpecTests.kt` (17 tests) that states every range the contract requires. **Against the current base `PublicAddress` these tests FAIL by design — that is the gate.** They pass once the patch below is applied (C3's local harness runs them against the canonical object copied into a shim: all pass).

## What C0 applies (to `runtime/Gateway.kt`, or to the `common/` extraction when D-003's task happens)

The object below is the canonical §8 version. Behaviour that matters:

- `isPublic(host)`: public only if the name/literal resolves and **every** address is public; unresolvable ⇒ not public.
- `isPublicAddress(InetAddress)`: pure, no DNS, fail closed — this is the entry point C3's pinned-address connectors use after their single resolve.
- IPv4 blocks: `0/8, 100.64/10, 192.0.0/24, 192.0.2/24, 192.88.99/24, 198.18/15, 198.51.100/24, 203.0.113/24, 240/4` (+ JDK loopback/site-local/link-local/any-local/multicast).
- IPv6 blocks: `::/96, ::ffff:0:0:0/96, 64:ff9b:1::/48, 100::/64, 2001::/23 (incl. Teredo 2001::/32), 2001:db8::/32, 2002::/16 (6to4), 3fff::/20, 5f00::/16, fc00::/7`.
- NAT64 `64:ff9b::/96` is judged by the **embedded IPv4** (so `64:ff9b::808:808` is public, `64:ff9b::a00:1` is not).

```kotlin
object PublicAddress {
    fun isPublic(host: String): Boolean {
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addrs.isNotEmpty() && addrs.all { isPublicAddress(it) }
    }

    fun isPublicAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        return when (a) {
            is Inet4Address -> V4.none { it.matches(b) }
            is Inet6Address -> when {
                (b[0].toInt() and 0xfe) == 0xfc -> false
                V6.any { it.matches(b) } -> false
                NAT64.matches(b) -> isPublicAddress(InetAddress.getByAddress(b.copyOfRange(12, 16)))
                else -> true
            }
            else -> false
        }
    }

    private class Cidr(literal: String, private val bits: Int) {
        private val prefix: ByteArray = InetAddress.getByName(literal).address     // IP literal: no DNS
        fun matches(a: ByteArray): Boolean {
            if (a.size != prefix.size) return false
            val full = bits / 8; val rest = bits % 8
            for (i in 0 until full) if (a[i] != prefix[i]) return false
            return rest == 0 || ((a[full].toInt() xor prefix[full].toInt()) and (0xff shl (8 - rest)) and 0xff) == 0
        }
    }

    private val V4 = listOf(Cidr("0.0.0.0", 8), Cidr("100.64.0.0", 10), Cidr("192.0.0.0", 24), Cidr("192.0.2.0", 24), Cidr("192.88.99.0", 24),
        Cidr("198.18.0.0", 15), Cidr("198.51.100.0", 24), Cidr("203.0.113.0", 24), Cidr("240.0.0.0", 4))
    private val V6 = listOf(Cidr("::", 96), Cidr("::ffff:0:0:0", 96), Cidr("64:ff9b:1::", 48), Cidr("100::", 64), Cidr("2001::", 23),
        Cidr("2001:db8::", 32), Cidr("2002::", 16), Cidr("3fff::", 20), Cidr("5f00::", 16))
    private val NAT64 = Cidr("64:ff9b::", 96)
}
```

## Required tests (C3 ships `AddressRangeSpecTests`; C0 should keep equivalents next to the object)

240/4 incl. 255.255.255.255 · 192.0.0/24 · 198.18/15 (both /16 halves, edges 198.17.255.255 and 198.20.0.0 public) · NAT64 with private and public embedded IPv4 · 6to4 · Teredo · 2001:db8 · ::/96 and ::ffff:0:0:0/96 · 100::/64 · fc00::/7 · loopback/link-local/site-local (v4 and v6) · public addresses stay public (8.8.8.8, 1.1.1.1, 2606:4700:4700::1111) · IPv6 zone id does not change the verdict. C0 should add one more next to the object: a name that fails to resolve is not public (C3's tests use IP literals only, so they never touch DNS).

## Callers after the patch

- C3: `PublicAddressPolicy` (REST/PostgreSQL connectors, after one resolve and pin) — already switched.
- C0-owned: `ConnectorProxyController`, `AdminConnectorController` and any other user of the old `isPublic` get the same fix for free; no signature change.
- After C0 applies the patch, B-C3-04 can be closed and `SupplementaryRanges` is already gone from C3.
