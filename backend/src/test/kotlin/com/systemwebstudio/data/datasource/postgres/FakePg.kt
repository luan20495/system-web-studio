package com.systemwebstudio.data.datasource.postgres

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.sql.Types
import java.util.concurrent.CopyOnWriteArrayList

/** A canned result: column labels, JDBC types and rows. */
class FakeRows(val labels: List<String>, val types: List<Int>, val rows: List<List<Any?>>) {
    companion object {
        val EMPTY = FakeRows(emptyList(), emptyList(), emptyList())
        fun one(vararg values: Any?) = FakeRows(values.indices.map { "c$it" }, values.map { Types.VARCHAR }, listOf(values.toList()))
        fun ids(count: Int) = FakeRows(listOf("id"), listOf(Types.BIGINT), (1..count).map { listOf<Any?>(it.toLong()) })
    }
}

/**
 * A `java.sql.Connection` without a database (dynamic proxy) that records what was done to it: every SQL text in order, session flags and
 * lifecycle events, prepared-statement parameters and limits. [answer] maps SQL text to a canned result.
 */
class FakePg(private val answer: (String) -> FakeRows = { FakeRows.EMPTY }) {
    val executed = CopyOnWriteArrayList<String>()
    val events = CopyOnWriteArrayList<String>()
    val params = CopyOnWriteArrayList<Any?>()
    @Volatile var maxRows = -1
    @Volatile var queryTimeout = -1
    @Volatile var fetchSize = -1

    val connection: Connection = proxy(Connection::class.java) { _, m, a ->
        when (m.name) {
            "setAutoCommit" -> { events += "autoCommit=${a!![0]}"; null }
            "setReadOnly" -> { events += "readOnly=${a!![0]}"; null }
            "createStatement" -> statement(null)
            "prepareStatement" -> statement(a!![0] as String)
            "rollback" -> { events += "rollback"; null }
            "close" -> { events += "close"; null }
            "setSavepoint" -> proxy(java.sql.Savepoint::class.java) { _, _, _ -> null }
            "createArrayOf" -> proxy(java.sql.Array::class.java) { _, _, _ -> null }
            "isClosed" -> events.contains("close")
            else -> default(m)
        }
    }

    private fun statement(prepared: String?): Any = proxy(PreparedStatement::class.java) { _, m, a ->
        when (m.name) {
            "executeQuery" -> {
                val sql = prepared ?: a!![0] as String
                executed += sql
                resultSet(answer(sql))
            }
            "setString", "setInt", "setLong", "setObject", "setBigDecimal", "setBoolean", "setArray" -> { params += a!![1]; null }
            "setNull" -> { params += null; null }
            "setMaxRows" -> { maxRows = a!![0] as Int; null }
            "setQueryTimeout" -> { queryTimeout = a!![0] as Int; null }
            "setFetchSize" -> { fetchSize = a!![0] as Int; null }
            "close" -> null
            else -> default(m)
        }
    }

    private fun resultSet(r: FakeRows): ResultSet {
        var cursor = -1; var wasNull = false
        val md = proxy(ResultSetMetaData::class.java) { _, m, a ->
            when (m.name) {
                "getColumnCount" -> r.labels.size
                "getColumnLabel" -> r.labels[(a!![0] as Int) - 1]
                "getColumnType" -> r.types[(a!![0] as Int) - 1]
                "getColumnTypeName" -> "fake"
                else -> default(m)
            }
        }
        fun cell(a: Array<Any?>?): Any? = r.rows[cursor][(a!![0] as Int) - 1].also { wasNull = it == null }
        return proxy(ResultSet::class.java) { _, m, a ->
            when (m.name) {
                "next" -> { cursor++; cursor < r.rows.size }
                "getString" -> cell(a)?.toString()
                "getBoolean" -> (cell(a) as? Boolean) ?: false
                "getLong" -> (cell(a) as? Number)?.toLong() ?: 0L
                "getInt" -> (cell(a) as? Number)?.toInt() ?: 0
                "getDouble" -> (cell(a) as? Number)?.toDouble() ?: 0.0
                "getBigDecimal" -> cell(a)?.let { BigDecimal(it.toString()) }
                "getBytes" -> cell(a) as? ByteArray
                "getArray" -> null
                "wasNull" -> wasNull
                "getMetaData" -> md
                "close" -> null
                else -> default(m)
            }
        }
    }

    companion object {
        /** the verdict row of [PgSessionPreflight]: ro, standard_conforming_strings, is_superuser, rolsuper, createrole, createdb, replication, bypassrls, reaches superuser, predefined role */
        fun safeRole(vararg override: Pair<Int, Any?>): List<Any?> =
            mutableListOf<Any?>("on", "on", "off", false, false, false, false, false, false, false).also { l -> override.forEach { (i, v) -> l[i] = v } }

        fun isPreflight(sql: String) = sql.contains("pg_roles r")

        /** a database whose role is [role] and whose approved query returns [data] */
        fun database(role: List<Any?>? = safeRole(), data: FakeRows = FakeRows.EMPTY, writable: Int = 0) = FakePg { sql ->
            when {
                sql == "SELECT 1" -> FakeRows.one(1)
                isPreflight(sql) -> if (role == null) FakeRows.EMPTY else FakeRows.one(*role.toTypedArray())
                sql.contains("has_table_privilege") -> FakeRows.one(writable)
                sql.startsWith("SELECT * FROM (") -> data
                else -> FakeRows.EMPTY
            }
        }

        private fun default(m: Method): Any? = when (m.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, h: (Any, Method, Array<Any?>?) -> Any?): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type), InvocationHandler { p, m, a -> h(p, m, a) }) as T
    }

    // instance-side helpers delegate to the companion so lambdas above can use them
    private fun default(m: Method): Any? = Companion.default(m)
    private fun <T> proxy(type: Class<T>, h: (Any, Method, Array<Any?>?) -> Any?): T = Companion.proxy(type, h)
}
