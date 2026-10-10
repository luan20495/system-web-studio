package com.systemwebstudio.data.datasource.postgres

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.MutationExecRequest
import com.systemwebstudio.data.datasource.MutationExecutor
import com.systemwebstudio.data.datasource.MutationOutcome
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.QueryParamSpec
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.sql.Connection
import java.sql.SQLException

/*
 * B-C0-W-04 · the production writable PostgreSQL connector.
 *
 * What a mutation IS here. An approved mutation (`MutationDefinition`, never client SQL) names a table and the role of its parameters in `target`:
 *
 *     target := [schema.]table [key=p1[,p2…]] [returning=col1[,col2…]]          (single spaces; at most 200 characters)
 *
 *  - CREATE → `INSERT INTO schema.table (<supplied params>) VALUES (?, …)`            (no `key`)
 *  - UPDATE → `UPDATE schema.table SET <supplied params that are not keys> WHERE key1 = ? AND key2 = ?`   (`key` required)
 *  - DELETE → `DELETE FROM schema.table WHERE key1 = ? AND key2 = ?`                   (`key` required; no other parameter)
 *  - SUBMIT → not a database operation: `MUTATION_UNSUPPORTED`
 *
 * A parameter's NAME is its column name (`QueryParamSpec.NAME` = `[a-z][A-Za-z0-9_]{0,39}`). The statement text is built ONLY from identifiers that were
 * validated against a strict pattern and are always double-quoted; the schema must be one of the data source's configured `schemas`. Every value travels as a
 * bound parameter — there is no code path that writes a caller's value into SQL text. UPDATE/DELETE without a key (which would touch the whole table) cannot
 * be expressed, and a statement that would change more than `maxAffectedRows` rows is rolled back and refused.
 *
 * Transaction: one explicit transaction per mutation — statement, row-count check, ONE commit. Failure mapping is in [PgWriteErrors] (frozen §4b semantics:
 * only what the server itself reported proves "nothing applied"; a lost answer, a timeout or a connection error leaves the outcome unknown).
 *
 * Idempotency is NOT the connector's job: the gateway reserves the key before this runs, keeps it reserved after an ambiguous failure and replays the stored
 * result afterwards, so a replay never reaches this class. NULL cannot be written (parameter binding has no JSON null), and values are text/integer/number/
 * boolean/timestamp/date as `QueryParams` coerces them.
 */

/** The parsed `target` of a PostgreSQL mutation. Pure: no I/O. */
internal class PgMutationTarget private constructor(val schema: String, val table: String, val keys: List<String>, val returning: List<String>) {
    companion object {
        private val IDENT = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")
        private const val MAX_KEYS = 10
        private const val MAX_RETURNING = 20

        fun parse(target: String, cfg: PostgresConnectorConfig): PgMutationTarget {
            val tokens = target.trim().split(' ').filter { it.isNotEmpty() }            // only a plain space separates; any other whitespace fails the identifier checks
            if (tokens.isEmpty() || tokens.size > 3) bad("shape")
            val ref = tokens[0].split('.')
            val schema: String; val table: String
            when (ref.size) { 1 -> { schema = cfg.schemas.first(); table = ref[0] }; 2 -> { schema = ref[0]; table = ref[1] }; else -> bad("table") }
            if (!IDENT.matches(schema) || !IDENT.matches(table)) bad("table")
            if (schema !in cfg.schemas) bad("schema is not one of the configured schemas")
            var keys: List<String>? = null; var returning: List<String>? = null
            for (token in tokens.drop(1)) {
                val eq = token.indexOf('=')
                if (eq < 0) bad("option")
                val values = token.substring(eq + 1).split(',')
                when (token.substring(0, eq)) {
                    "key" -> {
                        if (keys != null || values.size > MAX_KEYS || values.any { !QueryParamSpec.NAME.matches(it) } || values.toSet().size != values.size) bad("key")
                        keys = values
                    }
                    "returning" -> {
                        if (returning != null || values.size > MAX_RETURNING || values.any { !IDENT.matches(it) } || values.toSet().size != values.size) bad("returning")
                        returning = values
                    }
                    else -> bad("option")
                }
            }
            return PgMutationTarget(schema, table, keys.orEmpty(), returning.orEmpty())
        }

        private fun bad(what: String): Nothing = throw ConnectorFailure(FailureCodes.INVALID_CONFIG, "invalid mutation target: $what")
    }
}

/** The statement of one mutation: SQL text with `?` placeholders and the values in placeholder order. No readable `toString` (values may be personal data). */
internal class PgMutationPlan(val kind: MutationKind, val sql: String, val values: List<Any>, val returning: List<String>) {
    override fun toString() = "PgMutationPlan($kind)"
}

internal object PgMutationSql {
    /** [given] = the parameters that were supplied or defaulted, already validated and coerced by `QueryParams`. Pure: no I/O. */
    fun plan(def: MutationDefinition, target: PgMutationTarget, given: Map<String, Any>): PgMutationPlan {
        val declared = def.params.map { it.name }
        val table = "${quote(target.schema)}.${quote(target.table)}"
        val returning = if (target.returning.isEmpty()) "" else " RETURNING " + target.returning.joinToString(", ") { quote(it) }
        return when (def.kind) {
            MutationKind.CREATE -> {
                if (target.keys.isNotEmpty()) throw config("key is only for UPDATE and DELETE")
                val columns = declared.filter { it in given }
                if (columns.isEmpty()) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "there is no value to insert")
                PgMutationPlan(def.kind, "INSERT INTO $table (${columns.joinToString(", ") { quote(it) }}) VALUES (${columns.joinToString(", ") { "?" }})$returning",
                    columns.map { given.getValue(it) }, target.returning)
            }
            MutationKind.UPDATE -> {
                val keys = requireKeys(def, target, declared, given)
                val set = declared.filter { it in given && it !in keys }
                if (set.isEmpty()) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "there is nothing to update")
                PgMutationPlan(def.kind, "UPDATE $table SET ${set.joinToString(", ") { "${quote(it)} = ?" }} WHERE ${where(keys)}$returning",
                    (set + keys).map { given.getValue(it) }, target.returning)
            }
            MutationKind.DELETE -> {
                val keys = requireKeys(def, target, declared, given)
                if (declared.any { it !in keys }) throw config("DELETE takes only key parameters")
                PgMutationPlan(def.kind, "DELETE FROM $table WHERE ${where(keys)}$returning", keys.map { given.getValue(it) }, target.returning)
            }
            MutationKind.SUBMIT -> throw ConnectorFailure(FailureCodes.MUTATION_UNSUPPORTED, "SUBMIT is not a database operation")
        }
    }

    private fun requireKeys(def: MutationDefinition, target: PgMutationTarget, declared: List<String>, given: Map<String, Any>): List<String> {
        if (target.keys.isEmpty()) throw config("${def.kind.name} needs a key (a statement without WHERE is not allowed)")
        if (target.keys.any { it !in declared }) throw config("key is not a declared parameter")
        if (target.keys.any { it !in given }) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "a key parameter is missing")
        return target.keys
    }

    private fun where(keys: List<String>) = keys.joinToString(" AND ") { "${quote(it)} = ?" }
    private fun config(what: String) = ConnectorFailure(FailureCodes.INVALID_CONFIG, "invalid mutation: $what")

    /** identifiers were validated by [PgMutationTarget] / `QueryParamSpec.NAME`; they are quoted anyway, so a name can never end the identifier */
    fun quote(identifier: String) = "\"" + identifier.replace("\"", "\"\"") + "\""
}

class PostgresMutationExecutor internal constructor(private val sessions: PgSessions) : MutationExecutor {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The single `key=` of the approved target, parsed and validated exactly like at execution ([PgMutationTarget.parse]); anything invalid or ambiguous is "no key". */
    override fun recordKey(def: MutationDefinition, ds: DataSourceRef): String? {
        if (def.kind != MutationKind.UPDATE && def.kind != MutationKind.DELETE) return null
        return try {
            PgMutationTarget.parse(def.target, PostgresConnectorConfig.parse(ds.configNonSecret)).keys.singleOrNull()
        } catch (e: ConnectorFailure) { null }
    }

    override fun execute(req: MutationExecRequest, ds: DataSourceRef, cred: ResolvedCredential): MutationOutcome {
        val def = req.definition
        if (def.tenantId != ds.tenantId || def.dataSourceId != ds.id) throw ConnectorFailure(FailureCodes.TENANT_MISMATCH, "data source does not belong to the tenant")
        val cfg = PostgresConnectorConfig.parse(ds.configNonSecret)
        if (!cfg.writable) throw ConnectorFailure(FailureCodes.READ_ONLY_VIOLATION, "this PostgreSQL data source is read-only (writable is not enabled)")
        val plan = PgMutationSql.plan(def, PgMutationTarget.parse(def.target, cfg), req.params)      // every check that needs no database ran before a connection exists
        return try {
            sessions.withWritableConnection(ds, cred) { conn, c -> run(conn, c, plan) }
        } catch (e: ConnectorFailure) {
            throw e
        } catch (e: Exception) {
            log.debug("mutation failed unexpectedly: {}", e.javaClass.simpleName)         // never the message: it can quote SQL and values
            throw ConnectorFailure(FailureCodes.INTERNAL, "internal connector error")      // not on the NOT_EXECUTED list: the outcome is treated as unknown
        }
    }

    private fun run(conn: Connection, cfg: PostgresConnectorConfig, plan: PgMutationPlan): MutationOutcome {
        var affected = 0L
        var output: JsonNode? = null
        try {
            conn.prepareStatement(plan.sql).use { st ->
                st.queryTimeout = (cfg.timeoutMillis + 999) / 1000
                var i = 1
                for (v in plan.values) PgParams.bind(st, i++, v)                           // before anything is sent: an unbindable value is INVALID_PARAMS, nothing executed
                if (plan.returning.isEmpty()) affected = st.executeLargeUpdate()
                else st.executeQuery().use { rs ->
                    val md = rs.metaData
                    val names = (1..md.columnCount).map { md.getColumnLabel(it) }
                    while (rs.next()) {
                        affected++
                        if (affected > cfg.maxAffectedRows) break                          // already over the limit: the exact number does not matter
                        if (output == null) output = DataJson.toNode(LinkedHashMap<String, JsonNode>().also { row ->
                            for (c in 1..md.columnCount) row[names[c - 1]] = PgRows.cell(rs, c, md.getColumnType(c)).first
                        })
                    }
                }
            }
        } catch (e: SQLException) {
            throw PgWriteErrors.map(e, PgWritePhase.EXECUTE)
        }
        if (affected > cfg.maxAffectedRows) {
            runCatching { conn.rollback() }                                                // the transaction is never committed; if the rollback itself fails the server aborts it with the connection
            throw ConnectorFailure(FailureCodes.MUTATION_REJECTED, "the change would affect more rows than allowed (maxAffectedRows); nothing was applied")
        }
        try {
            conn.commit()                                                                  // the ONE commit; an I/O failure here is the ambiguous case (sent, outcome unknown)
        } catch (e: SQLException) {
            throw PgWriteErrors.map(e, PgWritePhase.COMMIT)
        }
        return MutationOutcome(affected, output)
    }
}
