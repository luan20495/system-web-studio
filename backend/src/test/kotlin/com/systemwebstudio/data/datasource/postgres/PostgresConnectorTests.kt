package com.systemwebstudio.data.datasource.postgres

import com.systemwebstudio.data.AllowAllAddresses
import com.systemwebstudio.data.FixedResolver
import com.systemwebstudio.data.InMemoryQueryCatalog
import com.systemwebstudio.data.LogCapture
import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceTypes
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.QueryRequest
import com.systemwebstudio.data.query.SqlQueryDefinition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.sql.SQLException
import java.util.Properties
import java.util.UUID

/** Everything about the PostgreSQL connector that does not need a database (the DB-backed cases are in PostgresConnectorIntegrationTests). */
class PostgresConnectorTests {
    private val tenant = UUID.randomUUID()
    private val dsId = UUID.randomUUID()
    private val password = "hunter2-TOPSECRET"
    private val cred = ResolvedCredential.of(mapOf("username" to "reader_bob", "password" to password))
    private val config = mapOf("host" to "db.example.com", "database" to "shop", "schemas" to "shop, reporting")
    private fun ref(cfg: Map<String, String> = config) = DataSourceRef(dsId, tenant, DataSourceTypes.POSTGRES, cfg)
    private fun failure(block: () -> Unit): ConnectorFailure { try { block() } catch (e: ConnectorFailure) { return e }; throw AssertionError("expected a ConnectorFailure") }
    private val publicResolver = FixedResolver(mapOf("db.example.com" to listOf("93.184.216.34")))

    /** a factory that must not be reached, or throws a canned error; counts calls */
    private class Factory(private val onOpen: () -> java.sql.Connection) : PgConnectionFactory {
        var opened = 0; var last: PgTarget? = null
        override fun open(target: PgTarget): java.sql.Connection { opened++; last = target; return onOpen() }
    }
    private fun connector(f: Factory, policy: PostgresTargetPolicy = PostgresTargetPolicy(), resolver: FixedResolver = publicResolver, catalog: InMemoryQueryCatalog = InMemoryQueryCatalog()) =
        PostgresConnector(catalog, policy, resolver, f)

    // ---------------------------------------------------------------- configuration

    @Test fun `configuration is strict and TLS cannot be switched off`() {
        val c = connector(Factory { error("not reached") })
        c.validateConfig(config)
        c.validateConfig(config + ("port" to "6432") + ("sslmode" to "verify-full"))
        c.validateConfig(mapOf("host" to "93.184.216.34", "database" to "x"))
        val bad = listOf(
            config + ("sslmode" to "disable"), config + ("sslmode" to "allow"), config + ("sslmode" to "prefer"),
            config + ("host" to "db.example.com:5432"), config + ("host" to "db.example.com/x"), config + ("host" to "a.example.com,b.example.com"), config + ("host" to "u:p@db.example.com"),
            config + ("host" to "db.example.com?ssl=false"), config + ("host" to "[::1]"), config + ("host" to ""),
            config + ("database" to "shop?user=postgres"), config + ("database" to "a/b"), config + ("database" to ""),
            config + ("port" to "0"), config + ("port" to "70000"), config + ("port" to "x"),
            config + ("schemas" to "public; drop"), config + ("schemas" to "a b"), config + ("timeoutMs" to "1"), config + ("maxRows" to "100000"),
            config + ("password" to password), config + ("user" to "postgres"), config - "database", config - "host"
        )
        for (cfg in bad) {
            val f = failure { c.validateConfig(cfg) }
            assertThat(f.code).isEqualTo(FailureCodes.INVALID_CONFIG)
            assertThat(f.message).doesNotContain("TOPSECRET")
        }
    }

    @Test fun `credential values that look like driver URL syntax are refused`() {
        val f = Factory { error("not reached") }
        for (user in listOf("a&b", "a=b", "bob;x", "a b", "a/b", "", "x".repeat(64)))
            assertThat((connector(f).test(ref(), ResolvedCredential.of(mapOf("username" to user, "password" to password))) as? ConnectionTestResult.Failed)?.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
        assertThat((connector(f).test(ref(), ResolvedCredential.of(mapOf("username" to "bob"))) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
        assertThat((connector(f).test(ref(), ResolvedCredential.NONE) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
        assertThat(f.opened).isEqualTo(0)
    }

    // ---------------------------------------------------------------- where it may connect (platform DB / apps DB stay out)

    @Test fun `by default only public addresses are reachable, so the platform databases are not`() {
        val f = Factory { error("not reached") }
        val internal = mapOf("postgres" to listOf("172.18.0.2"), "appdb" to listOf("172.18.0.3"), "db.corp.example.com" to listOf("10.1.2.3"), "meta.example.com" to listOf("169.254.169.254"),
            "loop.example.com" to listOf("127.0.0.1"), "mixed.example.com" to listOf("93.184.216.34", "10.0.0.9"))
        val resolver = FixedResolver(internal)
        for (host in listOf("localhost", "postgres", "appdb", "db.corp.example.com", "meta.example.com", "loop.example.com", "mixed.example.com", "127.0.0.1", "10.0.0.5", "192.168.1.1", "169.254.169.254", "db.internal", "pg.local")) {
            val r = connector(f, resolver = resolver).test(ref(config + ("host" to host)), cred) as ConnectionTestResult.Failed
            assertThat(r.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
            assertThat(r.message).doesNotContain(host)
        }
        // the syntactic part of the policy also refuses them when the data source is saved (no DNS involved)
        for (host in listOf("localhost", "postgres", "appdb", "127.0.0.1", "10.0.0.5", "192.168.1.1", "169.254.169.254", "db.internal", "pg.local"))
            assertThat(failure { connector(f, resolver = resolver).validateConfig(config + ("host" to host)) }.code).isEqualTo(FailureCodes.INVALID_CONFIG)
        assertThat(f.opened).isEqualTo(0)                              // refused before any connection attempt
    }

    @Test fun `a denied host stays denied even when it is public or allow-listed, and aliases of it are caught by address`() {
        val resolver = FixedResolver(mapOf("platform-db.example.com" to listOf("93.184.216.34"), "alias.example.com" to listOf("93.184.216.34"), "replica.corp.example.com" to listOf("10.1.1.1")))
        val policy = PostgresTargetPolicy(allowedPrivateHosts = setOf("platform-db.example.com:5432", "replica.corp.example.com:5432"), deniedHosts = setOf("platform-db.example.com"))
        assertThat(failure { policy.resolve("platform-db.example.com", 5432, resolver) }.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
        assertThat(failure { policy.resolve("alias.example.com", 5432, resolver) }.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)      // same address as a denied host
        assertThat(failure { policy.checkSyntax("platform-db.example.com", 5432) }.code).isEqualTo(FailureCodes.INVALID_CONFIG)
    }

    @Test fun `a private host is reachable only through the explicit server-side allow-list`() {
        val resolver = FixedResolver(mapOf("replica.corp.example.com" to listOf("10.1.1.1"), "other.corp.example.com" to listOf("10.1.1.2")))
        val policy = PostgresTargetPolicy(allowedPrivateHosts = setOf("replica.corp.example.com:5432"))
        assertThat(policy.resolve("replica.corp.example.com", 5432, resolver).map { it.hostAddress }).containsExactly("10.1.1.1")
        assertThat(failure { policy.resolve("other.corp.example.com", 5432, resolver) }.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
        policy.checkSyntax("replica.corp.example.com", 5432)
        assertThat(PostgresTargetPolicy().let { p -> failure { p.resolve("replica.corp.example.com", 5432, resolver) }.code }).isEqualTo(FailureCodes.ADDRESS_BLOCKED)   // no allow-list by default
    }

    @Test fun `the platform's own JDBC URLs yield the hosts to deny`() {
        assertThat(PostgresTargetPolicy.hostsOfJdbcUrls("jdbc:postgresql://postgres:5432/studio", "jdbc:postgresql://AppDB:5432/postgres?ssl=true", null, "garbage"))
            .containsExactlyInAnyOrder("postgres", "appdb")
    }

    // ---------------------------------------------------------------- the connection path and what it may reveal

    @Test fun `driver errors are mapped by SQLSTATE and their text never travels`() {
        val cases = mapOf("28P01" to FailureCodes.AUTH_REJECTED, "28000" to FailureCodes.AUTH_REJECTED, "08001" to FailureCodes.CONNECT_FAILED, "08006" to FailureCodes.CONNECT_FAILED,
            "3D000" to FailureCodes.CONNECT_FAILED, "53300" to FailureCodes.CONNECT_FAILED, "57014" to FailureCodes.TIMEOUT, "25006" to FailureCodes.READ_ONLY_VIOLATION,
            "42501" to FailureCodes.PERMISSION_DENIED, "42P01" to FailureCodes.QUERY_FAILED, "XX000" to FailureCodes.QUERY_FAILED)
        for ((state, code) in cases) {
            LogCapture().use { logs ->
                val f = Factory { throw SQLException("FATAL: password authentication failed for user \"reader_bob\" host=10.0.0.9 password=$password relation \"secret_customers\"", state) }
                val r = connector(f).test(ref(), cred) as ConnectionTestResult.Failed
                assertThat(r.code).isEqualTo(code)
                for (leak in listOf(password, "reader_bob", "10.0.0.9", "secret_customers", "db.example.com")) { assertThat(r.message).doesNotContain(leak); assertThat(logs.text).doesNotContain(leak) }
            }
        }
    }

    @Test fun `a socket timeout or an unexpected exception from the driver is a fixed-text failure`() {
        LogCapture().use { logs ->
            val timeout = Factory { throw SQLException("conn", "08001", java.net.SocketTimeoutException("read timed out to 10.0.0.9")) }
            assertThat((connector(timeout).test(ref(), cred) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.TIMEOUT)
            val boom = Factory { throw IllegalStateException("jdbc:postgresql://db.example.com/shop?user=reader_bob&password=$password") }
            val r = connector(boom).test(ref(), cred) as ConnectionTestResult.Failed
            assertThat(r.code).isEqualTo(FailureCodes.CONNECT_FAILED)
            assertThat(r.message).doesNotContain(password); assertThat(logs.text).doesNotContain(password)
        }
    }

    @Test fun `the connection target holds the checked addresses and prints nothing`() {
        val f = Factory { throw SQLException("x", "08001") }
        connector(f).test(ref(), cred)
        val t = f.last!!
        assertThat(t.addresses.map { it.hostAddress }).containsExactly("93.184.216.34")
        assertThat(t.toString()).doesNotContain(password)
        assertThat(t.user).isEqualTo("reader_bob")
        assertThat(t.config.schemas).containsExactly("shop", "reporting")
    }

    @Test fun `a query is rejected by the guard before a connection is opened`() {
        val def = SqlQueryDefinition("bad", tenant, dsId, "SELECT 1; DROP TABLE users")
        val f = Factory { error("not reached") }
        val e = connector(f, catalog = InMemoryQueryCatalog(def)).executor()
        assertThat(failure { e.execute(QueryRequest("bad", emptyMap(), null, TenantContext(tenant, null)), ref(), cred) }.code).isEqualTo(FailureCodes.INVALID_QUERY)
        assertThat(f.opened).isEqualTo(0)
        // undeclared parameter, unknown query, other tenant
        val undeclared = SqlQueryDefinition("u", tenant, dsId, "SELECT :nope")
        assertThat(failure { connector(f, catalog = InMemoryQueryCatalog(undeclared)).executor().execute(QueryRequest("u", emptyMap(), null, TenantContext(tenant, null)), ref(), cred) }.code).isEqualTo(FailureCodes.INVALID_QUERY)
        assertThat(failure { e.execute(QueryRequest("missing", emptyMap(), null, TenantContext(tenant, null)), ref(), cred) }.code).isEqualTo(FailureCodes.QUERY_NOT_FOUND)
        assertThat(failure { e.execute(QueryRequest("bad", emptyMap(), null, TenantContext(UUID.randomUUID(), null)), ref(), cred) }.code).isEqualTo(FailureCodes.TENANT_MISMATCH)
        assertThat(f.opened).isEqualTo(0)
    }

    // ---------------------------------------------------------------- pinning the driver's socket

    @Test fun `the socket factory refuses to exist without a pinned address`() {
        assertThatThrownBy { PinnedSocketFactory(Properties()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { PinnedSocketFactory("") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { PinnedSocketFactory("db.example.com") }.isInstanceOf(IllegalArgumentException::class.java)       // a name would mean a DNS lookup: literals only
    }

    @Test fun `whatever host the driver asks for, the socket connects to the pinned address`() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
            Thread { runCatching { server.accept().close(); accepted.set(true) } }.apply { isDaemon = true }.start()
            val props = Properties().apply { setProperty(PinnedSocketFactory.PROP, "127.0.0.1") }
            PinnedSocketFactory(props).createSocket().use { s ->
                // unresolved name that would fail (or, in a rebinding attack, point elsewhere) if the factory honoured it
                s.connect(InetSocketAddress.createUnresolved("rebound.example.invalid", server.localPort), 2_000)
                assertThat(s.isConnected).isTrue()
                assertThat((s.remoteSocketAddress as InetSocketAddress).address.hostAddress).isEqualTo("127.0.0.1")
            }
            val deadline = System.currentTimeMillis() + 2_000
            while (!accepted.get() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertThat(accepted.get()).isTrue()
        }
    }

    @Test fun `the string-argument form used by older driver versions pins the same way`() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            Thread { runCatching { server.accept().close() } }.apply { isDaemon = true }.start()
            PinnedSocketFactory("127.0.0.1").createSocket("ignored.example.invalid", server.localPort).use { assertThat(it.isConnected).isTrue() }
        }
    }

    // ---------------------------------------------------------------- types

    @Test fun `JSON null is a real JSON null`() {
        assertThat(DataJson.NULL.isNull).isTrue()
    }
}
