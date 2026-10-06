package com.systemwebstudio.data.datasource.postgres

import com.systemwebstudio.data.InMemoryQueryCatalog
import com.systemwebstudio.data.LogCapture
import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceTypes
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.MutationExecRequest
import com.systemwebstudio.data.datasource.MutationOutcome
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.data.datasource.SystemHostResolver
import com.systemwebstudio.data.gateway.DefaultDataGateway
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.net.ServerSocket
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B-C0-W-04 · the production writable PostgreSQL connector against a REAL server (Testcontainers, the image the platform tests use). Skipped when Docker is
 * not available. The same flows without a database (SQL construction, error mapping, session flow) are PostgresMutationTests; the full chain through the
 * Management API and the DataGateway is DataWritableE2ETests.
 *
 * The container speaks plain TCP, so the connector is built with `JdbcPgConnectionFactory(enforceTls = false)` (a code-only switch, never a configuration
 * value) and the container's host is allow-listed explicitly — exactly as in PostgresConnectorIntegrationTests.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresWritableIntegrationTests {
    private lateinit var pg: PostgreSQLContainer
    private val tenant = UUID.randomUUID()
    private val dsId = UUID.randomUUID()
    private val rwPass = "rw-pass-TOPSECRET-2"
    private val roPass = "ro-pass-TOPSECRET-1"
    private val ghostPass = "ghost-pass-TOPSECRET-3"
    private val rw get() = ResolvedCredential.of(mapOf("username" to "rw_user", "password" to rwPass))
    private val ro get() = ResolvedCredential.of(mapOf("username" to "ro_user", "password" to roPass))
    private val ghost get() = ResolvedCredential.of(mapOf("username" to "ghost_user", "password" to ghostPass))
    private val superuser get() = ResolvedCredential.of(mapOf("username" to pg.username, "password" to pg.password))

    @BeforeAll fun start() {
        pg = PostgreSQLContainer(DockerImageName.parse("postgres:17.6")).apply { start() }
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
            c.createStatement().use { st ->
                st.execute("""CREATE SCHEMA shop; CREATE SCHEMA hidden;
                    CREATE TABLE shop.customers (id bigserial PRIMARY KEY, email text NOT NULL UNIQUE, name text NOT NULL,
                        credit numeric(10,2) NOT NULL DEFAULT 0 CONSTRAINT credit_not_negative CHECK (credit >= 0), active boolean NOT NULL DEFAULT true, joined date);
                    CREATE TABLE shop.orders (id bigserial PRIMARY KEY, customer_id bigint NOT NULL REFERENCES shop.customers(id), status text NOT NULL DEFAULT 'new', total numeric(10,2));
                    CREATE TABLE shop.slow (id serial PRIMARY KEY, tag text NOT NULL);
                    CREATE FUNCTION shop.slow_trg() RETURNS trigger LANGUAGE plpgsql AS ${'$'}f${'$'} BEGIN IF NEW.tag = 'SLOW' THEN PERFORM pg_sleep(4); END IF; RETURN NEW; END ${'$'}f${'$'};
                    CREATE TRIGGER slow_ins BEFORE INSERT ON shop.slow FOR EACH ROW EXECUTE FUNCTION shop.slow_trg();
                    CREATE TABLE hidden.secrets (k text PRIMARY KEY, v text); INSERT INTO hidden.secrets VALUES ('k', 'untouched');
                    CREATE ROLE rw_user LOGIN PASSWORD '$rwPass' NOSUPERUSER NOCREATEDB NOCREATEROLE;
                    GRANT USAGE ON SCHEMA shop TO rw_user; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA shop TO rw_user; GRANT USAGE ON ALL SEQUENCES IN SCHEMA shop TO rw_user;
                    CREATE ROLE ro_user LOGIN PASSWORD '$roPass' NOSUPERUSER NOCREATEDB NOCREATEROLE;
                    GRANT USAGE ON SCHEMA shop TO ro_user; GRANT SELECT ON ALL TABLES IN SCHEMA shop TO ro_user;
                    CREATE ROLE ghost_user LOGIN PASSWORD '$ghostPass' NOSUPERUSER NOCREATEDB NOCREATEROLE;
                    GRANT USAGE ON SCHEMA shop TO ghost_user;""")
            }
        }
    }
    @AfterAll fun stop() { pg.stop() }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun config(extra: Map<String, String> = emptyMap()) =
        mapOf("host" to pg.host, "port" to pg.getMappedPort(5432).toString(), "database" to pg.databaseName, "schemas" to "shop", "writable" to "true") + extra
    private fun ref(extra: Map<String, String> = emptyMap()) = DataSourceRef(dsId, tenant, DataSourceTypes.POSTGRES, config(extra))
    private val allowContainerHost get() = PostgresTargetPolicy(allowedPrivateHosts = setOf(pg.host.lowercase()))
    private fun connector() = PostgresConnector(InMemoryQueryCatalog(), allowContainerHost, SystemHostResolver, JdbcPgConnectionFactory(enforceTls = false))
    private fun failure(block: () -> Unit): ConnectorFailure { try { block() } catch (e: ConnectorFailure) { return e }; throw AssertionError("expected a ConnectorFailure") }
    private fun uniq(p: String) = p + "-" + UUID.randomUUID().toString().take(8)

    private fun str(name: String) = QueryParamSpec(name, ParamType.STRING, false)
    private fun reqStr(name: String) = QueryParamSpec(name, ParamType.STRING, true)
    private fun num(name: String) = QueryParamSpec(name, ParamType.NUMBER, false)
    private fun int(name: String, required: Boolean = true) = QueryParamSpec(name, ParamType.INTEGER, required)
    private fun bool(name: String) = QueryParamSpec(name, ParamType.BOOLEAN, false)
    private fun date(name: String) = QueryParamSpec(name, ParamType.DATE, false)

    private fun def(kind: MutationKind, target: String, vararg params: QueryParamSpec, id: String = "m1", tenantId: UUID = tenant) =
        MutationDefinition(id, tenantId, dsId, kind, target, params.toList())
    private fun run(d: MutationDefinition, params: Map<String, Any>, cfg: Map<String, String> = emptyMap(), cred: ResolvedCredential = rw, key: String? = null): MutationOutcome =
        connector().mutator().execute(MutationExecRequest(d, params, key), ref(cfg), cred)

    private val insertCustomer get() = def(MutationKind.CREATE, "shop.customers returning=id,email", reqStr("email"), reqStr("name"), num("credit"), bool("active"), date("joined"))
    private val updateCustomer get() = def(MutationKind.UPDATE, "shop.customers key=email", reqStr("email"), str("name"), num("credit"), bool("active"))
    private val deleteCustomer get() = def(MutationKind.DELETE, "shop.customers key=email", reqStr("email"))

    private fun rows(sql: String, vararg args: Any): List<Map<String, Any?>> = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs ->
                val md = rs.metaData; val out = ArrayList<Map<String, Any?>>()
                while (rs.next()) out += (1..md.columnCount).associate { md.getColumnLabel(it) to rs.getObject(it) }
                out
            }
        }
    }
    private fun customer(email: String) = rows("SELECT * FROM shop.customers WHERE email = ?", email).singleOrNull()
    private fun exec(sql: String) { DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c -> c.createStatement().use { it.execute(sql) } } }
    private fun total(table: String) = (rows("SELECT count(*) AS n FROM $table").single()["n"] as Long)
    private fun newCustomer(email: String = uniq("c") + "@example.com", credit: String = "0"): String { run(insertCustomer, mapOf("email" to email, "name" to "Seed", "credit" to BigDecimal(credit))); return email }
    private val definite = DefaultDataGateway.NOT_EXECUTED

    // ------------------------------------------------------------------------------------------------ INSERT / UPDATE / DELETE

    @Test fun `INSERT writes a real row with typed values, reports one affected row and returns the generated key`() {
        val email = uniq("ann") + "@example.com"
        val out = run(insertCustomer, mapOf("email" to email, "name" to "Ann", "credit" to BigDecimal("12.50"), "active" to false, "joined" to LocalDate.parse("2026-03-04")))
        assertThat(out.affected).isEqualTo(1L)
        val id = out.output!!.get("id").asLong()
        assertThat(out.output!!.get("email").asString()).isEqualTo(email)
        val row = customer(email)!!
        assertThat(row["id"]).isEqualTo(id); assertThat(row["name"]).isEqualTo("Ann"); assertThat(row["credit"]).isEqualTo(BigDecimal("12.50"))
        assertThat(row["active"]).isEqualTo(false); assertThat(row["joined"].toString()).isEqualTo("2026-03-04")
        // columns that were not supplied take the database default
        val defaults = run(insertCustomer, mapOf("email" to uniq("d") + "@example.com", "name" to "Dee"))
        assertThat(defaults.affected).isEqualTo(1L)
        val d = rows("SELECT credit, active FROM shop.customers WHERE id = ?", defaults.output!!.get("id").asLong()).single()
        assertThat(d["credit"]).isEqualTo(BigDecimal("0.00")); assertThat(d["active"]).isEqualTo(true)
    }

    @Test fun `INSERT without RETURNING reports the row count and no output`() {
        val d = def(MutationKind.CREATE, "shop.customers", reqStr("email"), reqStr("name"))
        val out = run(d, mapOf("email" to uniq("n") + "@example.com", "name" to "No Return"))
        assertThat(out.affected).isEqualTo(1L); assertThat(out.output).isNull()
    }

    @Test fun `UPDATE changes exactly the supplied columns of exactly the keyed row`() {
        val email = newCustomer(credit = "5"); val other = newCustomer(credit = "7")
        val before = customer(email)!!
        val out = run(updateCustomer, mapOf("email" to email, "name" to "Renamed", "credit" to BigDecimal("99.99")))
        assertThat(out.affected).isEqualTo(1L)
        val after = customer(email)!!
        assertThat(after["name"]).isEqualTo("Renamed"); assertThat(after["credit"]).isEqualTo(BigDecimal("99.99"))
        assertThat(after["active"]).isEqualTo(before["active"]); assertThat(after["id"]).isEqualTo(before["id"])
        assertThat(customer(other)!!["credit"]).describedAs("another row is untouched").isEqualTo(BigDecimal("7.00"))
        // a column that is not supplied is not touched
        run(updateCustomer, mapOf("email" to email, "active" to false))
        assertThat(customer(email)!!["name"]).isEqualTo("Renamed"); assertThat(customer(email)!!["active"]).isEqualTo(false)
    }

    @Test fun `UPDATE and DELETE of a key that matches nothing succeed with zero affected rows`() {
        val ghost = uniq("nobody") + "@example.com"
        assertThat(run(updateCustomer, mapOf("email" to ghost, "name" to "x")).affected).isEqualTo(0L)
        assertThat(run(deleteCustomer, mapOf("email" to ghost)).affected).isEqualTo(0L)
    }

    @Test fun `DELETE removes exactly the keyed row once`() {
        val email = newCustomer(); val keep = newCustomer()
        assertThat(run(deleteCustomer, mapOf("email" to email)).affected).isEqualTo(1L)
        assertThat(customer(email)).isNull(); assertThat(customer(keep)).isNotNull()
        assertThat(run(deleteCustomer, mapOf("email" to email)).affected).describedAs("the second delete finds nothing").isEqualTo(0L)
    }

    @Test fun `the same mutation run twice is NOT idempotent at the connector - that is the gateway's job, and a unique key makes the replay a definite rejection`() {
        val email = uniq("twice") + "@example.com"
        assertThat(run(insertCustomer, mapOf("email" to email, "name" to "A"), key = "idem-key-0001").affected).isEqualTo(1L)
        val again = failure { run(insertCustomer, mapOf("email" to email, "name" to "A"), key = "idem-key-0001") }
        assertThat(again.code).isEqualTo(FailureCodes.MUTATION_REJECTED)                                           // the database's own unique constraint
        assertThat(rows("SELECT count(*) AS n FROM shop.customers WHERE email = ?", email).single()["n"]).isEqualTo(1L)
    }

    // ------------------------------------------------------------------------------------------------ SQL injection

    @Test fun `hostile values are stored as data and never executed`() {
        val before = total("shop.customers")
        val payloads = listOf("x'); DROP TABLE shop.customers; --", "Robert'); DELETE FROM shop.customers; --", "\"; TRUNCATE shop.customers; --", "a\\'; DROP SCHEMA shop CASCADE; --")
        payloads.forEachIndexed { i, p ->
            val email = uniq("inj$i") + "@example.com"
            run(insertCustomer, mapOf("email" to email, "name" to p))
            assertThat(customer(email)!!["name"]).isEqualTo(p)
        }
        // as a KEY value: the hostile string matches nothing and changes nothing
        assertThat(run(deleteCustomer, mapOf("email" to "' OR '1'='1")).affected).isEqualTo(0L)
        assertThat(run(updateCustomer, mapOf("email" to "' OR '1'='1", "name" to "pwned")).affected).isEqualTo(0L)
        assertThat(total("shop.customers")).isEqualTo(before + payloads.size)
        assertThat(rows("SELECT count(*) AS n FROM shop.customers WHERE name = 'pwned'").single()["n"]).isEqualTo(0L)
    }

    // ------------------------------------------------------------------------------------------------ constraint violations (definite: nothing applied)

    @Test fun `unique, not-null, check and foreign-key violations are MUTATION_REJECTED with a fixed message, nothing applied, and the key may be released`() {
        val email = newCustomer(); val before = total("shop.customers")
        val unique = failure { run(insertCustomer, mapOf("email" to email, "name" to "dup-value-VISIBLE?")) }
        assertThat(unique.code).isEqualTo(FailureCodes.MUTATION_REJECTED); assertThat(unique.safeMessage).contains("unique")
        val notNull = failure { run(def(MutationKind.CREATE, "shop.customers", reqStr("email")), mapOf("email" to uniq("nn") + "@example.com")) }     // `name` is NOT NULL and not supplied
        assertThat(notNull.code).isEqualTo(FailureCodes.MUTATION_REJECTED); assertThat(notNull.safeMessage).contains("NOT NULL")
        val check = failure { run(insertCustomer, mapOf("email" to uniq("ck") + "@example.com", "name" to "n", "credit" to BigDecimal("-1"))) }
        assertThat(check.code).isEqualTo(FailureCodes.MUTATION_REJECTED); assertThat(check.safeMessage).contains("check")
        val fk = failure { run(def(MutationKind.CREATE, "shop.orders", int("customer_id"), str("status")), mapOf("customer_id" to 999_999_999L, "status" to "new")) }
        assertThat(fk.code).isEqualTo(FailureCodes.MUTATION_REJECTED); assertThat(fk.safeMessage).contains("foreign key")
        for (f in listOf(unique, notNull, check, fk)) {
            assertThat(f.code).describedAs("a database-reported rejection is definite").isIn(definite)
            assertThat(f.safeMessage).doesNotContain(email).doesNotContain("dup-value-VISIBLE?").doesNotContain("credit_not_negative").doesNotContain("customers_email_key").doesNotContain("rw_user")
        }
        assertThat(total("shop.customers")).isEqualTo(before)
    }

    @Test fun `a constraint violation on UPDATE leaves the row as it was`() {
        val email = newCustomer(credit = "10")
        val f = failure { run(updateCustomer, mapOf("email" to email, "credit" to BigDecimal("-5"))) }
        assertThat(f.code).isEqualTo(FailureCodes.MUTATION_REJECTED)
        assertThat(customer(email)!!["credit"]).isEqualTo(BigDecimal("10.00"))
    }

    @Test fun `deleting a row that another table still references is refused and nothing is removed`() {
        val email = newCustomer()
        val id = customer(email)!!["id"] as Long
        exec("INSERT INTO shop.orders (customer_id, total) VALUES ($id, 5)")
        val f = failure { run(deleteCustomer, mapOf("email" to email)) }
        assertThat(f.code).isEqualTo(FailureCodes.MUTATION_REJECTED); assertThat(f.safeMessage).contains("foreign key")
        assertThat(customer(email)).isNotNull()
    }

    @Test fun `a value that does not fit the column is INVALID_PARAMS and nothing is applied`() {
        val email = uniq("big") + "@example.com"
        val f = failure { run(insertCustomer, mapOf("email" to email, "name" to "n", "credit" to BigDecimal("123456789012.00"))) }          // numeric(10,2)
        assertThat(f.code).isEqualTo(FailureCodes.INVALID_PARAMS); assertThat(f.code).isIn(definite)
        assertThat(customer(email)).isNull()
    }

    // ------------------------------------------------------------------------------------------------ affected-row limit

    @Test fun `a change that would touch more rows than maxAffectedRows is rolled back and refused`() {
        val email = newCustomer(); val id = customer(email)!!["id"] as Long
        repeat(3) { exec("INSERT INTO shop.orders (customer_id, status) VALUES ($id, 'new')") }
        val update = def(MutationKind.UPDATE, "shop.orders key=customer_id", int("customer_id"), str("status"))
        val f = failure { run(update, mapOf("customer_id" to id, "status" to "shipped"), cfg = mapOf("maxAffectedRows" to "2")) }
        assertThat(f.code).isEqualTo(FailureCodes.MUTATION_REJECTED); assertThat(f.safeMessage).contains("maxAffectedRows")
        assertThat(rows("SELECT count(*) AS n FROM shop.orders WHERE customer_id = ? AND status = 'new'", id).single()["n"]).describedAs("nothing was committed").isEqualTo(3L)
        val ok = run(update, mapOf("customer_id" to id, "status" to "shipped"), cfg = mapOf("maxAffectedRows" to "3"))
        assertThat(ok.affected).isEqualTo(3L)
        assertThat(rows("SELECT count(*) AS n FROM shop.orders WHERE customer_id = ? AND status = 'shipped'", id).single()["n"]).isEqualTo(3L)
        val delete = def(MutationKind.DELETE, "shop.orders key=customer_id", int("customer_id"))
        assertThat(failure { run(delete, mapOf("customer_id" to id), cfg = mapOf("maxAffectedRows" to "1")) }.code).isEqualTo(FailureCodes.MUTATION_REJECTED)
        assertThat(rows("SELECT count(*) AS n FROM shop.orders WHERE customer_id = ?", id).single()["n"]).isEqualTo(3L)
    }

    // ------------------------------------------------------------------------------------------------ refused before anything runs

    @Test fun `a data source that is not writable, a schema that is not configured and a statement without a key are refused and change nothing`() {
        val email = newCustomer(); val before = total("shop.customers")
        val readOnly = failure { run(deleteCustomer, mapOf("email" to email), cfg = mapOf("writable" to "false")) }
        assertThat(readOnly.code).isEqualTo(FailureCodes.READ_ONLY_VIOLATION)
        assertThat(failure { run(deleteCustomer, mapOf("email" to email), cfg = mapOf("writable" to "")) }.code).isIn(FailureCodes.READ_ONLY_VIOLATION, FailureCodes.INVALID_CONFIG)
        val foreignSchema = failure { run(def(MutationKind.DELETE, "hidden.secrets key=k", reqStr("k")), mapOf("k" to "k")) }
        assertThat(foreignSchema.code).isEqualTo(FailureCodes.INVALID_CONFIG)
        assertThat(rows("SELECT v FROM hidden.secrets WHERE k = 'k'").single()["v"]).isEqualTo("untouched")
        assertThat(failure { run(def(MutationKind.DELETE, "shop.customers", reqStr("email")), mapOf("email" to email)) }.code).describedAs("a delete without a key would empty the table").isEqualTo(FailureCodes.INVALID_CONFIG)
        assertThat(failure { run(def(MutationKind.UPDATE, "shop.customers key=email", reqStr("email"), str("name")), mapOf("email" to email)) }.code).describedAs("nothing to set").isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(failure { run(def(MutationKind.SUBMIT, "shop.customers"), emptyMap()) }.code).isEqualTo(FailureCodes.MUTATION_UNSUPPORTED)
        assertThat(failure { run(deleteCustomer.copy(tenantId = UUID.randomUUID()), mapOf("email" to email)) }.code).isEqualTo(FailureCodes.TENANT_MISMATCH)
        assertThat(total("shop.customers")).isEqualTo(before); assertThat(customer(email)).isNotNull()
    }

    // ------------------------------------------------------------------------------------------------ roles and credentials

    @Test fun `a role without write privilege gets PERMISSION_DENIED and nothing is written`() {
        val email = uniq("denied") + "@example.com"
        for (c in listOf(ro, ghost)) {
            val f = failure { run(insertCustomer, mapOf("email" to email, "name" to "n"), cred = c) }
            assertThat(f.code).isEqualTo(FailureCodes.PERMISSION_DENIED); assertThat(f.code).isIn(definite)
        }
        assertThat(customer(email)).isNull()
        val existing = newCustomer()
        assertThat(failure { run(deleteCustomer, mapOf("email" to existing), cred = ro) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(customer(existing)).isNotNull()
    }

    @Test fun `a superuser is refused before the statement is sent`() {
        val email = uniq("su") + "@example.com"
        assertThat(failure { run(insertCustomer, mapOf("email" to email, "name" to "n"), cred = superuser) }.code).isEqualTo(FailureCodes.ROLE_TOO_PRIVILEGED)
        assertThat(customer(email)).isNull()
    }

    @Test fun `a wrong password is AUTH_REJECTED, definite, and neither the password nor the user appears anywhere`() {
        LogCapture().use { logs ->
            val bad = ResolvedCredential.of(mapOf("username" to "rw_user", "password" to "wrong-TOPSECRET-guess"))
            val f = failure { run(insertCustomer, mapOf("email" to uniq("a") + "@example.com", "name" to "n"), cred = bad) }
            assertThat(f.code).isEqualTo(FailureCodes.AUTH_REJECTED); assertThat(f.code).isIn(definite)
            for (text in listOf(f.safeMessage, f.toString(), f.message ?: "", logs.text)) {
                assertThat(text).doesNotContain("TOPSECRET").doesNotContain("rw_user").doesNotContain(pg.host + ":")
            }
        }
    }

    @Test fun `an incomplete credential is INVALID_CREDENTIAL before any connection`() {
        assertThat(failure { run(insertCustomer, mapOf("email" to "x@example.com", "name" to "n"), cred = ResolvedCredential.of(mapOf("username" to "rw_user"))) }.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
        assertThat(failure { run(insertCustomer, mapOf("email" to "x@example.com", "name" to "n"), cred = ResolvedCredential.NONE) }.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
    }

    // ------------------------------------------------------------------------------------------------ connection failure, timeout, ambiguous outcome

    @Test fun `an unreachable database is CONNECT_FAILED and carries no address or credential`() {
        val closed = ServerSocket(0).use { it.localPort }
        LogCapture().use { logs ->
            val f = failure { run(insertCustomer, mapOf("email" to uniq("c") + "@example.com", "name" to "n"), cfg = mapOf("port" to closed.toString())) }
            assertThat(f.code).isEqualTo(FailureCodes.CONNECT_FAILED)
            for (text in listOf(f.safeMessage, f.toString(), logs.text)) assertThat(text).doesNotContain("TOPSECRET").doesNotContain("rw_user").doesNotContain(":$closed")
        }
    }

    @Test fun `a statement that runs past the time limit is TIMEOUT, is stopped by the server, and the outcome is treated as unknown`() {
        val slow = def(MutationKind.CREATE, "shop.slow", reqStr("tag"))
        val started = System.nanoTime()
        val f = failure { run(slow, mapOf("tag" to "SLOW"), cfg = mapOf("timeoutMs" to "500")) }
        assertThat(f.code).isEqualTo(FailureCodes.TIMEOUT)
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(8_000)
        assertThat(f.code).describedAs("a timeout is never 'nothing applied': the key stays reserved as UNKNOWN").isNotIn(definite)
        assertThat(f.safeMessage).contains("may or may not")
        Thread.sleep(500)
        assertThat(rows("SELECT count(*) AS n FROM shop.slow WHERE tag = 'SLOW'").single()["n"]).describedAs("the server rolled the cancelled statement back").isEqualTo(0L)
        // a fast statement on the same table is fine, so it is the sleep and not the table that timed out
        assertThat(run(slow, mapOf("tag" to "fast"), cfg = mapOf("timeoutMs" to "500")).affected).isEqualTo(1L)
    }

    @Test fun `a connection killed while the statement runs is an AMBIGUOUS outcome - CONNECT_FAILED, not definite, never retryable, and nothing was committed`() {
        val slow = def(MutationKind.CREATE, "shop.slow", reqStr("tag"))
        val killed = AtomicBoolean(false)
        val killer = Thread {
            repeat(150) {
                if (!killed.get()) {
                    val hit = rows("""SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                        WHERE application_name = 'xweb-data-connector' AND state = 'active' AND query LIKE 'INSERT INTO "shop"."slow"%'""")
                    if (hit.isNotEmpty()) killed.set(true)
                    Thread.sleep(100)
                }
            }
        }.also { it.isDaemon = true; it.start() }
        val f = failure { run(slow, mapOf("tag" to "SLOW"), cfg = mapOf("timeoutMs" to "20000")) }
        killer.join(20_000)
        assertThat(killed.get()).describedAs("the test killed the backend while the statement ran").isTrue()
        assertThat(f.code).isEqualTo(FailureCodes.CONNECT_FAILED)
        assertThat(f.code).isNotIn(definite)
        assertThat(f.safeMessage).contains("may or may not")
        Thread.sleep(300)
        assertThat(rows("SELECT count(*) AS n FROM shop.slow WHERE tag = 'SLOW'").single()["n"]).describedAs("a killed backend commits nothing").isEqualTo(0L)
    }

    // ------------------------------------------------------------------------------------------------ connection test

    @Test fun `the connection test of a writable source uses a read-write session and reports the role's ability to write`() {
        val c = connector()
        val ok = c.test(ref(), rw) as ConnectionTestResult.Ok
        assertThat(ok.warnings).isEmpty()
        val noWrite = c.test(ref(), ro) as ConnectionTestResult.Ok
        assertThat(noWrite.warnings.single()).contains("no write privilege")
        assertThat((c.test(ref(), ghost) as ConnectionTestResult.Ok).warnings.single()).contains("no write privilege")
        assertThat((c.test(ref(), superuser) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.ROLE_TOO_PRIVILEGED)
        assertThat((c.test(ref(), ResolvedCredential.of(mapOf("username" to "rw_user", "password" to "wrong-TOPSECRET"))) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.AUTH_REJECTED)
        // a data source that is not writable keeps the original behaviour: a role that can write is a warning
        val readOnly = c.test(ref(mapOf("writable" to "false")), rw) as ConnectionTestResult.Ok
        assertThat(readOnly.warnings.single()).contains("can write")
        assertThat(c.test(ref(mapOf("writable" to "false")), ro).let { (it as ConnectionTestResult.Ok).warnings }).isEmpty()
    }

    // ------------------------------------------------------------------------------------------------ hygiene

    @Test fun `no connection stays open after a mutation, a failure or a timeout`() {
        newCustomer()
        runCatching { run(insertCustomer, mapOf("email" to "dup@example.com", "name" to "n")); run(insertCustomer, mapOf("email" to "dup@example.com", "name" to "n")) }
        runCatching { run(def(MutationKind.CREATE, "shop.slow", reqStr("tag")), mapOf("tag" to "SLOW"), cfg = mapOf("timeoutMs" to "500")) }
        Thread.sleep(500)
        assertThat(rows("SELECT count(*) AS n FROM pg_stat_activity WHERE application_name = 'xweb-data-connector'").single()["n"]).isEqualTo(0L)
    }

    @Test fun `a failing mutation logs no value, no SQL and no credential`() {
        LogCapture().use { logs ->
            val email = newCustomer("logs-" + UUID.randomUUID().toString().take(6) + "@example.com")
            runCatching { run(insertCustomer, mapOf("email" to email, "name" to "personal-VALUE-4711")) }                    // unique violation
            runCatching { run(insertCustomer, mapOf("email" to uniq("x") + "@example.com", "name" to "personal-VALUE-4711", "credit" to BigDecimal("-1"))) }
            val text = logs.text
            assertThat(text).doesNotContain("personal-VALUE-4711").doesNotContain(email).doesNotContain("TOPSECRET").doesNotContain("INSERT INTO").doesNotContain("rw_user")
        }
    }
}
