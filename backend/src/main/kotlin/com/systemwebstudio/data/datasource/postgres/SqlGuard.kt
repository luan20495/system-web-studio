package com.systemwebstudio.data.datasource.postgres

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes

/** A validated query: [sql] uses JDBC `?` placeholders and has no comments or trailing `;`; [paramNames] gives the name bound to each `?`, in order. */
class CompiledSql(val sql: String, val paramNames: List<String>)

/**
 * Static check of an *approved* SQL template, run when a definition is registered and again before every execution.
 *
 * This is defence in depth, not the guarantee. The guarantee is that the session itself is read-only (`default_transaction_read_only`,
 * `Connection.setReadOnly(true)`, always rolled back), has a `statement_timeout`, and connects as a role that holds only SELECT. What the
 * guard adds is a refusal, before anything reaches the server, of:
 *
 * - more than one statement (pgjdbc would otherwise execute `SELECT 1; DROP TABLE x` as two statements of one `prepareStatement`),
 * - anything that does not start with `SELECT` / `WITH`,
 * - statement keywords that write, change session state or take locks (`INSERT`, `SET`, `COPY`, `INTO`, `FOR UPDATE`, …) wherever they appear
 *   — including inside a data-modifying CTE,
 * - functions that reach outside the query (`set_config`, `dblink*`, `pg_read_*`, `lo_*`, `query_to_xml`, `pg_terminate_backend`, …),
 * - `$n` positional placeholders and a bare `?` (only `:name` parameters exist), so a value can never be spliced into the text.
 *
 * Lexing follows PostgreSQL: `'…'` (and `E'…'` with backslash escapes), `"…"`, `$tag$…$tag$`, `--` (ended by `\n` or `\r`, as PostgreSQL does) and nested
 * block comments. The compiled text is re-emitted from the tokens (comments dropped), so the server sees exactly what was checked.
 *
 * Fail closed on everything the lexer cannot model with certainty: `U&"…"` / `U&'…'` Unicode-escape forms (the identifier would be decoded by the
 * server *after* the name check — `U&"\0073et_config"` is `set_config`), NUL and other control characters, non-ASCII whitespace or symbols outside
 * quoted text (PostgreSQL would read them as identifier characters), and catalog relations that expose credentials. The session additionally
 * requires `standard_conforming_strings = on` (see [PgSessions]); with it off a backslash would escape quotes in plain `'…'` and the lexing above
 * would be wrong.
 */
object SqlGuard {
    const val MAX_LENGTH = 20_000

    private val FORBIDDEN_WORDS = setOf(
        "insert", "update", "delete", "merge", "drop", "alter", "create", "truncate", "grant", "revoke", "copy", "call", "do", "set", "reset",
        "lock", "vacuum", "analyze", "cluster", "refresh", "reindex", "listen", "notify", "unlisten", "prepare", "execute", "deallocate", "discard",
        "begin", "commit", "rollback", "savepoint", "into", "load", "checkpoint", "import", "security", "returning"
    )
    /** Catalog relations that expose credentials, server files or other sessions' statements; refused by name (quoted or not, schema-qualified or not). */
    private val FORBIDDEN_RELATIONS = setOf(
        "pg_authid", "pg_shadow", "pg_auth_members", "pg_largeobject", "pg_largeobject_metadata", "pg_hba_file_rules", "pg_ident_file_mappings",
        "pg_file_settings", "pg_user_mappings", "pg_subscription", "pg_stat_activity", "pg_stat_ssl", "pg_stat_replication", "pg_settings"
    )
    private val FORBIDDEN_FUNCTIONS = Regex(
        "^(set_config|pg_sleep.*|dblink.*|lo_.*|pg_read_.*|pg_ls_.*|pg_stat_file|pg_file_.*|pg_terminate_backend|pg_cancel_backend|pg_reload_conf|" +
            "pg_rotate_logfile|pg_switch_wal|pg_create_.*|pg_drop_.*|pg_logical_.*|pg_replication_.*|pg_advisory_.*|nextval|setval|" +
            "query_to_xml.*|cursor_to_xml.*|database_to_xml.*|schema_to_xml.*|table_to_xml.*|pg_promote|pg_backup_.*|pg_start_backup|pg_stop_backup|pg_notify|pg_wal_.*|pg_stat_reset.*|pg_sync_.*|pg_log_.*|pg_export_snapshot|" +
            "pg_import_.*|pg_copy_.*|pg_reset_.*|pg_original_.*|pg_show_.*|pg_extension_config_dump|inet_server_.*|inet_client_.*)$"
    )

    private enum class Kind { WORD, QUOTED, SYMBOL }
    private class Token(val kind: Kind, val text: String)

    fun compile(sql: String): CompiledSql {
        if (sql.isBlank() || sql.length > MAX_LENGTH) throw invalid("query text is empty or too long")
        if (sql.any { it.code < 32 && it !in ASCII_WS || it.code == 127 }) throw invalid("control characters are not allowed")
        val out = StringBuilder(); val params = ArrayList<String>(); val tokens = ArrayList<Token>()
        var i = 0; var statementEnded = false
        val n = sql.length
        fun peek(k: Int = 0) = if (i + k < n) sql[i + k] else '\u0000'

        while (i < n) {
            val c = sql[i]
            when {
                c in ASCII_WS -> { out.append(c); i++ }
                c == '-' && peek(1) == '-' -> { while (i < n && sql[i] != '\n' && sql[i] != '\r') i++; out.append(' ') }
                c == '/' && peek(1) == '*' -> {
                    var depth = 1; i += 2
                    while (i < n && depth > 0) {
                        if (sql[i] == '/' && peek(1) == '*') { depth++; i += 2 } else if (sql[i] == '*' && peek(1) == '/') { depth--; i += 2 } else i++
                    }
                    if (depth != 0) throw invalid("unterminated comment")
                    out.append(' ')
                }
                else -> {
                    if (statementEnded) throw invalid("multiple statements are not allowed")
                    when {
                        c == '\'' -> { val escape = i > 0 && sql[i - 1].lowercaseChar() == 'e' && (i < 2 || !isIdentChar(sql[i - 2])); i = skipQuoted(sql, i, '\'', escape, out) }
                        c == '"' -> { val start = i; i = skipQuoted(sql, i, '"', false, out); tokens += Token(Kind.QUOTED, sql.substring(start + 1, i - 1).replace("\"\"", "\"")) }
                        c == '$' -> {
                            val tag = DOLLAR_TAG.matchAt(sql, i)
                            if (tag != null) {
                                val close = sql.indexOf(tag.value, i + tag.value.length)
                                if (close < 0) throw invalid("unterminated dollar-quoted string")
                                out.append(sql, i, close + tag.value.length); i = close + tag.value.length
                            } else throw invalid("positional parameters are not allowed; use :name")
                        }
                        c == ':' && peek(1) == ':' -> { out.append("::"); i += 2; tokens += Token(Kind.SYMBOL, "::") }
                        c == ':' && peek(1).isLetter() -> {
                            val m = NAME.matchAt(sql, i + 1) ?: throw invalid("invalid parameter name")
                            params += m.value; out.append('?'); i += 1 + m.value.length; tokens += Token(Kind.SYMBOL, "?")
                        }
                        c == ':' -> throw invalid("unexpected ':'")
                        c == '?' -> throw invalid("'?' is not allowed; use :name")
                        c == '&' && (peek(1) == '"' || peek(1) == '\'') && i > 0 && sql[i - 1].lowercaseChar() == 'u' ->
                            throw invalid("Unicode-escaped identifiers and strings (U&\"…\") are not allowed")
                        c == ';' -> { statementEnded = true; i++ }
                        c.code > 127 && !c.isLetter() -> throw invalid("only ASCII whitespace and symbols are allowed outside quoted text")
                        c.isLetter() || c == '_' -> {
                            val start = i; i++
                            while (i < n && isIdentChar(sql[i])) i++
                            val word = sql.substring(start, i); out.append(word); tokens += Token(Kind.WORD, word.lowercase())
                        }
                        c.isDigit() -> { val start = i; while (i < n && (sql[i].isLetterOrDigit() || sql[i] == '.' || sql[i] == '_')) i++; out.append(sql, start, i) }
                        else -> { out.append(c); i++; tokens += Token(Kind.SYMBOL, c.toString()) }
                    }
                }
            }
        }
        check(tokens)
        return CompiledSql(out.toString().trim(), params)
    }

    private fun check(tokens: List<Token>) {
        val first = tokens.firstOrNull { it.kind == Kind.WORD } ?: throw invalid("query text is empty")
        if (tokens.first().kind != Kind.WORD || (first.text != "select" && first.text != "with")) throw invalid("only SELECT queries are allowed")
        for ((idx, t) in tokens.withIndex()) {
            if ((t.kind == Kind.WORD || t.kind == Kind.QUOTED) && t.text.lowercase() in FORBIDDEN_RELATIONS) throw invalid("catalog relation ${t.text} is not allowed")
            if (t.kind == Kind.WORD && t.text in FORBIDDEN_WORDS) throw invalid("a read-only query cannot use ${t.text.uppercase()}")
            if (t.kind == Kind.WORD && t.text == "for") {
                val next = tokens.getOrNull(idx + 1)
                if (next != null && next.kind == Kind.WORD && next.text in setOf("share", "key", "no")) throw invalid("row locking is not allowed")
            }
            // a function call is a name directly followed by '(' — quoted names count too ("set_config"(…) is the same function)
            if ((t.kind == Kind.WORD || t.kind == Kind.QUOTED) && tokens.getOrNull(idx + 1)?.let { it.kind == Kind.SYMBOL && it.text == "(" } == true &&
                FORBIDDEN_FUNCTIONS.matches(t.text.lowercase())) throw invalid("function ${t.text} is not allowed")
        }
    }

    private fun skipQuoted(sql: String, start: Int, quote: Char, backslash: Boolean, out: StringBuilder): Int {
        var i = start + 1
        while (i < sql.length) {
            val c = sql[i]
            if (backslash && c == '\\') { i += 2; continue }
            if (c == quote) { if (i + 1 < sql.length && sql[i + 1] == quote) { i += 2; continue }; out.append(sql, start, i + 1); return i + 1 }
            i++
        }
        throw invalid("unterminated quoted text")
    }

    private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
    private fun invalid(reason: String) = ConnectorFailure(FailureCodes.INVALID_QUERY, "query rejected: $reason")

    private val ASCII_WS = setOf(' ', '\t', '\n', '\r', '\u000C', '\u000B')
    private val DOLLAR_TAG = Regex("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$")
    private val NAME = Regex("[a-z][A-Za-z0-9_]{0,39}")
}
