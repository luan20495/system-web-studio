package com.systemwebstudio.data.datasource.postgres

import com.systemwebstudio.data.FixedResolver
import com.systemwebstudio.data.InMemoryQueryCatalog
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceTypes
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.data.discovery.DiscoveryOptions
import com.systemwebstudio.data.discovery.EntityKind
import com.systemwebstudio.data.discovery.NormalizedType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Savepoint
import java.sql.Types
import java.util.UUID

/**
 * Discovery SQL path (columns, primary keys, foreign keys, masked samples, savepoints) against a scripted JDBC connection. The integration
 * tests run the same code against a real PostgreSQL in Testcontainers; this makes the logic checkable without Docker.
 */
class PostgresDiscoveryTests {
    private class Col(val label: String, val type: Int, val typeName: String)

    /** scripted JDBC: routes by SQL text, records everything that was executed or configured */
    private class FakeJdbc {
        val executed = mutableListOf<String>()
        val events = mutableListOf<String>()            // autocommit/readonly/savepoint/rollback/close in order
        val arrays = mutableListOf<List<String>>()
        var columns: List<List<Any?>> = emptyList(); var pks: List<List<Any?>> = emptyList(); var fks: List<List<Any?>> = emptyList()
        var samples: Map<String, Pair<List<Col>, List<List<Any?>>>> = emptyMap()
        var unreadable: Set<String> = emptySet()
        val maxRowsSet = mutableListOf<Int>(); val limitsBound = mutableListOf<Int>()

        private fun rs(cols: List<Col>, rows: List<List<Any?>>): ResultSet {
            var i = -1; var wasNull = false
            val meta = proxy<ResultSetMetaData> { m, a -> when (m) {
                "getColumnCount" -> cols.size; "getColumnLabel" -> cols[(a[0] as Int) - 1].label
                "getColumnType" -> cols[(a[0] as Int) - 1].type; "getColumnTypeName" -> cols[(a[0] as Int) - 1].typeName; else -> null } }
            return proxy { m, a -> when (m) {
                "next" -> { i++; i < rows.size }
                "getMetaData" -> meta
                "wasNull" -> wasNull
                "getString" -> rows[i][(a[0] as Int) - 1].also { wasNull = it == null }?.toString()
                "getLong" -> (rows[i][(a[0] as Int) - 1].also { wasNull = it == null } as Number?)?.toLong() ?: 0L
                "getInt" -> (rows[i][(a[0] as Int) - 1].also { wasNull = it == null } as Number?)?.toInt() ?: 0
                "getBoolean" -> (rows[i][(a[0] as Int) - 1].also { wasNull = it == null } as Boolean?) ?: false
                "getDouble" -> (rows[i][(a[0] as Int) - 1].also { wasNull = it == null } as Number?)?.toDouble() ?: 0.0
                "getBigDecimal" -> rows[i][(a[0] as Int) - 1].also { wasNull = it == null }
                "getBytes" -> null.also { wasNull = true }
                "getArray" -> { @Suppress("UNCHECKED_CAST") val l = rows[i][(a[0] as Int) - 1] as List<String>; array(l) }
                else -> null } }
        }
        private fun array(l: List<String>): java.sql.Array = proxy { m, _ -> when (m) { "getArray" -> l.toTypedArray(); else -> null } }
        private fun ints(sql: String) = sql.trim().replace(Regex("\\s+"), " ")

        private fun run(sql: String): ResultSet {
            val s = ints(sql); executed += s
            return when {
                s == "SELECT 1" -> rs(listOf(Col("?column?", Types.INTEGER, "int4")), listOf(listOf(1)))
                FakePg.isPreflight(s) -> FakePg.safeRole().let { row -> rs(row.indices.map { Col("c$it", Types.VARCHAR, "text") }, listOf(row)) }   // the per-session role check every session now runs
                s.contains("information_schema.columns") -> rs(List(7) { Col("c$it", Types.VARCHAR, "text") }, columns)
                s.contains("con.contype = 'p'") -> rs(List(3) { Col("c$it", Types.VARCHAR, "text") }, pks)
                s.contains("con.contype = 'f'") -> rs(List(7) { Col("c$it", Types.VARCHAR, "text") }, fks)
                s.startsWith("SELECT * FROM ") -> {
                    val table = s.removePrefix("SELECT * FROM ").substringBefore(" LIMIT")
                    if (table in unreadable) throw SQLException("permission denied for table $table", "42501")
                    val (c, r) = samples[table] ?: (emptyList<Col>() to emptyList())
                    rs(c, r)
                }
                else -> throw AssertionError("unexpected SQL: $s")
            }
        }

        private fun statement(sql: String?): java.sql.PreparedStatement = proxy { m, a -> when (m) {
            "executeQuery" -> run(sql ?: a[0] as String)
            "setArray" -> { events += "setArray"; null }
            "setMaxRows" -> { maxRowsSet += a[0] as Int; null }
            "setInt" -> { limitsBound += a[1] as Int; null }
            else -> null } }

        fun connection(): Connection = proxy { m, a -> when (m) {
            "setAutoCommit" -> { events += "autoCommit=${a[0]}"; null }
            "setReadOnly" -> { events += "readOnly=${a[0]}"; null }
            "createStatement" -> statement(null)
            "prepareStatement" -> statement(a[0] as String)
            "createArrayOf" -> { @Suppress("UNCHECKED_CAST") val l = (a[1] as Array<Any?>).map { it.toString() }; arrays += l; array(l) }
            "setSavepoint" -> { events += "savepoint"; proxy<Savepoint> { _, _ -> null } }
            "rollback" -> { events += if (a == null || a.isEmpty()) "rollback" else "rollbackTo"; null }
            "releaseSavepoint" -> { events += "release"; null }
            "close" -> { events += "close"; null }
            else -> null } }
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        inline fun <reified T> proxy(crossinline h: (String, Array<Any?>) -> Any?): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java), InvocationHandler { _, method, args ->
            val r = h(method.name, args ?: emptyArray())
            if (r == null && method.returnType.isPrimitive) when (method.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; else -> null } else r
        }) as T
    }

    private val jdbc = FakeJdbc()
    private val tenant = UUID.randomUUID()
    private val cfg = mapOf("host" to "db.example.com", "database" to "shop", "schemas" to "shop, reporting")
    private val ref = DataSourceRef(UUID.randomUUID(), tenant, DataSourceTypes.POSTGRES, cfg)
    private val cred = ResolvedCredential.of(mapOf("username" to "reader", "password" to "pw-TOPSECRET"))
    private val factory = object : PgConnectionFactory { override fun open(target: PgTarget): Connection = jdbc.connection() }
    private fun discover(options: DiscoveryOptions = DiscoveryOptions()) =
        PostgresConnector(InMemoryQueryCatalog(), PostgresTargetPolicy(), FixedResolver(mapOf("db.example.com" to listOf("93.184.216.34"))), factory).discovery().discover(ref, cred, options)

    private fun schema() {
        jdbc.columns = listOf(
            listOf("shop", "customers", "BASE TABLE", "id", "integer", "int4", "NO"), listOf("shop", "customers", "BASE TABLE", "email", "text", "text", "YES"),
            listOf("shop", "customers", "BASE TABLE", "name", "character varying", "varchar", "YES"),
            listOf("shop", "orders", "BASE TABLE", "id", "bigint", "int8", "NO"), listOf("shop", "orders", "BASE TABLE", "customer_id", "integer", "int4", "NO"),
            listOf("shop", "orders", "BASE TABLE", "total", "numeric", "numeric", "YES"), listOf("shop", "orders", "BASE TABLE", "placed", "timestamp with time zone", "timestamptz", "YES"),
            listOf("reporting", "order_totals", "VIEW", "total", "numeric", "numeric", "YES"))
        jdbc.pks = listOf(listOf("shop", "customers", "id"), listOf("shop", "orders", "id"))
        jdbc.fks = listOf(
            listOf("shop", "orders", "orders_customer_fk", listOf("customer_id"), "shop", "customers", listOf("id")),
            listOf("shop", "orders", "orders_ghost_fk", listOf("customer_id"), "hidden", "ghost_table", listOf("id")))
        jdbc.samples = mapOf(
            "\"shop\".\"customers\"" to (listOf(Col("id", Types.INTEGER, "int4"), Col("email", Types.VARCHAR, "text"), Col("name", Types.VARCHAR, "varchar")) to
                listOf(listOf(1, "ada@example.com", "Ada Lovelace"), listOf(2, "alan@example.com", "Alan Turing"))),
            "\"shop\".\"orders\"" to (listOf(Col("id", Types.BIGINT, "int8"), Col("total", Types.NUMERIC, "numeric")) to listOf(listOf(10L, java.math.BigDecimal("12.50")))))
    }

    @Test fun `columns types nullability primary keys and relations come out structured`() {
        schema()
        val s = discover()
        assertThat(s.entities.map { "${it.schema}.${it.name}" }).containsExactly("shop.customers", "shop.orders", "reporting.order_totals")
        val customers = s.entities[0]; val orders = s.entities[1]
        assertThat(customers.kind).isEqualTo(EntityKind.TABLE); assertThat(s.entities[2].kind).isEqualTo(EntityKind.VIEW)
        assertThat(customers.primaryKey).containsExactly("id")
        assertThat(customers.fields.first { it.name == "id" }.primaryKey).isTrue(); assertThat(customers.fields.first { it.name == "email" }.primaryKey).isFalse()
        assertThat(customers.fields.first { it.name == "id" }.nullable).isFalse(); assertThat(customers.fields.first { it.name == "email" }.nullable).isTrue()
        assertThat(orders.fields.first { it.name == "total" }.type).isEqualTo(NormalizedType.NUMBER)
        assertThat(orders.fields.first { it.name == "placed" }.type).isEqualTo(NormalizedType.TIMESTAMP)
        assertThat(orders.fields.first { it.name == "id" }.type).isEqualTo(NormalizedType.INTEGER)
        assertThat(orders.relations).hasSize(1)                                         // the foreign key to a table outside the listed schemas is not exposed
        val r = orders.relations.single()
        assertThat(r.name).isEqualTo("orders_customer_fk"); assertThat(r.fromFields).containsExactly("customer_id"); assertThat(r.toEntity).isEqualTo("customers"); assertThat(r.toFields).containsExactly("id")
        assertThat(orders.metadata["source"]).isEqualTo("postgres")
        assertThat(s.truncated).isFalse()
    }

    @Test fun `primary keys are read from the catalog so a SELECT-only role still sees them`() {
        schema(); discover()
        val pk = jdbc.executed.single { it.contains("con.contype = 'p'") }
        assertThat(pk).doesNotContain("information_schema")                                 // those views hide constraints from a role without more than SELECT
    }

    @Test fun `without a sample request no table data is read at all`() {
        schema(); discover()
        assertThat(jdbc.executed.none { it.startsWith("SELECT * FROM") }).isTrue()
    }

    @Test fun `samples are masked before they leave the connector and bounded`() {
        schema()
        val s = discover(DiscoveryOptions(2))
        val text = s.entities.joinToString { it.sample.toString() }
        for (leak in listOf("ada@example.com", "alan@example.com", "Ada Lovelace", "Alan Turing")) assertThat(text).doesNotContain(leak)
        val total = s.entities[1].sample.single()["total"]                                  // a NUMERIC stays a number and keeps its value; its scale (12.50) is the database's, not a contract
        assertThat(total!!.isNumber).isTrue(); assertThat(total.decimalValue()).isEqualByComparingTo(java.math.BigDecimal("12.5"))
        assertThat(jdbc.maxRowsSet.all { it == 2 }).isTrue(); assertThat(jdbc.limitsBound.all { it == 2 }).isTrue()
        assertThat(jdbc.executed.filter { it.startsWith("SELECT * FROM") }).containsExactly("SELECT * FROM \"shop\".\"customers\" LIMIT ?", "SELECT * FROM \"shop\".\"orders\" LIMIT ?", "SELECT * FROM \"reporting\".\"order_totals\" LIMIT ?")
    }

    @Test fun `a table that cannot be read costs a warning and a savepoint rollback not the discovery`() {
        schema(); jdbc.unreadable = setOf("\"shop\".\"orders\"")
        val s = discover(DiscoveryOptions(1))
        assertThat(s.entities[0].sample).hasSize(1); assertThat(s.entities[1].sample).isEmpty()
        assertThat(s.warnings.joinToString()).contains("sample skipped for 1 entities")
        assertThat(s.warnings.joinToString()).doesNotContain("permission denied").doesNotContain("orders")
        val i = jdbc.events.indexOf("rollbackTo")
        assertThat(i).isGreaterThan(-1)
        assertThat(jdbc.events.count { it == "savepoint" }).isEqualTo(3); assertThat(jdbc.events.count { it == "release" }).isEqualTo(3)
    }

    @Test fun `the session is read only and ends with a rollback and a close`() {
        schema(); discover(DiscoveryOptions(1))
        assertThat(jdbc.events.take(2)).containsExactly("autoCommit=false", "readOnly=true")
        assertThat(jdbc.executed.first()).isEqualTo("SELECT 1")                         // the read-only window is closed before any catalogue query
        assertThat(jdbc.events.takeLast(2)).containsExactly("rollback", "close")
        assertThat(jdbc.executed.none { Regex("(?i)\\b(insert|update|delete|drop|alter|create|truncate|grant)\\b").containsMatchIn(it) }).isTrue()
    }

    @Test fun `only the configured schemas are asked for`() {
        schema(); discover()
        assertThat(jdbc.arrays.distinct()).containsExactly(listOf("shop", "reporting"))
    }

    @Test fun `identifiers are quoted never concatenated raw`() {
        val d = PostgresSchemaDiscovery(PgSessions(PostgresTargetPolicy(), FixedResolver(emptyMap()), factory))
        assertThat(d.quote("orders")).isEqualTo("\"orders\"")
        assertThat(d.quote("we\"ird; DROP TABLE x")).isEqualTo("\"we\"\"ird; DROP TABLE x\"")
        assertThat(d.typeOf("jsonb")).isEqualTo(NormalizedType.JSON); assertThat(d.typeOf("bytea")).isEqualTo(NormalizedType.BINARY); assertThat(d.typeOf("weird")).isEqualTo(NormalizedType.OTHER)
    }

    @Test fun `nothing from the credential reaches the discovery result`() {
        schema()
        val text = discover(DiscoveryOptions(2)).toString()
        assertThat(text).doesNotContain("pw-TOPSECRET").doesNotContain("reader").doesNotContain("db.example.com").doesNotContain("93.184.216.34")
    }
}
