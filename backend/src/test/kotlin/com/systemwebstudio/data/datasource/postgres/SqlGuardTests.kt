package com.systemwebstudio.data.datasource.postgres

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SqlGuardTests {
    private fun rejected(sql: String): ConnectorFailure {
        try { SqlGuard.compile(sql) } catch (e: ConnectorFailure) { assertThat(e.code).isEqualTo(FailureCodes.INVALID_QUERY); return e }
        throw AssertionError("expected the query to be rejected: $sql")
    }

    @Test fun `plain selects with named parameters compile to prepared-statement placeholders`() {
        val c = SqlGuard.compile("SELECT id, name FROM shop.orders WHERE customer_id = :customerId AND created_at >= :since AND status = :status ORDER BY id;")
        assertThat(c.sql).isEqualTo("SELECT id, name FROM shop.orders WHERE customer_id = ? AND created_at >= ? AND status = ? ORDER BY id")
        assertThat(c.paramNames).containsExactly("customerId", "since", "status")
        assertThat(SqlGuard.compile("select :a, :a").paramNames).containsExactly("a", "a")          // a repeated name binds twice
    }

    @Test fun `casts, CTEs, quoted identifiers and literals that look dangerous are fine`() {
        val ok = listOf(
            "SELECT id::text, :x::int FROM t",
            "WITH recent AS (SELECT * FROM orders WHERE total > :min) SELECT * FROM recent",
            "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 10) SELECT n FROM r",
            "SELECT \"update\", \"set\" FROM \"insert\"",
            "SELECT 'DROP TABLE x; DELETE FROM y; -- :notAParam ?' AS note",
            "SELECT 'it''s; fine' AS s",
            "SELECT \$\$a;b; DROP TABLE x\$\$ AS d, \$tag\$ ; INSERT \$tag\$ AS e",
            "SELECT 1 -- trailing comment ; DROP TABLE x",
            "SELECT /* ; DROP TABLE x */ 1 /* nested /* ; */ still comment */",
            "SELECT 1;   -- nothing after the semicolon\n  /* but comments */  ",
            "SELECT E'a\\'; b' AS escaped",
            "SELECT count(*) FILTER (WHERE x) FROM t WHERE substring(name FROM 1 FOR 3) = 'abc'"
        )
        for (sql in ok) SqlGuard.compile(sql)
        assertThat(SqlGuard.compile("SELECT 'a :b ?' , :c").paramNames).containsExactly("c")
    }

    @Test fun `only one statement is allowed - the classic stacked-query shapes`() {
        for (sql in listOf(
            "SELECT 1; SELECT 2", "SELECT 1; DROP TABLE users", "SELECT 1;DROP TABLE users;", "SELECT 1;;", ";SELECT 1", "SELECT 1; -- c\n DELETE FROM t",
            "SELECT 1 /* ; */ ; DROP TABLE x", "SELECT 1 -- ;\n; DROP TABLE x",
            // standard_conforming_strings: a backslash is an ordinary character in '...', so this closes the string and starts a second statement
            "SELECT '\\'; DROP TABLE x; --'"
        )) rejected(sql)
        // …while in an E'...' string the backslash escapes the quote, so the whole text is ONE string literal and one statement
        SqlGuard.compile("SELECT E'\\'; DROP TABLE x; --'")
    }

    @Test fun `anything that writes, changes state or takes locks is refused wherever it appears`() {
        for (sql in listOf(
            "INSERT INTO t VALUES (1)", "UPDATE t SET a = 1", "DELETE FROM t", "MERGE INTO t USING s ON true WHEN MATCHED THEN DELETE", "DROP TABLE t", "ALTER TABLE t ADD c int",
            "CREATE TABLE t (a int)", "TRUNCATE t", "GRANT ALL ON t TO PUBLIC", "REVOKE ALL ON t FROM PUBLIC", "COPY t TO PROGRAM 'id'", "CALL p()", "DO \$\$ BEGIN END \$\$",
            "SET default_transaction_read_only = off", "RESET ALL", "BEGIN", "COMMIT", "LOCK TABLE t", "VACUUM", "ANALYZE t", "REFRESH MATERIALIZED VIEW v", "LISTEN c", "NOTIFY c",
            "WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d", "WITH i AS (INSERT INTO t VALUES (1) RETURNING *) SELECT * FROM i", "WITH u AS (UPDATE t SET a = 1 RETURNING *) SELECT * FROM u",
            "SELECT * INTO backup FROM t", "SELECT * FROM t FOR UPDATE", "SELECT * FROM t FOR SHARE", "SELECT * FROM t FOR NO KEY UPDATE", "SELECT * FROM t FOR KEY SHARE",
            "EXPLAIN ANALYZE DELETE FROM t", "SHOW all", "VALUES (1)", "(SELECT 1)", "TABLE t", "", "   ", "-- only a comment"
        )) rejected(sql)
    }

    @Test fun `functions that reach outside the query are refused, quoted or schema-qualified`() {
        for (f in listOf("set_config('default_transaction_read_only','off',false)", "pg_sleep(10)", "pg_sleep_for('1 hour')", "dblink('host=x','select 1')", "dblink_exec('x','y')",
            "lo_import('/etc/passwd')", "lo_export(1,'/tmp/x')", "pg_read_file('/etc/passwd')", "pg_read_binary_file('x')", "pg_ls_dir('.')", "pg_stat_file('x')", "pg_terminate_backend(1)",
            "pg_cancel_backend(1)", "pg_reload_conf()", "pg_advisory_lock(1)", "nextval('s')", "setval('s', 1)", "query_to_xml('delete from t', true, true, '')", "table_to_xml('t', true, true, '')")) {
            rejected("SELECT $f")
            rejected("SELECT pg_catalog.$f")
            rejected("SELECT x FROM t WHERE y = ($f)")
        }
        rejected("SELECT \"set_config\"('a','b',false)")
        rejected("SELECT pg_catalog.\"pg_read_file\"('x')")
        rejected("SELECT * FROM pg_ls_dir('.')")
        SqlGuard.compile("SELECT now(), current_setting('transaction_read_only'), lower(name), count(*) FROM t GROUP BY 2, 3")      // ordinary functions stay available
    }

    @Test fun `positional and bare placeholders cannot smuggle a value into the text`() {
        rejected("SELECT * FROM t WHERE a = \$1")
        rejected("SELECT * FROM t WHERE a = ?")
        rejected("SELECT * FROM t WHERE a = :")
        rejected("SELECT * FROM t WHERE a = :1")
        rejected("SELECT * FROM t WHERE a = :UPPER")
        rejected("SELECT 'unterminated")
        rejected("SELECT \"unterminated")
        rejected("SELECT 1 /* unterminated")
        rejected("SELECT \$\$unterminated")
        rejected("S" + "E".repeat(1) + "LECT 1" + " ".repeat(SqlGuard.MAX_LENGTH))
    }

    @Test fun `a comment cannot split or hide a keyword`() {
        rejected("SEL/**/ECT 1")
        rejected("SELECT 1 /**/; /**/ DROP TABLE x")
        rejected("SELECT * FROM t WHERE a = 1 /* */ UNION SELECT 1; DELETE FROM t")
        rejected("SELECT 1 FROM t FOR/**/UPDATE")
        rejected("SELECT 1 /* */ INTO x")
    }

    @Test fun `Unicode-escaped identifiers and strings are refused - the U-ampersand bypass`() {
        // U&"\0073et_config" is set_config once the server decodes it, i.e. AFTER a name check on the raw text would have passed
        rejected("SELECT U&\"\\0073et_config\"('default_transaction_read_only','off',false)")
        rejected("SELECT u&\"\\0073et_config\"('a','b',false)")
        rejected("SELECT pg_catalog.U&\"\\0070g_read_file\"('/etc/passwd')")
        rejected("SELECT * FROM U&\"\\0070g_authid\"")
        rejected("SELECT U&'\\0041' UESCAPE '!'")
        rejected("SELECT U&\"d!0061t!0061\" UESCAPE '!' FROM t")
        rejected("SELECT 1 FROM t WHERE U&'x' = 'x'")
        rejected("SELECT/**/U&\"x\"")
        rejected("SELECT 1U&\"x\"")
        // not the Unicode form: spaced operator, string content and comments are all fine
        SqlGuard.compile("SELECT a & b FROM t")
        SqlGuard.compile("SELECT 'U&\"x\"' AS s")
        SqlGuard.compile("SELECT 1 /* U&\"x\" */")
        SqlGuard.compile("SELECT \"U\" FROM t WHERE flags & 1 = 1")
    }

    @Test fun `characters PostgreSQL would read differently are refused outside quoted text`() {
        rejected("SELECT 1\u0000")
        rejected("SELECT 1\u0001")
        rejected("SELECT 1;\u2003DROP TABLE x")
        rejected("SELECT\u00A0set_config('a','b',false)")
        rejected("SELECT 1 \u2028")
        rejected("SELECT \uD83D\uDE00 FROM t")
        SqlGuard.compile("SELECT t\u00EAn FROM t")                         // an accented letter is an identifier character in PostgreSQL too
        SqlGuard.compile("SELECT 'caf\u00E9 \u2003 \uD83D\uDE00' AS s, \"t\u00EAn\u2003\" FROM t")  // anything is fine inside quotes
        SqlGuard.compile("SELECT\t1\r\nFROM\u000Ct")
    }

    @Test fun `a carriage return ends a line comment as in PostgreSQL`() {
        rejected("SELECT 1 -- x\r; DROP TABLE y")
        rejected("SELECT 1 -- x\rDELETE FROM t")
        SqlGuard.compile("SELECT 1 -- x\r\n")
        assertThat(SqlGuard.compile("SELECT 1 -- harmless\rFROM t").sql).contains("FROM t").doesNotContain("harmless")
    }

    @Test fun `every statement kind named by the contract is refused`() {
        // multi-statement, COPY, DO, CALL, SELECT INTO, write CTE (all three DML verbs and MERGE), in lower case and mixed case too
        for (sql in listOf(
            "select 1; select 2", "copy (select 1) to stdout", "Copy t From Program 'x'", "do $$ begin perform 1; end $$", "DO LANGUAGE plpgsql $$ BEGIN END $$",
            "call do_things()", "CALL p(1)", "select * into new_t from t", "SELECT a INTO TEMP TABLE x FROM t", "select a into unlogged x from t",
            "with a as (insert into t values (1) returning 1) select * from a", "with a as (update t set x = 1 returning 1) select * from a",
            "with a as (delete from t returning 1) select * from a", "with a as (merge into t using s on true when matched then delete returning 1) select 1",
            "WITH a AS (SELECT 1) INSERT INTO t SELECT * FROM a", "WITH a AS (SELECT 1) DELETE FROM t", "with a as (select 1) update t set x = 1",
            "WITH a AS (SELECT 1), b AS (DELETE FROM t RETURNING *) SELECT * FROM a, b", "SELECT 1 FROM t WHERE x IN (WITH d AS (DELETE FROM t RETURNING x) SELECT x FROM d)"
        )) rejected(sql)
    }

    @Test fun `further dangerous functions and credential relations are refused`() {
        for (f in listOf("pg_notify('c','p')", "pg_wal_replay_pause()", "pg_stat_reset()", "pg_log_backend_memory_contexts(1)", "pg_export_snapshot()", "pg_copy_physical_replication_slot('a','b')",
            "pg_show_all_settings()", "inet_server_addr()", "pg_sleep_until('tomorrow')", "pg_advisory_xact_lock(1)", "pg_logical_slot_get_changes('a',null,null)", "lo_unlink(1)", "lo_get(1)")) {
            rejected("SELECT $f")
            rejected("SELECT * FROM pg_catalog.\"${f.substringBefore('(')}\"()")
        }
        for (r in listOf("pg_authid", "pg_shadow", "pg_largeobject", "pg_hba_file_rules", "pg_file_settings", "pg_user_mappings", "pg_stat_activity", "pg_settings", "PG_AUTHID")) {
            rejected("SELECT * FROM $r")
            rejected("SELECT * FROM pg_catalog.$r")
            rejected("SELECT * FROM pg_catalog.\"${r.lowercase()}\"")
        }
        rejected("SELECT 1 RETURNING x")
        SqlGuard.compile("SELECT relname FROM pg_class WHERE relkind = 'r'")          // ordinary catalog reads stay available
    }

    @Test fun `escape-string and dollar-quote lexing cannot be used to hide a second statement`() {
        rejected("SELECT 'a'; DROP TABLE x -- E'")
        rejected("SELECT E'x' ; SELECT 1")
        rejected("SELECT e'\\'' ; DROP TABLE x --'")        // E'\'' is one literal; what follows the ; is a second statement
        rejected("SELECT \$a\$ x \$b\$ ; \$a\$; DROP TABLE x")
        rejected("SELECT \$é\$ x \$é\$")                    // tags the lexer cannot model are refused, not guessed
        SqlGuard.compile("SELECT \$a\$ x \$b\$ ; DROP \$a\$ AS t")
    }

    @Test fun `rejection messages are fixed text`() {
        val m = rejected("SELECT 1; DROP TABLE secret_customers").message!!
        assertThat(m).doesNotContain("secret_customers")
    }
}
