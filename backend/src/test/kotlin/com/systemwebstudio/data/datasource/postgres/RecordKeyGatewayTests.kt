package com.systemwebstudio.data.datasource.postgres

import com.systemwebstudio.data.FixedResolver
import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.InMemoryQueryCatalog
import com.systemwebstudio.data.datasource.DataConnectorRegistry
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceService
import com.systemwebstudio.data.datasource.DataSourceTypes
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.DefaultDataGateway
import com.systemwebstudio.data.gateway.GatewayMutation
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.RecordKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import java.sql.SQLException
import java.util.UUID

/**
 * FQ-ACT-02: the logical record identifier `recordId` of UPDATE_RECORD / DELETE_RECORD reaches the key column that the APPROVED mutation names (`key=`), whatever that
 * column is called. The REAL DefaultDataGateway and the REAL PostgresConnector over a recording fake database: the SQL text and the bound values are what is asserted,
 * so no column named `recordId` is needed and no identifier ever comes from a caller. The same flow on a real PostgreSQL and an ordinary table is RecordKeyActionE2ETests.
 */
class RecordKeyGatewayTests {
    private val config = mapOf("host" to "db.example.com", "database" to "shop", "schemas" to "shop", "writable" to "true")
    private val resolver = FixedResolver(mapOf("db.example.com" to listOf("93.184.216.34")))

    private class Factory(private val open: () -> java.sql.Connection) : PgConnectionFactory { override fun open(target: PgTarget): java.sql.Connection = open() }

    private fun db(updateCount: Long = 1, answer: (String) -> FakeRows = { FakeRows.EMPTY }) = FakePg { sql ->
        when {
            FakePg.isPreflight(sql) -> FakeRows.one(*FakePg.safeRole(0 to "off").toTypedArray())
            sql.contains("has_table_privilege") -> FakeRows.one(1)
            else -> answer(sql)
        }
    }.also { it.updateCount = updateCount }

    private class Rig(val f: GatewayFixture, val database: FakePg, val gateway: DefaultDataGateway, val dsId: UUID, val ctx: com.systemwebstudio.data.gateway.GatewayContext, val tenant: UUID)

    private fun rig(database: FakePg, vararg defs: (UUID, UUID) -> MutationDefinition): Rig {
        val f = GatewayFixture(); val t = f.tenant(); val ctx = f.ctx(t)
        val pg = PostgresConnector(InMemoryQueryCatalog(), PostgresTargetPolicy(), resolver, Factory { database.connection })
        val service = DataSourceService(f.repo, f.vault, DataConnectorRegistry(listOf(pg)), f.limiter, f.audit)
        val id = UUID.randomUUID()
        f.repo.save(DataSource(DataSourceRef(id, t.tenantId, DataSourceTypes.POSTGRES, config), "pg-" + id.toString().take(4), credentialRef = f.vault.store(t.tenantId, mapOf("username" to "writer", "password" to "pw"))))
        defs.forEach { f.mutations.add(it(t.tenantId, id)) }
        val gateway = DefaultDataGateway(f.guard, service, f.queries, f.mutations, f.mappings, f.discovery, f.cache, f.idempotency, f.notifier, f.audit, f.limiter, clock = f.clock)
        return Rig(f, database, gateway, id, ctx, t.tenantId)
    }

    private fun p(name: String, type: ParamType = ParamType.STRING, required: Boolean = true) = QueryParamSpec(name, type, required)
    private fun update(id: String, target: String, vararg params: QueryParamSpec): (UUID, UUID) -> MutationDefinition = { t, ds -> MutationDefinition(id, t, ds, MutationKind.UPDATE, target, params.toList()) }
    private fun delete(id: String, target: String, vararg params: QueryParamSpec): (UUID, UUID) -> MutationDefinition = { t, ds -> MutationDefinition(id, t, ds, MutationKind.DELETE, target, params.toList()) }
    private fun Rig.run(op: String, key: String, vararg values: Pair<String, Any>) =
        gateway.mutate(ctx, GatewayMutation(dsId, op, values.associate { it.first to DataJson.toNode(it.second) }, key))
    private fun Rig.refused(op: String, key: String, vararg values: Pair<String, Any>): ConnectorFailure = f.failure { run(op, key, *values) }
    private fun writes(d: FakePg) = d.executed.filter { it.startsWith("UPDATE") || it.startsWith("DELETE") || it.startsWith("INSERT") }

    // ---- the logical identifier reaches the key column of the approved definition ---------------------------------------------------

    @Test fun `UPDATE - recordId becomes the key named by the definition, whatever the column is called`() {
        for (column in listOf("id", "uuid", "order_no", "pk")) {
            val r = rig(db(), update("u", "shop.orders key=$column", p(column), p("status", required = false)))
            val out = r.run("u", "idem-upd-0001", "recordId" to "9f0c7d4e-1111-4222-8333-444455556666", "status" to "shipped")
            assertThat(out.affected).isEqualTo(1L)
            assertThat(writes(r.database)).describedAs("key column $column").containsExactly("""UPDATE "shop"."orders" SET "status" = ? WHERE "$column" = ?""")
            assertThat(r.database.params).containsExactly("shipped", "9f0c7d4e-1111-4222-8333-444455556666")
            assertThat(r.database.executed.joinToString()).doesNotContain("recordId")
        }
    }

    @Test fun `DELETE - recordId becomes the key named by the definition`() {
        val r = rig(db(), delete("d", "shop.orders key=id", p("id")))
        assertThat(r.run("d", "idem-del-0001", "recordId" to "abc-1").affected).isEqualTo(1L)
        assertThat(writes(r.database)).containsExactly("""DELETE FROM "shop"."orders" WHERE "id" = ?""")
        assertThat(r.database.params).containsExactly("abc-1")
    }

    @Test fun `a row that does not exist is a change of zero rows, not an error, and nothing else is touched`() {
        val r = rig(db(updateCount = 0), update("u", "shop.orders key=id", p("id"), p("status", required = false)), delete("d", "shop.orders key=id", p("id")))
        assertThat(r.run("u", "idem-miss-0001", "recordId" to "nope", "status" to "x").affected).isEqualTo(0L)
        assertThat(r.run("d", "idem-miss-0002", "recordId" to "nope").affected).isEqualTo(0L)
        assertThat(writes(r.database)).hasSize(2)
    }

    @Test fun `an operation that declares its own recordId keeps working exactly as before - the column really is called recordId`() {
        val r = rig(db(), update("u", "shop.legacy key=recordId", p("recordId"), p("status", required = false)))
        r.run("u", "idem-legacy-001", "recordId" to "L-1", "status" to "x")
        assertThat(writes(r.database)).containsExactly("""UPDATE "shop"."legacy" SET "status" = ? WHERE "recordId" = ?""")
    }

    @Test fun `an INTEGER key accepts the textual identifier, and refuses anything that is not a whole number before any statement`() {
        val r = rig(db(), update("u", "shop.numbered key=no", p("no", ParamType.INTEGER), p("status", required = false)))
        r.run("u", "idem-int-0001", "recordId" to "42", "status" to "x")
        assertThat(r.database.params).containsExactly("x", 42L)
        val before = writes(r.database).size
        for (bad in listOf("42; DROP TABLE shop.numbered", "4.2", "abc", "", "99999999999999999999")) {
            assertThat(r.refused("u", "idem-int-bad-" + bad.length, "recordId" to bad, "status" to "x").code).describedAs("'$bad'").isEqualTo(FailureCodes.INVALID_PARAMS)
        }
        assertThat(writes(r.database)).hasSize(before)
    }

    // ---- metadata that does not say which column is the key ---------------------------------------------------------------------------

    @Test fun `missing, undeclared, ambiguous or unsafe key metadata refuses the change before any statement, as a definite failure that releases the key`() {
        val cases = mapOf(
            "no key at all" to "shop.orders",
            "key is an undeclared parameter" to "shop.orders key=ghost",
            "two keys: no single logical identifier" to "shop.orders key=id,status",
            "not an identifier" to "shop.orders key=id\"; DROP TABLE shop.orders; --",
            "an option that is not key" to "shop.orders keys=id",
            "a schema that is not configured" to "hidden.orders key=id"
        )
        for ((what, target) in cases) {
            val r = rig(db(), update("u", target, p("id"), p("status", required = false)))
            val first = r.refused("u", "idem-meta-0001", "recordId" to "7", "status" to "x")
            assertThat(first.code).describedAs(what).isEqualTo(FailureCodes.INVALID_PARAMS)
            assertThat(first.code in DefaultDataGateway.NOT_EXECUTED).describedAs("$what: nothing was sent").isTrue()
            assertThat(writes(r.database)).describedAs(what).isEmpty()
            assertThat(r.refused("u", "idem-meta-0001", "recordId" to "7", "status" to "x").code).describedAs("$what: the key was released, not UNKNOWN").isEqualTo(FailureCodes.INVALID_PARAMS)
        }
    }

    @Test fun `a key naming a column that does not exist is refused by the database as a definite rejection`() {
        val database = db(); database.failure = { sql -> if (sql.startsWith("UPDATE")) SQLException("column \"nope\" does not exist", "42703") else null }
        val r = rig(database, update("u", "shop.orders key=nope", p("nope"), p("status", required = false)))
        val f = r.refused("u", "idem-col-0001", "recordId" to "7", "status" to "x")
        assertThat(f.code).isEqualTo(FailureCodes.MUTATION_REJECTED)
        assertThat(f.code in DefaultDataGateway.NOT_EXECUTED).isTrue()
    }

    @Test fun `the key is never taken from the request - recordId plus the key itself is ambiguous, and recordId on CREATE or SUBMIT stays an unknown parameter`() {
        val r = rig(db(), update("u", "shop.orders key=id", p("id"), p("status", required = false)),
            { t, ds -> MutationDefinition("c", t, ds, MutationKind.CREATE, "shop.orders", listOf(p("status"))) },
            { t, ds -> MutationDefinition("s", t, ds, MutationKind.SUBMIT, "shop.orders", listOf(p("status"))) })
        assertThat(r.refused("u", "idem-twice-001", "recordId" to "1", "id" to "2").code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(r.refused("c", "idem-create-01", "recordId" to "1", "status" to "x").code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(r.refused("s", "idem-submit-01", "recordId" to "1", "status" to "x").code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(writes(r.database)).isEmpty()
    }

    @Test fun `a hostile value is one bound parameter and a hostile NAME is refused as an unknown parameter - no identifier comes from the request`() {
        val r = rig(db(), update("u", "shop.orders key=id", p("id"), p("status", required = false)))
        val evil = "x'; DROP TABLE shop.orders; --"
        r.run("u", "idem-evil-0001", "recordId" to evil, "status" to "a")
        assertThat(writes(r.database).single()).doesNotContain("DROP").doesNotContain(evil)
        assertThat(r.database.params).contains(evil)
        assertThat(r.refused("u", "idem-evil-0002", "recordId" to "1", "id\" = id; DROP TABLE shop.orders; --" to "y").code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(writes(r.database)).hasSize(1)
    }

    // ---- authorization and tenancy stay where they were --------------------------------------------------------------------------------

    @Test fun `without MUTATION_EXECUTE or for another tenant nothing is resolved, sent or reserved`() {
        val r = rig(db(), update("u", "shop.orders key=id", p("id"), p("status", required = false)))
        r.f.authorizer.denied += GatewayOperation.MUTATION_EXECUTE
        assertThat(r.f.failure { r.run("u", "idem-authz-0001", "recordId" to "1", "status" to "x") }.code).isIn(FailureCodes.PERMISSION_DENIED, FailureCodes.NOT_FOUND)
        r.f.authorizer.denied.clear()
        val other = r.f.ctx(r.f.tenant())
        assertThat(r.f.failure { r.gateway.mutate(other, GatewayMutation(r.dsId, "u", mapOf("recordId" to DataJson.toNode("1")), "idem-authz-0002")) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(writes(r.database)).isEmpty()
        assertThat(r.database.executed).describedAs("not even a connection was used").isEmpty()
    }

    // ---- frozen semantics: ambiguous outcomes -----------------------------------------------------------------------------------------

    @Test fun `an ambiguous UPDATE or DELETE keeps its key UNKNOWN, the same key never reaches the database again`() {
        for (ambiguous in listOf<(FakePg) -> Unit>(
            { it.failure = { sql -> if (sql.startsWith("UPDATE") || sql.startsWith("DELETE")) SQLException("timeout", "08006", SocketTimeoutException("Read timed out")) else null } },
            { it.failure = { sql -> if (sql.startsWith("UPDATE") || sql.startsWith("DELETE")) SQLException("cancelled", "57014") else null } },
            { it.commitFailure = SQLException("lost", "08006", java.io.IOException("Broken pipe")) })) {
            for (op in listOf("u" to arrayOf("recordId" to "1", "status" to "x"), "d" to arrayOf("recordId" to "1"))) {
                val database = db(); ambiguous(database)
                val r = rig(database, update("u", "shop.orders key=id", p("id"), p("status", required = false)), delete("d", "shop.orders key=id", p("id")))
                val first = r.refused(op.first, "idem-unk-0001", *op.second)
                assertThat(first.code in DefaultDataGateway.NOT_EXECUTED).describedAs("an ambiguous failure is not a definite one").isFalse()
                val statements = database.executed.size
                val second = r.refused(op.first, "idem-unk-0001", *op.second)
                assertThat(second.code).isEqualTo(FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
                assertThat(database.executed.size).describedAs("no second statement").isEqualTo(statements)
            }
        }
    }

    @Test fun `a duplicate key replays the stored answer, another input under it is a conflict`() {
        val r = rig(db(), update("u", "shop.orders key=id", p("id"), p("status", required = false)))
        r.run("u", "idem-dup-00001", "recordId" to "1", "status" to "a")
        assertThat(r.run("u", "idem-dup-00001", "recordId" to "1", "status" to "a").replayed).isTrue()
        assertThat(writes(r.database)).hasSize(1)
        assertThat(r.refused("u", "idem-dup-00001", "recordId" to "1", "status" to "b").code).isEqualTo(FailureCodes.IDEMPOTENCY_CONFLICT)
        assertThat(writes(r.database)).hasSize(1)
    }

    // ---- the connector reads the key from the validated target ----------------------------------------------------------------------

    @Test fun `the PostgreSQL connector reports exactly one key of an UPDATE or DELETE and nothing else`() {
        val pg = PostgresConnector(InMemoryQueryCatalog(), PostgresTargetPolicy(), resolver, Factory { db().connection })
        val ds = DataSourceRef(UUID.randomUUID(), UUID.randomUUID(), DataSourceTypes.POSTGRES, config)
        fun key(kind: MutationKind, target: String) = pg.mutator()!!.recordKey(MutationDefinition("m", ds.tenantId, ds.id, kind, target, listOf(p("id"))), ds)
        assertThat(key(MutationKind.UPDATE, "shop.orders key=id")).isEqualTo("id")
        assertThat(key(MutationKind.DELETE, "orders key=id returning=id")).isEqualTo("id")
        assertThat(key(MutationKind.UPDATE, "shop.orders key=id,status")).isNull()
        assertThat(key(MutationKind.UPDATE, "shop.orders")).isNull()
        assertThat(key(MutationKind.UPDATE, "shop.orders key=id\"x")).isNull()
        assertThat(key(MutationKind.CREATE, "shop.orders key=id")).isNull()
        assertThat(key(MutationKind.SUBMIT, "shop.orders key=id")).isNull()
        assertThat(key(MutationKind.UPDATE, "hidden.orders key=id")).describedAs("a schema that is not configured").isNull()
    }

    @Test fun `RecordKey is pure - it changes nothing unless every condition holds`() {
        fun d(kind: MutationKind, vararg ps: QueryParamSpec) = MutationDefinition("m", UUID.randomUUID(), UUID.randomUUID(), kind, "t", ps.toList())
        val given = mapOf("recordId" to DataJson.toNode("5"), "status" to DataJson.toNode("x"))
        assertThat(RecordKey.resolve(d(MutationKind.UPDATE, p("id"), p("status")), "id", given).keys).containsExactlyInAnyOrder("id", "status")
        assertThat(RecordKey.resolve(d(MutationKind.UPDATE, p("id"), p("status")), null, given)).isEqualTo(given)
        assertThat(RecordKey.resolve(d(MutationKind.UPDATE, p("status")), "id", given)).describedAs("the key is not a declared parameter").isEqualTo(given)
        assertThat(RecordKey.resolve(d(MutationKind.CREATE, p("id")), "id", given)).isEqualTo(given)
        assertThat(RecordKey.resolve(d(MutationKind.UPDATE, p("recordId"), p("id")), "id", given)).describedAs("it declares recordId itself").isEqualTo(given)
        assertThat(RecordKey.resolve(d(MutationKind.UPDATE, p("id")), "id", mapOf("status" to DataJson.toNode("x")))).describedAs("no recordId given").containsKey("status")
    }
}
