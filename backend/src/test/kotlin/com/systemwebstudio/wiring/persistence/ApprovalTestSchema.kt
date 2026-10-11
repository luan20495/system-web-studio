package com.systemwebstudio.wiring.persistence

import org.springframework.jdbc.core.JdbcTemplate

/** The `approvals` table is created by `V33__approvals.sql` (Flyway, D-C0-61). This helper only fails loudly, with the reason, when a test database does not carry it. */
object ApprovalTestSchema {
    fun ensure(jdbc: JdbcTemplate) {
        check(jdbc.queryForObject("SELECT to_regclass('public.approvals') IS NOT NULL", Boolean::class.java) == true) {
            "table 'approvals' is missing: the test database must be migrated with V33__approvals.sql (db/migration)"
        }
    }
}
