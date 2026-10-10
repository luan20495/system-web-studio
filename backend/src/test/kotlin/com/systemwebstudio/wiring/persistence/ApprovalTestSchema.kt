package com.systemwebstudio.wiring.persistence

import org.springframework.jdbc.core.JdbcTemplate

/**
 * The `approvals` table is a C4 PROPOSAL until C0 allocates a Flyway number (`src/test/resources/db/proposed/approvals.sql`). Tests apply that file verbatim, once per database,
 * so what is tested is exactly what C0 will import. When C0 moves it to `db/migration`, this helper finds the table already there and does nothing.
 */
object ApprovalTestSchema {
    fun ensure(jdbc: JdbcTemplate) {
        synchronized(this) {
            if (jdbc.queryForObject("SELECT to_regclass('public.approvals') IS NOT NULL", Boolean::class.java) == true) return
            val sql = ApprovalTestSchema::class.java.getResourceAsStream("/db/proposed/approvals.sql")!!.bufferedReader().readText()
            jdbc.execute(sql)
        }
    }
}
