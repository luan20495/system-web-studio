package com.systemwebstudio.data.org

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationState
import org.flywaydb.core.api.MigrationVersion
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Reusable Flyway harness: a clean install and an upgrade of the REAL migrations of the classpath (`db/migration`), each in its own throw-away database of the test container.
 * Nothing in it names a version: the ceiling is whatever the classpath holds, so when V32 exists the same two tests prove "clean V1 -> V32" and "pre-V32 -> V32" with no change.
 * An optional `-Dc3.expected.ceiling=32` pins the ceiling.
 */
object FlywayHarness {
    class Database(val name: String, val ds: HikariDataSource) : AutoCloseable { val jdbc = JdbcTemplate(ds); override fun close() { ds.close() } }

    private var counter = 0

    fun newDatabase(prefix: String = "flyway"): Database {
        val name = "${prefix}_${System.nanoTime()}_${counter++}"
        OrgTestDb.pool(1, "flyway-admin").use { admin -> JdbcTemplate(admin).execute("CREATE DATABASE $name") }
        return Database(name, HikariDataSource(HikariConfig().apply { jdbcUrl = OrgTestDb.databaseUrl(name); username = OrgTestDb.postgres.username; password = OrgTestDb.postgres.password; maximumPoolSize = 2; poolName = "flyway-$name" }))
    }

    fun flyway(db: Database, target: MigrationVersion? = null): Flyway = Flyway.configure().dataSource(db.ds).locations("classpath:db/migration").also { if (target != null) it.target(target) }.load()

    /** every versioned migration of the classpath, ascending */
    fun versions(): List<MigrationVersion> = newDatabase("probe").use { db -> flyway(db).info().all().mapNotNull { it.version }.sorted() }
    fun ceiling(): MigrationVersion = versions().last()
    /** the version right below the ceiling: the "pre-V32" state once V32 is the ceiling */
    fun preCeiling(): MigrationVersion = versions().let { it[it.size - 2] }

    class Snapshot(val perTable: Map<String, Pair<Long, String>>) { override fun equals(other: Any?) = other is Snapshot && other.perTable == perTable; override fun hashCode() = perTable.hashCode() }

    /** row count + md5 over the listed columns (so a later migration that ADDS a column to one of these tables does not look like a change of the data) */
    fun snapshot(jdbc: JdbcTemplate, tables: Map<String, String>): Snapshot = Snapshot(tables.mapValues { (table, cols) ->
        val row = jdbc.queryForMap("SELECT count(*) AS n, coalesce(md5(string_agg(row($cols)::text, '|' ORDER BY row($cols)::text)), '') AS h FROM $table")
        (row["n"] as Number).toLong() to row["h"] as String
    })
}
