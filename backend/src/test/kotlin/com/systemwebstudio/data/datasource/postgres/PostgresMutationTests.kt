package com.systemwebstudio.data.datasource.postgres

import com.systemwebstudio.data.FixedResolver
import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.InMemoryQueryCatalog
import com.systemwebstudio.data.LogCapture
import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorCapability
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataConnectorRegistry
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceService
import com.systemwebstudio.data.datasource.DataSourceTypes
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.MutationExecRequest
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.data.gateway.DefaultDataGateway
import com.systemwebstudio.data.gateway.GatewayMutation
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import java.sql.SQLException
import java.util.UUID

/**
 * B-C0-W-04 without a database: target grammar, SQL construction (no value ever in SQL text), session/transaction flow, the error mapping and — through the
 * REAL DefaultDataGateway — what each outcome does to the idempotency key (the frozen §4b semantics). The same flows against a real server are in
 * PostgresWritableIntegrationTests (Testcontainers).
 */
class PostgresMutationTests {
    private val tenant = UUID.randomUUID()
    private val dsId = UUID.randomUUID()
    private val password = "writer-pass-TOPSECRET-9"
    private val cred = ResolvedCredential.of(mapOf("username" to "writer_bob", "password" to password))
    private val config = mapOf("host" to "db.example.com", "database" to "shop", "schemas" to "shop, reporting", "writable" to "true")
    private fun ref(cfg: Map<String, String> = config) = DataSourceRef(dsId, tenant, DataSourceTypes.POSTGRES, cfg)
    private val resolver = FixedResolver(mapOf("db.example.com" to listOf("93.184.216.34")))
    private fun failure(block: () -> Unit): ConnectorFailure { try { block() } catch (e: ConnectorFailure) { return e }; throw AssertionError("expected a ConnectorFailure") }
    private val cfgParsed get() = PostgresConnectorConfig.parse(config)

    private class Factory(private val onOpen: () -> java.sql.Connection) : PgConnectionFactory {
        var opened = 0; val targets = mutableListOf<PgTarget>()
        override fun open(target: PgTarget): java.sql.Connection { opened++; targets += target; return onOpen() }
    }

    /** a database whose role is a clean, READ WRITE writer; [answer] serves the RETURNING statements */
    private fun db(role: List<Any?> = FakePg.safeRole(0 to "off"), answer: (String) -> FakeRows = { FakeRows.EMPTY }) = FakePg { sql ->
        when {
            FakePg.isPreflight(sql) -> FakeRows.one(*role.toTypedArray())
            sql.contains("has_table_privilege") -> FakeRows.one(1)
            else -> answer(sql)
        }
    }

    private fun connector(f: PgConnectionFactory) = PostgresConnector(InMemoryQueryCatalog(), PostgresTargetPolicy(), resolver, f)

    private fun def(kind: MutationKind, target: String, params: List<QueryParamSpec>, id: String = "m1") = MutationDefinition(id, tenant, dsId, kind, target, params)
    private fun req(def: MutationDefinition, params: Map<String, Any>, key: String? = "idem-key-0001") = MutationExecRequest(def, params, key)
    private val name = QueryParamSpec("name", ParamType.STRING, true)
    private val id = QueryParamSpec("id", ParamType.INTEGER, true)
    private val note = QueryParamSpec("note", ParamType.STRING, false)

    // ---------------------------------------------------------------- configuration and descriptor

    @Test fun `writable is off by default and strictly true or false`() {
        assertThat(PostgresConnectorConfig.parse(config - "writable").writable).isFalse()
        assertThat(PostgresConnectorConfig.parse(config + ("writable" to "false")).writable).isFalse()
        assertThat(cfgParsed.writable).isTrue(); assertThat(cfgParsed.maxAffectedRows).isEqualTo(1000)
        assertThat(PostgresConnectorConfig.parse(config + ("maxAffectedRows" to "50")).maxAffectedRows).isEqualTo(50)
        for (bad in listOf("TRUE", "yes", "1", "", "on")) assertThat(failure { PostgresConnectorConfig.parse(config + ("writable" to bad)) }.code).isEqualTo(FailureCodes.INVALID_CONFIG)
        for (bad in listOf("0", "100001", "x", "-1")) assertThat(failure { PostgresConnectorConfig.parse(config + ("maxAffectedRows" to bad)) }.code).isEqualTo(FailureCodes.INVALID_CONFIG)
    }

    @Test fun `the connector declares mutation support and the credential entries it reads`() {
        val c = connector(Factory { error("not reached") })
        assertThat(c.descriptor.capabilities).contains(ConnectorCapability.QUERY, ConnectorCapability.DISCOVERY, ConnectorCapability.MUTATION)
        assertThat(c.descriptor.credentialKeys).containsExactly("username", "password")
        assertThat(c.descriptor.configKeys.map { it.name }).contains("host", "database", "writable", "maxAffectedRows", "schemas")
        assertThat(c.descriptor.toString()).doesNotContain(password)
        assertThat(c.mutator()).isNotNull()
    }

    // ---------------------------------------------------------------- target grammar

    @Test fun `a target names a table in a configured schema and optional key and returning columns`() {
        val a = PgMutationTarget.parse("shop.customers", cfgParsed)
        assertThat(a.schema).isEqualTo("shop"); assertThat(a.table).isEqualTo("customers"); assertThat(a.keys).isEmpty(); assertThat(a.returning).isEmpty()
        val b = PgMutationTarget.parse("customers key=id returning=id,created_at", cfgParsed)
        assertThat(b.schema).isEqualTo("shop")                                                             // no schema = the first configured one
        assertThat(b.keys).containsExactly("id"); assertThat(b.returning).containsExactly("id", "created_at")
        assertThat(PgMutationTarget.parse("reporting.t key=a,b", cfgParsed).keys).containsExactly("a", "b")
        assertThat(PgMutationTarget.parse("  customers   returning=id  ", cfgParsed).returning).containsExactly("id")
    }

    @Test fun `anything that is not a plain identifier is refused as an invalid target`() {
        val targets = listOf("", " ", "hidden.t", "a.b.c", "customers;DROP TABLE x", "customers--", "cust\"omers", "customers key=id key=id2", "customers key=", "customers key=Id",
            "customers key=id,id", "customers returning=", "customers returning=id,id", "customers returning=1a", "customers where=1", "customers key", "customers key=id returning=id extra=1",
            "customers\tkey=id", "customers\nkey=id", "1customers", "shop.", ".customers", "shop.cust omers", "x".repeat(64), "customers key=" + (1..11).joinToString(",") { "k$it" },
            "customers returning=" + (1..21).joinToString(",") { "c$it" }, "customers' OR '1'='1", "customers/*x*/", "pg_catalog.pg_class")
        for (t in targets) assertThat(failure { PgMutationTarget.parse(t, cfgParsed) }.code).isEqualTo(FailureCodes.INVALID_CONFIG)
    }

    // ---------------------------------------------------------------- SQL construction

    private fun plan(d: MutationDefinition, params: Map<String, Any>) = PgMutationSql.plan(d, PgMutationTarget.parse(d.target, cfgParsed), params)

    @Test fun `create builds a parameterised INSERT from the supplied parameters`() {
        val p = plan(def(MutationKind.CREATE, "shop.customers returning=id", listOf(name, note)), mapOf("name" to "Grace"))
        assertThat(p.sql).isEqualTo("""INSERT INTO "shop"."customers" ("name") VALUES (?) RETURNING "id"""")        // the optional, unsupplied column is left to its default
        assertThat(p.values).containsExactly("Grace"); assertThat(p.returning).containsExactly("id")
        assertThat(plan(def(MutationKind.CREATE, "customers", listOf(name, note)), mapOf("name" to "Grace", "note" to "x")).sql)
            .isEqualTo("""INSERT INTO "shop"."customers" ("name", "note") VALUES (?, ?)""")
    }

    @Test fun `update sets the supplied non key parameters where the keys match`() {
        val d = def(MutationKind.UPDATE, "shop.customers key=id", listOf(id, name, note))
        val p = plan(d, mapOf("id" to 7L, "name" to "Grace"))
        assertThat(p.sql).isEqualTo("""UPDATE "shop"."customers" SET "name" = ? WHERE "id" = ?""")
        assertThat(p.values).containsExactly("Grace", 7L)                                                          // SET values first, key values last, in placeholder order
        assertThat(plan(def(MutationKind.UPDATE, "customers key=id,name", listOf(id, name, note)), mapOf("id" to 1L, "name" to "a", "note" to "n")).sql)
            .isEqualTo("""UPDATE "shop"."customers" SET "note" = ? WHERE "id" = ? AND "name" = ?""")
    }

    @Test fun `delete needs a key and takes nothing but the key`() {
        val p = plan(def(MutationKind.DELETE, "shop.customers key=id", listOf(id)), mapOf("id" to 7L))
        assertThat(p.sql).isEqualTo("""DELETE FROM "shop"."customers" WHERE "id" = ?"""); assertThat(p.values).containsExactly(7L)
    }

    @Test fun `no value ever reaches the SQL text`() {
        val evil = "x'); DROP TABLE shop.customers; --"
        val plans = listOf(
            plan(def(MutationKind.CREATE, "customers", listOf(name)), mapOf("name" to evil)),
            plan(def(MutationKind.UPDATE, "customers key=name", listOf(name, note)), mapOf("name" to evil, "note" to evil)),
            plan(def(MutationKind.DELETE, "customers key=name", listOf(name)), mapOf("name" to evil)))
        for (p in plans) { assertThat(p.sql).doesNotContain("DROP").doesNotContain(evil).doesNotContain("--"); assertThat(p.values).contains(evil); assertThat(p.toString()).doesNotContain(evil) }
    }

    @Test fun `statements that would touch a whole table or cannot be expressed are refused before any connection`() {
        fun code(d: MutationDefinition, p: Map<String, Any>) = failure { plan(d, p) }.code
        assertThat(code(def(MutationKind.UPDATE, "customers", listOf(id, name)), mapOf("id" to 1L, "name" to "a"))).isEqualTo(FailureCodes.INVALID_CONFIG)      // UPDATE without key
        assertThat(code(def(MutationKind.DELETE, "customers", listOf(id)), mapOf("id" to 1L))).isEqualTo(FailureCodes.INVALID_CONFIG)                              // DELETE without key
        assertThat(code(def(MutationKind.DELETE, "customers key=id", listOf(id, name)), mapOf("id" to 1L, "name" to "a"))).isEqualTo(FailureCodes.INVALID_CONFIG)  // a non key parameter on DELETE
        assertThat(code(def(MutationKind.CREATE, "customers key=id", listOf(id, name)), mapOf("id" to 1L, "name" to "a"))).isEqualTo(FailureCodes.INVALID_CONFIG)  // key on CREATE
        assertThat(code(def(MutationKind.UPDATE, "customers key=other", listOf(id, name)), mapOf("id" to 1L, "name" to "a"))).isEqualTo(FailureCodes.INVALID_CONFIG)  // key is not a parameter
        assertThat(code(def(MutationKind.UPDATE, "customers key=id", listOf(QueryParamSpec("id", ParamType.INTEGER, false), name)), mapOf("name" to "a"))).isEqualTo(FailureCodes.INVALID_PARAMS) // key not supplied
        assertThat(code(def(MutationKind.UPDATE, "customers key=id", listOf(id, note)), mapOf("id" to 1L))).isEqualTo(FailureCodes.INVALID_PARAMS)                  // nothing to set
        assertThat(code(def(MutationKind.CREATE, "customers", listOf(note)), emptyMap())).isEqualTo(FailureCodes.INVALID_PARAMS)                                  // nothing to insert
        assertThat(code(def(MutationKind.SUBMIT, "customers", listOf(name)), mapOf("name" to "a"))).isEqualTo(FailureCodes.MUTATION_UNSUPPORTED)
    }

    // ---------------------------------------------------------------- the session and the transaction

    private val create get() = def(MutationKind.CREATE, "shop.customers", listOf(name))

    @Test fun `a data source that is not writable never opens a connection`() {
        val f = Factory { error("must not be reached") }
        val e = failure { connector(f).mutator().execute(req(create, mapOf("name" to "Grace")), ref(config - "writable"), cred) }
        assertThat(e.code).isEqualTo(FailureCodes.READ_ONLY_VIOLATION); assertThat(f.opened).isEqualTo(0)
        assertThat(failure { connector(f).mutator().execute(req(create, mapOf("name" to "Grace")), ref(config + ("writable" to "false")), cred) }.code).isEqualTo(FailureCodes.READ_ONLY_VIOLATION)
    }

    @Test fun `another tenant's definition or data source is refused before any connection`() {
        val f = Factory { error("must not be reached") }
        val foreign = MutationDefinition("m1", UUID.randomUUID(), dsId, MutationKind.CREATE, "customers", listOf(name))
        assertThat(failure { connector(f).mutator().execute(req(foreign, mapOf("name" to "x")), ref(), cred) }.code).isEqualTo(FailureCodes.TENANT_MISMATCH)
        val otherSource = MutationDefinition("m1", tenant, UUID.randomUUID(), MutationKind.CREATE, "customers", listOf(name))
        assertThat(failure { connector(f).mutator().execute(req(otherSource, mapOf("name" to "x")), ref(), cred) }.code).isEqualTo(FailureCodes.TENANT_MISMATCH)
        assertThat(f.opened).isEqualTo(0)
    }

    @Test fun `a mutation runs as one read write transaction that is verified executed bounded and committed once`() {
        val database = db(); database.updateCount = 1
        val f = Factory { database.connection }
        val outcome = connector(f).mutator().execute(req(create, mapOf("name" to "Grace")), ref(), cred)
        assertThat(outcome.affected).isEqualTo(1L)
        assertThat(f.targets.single().writable).isTrue()
        assertThat(database.events.toList()).containsExactly("autoCommit=false", "readOnly=false", "commit", "rollback", "close")      // the rollback after the commit is a no-op
        assertThat(database.executed.size).isEqualTo(2)                                                                         // the role preflight, then the one statement
        assertThat(FakePg.isPreflight(database.executed[0])).isTrue()
        assertThat(database.executed[1]).isEqualTo("""INSERT INTO "shop"."customers" ("name") VALUES (?)""")
        assertThat(database.params.toList()).containsExactly("Grace")
        assertThat(database.queryTimeout).isEqualTo(10)                                                                         // 10 000 ms default, rounded up to whole seconds
    }

    @Test fun `the connection properties of a write session are read write and everything else is unchanged`() {
        val target = { writable: Boolean -> PgTarget(cfgParsed, listOf(java.net.InetAddress.getByName("93.184.216.34")), "writer_bob", password, writable) }
        val rw = PgConnectionProperties.build(target(true), true); val ro = PgConnectionProperties.build(target(false), true)
        assertThat(rw.getProperty("options")).contains("default_transaction_read_only=off").contains("statement_timeout=10000").contains("lock_timeout=2000")
        assertThat(ro.getProperty("options")).contains("default_transaction_read_only=on")
        for (p in listOf(rw, ro)) {
            assertThat(p.getProperty("sslmode")).isEqualTo("verify-full"); assertThat(p.getProperty("ssl")).isEqualTo("true")
            assertThat(p.getProperty("socketTimeout")).isEqualTo("10"); assertThat(p.getProperty("password")).isEqualTo(password)
        }
        assertThat(PgConnectionProperties.url(cfgParsed)).doesNotContain(password).doesNotContain("writer_bob")
        assertThat(target(true).toString()).doesNotContain(password)
    }

    @Test fun `a session that is read only or whose role is too privileged is refused before the statement`() {
        for ((role, code) in listOf(
            FakePg.safeRole() to FailureCodes.READ_ONLY_VIOLATION,                                      // transaction_read_only = on (a replica or a forced default)
            FakePg.safeRole(0 to "off", 3 to true) to FailureCodes.ROLE_TOO_PRIVILEGED,                 // superuser
            FakePg.safeRole(0 to "off", 2 to "on") to FailureCodes.ROLE_TOO_PRIVILEGED,                 // is_superuser = on
            FakePg.safeRole(0 to "off", 9 to true) to FailureCodes.ROLE_TOO_PRIVILEGED,                 // member of a pg_* role
            FakePg.safeRole(0 to "off", 4 to true) to FailureCodes.ROLE_TOO_PRIVILEGED,                 // CREATEROLE
            FakePg.safeRole(0 to "off", 1 to "off") to FailureCodes.READ_ONLY_VIOLATION)) {             // standard_conforming_strings off
            val database = db(role = role)
            val e = failure { connector(Factory { database.connection }).mutator().execute(req(create, mapOf("name" to "Grace")), ref(), cred) }
            assertThat(e.code).isEqualTo(code)
            assertThat(database.executed.none { it.startsWith("INSERT") }).isTrue(); assertThat(database.events.toList()).doesNotContain("commit").contains("close")
        }
    }

    @Test fun `affected rows are reported and the row cap rolls back a statement that would change too many`() {
        val update = def(MutationKind.UPDATE, "shop.customers key=id", listOf(id, name))
        val database = db(); database.updateCount = 3
        assertThat(connector(Factory { database.connection }).mutator().execute(req(update, mapOf("id" to 1L, "name" to "a")), ref(), cred).affected).isEqualTo(3L)
        val none = db(); none.updateCount = 0
        val zero = connector(Factory { none.connection }).mutator().execute(req(update, mapOf("id" to 999L, "name" to "a")), ref(), cred)
        assertThat(zero.affected).isEqualTo(0L); assertThat(none.events.toList()).contains("commit")                    // nothing matched: a successful no-op, reported as 0

        val many = db(); many.updateCount = 5
        val e = failure { connector(Factory { many.connection }).mutator().execute(req(update, mapOf("id" to 1L, "name" to "a")), ref(config + ("maxAffectedRows" to "2")), cred) }
        assertThat(e.code).isEqualTo(FailureCodes.MUTATION_REJECTED)
        assertThat(many.events.toList()).doesNotContain("commit").contains("rollback", "close")
    }

    @Test fun `returning gives the first row and the row count`() {
        val d = def(MutationKind.CREATE, "shop.customers returning=id,name", listOf(name))
        val database = db { sql -> if (sql.startsWith("INSERT")) FakeRows(listOf("id", "name"), listOf(java.sql.Types.BIGINT, java.sql.Types.VARCHAR), listOf(listOf(7L, "Grace"))) else FakeRows.EMPTY }
        val outcome = connector(Factory { database.connection }).mutator().execute(req(d, mapOf("name" to "Grace")), ref(), cred)
        assertThat(outcome.affected).isEqualTo(1L)
        assertThat(outcome.output!!.get("id").asLong()).isEqualTo(7L); assertThat(outcome.output!!.get("name").asString()).isEqualTo("Grace")
        assertThat(database.executed.last()).isEqualTo("""INSERT INTO "shop"."customers" ("name") VALUES (?) RETURNING "id", "name"""")
        assertThat(database.events.toList()).contains("commit")
        assertThat(connector(Factory { db().also { it.updateCount = 1 }.connection }).mutator().execute(req(create, mapOf("name" to "x")), ref(), cred).output).isNull()
    }

    // ---------------------------------------------------------------- error mapping

    private fun sqlFailure(state: String?, cause: Throwable? = null) = SQLException("driver text: INSERT INTO customers VALUES ('$password') violates constraint secret_idx", state, cause)

    private fun runWith(failOnStatement: Throwable? = null, failOnCommit: Throwable? = null): Pair<ConnectorFailure, FakePg> {
        val database = db()
        failOnStatement?.let { t -> database.failure = { sql -> if (sql.startsWith("INSERT")) t else null } }
        database.commitFailure = failOnCommit
        val e = failure { connector(Factory { database.connection }).mutator().execute(req(create, mapOf("name" to "Grace")), ref(), cred) }
        return e to database
    }

    @Test fun `failures the server reported on the statement are definite and release the key`() {
        val definite = mapOf(
            "23505" to FailureCodes.MUTATION_REJECTED, "23503" to FailureCodes.MUTATION_REJECTED, "23502" to FailureCodes.MUTATION_REJECTED, "23514" to FailureCodes.MUTATION_REJECTED,
            "22P02" to FailureCodes.INVALID_PARAMS, "22001" to FailureCodes.INVALID_PARAMS, "22003" to FailureCodes.INVALID_PARAMS,
            "42501" to FailureCodes.PERMISSION_DENIED, "42P01" to FailureCodes.MUTATION_REJECTED, "42703" to FailureCodes.MUTATION_REJECTED,
            "25006" to FailureCodes.READ_ONLY_VIOLATION,
            "40001" to FailureCodes.MUTATION_REJECTED, "40P01" to FailureCodes.MUTATION_REJECTED, "55P03" to FailureCodes.MUTATION_REJECTED, "53200" to FailureCodes.MUTATION_REJECTED)
        for ((state, code) in definite) {
            val (e, database) = runWith(failOnStatement = sqlFailure(state))
            assertThat(e.code).isEqualTo(code)
            assertThat(e.code in DefaultDataGateway.NOT_EXECUTED).isTrue()                                  // the gateway releases the key: certain that nothing was applied
            assertThat(e.safeMessage).doesNotContain(password).doesNotContain("INSERT").doesNotContain("secret_idx")
            assertThat(database.events.toList()).doesNotContain("commit").contains("rollback", "close")
        }
        assertThat(runWith(failOnStatement = sqlFailure("23505")).first.safeMessage).contains("unique")
        assertThat(runWith(failOnStatement = sqlFailure("23503")).first.safeMessage).contains("foreign key")
        assertThat(runWith(failOnStatement = sqlFailure("23502")).first.safeMessage).contains("NOT NULL")
    }

    @Test fun `timeouts and lost connections after the statement was sent are ambiguous and keep the key`() {
        val ambiguous = mapOf(
            "57014" to FailureCodes.TIMEOUT, "08006" to FailureCodes.CONNECT_FAILED, "08003" to FailureCodes.CONNECT_FAILED, "57P01" to FailureCodes.CONNECT_FAILED,
            "XX000" to FailureCodes.QUERY_FAILED, "P0001" to FailureCodes.QUERY_FAILED)
        for ((state, code) in ambiguous) {
            val (e, database) = runWith(failOnStatement = sqlFailure(state))
            assertThat(e.code).isEqualTo(code)
            assertThat(e.code in DefaultDataGateway.NOT_EXECUTED).isFalse()                                // the gateway marks the key UNKNOWN: never retried automatically
            assertThat(e.safeMessage).doesNotContain(password).doesNotContain("INSERT")
            assertThat(database.events.toList()).doesNotContain("commit")
        }
        val socket = runWith(failOnStatement = sqlFailure("08006", SocketTimeoutException("Read timed out to 93.184.216.34")))
        assertThat(socket.first.code).isEqualTo(FailureCodes.TIMEOUT); assertThat(socket.first.safeMessage).doesNotContain("93.184")
        val noState = runWith(failOnStatement = sqlFailure(null, java.io.IOException("reset")))
        assertThat(noState.first.code).isEqualTo(FailureCodes.CONNECT_FAILED)
        assertThat(runWith(failOnStatement = sqlFailure(null)).first.code).isEqualTo(FailureCodes.QUERY_FAILED)
        // a TLS error while the statement is in flight is NOT the pre-connect TLS_FAILED (which releases the key): the statement may have arrived
        val tls = runWith(failOnStatement = sqlFailure("08006", javax.net.ssl.SSLException("handshake_failure")))
        assertThat(tls.first.code).isEqualTo(FailureCodes.CONNECT_FAILED); assertThat(tls.first.code in DefaultDataGateway.NOT_EXECUTED).isFalse()
        for (e in listOf(socket.first, noState.first, tls.first)) assertThat(e.safeMessage).contains("may or may not have been applied")
    }

    @Test fun `a failure at commit is ambiguous unless the server itself refused the commit`() {
        // the commit was sent and the answer never came: the transaction may be committed
        val lost = runWith(failOnCommit = sqlFailure("08006", java.io.IOException("Broken pipe")))
        assertThat(lost.first.code).isEqualTo(FailureCodes.CONNECT_FAILED); assertThat(lost.first.safeMessage).contains("while committing").contains("may or may not")
        assertThat(lost.first.code in DefaultDataGateway.NOT_EXECUTED).isFalse()
        val timedOut = runWith(failOnCommit = sqlFailure("08006", SocketTimeoutException("Read timed out")))
        assertThat(timedOut.first.code).isEqualTo(FailureCodes.TIMEOUT); assertThat(timedOut.first.code in DefaultDataGateway.NOT_EXECUTED).isFalse()
        // the server answered the COMMIT with an error (deferred constraint, serialization failure): it rolled the transaction back
        for (state in listOf("23505", "40001")) {
            val refused = runWith(failOnCommit = sqlFailure(state))
            assertThat(refused.first.code).isEqualTo(FailureCodes.MUTATION_REJECTED); assertThat(refused.first.code in DefaultDataGateway.NOT_EXECUTED).isTrue()
        }
        for (r in listOf(lost, timedOut)) { assertThat(r.second.events.toList()).contains("commit", "rollback", "close"); assertThat(r.first.safeMessage).doesNotContain(password) }
    }

    @Test fun `an unexpected exception is an internal connector error with no detail and the transaction is rolled back`() {
        val database = db(); database.failure = { sql -> if (sql.startsWith("INSERT")) IllegalStateException("boom $password INSERT INTO customers") else null }
        LogCapture().use { logs ->
            val e = failure { connector(Factory { database.connection }).mutator().execute(req(create, mapOf("name" to "Grace")), ref(), cred) }
            assertThat(e.code).isEqualTo(FailureCodes.INTERNAL); assertThat(e.code in DefaultDataGateway.NOT_EXECUTED).isFalse()
            assertThat(e.safeMessage).doesNotContain(password).doesNotContain("boom"); assertThat(e.toString()).doesNotContain(password)
            assertThat(logs.text).doesNotContain(password).doesNotContain("Grace")
        }
        assertThat(database.events.toList()).doesNotContain("commit").contains("rollback", "close")
    }

    @Test fun `connection and authentication failures before the statement are mapped exactly and send nothing`() {
        val cases = listOf(
            sqlFailure("28P01") to FailureCodes.AUTH_REJECTED, sqlFailure("28000") to FailureCodes.AUTH_REJECTED, sqlFailure("08001") to FailureCodes.CONNECT_FAILED,
            sqlFailure("3D000") to FailureCodes.CONNECT_FAILED, sqlFailure("53300") to FailureCodes.CONNECT_FAILED,
            sqlFailure("08001", SocketTimeoutException("connect timed out")) to FailureCodes.TIMEOUT, sqlFailure("08006", javax.net.ssl.SSLException("PKIX path building failed")) to FailureCodes.TLS_FAILED)
        for ((ex, code) in cases) {
            val f = Factory { throw ex }
            val e = failure { connector(f).mutator().execute(req(create, mapOf("name" to "Grace")), ref(), cred) }
            assertThat(e.code).isEqualTo(code); assertThat(f.opened).isEqualTo(1)
            assertThat(e.safeMessage).doesNotContain(password).doesNotContain("INSERT"); assertThat(e.toString()).doesNotContain(password)
        }
        val blocked = failure { PostgresConnector(InMemoryQueryCatalog(), PostgresTargetPolicy(), FixedResolver(mapOf("db.example.com" to listOf("10.0.0.5"))), Factory { error("not reached") })
            .mutator().execute(req(create, mapOf("name" to "Grace")), ref(), cred) }
        assertThat(blocked.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)                                    // the same target policy as every session
    }

    // ---------------------------------------------------------------- the connection test

    @Test fun `the connection test of a writable data source uses a read write session and changes nothing`() {
        val database = db(); val f = Factory { database.connection }
        val ok = connector(f).test(ref(), cred) as ConnectionTestResult.Ok
        assertThat(ok.warnings).isEmpty(); assertThat(f.targets.single().writable).isTrue()
        assertThat(database.events.toList()).doesNotContain("commit").contains("rollback", "close")
        assertThat(database.executed.none { it.startsWith("INSERT") || it.startsWith("UPDATE") || it.startsWith("DELETE") }).isTrue()
        val noPrivilege = FakePg { sql -> if (FakePg.isPreflight(sql)) FakeRows.one(*FakePg.safeRole(0 to "off").toTypedArray()) else if (sql.contains("has_table_privilege")) FakeRows.one(0) else FakeRows.EMPTY }
        val warned = connector(Factory { noPrivilege.connection }).test(ref(), cred) as ConnectionTestResult.Ok
        assertThat(warned.warnings.single()).contains("no write privilege")
        val readOnlyRole = db(role = FakePg.safeRole())                                                     // the server forces READ ONLY although writable=true
        assertThat((connector(Factory { readOnlyRole.connection }).test(ref(), cred) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.READ_ONLY_VIOLATION)
    }

    @Test fun `the connection test of a read only data source is unchanged`() {
        val database = FakePg.database(writable = 2); val f = Factory { database.connection }
        val ok = connector(f).test(ref(config - "writable"), cred) as ConnectionTestResult.Ok
        assertThat(f.targets.single().writable).isFalse()
        assertThat(ok.warnings.single()).contains("can write to 2 table(s)").contains("SELECT-only")
        assertThat(database.events.toList()).contains("readOnly=true")
    }

    // ---------------------------------------------------------------- the real gateway: what each outcome does to the idempotency key

    private class Rig(val f: GatewayFixture, val database: FakePg, val gateway: DefaultDataGateway, val dsId: UUID, val ctx: com.systemwebstudio.data.gateway.GatewayContext)

    private fun rig(database: FakePg, cfg: Map<String, String> = emptyMap()): Rig {
        val f = GatewayFixture(); val t = f.tenant(); val ctx = f.ctx(t)
        val pg = PostgresConnector(InMemoryQueryCatalog(), PostgresTargetPolicy(), resolver, Factory { database.connection })
        val service = DataSourceService(f.repo, f.vault, DataConnectorRegistry(listOf(pg)), f.limiter, f.audit)
        val id = UUID.randomUUID()
        f.repo.save(DataSource(DataSourceRef(id, t.tenantId, DataSourceTypes.POSTGRES, config + cfg), "pg-" + id.toString().take(4),
            credentialRef = f.vault.store(t.tenantId, mapOf("username" to "writer_bob", "password" to password))))
        f.mutations.add(MutationDefinition("create-customer", t.tenantId, id, MutationKind.CREATE, "shop.customers returning=id", listOf(name), listOf("customers"), "customer"))
        val gateway = DefaultDataGateway(f.guard, service, f.queries, f.mutations, f.mappings, f.discovery, f.cache, f.idempotency, f.notifier, f.audit, f.limiter, clock = f.clock)
        return Rig(f, database, gateway, id, ctx)
    }
    private fun Rig.mutate(key: String, who: String = "Grace") = gateway.mutate(ctx, GatewayMutation(dsId, "create-customer", mapOf("name" to DataJson.toNode(who)), key))
    private fun inserts(database: FakePg) = database.executed.count { it.startsWith("INSERT") }
    private val returning = { sql: String -> if (sql.startsWith("INSERT")) FakeRows(listOf("id"), listOf(java.sql.Types.BIGINT), listOf(listOf(41L))) else FakeRows.EMPTY }

    @Test fun `success is stored and a replay never reaches the database again`() {
        val r = rig(db(answer = returning))
        val first = r.mutate("idem-key-0001"); val replay = r.mutate("idem-key-0001")
        assertThat(first.replayed).isFalse(); assertThat(first.affected).isEqualTo(1L); assertThat(first.output!!.get("id").asLong()).isEqualTo(41L)
        assertThat(replay.replayed).isTrue(); assertThat(replay.output!!.get("id").asLong()).isEqualTo(41L)
        assertThat(inserts(r.database)).isEqualTo(1)
        assertThat(r.f.audit.text).doesNotContain(password).doesNotContain("Grace")
    }

    @Test fun `a constraint violation is rejected and the key is released so the corrected request can use it`() {
        val database = db(answer = returning); var violate = true
        database.failure = { sql -> if (sql.startsWith("INSERT") && violate) sqlFailure("23505") else null }
        val r = rig(database)
        assertThat(r.f.failure { r.mutate("idem-key-0002") }.code).isEqualTo(FailureCodes.MUTATION_REJECTED)
        violate = false
        val retry = r.mutate("idem-key-0002")                                                                  // the same key runs again: the first attempt certainly applied nothing
        assertThat(retry.replayed).isFalse(); assertThat(inserts(database)).isEqualTo(2)
    }

    @Test fun `an ambiguous failure keeps the key and the next attempt is answered without touching the database`() {
        for (ambiguous in listOf<(FakePg) -> Unit>(
            { it.commitFailure = sqlFailure("08006", java.io.IOException("Broken pipe")) },                                             // commit sent, answer lost
            { it.failure = { sql -> if (sql.startsWith("INSERT")) sqlFailure("08006", SocketTimeoutException("Read timed out")) else null } },   // timeout while running
            { it.failure = { sql -> if (sql.startsWith("INSERT")) sqlFailure("57014") else null } })) {                                // cancelled by statement_timeout
            val database = db(answer = returning); ambiguous(database)
            val r = rig(database)
            val first = r.f.failure { r.mutate("idem-key-0003") }
            assertThat(first.code in DefaultDataGateway.NOT_EXECUTED).isFalse()
            val statementsAfterFirst = database.executed.size
            val second = r.f.failure { r.mutate("idem-key-0003") }
            assertThat(second.code).isEqualTo(FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)                         // C4: OUTCOME_UNKNOWN, never retryable, never run again with this key
            assertThat(database.executed.size).isEqualTo(statementsAfterFirst)
            assertThat(inserts(database)).isEqualTo(1)
        }
    }

    @Test fun `a read only data source answers a definite refusal and a different key is unaffected`() {
        val database = db(answer = returning)
        val r = rig(database, cfg = mapOf("writable" to "false"))
        assertThat(r.f.failure { r.mutate("idem-key-0004") }.code).isEqualTo(FailureCodes.READ_ONLY_VIOLATION)
        assertThat(r.f.failure { r.mutate("idem-key-0004") }.code).isEqualTo(FailureCodes.READ_ONLY_VIOLATION)      // released, not UNKNOWN
        assertThat(database.executed).isEmpty(); assertThat(database.events).isEmpty()
    }

    @Test fun `authentication failure is a definite refusal and releases the key`() {
        val r = rig(db(answer = returning))
        val service = DataSourceService(r.f.repo, r.f.vault, DataConnectorRegistry(listOf(PostgresConnector(InMemoryQueryCatalog(), PostgresTargetPolicy(), resolver, Factory { throw sqlFailure("28P01") }))), r.f.limiter, r.f.audit)
        val gateway = DefaultDataGateway(r.f.guard, service, r.f.queries, r.f.mutations, r.f.mappings, r.f.discovery, r.f.cache, r.f.idempotency, r.f.notifier, r.f.audit, r.f.limiter, clock = r.f.clock)
        val m = GatewayMutation(r.dsId, "create-customer", mapOf("name" to DataJson.toNode("Grace")), "idem-key-0005")
        assertThat(r.f.failure { gateway.mutate(r.ctx, m) }.code).isEqualTo(FailureCodes.AUTH_REJECTED)
        assertThat(r.f.failure { gateway.mutate(r.ctx, m) }.code).isEqualTo(FailureCodes.AUTH_REJECTED)               // not UNKNOWN: nothing was ever sent
        assertThat(r.f.audit.text).doesNotContain(password)
    }
}
