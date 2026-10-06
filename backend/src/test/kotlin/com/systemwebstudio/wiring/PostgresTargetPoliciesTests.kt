package com.systemwebstudio.wiring

import com.systemwebstudio.common.ProductionConfigValidator
import com.systemwebstudio.data.FixedResolver
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.postgres.PostgresTargetPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment

/**
 * B-C0-W-06 / D-C0-31 · where a PostgreSQL data source may point, as the production wiring builds it ([PostgresTargetPolicies.fromConfig]).
 * The local topology is the real one: platform DB 127.0.0.1:15432, apps DB 127.0.0.1:15434, the V1 local data target 127.0.0.1:15440 — three services on ONE host.
 * Pure: no Spring, no Docker, no DNS (a fixed resolver), so every decision is exact.
 */
class PostgresTargetPoliciesTests {
    private val platform = "jdbc:postgresql://127.0.0.1:15432/system_web_studio"
    private val appDb = "jdbc:postgresql://127.0.0.1:15434/appdb"
    private val target = "127.0.0.1:15440"
    private val resolver = FixedResolver(mapOf(
        "db.example.com" to listOf("93.184.216.34"), "evil.example.com" to listOf("127.0.0.1"), "mixed.example.com" to listOf("93.184.216.34", "127.0.0.1"),
        "localhost" to listOf("127.0.0.1"), "replica.corp.example.com" to listOf("10.1.1.1")
    ))

    private fun policy(allowed: List<String> = emptyList(), denied: List<String> = emptyList(), platformUrl: String = platform, apps: String? = appDb) =
        PostgresTargetPolicies.fromConfig(platformUrl, apps, allowed, denied)
    private fun code(block: () -> Unit): String? = try { block(); null } catch (e: ConnectorFailure) { e.code }
    private fun PostgresTargetPolicy.blocked(host: String, port: Int) = code { resolve(host, port, resolver) } == FailureCodes.ADDRESS_BLOCKED
    private fun PostgresTargetPolicy.addresses(host: String, port: Int) = resolve(host, port, resolver).map { it.hostAddress }

    // 1. no allow-list: private and loopback are blocked, whatever the port
    @Test fun `without an allow-list every private or loopback target is blocked`() {
        val p = policy()
        for ((h, port) in listOf("127.0.0.1" to 15440, "127.0.0.1" to 5432, "localhost" to 15440, "10.0.0.5" to 5432, "192.168.1.9" to 5432, "172.16.0.1" to 5432, "169.254.169.254" to 5432, "replica.corp.example.com" to 5432))
            assertThat(p.blocked(h, port)).describedAs("$h:$port").isTrue()
        assertThat(code { p.checkSyntax("127.0.0.1", 15440) }).isEqualTo(FailureCodes.INVALID_CONFIG)
        assertThat(code { p.checkSyntax("10.0.0.5", 5432) }).isEqualTo(FailureCodes.INVALID_CONFIG)
    }

    // 2 + 10. the exact allowed local target works; the V1 local profile names exactly that one
    @Test fun `the exact allowed local target is reachable`() {
        val p = policy(allowed = listOf(target))
        assertThat(p.addresses("127.0.0.1", 15440)).containsExactly("127.0.0.1")
        p.checkSyntax("127.0.0.1", 15440)
    }

    // 11. the platform DBs on the same host stay blocked, even when the allow-list is wrong or broad-looking
    @Test fun `the platform and apps databases stay blocked on the very same host`() {
        val p = policy(allowed = listOf(target))
        assertThat(p.blocked("127.0.0.1", 15432)).describedAs("platform DB").isTrue()
        assertThat(p.blocked("127.0.0.1", 15434)).describedAs("apps DB").isTrue()
        assertThat(code { p.checkSyntax("127.0.0.1", 15432) }).isEqualTo(FailureCodes.INVALID_CONFIG)
        assertThat(code { p.checkSyntax("127.0.0.1", 15434) }).isEqualTo(FailureCodes.INVALID_CONFIG)
        // an operator who allow-lists the platform endpoint by mistake still cannot reach it: deny wins over allow
        val mistaken = policy(allowed = listOf(target, "127.0.0.1:15432", "127.0.0.1:15434"))
        assertThat(mistaken.blocked("127.0.0.1", 15432)).isTrue()
        assertThat(mistaken.blocked("127.0.0.1", 15434)).isTrue()
        assertThat(mistaken.addresses("127.0.0.1", 15440)).containsExactly("127.0.0.1")
        // a name or an alias of the platform endpoint is caught by address + port
        assertThat(policy(allowed = listOf("localhost:15432", "evil.example.com:15432")).let { it.blocked("localhost", 15432) && it.blocked("evil.example.com", 15432) }).isTrue()
        // the target by another name is not the allow-listed endpoint
        assertThat(p.blocked("localhost", 15440)).isTrue()
    }

    // 3. denied wins over allowed
    @Test fun `a denied target is blocked even when it is allowed`() {
        val p = policy(allowed = listOf(target, "10.1.1.1:5432"), denied = listOf(target, "10.1.1.1"))
        assertThat(p.blocked("127.0.0.1", 15440)).isTrue()
        assertThat(p.blocked("10.1.1.1", 5432)).isTrue()
        assertThat(p.blocked("replica.corp.example.com", 5432)).describedAs("an alias of a denied address").isTrue()
    }

    // 4. a private host that is not allow-listed
    @Test fun `a private target that is not on the allow-list is blocked`() {
        val p = policy(allowed = listOf(target))
        for ((h, port) in listOf("127.0.0.1" to 15441, "127.0.0.1" to 5432, "127.0.0.2" to 15440, "10.0.0.5" to 15440, "replica.corp.example.com" to 15440))
            assertThat(p.blocked(h, port)).describedAs("$h:$port").isTrue()
    }

    // 5. the public behavior is unchanged by the allow-list
    @Test fun `a public target is judged exactly as before, on any port`() {
        val p = policy(allowed = listOf(target))
        assertThat(p.addresses("db.example.com", 5432)).containsExactly("93.184.216.34")
        assertThat(p.addresses("db.example.com", 6543)).containsExactly("93.184.216.34")
        p.checkSyntax("db.example.com", 5432)
        assertThat(policy().addresses("93.184.216.34", 5432)).containsExactly("93.184.216.34")
    }

    // 6. a name that resolves to a private / platform address
    @Test fun `a public-looking name that resolves to a private or platform address is blocked`() {
        val p = policy(allowed = listOf(target))
        assertThat(p.blocked("evil.example.com", 15440)).describedAs("resolves to the allow-listed IP but is not the allow-listed name").isTrue()
        assertThat(p.blocked("evil.example.com", 15432)).isTrue()
        assertThat(p.blocked("mixed.example.com", 5432)).describedAs("one bad address refuses the whole name").isTrue()
        // a platform database on a NON-loopback host: the whole host is denied, every port
        val remote = policy(platformUrl = "jdbc:postgresql://db.example.com:5432/studio", apps = null)
        assertThat(remote.blocked("db.example.com", 5432)).isTrue()
        assertThat(remote.blocked("db.example.com", 6543)).describedAs("another port of the platform host").isTrue()
        assertThat(remote.blocked("93.184.216.34", 6543)).describedAs("its address under another form").isTrue()
    }

    // 7. malformed hosts
    @Test fun `a malformed host is blocked`() {
        val p = policy(allowed = listOf(target))
        for (h in listOf("", " ", "a b", "127.0.0.1/8", "0x7f.1", "2130706433", "127.1", "..", "-x", "x".repeat(300), "a..b", "[::1]", "::1", "0.0.0.0", "user@127.0.0.1", "127.0.0.1:15440"))
            assertThat(code { p.resolve(h, 15440, resolver) }).describedAs("'$h'").isIn(FailureCodes.ADDRESS_BLOCKED, FailureCodes.HOST_UNRESOLVED)
        for (h in listOf("", "a b", "127.1", "999.1.1.1", "1.2.3"))  // "0x7f.1" is a valid-looking name: only the resolved address decides, and that is checked in resolve (above)
            assertThat(code { p.checkSyntax(h, 15440) }).describedAs("syntax '$h'").isEqualTo(FailureCodes.INVALID_CONFIG)
    }

    // the allow-list itself is validated, never guessed: a bad entry fails startup
    @Test fun `an allow-list entry that is not an exact endpoint fails construction`() {
        for (bad in listOf("127.0.0.1", "localhost", "10.0.0.0/8", "*", "*.local", "0.0.0.0:5432", "[::]:5432", "169.254.169.254:5432", "[fe80::1]:5432", "224.0.0.1:5432", "255.255.255.255:5432",
            "127.0.0.1:0", "127.0.0.1:70000", "127.0.0.1:abc", ":5432", "a b:5432", "db@host:5432", "host/path:5432", "0.1.2.3:5432"))
            assertThatThrownBy { policy(allowed = listOf(bad)) }.describedAs(bad).isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { policy(allowed = (1..21).map { "10.0.0.$it:5432" }) }.isInstanceOf(IllegalArgumentException::class.java)
        policy(allowed = listOf("127.0.0.1:15440", "[::1]:15440", "replica.corp.example.com:5432"))
        // comma lists as the @Value produces them, with blanks
        assertThat(policy(allowed = listOf(" 127.0.0.1:15440 , 10.1.1.1:5432 ", "")).addresses("127.0.0.1", 15440)).containsExactly("127.0.0.1")
    }

    @Test fun `a platform JDBC URL that cannot be read refuses to start - the deny-list is never empty by accident`() {
        assertThatThrownBy { PostgresTargetPolicies.fromConfig("", null, listOf(target), emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { PostgresTargetPolicies.fromConfig("not-a-jdbc-url", null, emptyList(), emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // 9. profiles: the base and prod files allow nothing; only the local file names the V1 local target
    private fun load(name: String): Map<String, Any?> = YamlPropertySourceLoader().load(name, ClassPathResource(name)).flatMap { ps -> (ps.source as Map<*, *>).entries.map { it.key.toString() to it.value } }.toMap()
    private val key = "app.data-platform.postgres-targets.allowed-private"

    @Test fun `base and prod configuration allow no private target, only the local profile names the V1 data target`() {
        assertThat(load("application.yml")[key]?.toString()).describedAs("base: empty default").isEqualTo("\${DATA_PLATFORM_POSTGRES_ALLOWED_PRIVATE:}")
        assertThat(load("application-prod.yml")).describedAs("prod does not set it, so it inherits the empty base").doesNotContainKey(key)
        assertThat(load("application-local.yml")[key]?.toString()).isEqualTo("\${DATA_PLATFORM_POSTGRES_ALLOWED_PRIVATE:127.0.0.1:15440}")
    }

    @Test fun `the production validator refuses a loopback allow-list and accepts an exact private endpoint`() {
        val strong = "Aa1-very-strong-secret-value-0001"
        fun env() = MockEnvironment().withProperty("spring.datasource.password", strong).withProperty("spring.data.redis.password", strong)
            .withProperty("spring.rabbitmq.password", strong).withProperty("app.storage.secret-key", strong).withProperty("server.servlet.session.cookie.secure", "true")
            .withProperty("app.cors.allowed-origins", "https://studio.example.com").withProperty("app.storage.public-endpoint", "https://files.example.com")
            .withProperty("app.forms.ip-salt", strong).withProperty("app.deploy.provider", "static")
        ProductionConfigValidator(env())
        ProductionConfigValidator(env().withProperty(key, "replica.corp.example.com:5432,10.1.2.3:5432"))
        for (loop in listOf("127.0.0.1:15440", "localhost:5432", "[::1]:5432", "10.1.2.3:5432,127.0.0.1:15440"))
            assertThatThrownBy { ProductionConfigValidator(env().withProperty(key, loop)) }.describedAs(loop).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("loopback")
    }
}
