package com.systemwebstudio.data.datasource

import com.systemwebstudio.data.FixedResolver
import com.systemwebstudio.data.InMemoryCredentialStore
import com.systemwebstudio.runtime.SecretsCrypto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.Base64
import java.util.UUID

/** T8 security coverage: credential handling, reuse of the platform SSRF guard, pinned resolution, host-name rules. */
class SecurityPrimitivesTests {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7).toByte() })
    private val secret = "sk-live-TOPSECRET-9f8e7d"
    private val store = InMemoryCredentialStore()
    private fun vault() = SecretsCryptoCredentialVault(SecretsCrypto(key), store)
    private fun failure(block: () -> Unit): ConnectorFailure {
        try { block() } catch (e: ConnectorFailure) { return e }
        throw AssertionError("expected a ConnectorFailure")
    }

    // ---------------------------------------------------------------- credentials

    @Test fun `a resolved credential never prints, compares or destructures its secret`() {
        val c = ResolvedCredential.of(mapOf("authValue" to secret))
        assertThat(c.toString()).doesNotContain(secret)
        assertThat(c.toString()).isEqualTo("ResolvedCredential(***)")
        assertThat(c.equals(ResolvedCredential.of(mapOf("authValue" to secret)))).isFalse()   // identity only: no equality oracle on secret values
        assertThat(c.get("authValue")).isEqualTo(secret)
    }

    @Test fun `a data source can be logged without its credential reference`() {
        val ref = DataSourceRef(UUID.randomUUID(), UUID.randomUUID(), DataSourceTypes.REST, mapOf("baseUrl" to "https://api.example.com"))
        val credRef = vault().store(ref.tenantId, mapOf("authValue" to secret))
        val ds = DataSource(ref, "billing", credentialRef = credRef)
        assertThat(ds.toString()).doesNotContain(credRef)
        assertThat(ds.toString()).doesNotContain(store.find(ref.tenantId, credRef)!!)
        assertThat(ds.toString()).doesNotContain(secret)
        assertThat(ds.hasCredential).isTrue()
    }

    @Test fun `credentials are stored only as SecretsCrypto ciphertext in their own store and round-trip`() {
        val sealed = vault().seal(mapOf("authValue" to secret, "username" to "reader"))
        assertThat(sealed).startsWith("v1:")                                             // the platform's existing AES-256-GCM format
        assertThat(sealed).doesNotContain(secret)
        assertThat(sealed).doesNotContain("reader")
        assertThat(vault().seal(mapOf("authValue" to secret))).isNotEqualTo(vault().seal(mapOf("authValue" to secret)))   // random IV
        val tenant = UUID.randomUUID()
        val credRef = vault().store(tenant, mapOf("authValue" to secret, "username" to "reader"))
        assertThat(store.find(tenant, credRef)).startsWith("v1:")
        assertThat(store.find(tenant, credRef)).doesNotContain(secret)
        val ds = DataSource(DataSourceRef(UUID.randomUUID(), tenant, "rest", emptyMap()), "x", credentialRef = credRef)
        val opened = vault().open(ds)
        assertThat(opened.get("authValue")).isEqualTo(secret)
        assertThat(opened.get("username")).isEqualTo("reader")
    }

    @Test fun `a credential reference of another tenant resolves to nothing`() {
        val owner = UUID.randomUUID(); val intruder = UUID.randomUUID()
        val credRef = vault().store(owner, mapOf("authValue" to secret))
        val stolen = DataSource(DataSourceRef(UUID.randomUUID(), intruder, "rest", emptyMap()), "x", credentialRef = credRef)
        assertThat(failure { vault().open(stolen) }.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
    }

    @Test fun `discarding a credential removes it`() {
        val tenant = UUID.randomUUID()
        val credRef = vault().store(tenant, mapOf("authValue" to secret))
        vault().discard(tenant, credRef)
        assertThat(store.find(tenant, credRef)).isNull()
        vault().discard(tenant, null)                                                    // no reference: nothing to do
    }

    @Test fun `no master key means no credentials, with a fixed message`() {
        val noKey = SecretsCryptoCredentialVault(SecretsCrypto(""), store)
        val f = failure { noKey.seal(mapOf("authValue" to secret)) }
        assertThat(f.code).isEqualTo(FailureCodes.SECRETS_UNAVAILABLE)
        assertThat(f.message).doesNotContain(secret)
    }

    @Test fun `a tampered or foreign ciphertext fails closed without echoing anything`() {
        val tenant = UUID.randomUUID()
        val sealed = vault().seal(mapOf("authValue" to secret))
        val tampered = sealed.dropLast(6) + "AAAAA="
        store.put(tenant, "t1", tampered); store.put(tenant, "t2", sealed)
        val ds = DataSource(DataSourceRef(UUID.randomUUID(), tenant, "rest", emptyMap()), "x", credentialRef = "t1")
        val f = failure { vault().open(ds) }
        assertThat(f.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
        assertThat(f.message).doesNotContain(secret)
        assertThat(f.message).doesNotContain(tampered)
        val otherKey = SecretsCryptoCredentialVault(SecretsCrypto(Base64.getEncoder().encodeToString(ByteArray(32) { 1 })), store)
        assertThat(failure { otherKey.open(DataSource(ds.ref, "x", credentialRef = "t2")) }.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
    }

    @Test fun `credential shape is validated before it is sealed`() {
        assertThat(failure { vault().seal(emptyMap()) }.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
        assertThat(failure { vault().seal(mapOf("bad key!" to "v")) }.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
        assertThat(failure { vault().seal(mapOf("k" to "")) }.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
    }

    @Test fun `redactor removes every known secret value`() {
        val out = Redactor.redact("login failed for hunter22 with token abc-123-token", listOf("hunter22", "abc-123-token"))
        assertThat(out).doesNotContain("hunter22")
        assertThat(out).doesNotContain("abc-123-token")
        assertThat(out).contains("***")
    }

    @Test fun `a connector failure carries no cause and no stack`() {
        val f = ConnectorFailure(FailureCodes.CONNECT_FAILED, "the data source could not be reached")
        assertThat(f.cause).isNull()
        assertThat(f.stackTrace.size).isEqualTo(0)
    }

    // ---------------------------------------------------------------- SSRF: the platform's PublicAddress, reused

    @Test fun `the platform address guard refuses every non-public address class`() {
        val blocked = listOf(
            "127.0.0.1", "127.8.8.8", "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.1", "169.254.169.254", "169.254.10.10",
            "100.64.0.1", "100.127.255.254", "0.0.0.0", "0.1.2.3", "224.0.0.1",
            "::1", "::", "fc00::1", "fd12:3456::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254"
        )
        for (ip in blocked) assertThat(PublicAddressPolicy.isAllowed(InetAddress.getByName(ip))).isEqualTo(false)
        for (ip in listOf("8.8.8.8", "93.184.216.34", "1.1.1.1", "2606:4700:4700::1111", "2a00:1450:4001:81b::200e")) assertThat(PublicAddressPolicy.isAllowed(InetAddress.getByName(ip))).isTrue()
    }

    // the reserved and IPv4-embedding ranges (240/4, 192.0.0/24, 198.18/15, NAT64, 6to4, Teredo, 2001:db8, …) are specified in AddressRangeSpecTests

    @Test fun `one private address among public ones refuses the whole host`() {
        val resolver = FixedResolver(mapOf("rebind.example.com" to listOf("93.184.216.34", "10.0.0.7")))
        val f = failure { PinnedResolution.resolve("rebind.example.com", resolver, PublicAddressPolicy) }
        assertThat(f.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
        assertThat(f.message).doesNotContain("10.0.0.7")
        assertThat(f.message).doesNotContain("rebind.example.com")
    }

    @Test fun `resolution happens once and returns the checked addresses`() {
        val resolver = FixedResolver(mapOf("ok.example.com" to listOf("2606:4700:4700::1111", "93.184.216.34")))
        val addresses = PinnedResolution.resolve("ok.example.com", resolver, PublicAddressPolicy)
        assertThat(resolver.lookups.get()).isEqualTo(1)
        assertThat(addresses.map { it.hostAddress }).containsExactly("93.184.216.34", "2606:4700:4700:0:0:0:0:1111")   // IPv4 first
    }

    @Test fun `an unresolvable host is its own failure`() {
        assertThat(failure { PinnedResolution.resolve("nope.example.com", FixedResolver(emptyMap()), PublicAddressPolicy) }.code).isEqualTo(FailureCodes.HOST_UNRESOLVED)
    }

    // ---------------------------------------------------------------- host names

    @Test fun `host name rules`() {
        for (h in listOf("localhost", "db.localhost", "service", "postgres", "appdb", "x.internal", "x.local", "metadata.google.internal", "10.0.0.5", "127.0.0.1", "[::1]", "::1", "a_b.example.com", "-a.example.com", "", "a..b.com"))
            assertThat(HostNamePolicy.reject(h)).isNotNull()
        for (h in listOf("api.example.com", "a.b.c.example.org", "xn--bcher-kva.example")) assertThat(HostNamePolicy.reject(h)).isNull()
        assertThat(HostNamePolicy.reject("93.184.216.34", allowIpLiteral = true)).isNull()
    }
}
