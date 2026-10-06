package com.systemwebstudio.data.datasource.postgres

import com.systemwebstudio.data.FixedResolver
import com.systemwebstudio.data.InMemoryQueryCatalog
import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceTypes
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.data.query.QueryRequest
import com.systemwebstudio.data.query.SqlQueryDefinition
import com.systemwebstudio.tenancy.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Security of every PostgreSQL session, tested against a recording fake JDBC connection (no database): the role/privilege preflight on EVERY
 * connection, session setup order and cleanup, read-only/row/time limits, TLS properties, the deny-list failing closed.
 * Real-server behaviour is covered by PostgresConnectorIntegrationTests (Testcontainers) which has not been run in this environment.
 */
class PostgresSessionSecurityTests {
    private val tenant = UUID.randomUUID()
    private val dsId = UUID.randomUUID()
    private val password = "hunter2-TOPSECRET"
    private val cred = ResolvedCredential.of(mapOf("username" to "reader_bob", "password" to password))
    private val config = mapOf("host" to "db.example.com", "database" to "shop", "schemas" to "shop")
    private val ref = DataSourceRef(dsId, tenant, DataSourceTypes.POSTGRES, config)
    private val resolver = FixedResolver(mapOf("db.example.com" to listOf("93.184.216.34")))
    private val query = SqlQueryDefinition("orders", tenant, dsId, "SELECT id FROM t", maxRows = 3)
    private fun request() = QueryRequest("orders", emptyMap(), null, TenantContext(tenant, null))
    private fun failure(block: () -> Unit): ConnectorFailure { try { block() } catch (e: ConnectorFailure) { return e }; throw AssertionError("expected a ConnectorFailure") }

    /** hands out the given fake connections in order and counts how many were opened */
    private class Sequence(vararg dbs: FakePg) : PgConnectionFactory {
        private val queue = dbs.toMutableList(); val opened = AtomicInteger(); val targets = mutableListOf<PgTarget>()
        override fun open(target: PgTarget): java.sql.Connection { opened.incrementAndGet(); targets += target; return queue.removeAt(0).connection }
    }
    private fun connector(f: PgConnectionFactory, catalog: InMemoryQueryCatalog = InMemoryQueryCatalog(query)) = PostgresConnector(catalog, PostgresTargetPolicy(), resolver, f)

    // ---------------------------------------------------------------- role preflight on every session

    @Test fun `the role check runs on every connection - a role that turns superuser later is caught on its next session`() {
        val first = FakePg.database(data = FakeRows.ids(2))
        val second = FakePg.database(role = FakePg.safeRole(3 to true, 9 - 1 to true))   // superuser by the time of the 2nd connection
        val third = FakePg.database(data = FakeRows.ids(1))
        val seq = Sequence(first, second, third)
        val exec = connector(seq).executor()

        assertThat(exec.execute(request(), ref, cred).rows).hasSize(2)
        assertThat(failure { exec.execute(request(), ref, cred) }.code).isEqualTo(FailureCodes.ROLE_TOO_PRIVILEGED)
        assertThat(exec.execute(request(), ref, cred).rows).hasSize(1)

        assertThat(seq.opened.get()).isEqualTo(3)
        for (db in listOf(first, second, third)) assertThat(db.executed.count { FakePg.isPreflight(it) }).isEqualTo(1)      // once per connection
        assertThat(second.executed.none { it.startsWith("SELECT * FROM (") }).isTrue()                                    // the caller's SQL never reached the unsafe session
    }

    @Test fun `every unsafe role attribute aborts the session before anything else runs`() {
        // index → what it reports: 2 is_superuser, 3 rolsuper, 4 createrole, 5 createdb, 6 replication, 7 bypassrls, 8 reaches a superuser role, 9 member of a pg_* role
        val unsafe = mapOf(2 to "on", 3 to true, 4 to true, 5 to true, 6 to true, 7 to true, 8 to true, 9 to true)
        for ((index, value) in unsafe) {
            val db = FakePg.database(role = FakePg.safeRole(index to value), data = FakeRows.ids(1))
            val r = connector(Sequence(db)).test(ref, cred) as ConnectionTestResult.Failed
            assertThat(r.code).isEqualTo(FailureCodes.ROLE_TOO_PRIVILEGED)
            assertThat(r.message).doesNotContain("reader_bob").doesNotContain(password).doesNotContain("pg_read_server_files")
            assertThat(db.executed.none { it.contains("has_table_privilege") }).isTrue()
        }
    }

    @Test fun `unexpected session state fails closed`() {
        val noRow = FakePg.database(role = null)
        assertThat((connector(Sequence(noRow)).test(ref, cred) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.ROLE_TOO_PRIVILEGED)
        val writable = FakePg.database(role = FakePg.safeRole(0 to "off"))
        assertThat((connector(Sequence(writable)).test(ref, cred) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.READ_ONLY_VIOLATION)
        val backslashes = FakePg.database(role = FakePg.safeRole(1 to "off"))             // standard_conforming_strings=off would invalidate SqlGuard's lexing
        assertThat((connector(Sequence(backslashes)).test(ref, cred) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.READ_ONLY_VIOLATION)
        val unknown = FakePg.database(role = FakePg.safeRole(2 to "maybe"))
        assertThat((connector(Sequence(unknown)).test(ref, cred) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.ROLE_TOO_PRIVILEGED)
        // the check query itself failing is not a pass
        val denied = FakePg { sql -> if (FakePg.isPreflight(sql)) throw SQLException("permission denied for relation pg_roles", "42501") else FakeRows.one(1) }
        assertThat((connector(Sequence(denied)).test(ref, cred) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(denied.executed.none { it.startsWith("SELECT * FROM (") }).isTrue()
    }

    @Test fun `discovery and the connection test use the same checked session as queries`() {
        val unsafe = { FakePg.database(role = FakePg.safeRole(3 to true)) }
        val d = unsafe()
        assertThat(failure { connector(Sequence(d)).discovery().discover(ref, cred) }.code).isEqualTo(FailureCodes.ROLE_TOO_PRIVILEGED)
        assertThat(d.executed.none { it.contains("information_schema") }).isTrue()
        val ok = FakePg.database()
        assertThat(connector(Sequence(ok)).discovery().discover(ref, cred).entities).isEmpty()
        assertThat(ok.executed.count { FakePg.isPreflight(it) }).isEqualTo(1)
        val t = FakePg.database(writable = 2)
        val r = connector(Sequence(t)).test(ref, cred) as ConnectionTestResult.Ok
        assertThat(r.warnings.single()).contains("write")
        assertThat(t.executed.count { FakePg.isPreflight(it) }).isEqualTo(1)                  // test() adds no second, separate check
    }

    // ---------------------------------------------------------------- session set-up and clean-up

    @Test fun `a session is read-only, warmed up and verified before the caller's statement, and always rolled back and closed`() {
        val db = FakePg.database(data = FakeRows.ids(1))
        connector(Sequence(db)).executor().execute(request(), ref, cred)
        assertThat(db.events.take(2)).containsExactly("autoCommit=false", "readOnly=true")
        assertThat(db.executed[0]).isEqualTo("SELECT 1")                                      // closes the SET TRANSACTION READ WRITE window
        assertThat(FakePg.isPreflight(db.executed[1])).isTrue()
        assertThat(db.executed[2]).startsWith("SELECT * FROM (")
        assertThat(db.events.takeLast(2)).containsExactly("rollback", "close")                // nothing committed

        val bad = FakePg.database(role = FakePg.safeRole(3 to true))
        failure { connector(Sequence(bad)).executor().execute(request(), ref, cred) }
        assertThat(bad.events.takeLast(2)).containsExactly("rollback", "close")                // cleaned up on the failure path too

        val boom = FakePg { sql -> when { sql == "SELECT 1" -> FakeRows.one(1); FakePg.isPreflight(sql) -> FakeRows.one(*FakePg.safeRole().toTypedArray()); else -> throw SQLException("canceling statement due to statement timeout", "57014") } }
        assertThat(failure { connector(Sequence(boom)).executor().execute(request(), ref, cred) }.code).isEqualTo(FailureCodes.TIMEOUT)
        assertThat(boom.events.takeLast(2)).containsExactly("rollback", "close")
    }

    @Test fun `row limit, paging and statement timeout are applied by the executor`() {
        val db = FakePg.database(data = FakeRows.ids(10))                                     // the definition allows 3 rows; the server "returns" 10
        val r = connector(Sequence(db)).executor().execute(request(), ref, cred)
        assertThat(r.rows).hasSize(3)
        assertThat(r.truncated).isTrue()
        assertThat(db.executed.last()).isEqualTo("SELECT * FROM (SELECT id FROM t) AS xweb_q LIMIT ? OFFSET ?")
        assertThat(db.maxRows).isEqualTo(4)                                                    // limit + 1 so truncation is detectable
        assertThat(db.params.takeLast(2)).containsExactly(4, 0)
        assertThat(db.queryTimeout).isGreaterThanOrEqualTo(1)
    }

    // ---------------------------------------------------------------- TLS and driver properties

    private fun target(cfg: Map<String, String> = config): PgTarget =
        PgTarget(PostgresConnectorConfig.parse(cfg), listOf(InetAddress.getByName("93.184.216.34")), "reader_bob", password)

    @Test fun `TLS is verify-full with host-name verification, never require`() {
        val p = PgConnectionProperties.build(target(), enforceTls = true)
        assertThat(p.getProperty("ssl")).isEqualTo("true")
        assertThat(p.getProperty("sslmode")).isEqualTo("verify-full")
        assertThat(p.getProperty("sslfactory")).isEqualTo("org.postgresql.ssl.DefaultJavaSSLFactory")
        assertThat(p.getProperty("sslrootcert")).isNull()                                      // a tenant cannot point the driver at a file
        assertThat(p.values.map { it.toString() }.none { it == "require" || it == "verify-ca" || it == "prefer" || it == "allow" }).isTrue()
        assertThat(JdbcPgConnectionFactory().enforceTls).isTrue()                              // the public constructor cannot switch TLS off
    }

    @Test fun `configuration accepts only verify-full`() {
        val c = connector(Sequence())
        c.validateConfig(config)
        c.validateConfig(config + ("sslmode" to "verify-full"))
        assertThat(PostgresConnectorConfig.parse(config).sslMode).isEqualTo("verify-full")
        for (mode in listOf("require", "verify-ca", "prefer", "allow", "disable", "REQUIRE", ""))
            assertThat(failure { c.validateConfig(config + ("sslmode" to mode)) }.code).isEqualTo(FailureCodes.INVALID_CONFIG)
    }

    @Test fun `driver properties carry the limits, the pinned address and the credential - the URL carries none of them`() {
        val t = target(config + ("timeoutMs" to "4000"))
        val p = PgConnectionProperties.build(t, enforceTls = true)
        val url = PgConnectionProperties.url(t.config)
        assertThat(url).isEqualTo("jdbc:postgresql://db.example.com:5432/shop")
        assertThat(url).doesNotContain(password).doesNotContain("reader_bob").doesNotContain("ssl")
        assertThat(p.getProperty("password")).isEqualTo(password)
        val options = p.getProperty("options")
        assertThat(options).contains("default_transaction_read_only=on").contains("statement_timeout=4000").contains("lock_timeout=").contains("idle_in_transaction_session_timeout=4000")
        assertThat(p.getProperty("connectTimeout")).isEqualTo("4")
        assertThat(p.getProperty("loginTimeout")).isEqualTo("4")
        assertThat(p.getProperty("socketTimeout")).isEqualTo("4")
        assertThat(p.getProperty("socketFactory")).isEqualTo(PinnedSocketFactory::class.java.name)
        assertThat(p.getProperty(PinnedSocketFactory.PROP)).isEqualTo("93.184.216.34")
        assertThat(p.getProperty("gssEncMode")).isEqualTo("disable")
        assertThat(PgConnectionProperties.build(t, enforceTls = false).getProperty("sslmode")).isEqualTo("disable")      // only an explicit test-only construction
    }

    // ---------------------------------------------------------------- deny-list fails closed

    @Test fun `a denied host whose address cannot be determined blocks the connection instead of being skipped`() {
        val res = FixedResolver(mapOf("db.example.com" to listOf("93.184.216.34")))        // knows nothing about the denied name
        val policy = PostgresTargetPolicy(deniedHosts = setOf("platform-db.internal-name.example.com"))
        assertThat(failure { policy.resolve("db.example.com", res) }.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
        val throwing = object : com.systemwebstudio.data.datasource.HostResolver {
            override fun resolve(host: String): List<InetAddress> = if (host == "db.example.com") listOf(InetAddress.getByName("93.184.216.34")) else error("dns down")
        }
        assertThat(failure { policy.resolve("db.example.com", throwing) }.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
        // a denied entry that resolves still only blocks what shares its address
        val ok = FixedResolver(mapOf("db.example.com" to listOf("93.184.216.34"), "platform-db.internal-name.example.com" to listOf("198.51.100.7")))
        assertThat(policy.resolve("db.example.com", ok).map { it.hostAddress }).containsExactly("93.184.216.34")
    }

    @Test fun `a denied literal address blocks every name that resolves to it`() {
        val res = FixedResolver(mapOf("alias.example.com" to listOf("93.184.216.34"), "other.example.com" to listOf("93.184.216.35")))
        val policy = PostgresTargetPolicy(deniedHosts = setOf("93.184.216.34"))
        assertThat(failure { policy.resolve("alias.example.com", res) }.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
        assertThat(failure { policy.resolve("93.184.216.34", res) }.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
        assertThat(policy.resolve("other.example.com", res)).hasSize(1)
    }

    @Test fun `JDBC URLs are read completely - failover lists, userinfo, IPv6 and implied localhost`() {
        assertThat(PostgresTargetPolicy.hostsOfJdbcUrls("jdbc:postgresql://a.example:5432,B.example:5433/db?ssl=true")).containsExactlyInAnyOrder("a.example", "b.example")
        assertThat(PostgresTargetPolicy.hostsOfJdbcUrls("jdbc:postgresql://user:pw@c.example/db")).containsExactly("c.example")
        assertThat(PostgresTargetPolicy.hostsOfJdbcUrls("jdbc:postgresql://[2001:db8::1]:5432/db")).containsExactly("2001:db8::1")
        assertThat(PostgresTargetPolicy.hostsOfJdbcUrls("jdbc:postgresql:db")).containsExactly("localhost")
        assertThat(PostgresTargetPolicy.hostsOfJdbcUrls("jdbc:postgresql:///db")).containsExactly("localhost")
    }

    @Test fun `the production policy refuses to be built from a missing or unreadable platform URL`() {
        for (bad in listOf<Array<String?>>(arrayOf(null), arrayOf(""), arrayOf("  "), arrayOf("garbage"), arrayOf("jdbc:mysql://x/db"), arrayOf("jdbc:postgresql://good:5432/db", null), arrayOf("jdbc:postgresql://,/db")))
            assertThatThrownBy { PostgresTargetPolicy.denyingPlatformDatabases(*bad) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { PostgresTargetPolicy.denyingPlatformDatabases() }.isInstanceOf(IllegalArgumentException::class.java)
        val policy = PostgresTargetPolicy.denyingPlatformDatabases("jdbc:postgresql://platform-db.example.com:5432/studio", "jdbc:postgresql://apps-db.example.com:5432,apps-db2.example.com:5432/apps")
        val res = FixedResolver(mapOf("platform-db.example.com" to listOf("93.184.216.40"), "apps-db.example.com" to listOf("93.184.216.41"), "apps-db2.example.com" to listOf("93.184.216.42"), "ok.example.com" to listOf("93.184.216.50")))
        for (h in listOf("platform-db.example.com", "apps-db.example.com", "apps-db2.example.com")) assertThat(failure { policy.resolve(h, res) }.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
        assertThat(policy.resolve("ok.example.com", res)).hasSize(1)
    }
}
