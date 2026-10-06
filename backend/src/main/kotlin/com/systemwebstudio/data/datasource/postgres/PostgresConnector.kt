package com.systemwebstudio.data.datasource.postgres

import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataConnector
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceTypes
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.HostResolver
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.data.datasource.SystemHostResolver
import com.systemwebstudio.data.discovery.DiscoveredEntity
import com.systemwebstudio.data.discovery.DiscoveredRelation
import com.systemwebstudio.data.discovery.DiscoveryOptions
import com.systemwebstudio.data.discovery.SampleMasker
import com.systemwebstudio.data.discovery.DiscoveredField
import com.systemwebstudio.data.discovery.DiscoveredSchema
import com.systemwebstudio.data.discovery.EntityKind
import com.systemwebstudio.data.discovery.NormalizedType
import com.systemwebstudio.data.discovery.SchemaDiscovery
import com.systemwebstudio.data.query.Column
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.QueryCatalog
import com.systemwebstudio.data.query.QueryExecutor
import com.systemwebstudio.data.query.QueryParams
import com.systemwebstudio.data.query.QueryRequest
import com.systemwebstudio.data.query.QueryResult
import com.systemwebstudio.data.query.SqlQueryDefinition
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.Base64
import java.util.Properties
import javax.net.ssl.SSLException

/** Everything needed to open one connection. No readable `toString`: it holds the password. */
class PgTarget(val config: PostgresConnectorConfig, val addresses: List<InetAddress>, val user: String, val password: String) {
    override fun toString() = "PgTarget(***)"
}

/** Seam so the guards around opening a connection are testable without a database; production is [JdbcPgConnectionFactory]. */
fun interface PgConnectionFactory {
    @Throws(SQLException::class)
    fun open(target: PgTarget): Connection
}

/**
 * The JDBC URL and driver properties of one connection — a pure function so the security-relevant settings are unit-testable without a database.
 *
 * - TLS: `ssl=true`, `sslmode=verify-full` (certificate chain **and** host name checked against the data source's `host`, not against the pinned
 *   address) with `sslfactory=DefaultJavaSSLFactory` (the JVM's trust store; a tenant cannot supply a CA file path). `require` is never produced: it
 *   encrypts without verifying anything, so a man-in-the-middle with any certificate would be accepted. The only other value is `disable`, and only
 *   when a test explicitly builds the factory without TLS.
 * - session: read-only transactions, `statement_timeout`, `lock_timeout`, `idle_in_transaction_session_timeout`; connect/login/socket timeouts.
 * - credentials are driver properties, never part of the URL; the socket is pinned to the already-checked addresses.
 */
internal object PgConnectionProperties {
    fun url(cfg: PostgresConnectorConfig) = "jdbc:postgresql://${cfg.host}:${cfg.port}/${cfg.database}"

    fun build(target: PgTarget, enforceTls: Boolean): Properties {
        val cfg = target.config
        val seconds = ((cfg.timeoutMillis + 999) / 1000).toString()
        val pinned = target.addresses.joinToString(",") { it.hostAddress.substringBefore('%') }
        return Properties().apply {
            setProperty("user", target.user); setProperty("password", target.password)
            if (enforceTls) {
                setProperty("ssl", "true"); setProperty("sslmode", "verify-full")
                setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory")
            } else setProperty("sslmode", "disable")
            setProperty("connectTimeout", seconds); setProperty("loginTimeout", seconds); setProperty("socketTimeout", seconds)
            setProperty("options", "-c default_transaction_read_only=on -c statement_timeout=${cfg.timeoutMillis} -c lock_timeout=2000 -c idle_in_transaction_session_timeout=${cfg.timeoutMillis}")
            setProperty("ApplicationName", "xweb-data-connector")
            setProperty("stringtype", "unspecified")          // string parameters adopt the column type (uuid, enum, …) instead of failing
            setProperty("gssEncMode", "disable")
            setProperty("tcpKeepAlive", "false")
            setProperty("socketFactory", PinnedSocketFactory::class.java.name)
            setProperty(PinnedSocketFactory.PROP, pinned)
            setProperty("socketFactoryArg", pinned)
        }
    }
}

/**
 * PostgreSQL JDBC connection (driver is the platform's existing `org.postgresql` runtime dependency). Every connection is:
 * TLS-only with `verify-full` (see [PgConnectionProperties]), connected through [PinnedSocketFactory] to the already-checked addresses, **read-only at
 * the session level** (`default_transaction_read_only=on`), bounded (`statement_timeout`, lock/idle timeouts, connect and socket timeouts) and
 * single-statement by construction (see [SqlGuard]). Credentials go into driver properties, never into the URL.
 */
class JdbcPgConnectionFactory internal constructor(
    /** `true` in production and the only value reachable from outside this module; a test against a plain-TCP container passes `false` */
    internal val enforceTls: Boolean
) : PgConnectionFactory {
    constructor() : this(true)

    override fun open(target: PgTarget): Connection =
        DriverManager.getConnection(PgConnectionProperties.url(target.config), PgConnectionProperties.build(target, enforceTls))
}

/** SQLSTATE → fixed code/message. The driver's own message is never used: it can quote SQL, identifiers, hosts and server version. */
internal object PgErrors {
    private val log = LoggerFactory.getLogger(PgErrors::class.java)

    fun map(e: SQLException): ConnectorFailure {
        val chain = generateSequence<Throwable>(e) { it.cause }.take(8).toList()
        log.debug("postgres failure: state={} type={}", e.sqlState, e.javaClass.simpleName)
        val state = e.sqlState ?: ""
        return when {
            chain.any { it is SocketTimeoutException } -> ConnectorFailure(FailureCodes.TIMEOUT, "the database did not answer in time")
            state == "57014" -> ConnectorFailure(FailureCodes.TIMEOUT, "the query exceeded the time limit")
            state == "28000" || state == "28P01" -> ConnectorFailure(FailureCodes.AUTH_REJECTED, "the database rejected the credential")
            chain.any { it is SSLException } -> ConnectorFailure(FailureCodes.TLS_FAILED, "secure connection could not be established")
            state == "25006" -> ConnectorFailure(FailureCodes.READ_ONLY_VIOLATION, "the connection is read-only; the statement was rejected")
            state == "42501" -> ConnectorFailure(FailureCodes.PERMISSION_DENIED, "the database role is not allowed to read that")
            state.startsWith("08") || state == "3D000" || state == "53300" || state.startsWith("57P") -> ConnectorFailure(FailureCodes.CONNECT_FAILED, "the database could not be reached")
            else -> ConnectorFailure(FailureCodes.QUERY_FAILED, "the query could not be executed")
        }
    }
}

/**
 * What must be true of *this* session before anything the caller supplied runs on it. Run for **every** connection a [PgSessions] hands out — query,
 * discovery, sampling and connection test alike — and never cached across connections: a role can be altered (made superuser, added to
 * `pg_read_server_files`) between two connections, and a pooled or re-resolved target can land on a different server.
 *
 * Refused (fail closed — a missing row, an unexpected value or a failing check query all abort the session):
 * - the transaction is not read-only (`READ_ONLY_VIOLATION`);
 * - `standard_conforming_strings` is not `on` ([SqlGuard]'s lexing assumes a backslash is an ordinary character in `'…'`);
 * - the role is a superuser, or reaches one through membership (`is_superuser`, `rolsuper`, `pg_has_role(…, 'USAGE')` on any superuser role);
 * - the role is a member of any predefined `pg_*` role (`pg_read_server_files`, `pg_write_server_files`, `pg_execute_server_program`,
 *   `pg_read_all_data`, `pg_signal_backend`, `pg_monitor`, …);
 * - the role has `CREATEROLE`, `CREATEDB`, `REPLICATION` or `BYPASSRLS`.
 * All of these are `ROLE_TOO_PRIVILEGED` with fixed text: neither role names nor server details are reported.
 */
internal object PgSessionPreflight {
    private const val SQL = """SELECT current_setting('transaction_read_only'), current_setting('standard_conforming_strings'), current_setting('is_superuser'),
        r.rolsuper, r.rolcreaterole, r.rolcreatedb, r.rolreplication, r.rolbypassrls,
        EXISTS (SELECT 1 FROM pg_roles s WHERE s.rolsuper AND pg_has_role(current_user, s.oid, 'USAGE')),
        EXISTS (SELECT 1 FROM pg_roles g WHERE left(g.rolname, 3) = 'pg_' AND pg_has_role(current_user, g.oid, 'MEMBER'))
        FROM pg_roles r WHERE r.rolname = current_user"""

    fun verify(conn: Connection) {
        conn.createStatement().use { st -> st.executeQuery(SQL).use { rs ->
            if (!rs.next()) throw tooPrivileged("the database role could not be verified")
            if (rs.getString(1) != "on") throw ConnectorFailure(FailureCodes.READ_ONLY_VIOLATION, "could not enforce a read-only session")
            if (rs.getString(2) != "on") throw ConnectorFailure(FailureCodes.READ_ONLY_VIOLATION, "the session does not use standard string literals")
            if (rs.getString(3) != "off" || rs.getBoolean(4) || rs.getBoolean(9)) throw tooPrivileged("the database role must not be a superuser")
            if (rs.getBoolean(10)) throw tooPrivileged("the database role must not be a member of a predefined administrative role")
            if (rs.getBoolean(5) || rs.getBoolean(6) || rs.getBoolean(7) || rs.getBoolean(8))
                throw tooPrivileged("the database role has administrative privileges; use a SELECT-only role")
        } }
    }

    private fun tooPrivileged(message: String) = ConnectorFailure(FailureCodes.ROLE_TOO_PRIVILEGED, message)
}

/** Resolve → policy → connect → read-only session → run → always roll back and close. The one place a connection is created. */
internal class PgSessions(private val policy: PostgresTargetPolicy, private val resolver: HostResolver, private val factory: PgConnectionFactory) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> withConnection(ds: DataSourceRef, cred: ResolvedCredential, block: (Connection, PostgresConnectorConfig) -> T): T {
        val cfg = PostgresConnectorConfig.parse(ds.configNonSecret)
        val (user, password) = PostgresConnectorConfig.credentialOf(cred)
        val addresses = policy.resolve(cfg.host, resolver)                               // refuses before any connection exists
        val conn = try { factory.open(PgTarget(cfg, addresses, user, password)) }
            catch (e: SQLException) { throw PgErrors.map(e) }
            catch (e: ConnectorFailure) { throw e }
            catch (e: Exception) { log.debug("connect failure: {}", e.javaClass.simpleName); throw ConnectorFailure(FailureCodes.CONNECT_FAILED, "the database could not be reached") }
        try {
            conn.autoCommit = false
            conn.isReadOnly = true                                                       // the driver opens the transaction as BEGIN READ ONLY
            // PostgreSQL lets a read-only transaction switch itself to read-write (SET TRANSACTION READ WRITE) until its first query has run.
            // Running one here, before any caller-supplied statement, closes that window: from now on that switch fails with 25006/25001.
            conn.createStatement().use { st -> st.executeQuery("SELECT 1").use { it.next() } }
            PgSessionPreflight.verify(conn)                                              // EVERY session, before any caller-supplied statement
            return block(conn, cfg)
        } catch (e: SQLException) {
            throw PgErrors.map(e)
        } finally {
            runCatching { conn.rollback() }                                              // nothing is ever committed
            runCatching { conn.close() }
        }
    }
}

class PostgresConnector(
    catalog: QueryCatalog,
    private val policy: PostgresTargetPolicy = PostgresTargetPolicy(),
    resolver: HostResolver = SystemHostResolver,
    connections: PgConnectionFactory = JdbcPgConnectionFactory()
) : DataConnector {
    override val type = DataSourceTypes.POSTGRES
    private val sessions = PgSessions(policy, resolver, connections)
    private val exec = PostgresQueryExecutor(catalog, sessions)
    private val discovery = PostgresSchemaDiscovery(sessions)

    override fun validateConfig(config: Map<String, String>) {
        val cfg = PostgresConnectorConfig.parse(config)
        policy.checkSyntax(cfg.host)
    }
    override fun discovery(): SchemaDiscovery = discovery
    override fun executor(): QueryExecutor = exec

    override fun test(ds: DataSourceRef, cred: ResolvedCredential): ConnectionTestResult = try {
        val started = System.nanoTime()
        val warnings = sessions.withConnection(ds, cred) { conn, cfg ->
            // the read-only / role checks already ran in PgSessions for this very connection
            val writable = conn.prepareStatement(WRITABLE_TABLES).use { ps ->
                ps.setArray(1, conn.createArrayOf("text", cfg.schemas.toTypedArray()))
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
            if (writable > 0) listOf("the database role can write to $writable table(s); use a SELECT-only role") else emptyList()
        }
        ConnectionTestResult.Ok((System.nanoTime() - started) / 1_000_000, warnings)
    } catch (e: ConnectorFailure) {
        ConnectionTestResult.Failed(e.code, e.safeMessage)
    }

    private companion object {
        const val WRITABLE_TABLES = """SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ANY (?) AND c.relkind IN ('r', 'p') AND (has_table_privilege(c.oid, 'INSERT') OR has_table_privilege(c.oid, 'UPDATE')
            OR has_table_privilege(c.oid, 'DELETE') OR has_table_privilege(c.oid, 'TRUNCATE'))"""
    }
}

class PostgresQueryExecutor internal constructor(private val catalog: QueryCatalog, private val sessions: PgSessions) : QueryExecutor {

    override fun execute(req: QueryRequest, ds: DataSourceRef, cred: ResolvedCredential): QueryResult {
        if (req.tenant.tenantId != ds.tenantId) throw ConnectorFailure(FailureCodes.TENANT_MISMATCH, "data source does not belong to the tenant")
        val def = catalog.find(ds.tenantId, ds.id, req.queryId) as? SqlQueryDefinition ?: throw ConnectorFailure(FailureCodes.QUERY_NOT_FOUND, "query not found")
        val compiled = SqlGuard.compile(def.sql)                                          // re-checked on every run, not only at registration
        val declared = def.params.map { it.name }.toSet()
        if (compiled.paramNames.any { it !in declared }) throw ConnectorFailure(FailureCodes.INVALID_QUERY, "query uses an undeclared parameter")
        val bound = QueryParams.bind(def.params, req.params)

        return sessions.withConnection(ds, cred) { conn, cfg ->
            val limit = minOf(def.maxRows, cfg.maxRows, req.page?.limit ?: Int.MAX_VALUE)
            val offset = req.page?.offset ?: 0
            // the caller's page is applied by the database, the approved SQL stays one opaque subquery
            conn.prepareStatement("SELECT * FROM (${compiled.sql}) AS xweb_q LIMIT ? OFFSET ?").use { st ->
                st.queryTimeout = (cfg.timeoutMillis + 999) / 1000
                st.maxRows = limit + 1
                st.fetchSize = minOf(limit + 1, 500)
                var i = 1
                for (name in compiled.paramNames) bind(st, i++, bound[name])
                st.setInt(i++, limit + 1); st.setInt(i, offset)
                st.executeQuery().use { rs -> PgRows.read(rs, limit, cfg.maxResponseBytes) }
            }
        }
    }

    private fun bind(st: java.sql.PreparedStatement, i: Int, v: Any?) {
        when (v) {
            null -> st.setNull(i, Types.NULL)
            is String -> st.setString(i, v)
            is Long -> st.setLong(i, v)
            is java.math.BigDecimal -> st.setBigDecimal(i, v)
            is Boolean -> st.setBoolean(i, v)
            is java.time.OffsetDateTime -> st.setObject(i, v)
            is java.time.LocalDate -> st.setObject(i, v)
            else -> throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "unsupported parameter type")
        }
    }

    companion object {
        internal fun normalize(sqlType: Int, typeName: String?): NormalizedType = when {
            typeName != null && (typeName.equals("json", true) || typeName.equals("jsonb", true)) -> NormalizedType.JSON
            sqlType == Types.BIT || sqlType == Types.BOOLEAN -> NormalizedType.BOOLEAN
            sqlType in setOf(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT) -> NormalizedType.INTEGER
            sqlType in setOf(Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL) -> NormalizedType.NUMBER
            sqlType == Types.DATE -> NormalizedType.DATE
            sqlType == Types.TIME || sqlType == Types.TIME_WITH_TIMEZONE -> NormalizedType.TIME
            sqlType == Types.TIMESTAMP || sqlType == Types.TIMESTAMP_WITH_TIMEZONE -> NormalizedType.TIMESTAMP
            sqlType in setOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB) -> NormalizedType.BINARY
            sqlType in setOf(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR) -> NormalizedType.STRING
            typeName != null && typeName.equals("uuid", true) -> NormalizedType.STRING
            else -> NormalizedType.OTHER
        }
    }
}

/** ResultSet → bounded JSON rows, shared by the query executor and discovery sampling. */
internal object PgRows {
    fun read(rs: ResultSet, limit: Int, maxBytes: Int): QueryResult {
        val md = rs.metaData
        val n = md.columnCount
        val names = (1..n).map { md.getColumnLabel(it) }
        val types = (1..n).map { PostgresQueryExecutor.normalize(md.getColumnType(it), md.getColumnTypeName(it)) }
        val rows = ArrayList<Map<String, JsonNode>>(); var bytes = 0L; var truncated = false
        while (rs.next()) {
            if (rows.size >= limit) { truncated = true; break }
            val row = LinkedHashMap<String, JsonNode>(n * 2)
            for (c in 1..n) {
                val (node, size) = cell(rs, c, md.getColumnType(c))
                row[names[c - 1]] = node; bytes += size + names[c - 1].length
            }
            if (bytes > maxBytes) { truncated = true; break }                             // this row is dropped: the cap is on what we return
            rows += row
        }
        return QueryResult(names.indices.map { Column(names[it], types[it]) }, rows, truncated)
    }

    private fun cell(rs: ResultSet, i: Int, sqlType: Int): Pair<JsonNode, Int> {
        val value: Any? = when (sqlType) {
            Types.BIT, Types.BOOLEAN -> rs.getBoolean(i)
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> rs.getLong(i)
            Types.REAL, Types.FLOAT, Types.DOUBLE -> rs.getDouble(i).let { if (it.isFinite()) it else it.toString() }
            Types.NUMERIC, Types.DECIMAL -> rs.getBigDecimal(i)
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> rs.getBytes(i)?.let { Base64.getEncoder().encodeToString(it) }
            else -> rs.getString(i)
        }
        if (rs.wasNull() || value == null) return DataJson.NULL to 4
        val size = if (value is String) value.length else 24
        return DataJson.toNode(value) to size
    }
}

/**
 * Table/column discovery from `information_schema` + `pg_constraint`, limited to the data source's configured schemas, through the same
 * read-only session: columns (type, nullability), primary keys, foreign keys (as relations) and — only when the caller asks — a few rows per
 * table that are **masked right here** ([SampleMasker]) before they leave this function. Sampling is bounded (entities, rows, bytes, total
 * time) and one table that cannot be read (privileges, timeout) costs a warning, not the discovery: each sample runs behind a savepoint,
 * because a failed statement would otherwise poison the rest of the transaction.
 */
class PostgresSchemaDiscovery internal constructor(private val sessions: PgSessions) : SchemaDiscovery {
    override fun discover(ds: DataSourceRef, cred: ResolvedCredential): DiscoveredSchema = discover(ds, cred, DiscoveryOptions())

    override fun discover(ds: DataSourceRef, cred: ResolvedCredential, options: DiscoveryOptions): DiscoveredSchema = sessions.withConnection(ds, cred) { conn, cfg ->
        class Entity(val schema: String, val name: String, val kind: EntityKind, val fields: MutableList<DiscoveredField> = ArrayList())
        val entities = LinkedHashMap<Pair<String, String>, Entity>(); var fields = 0; var truncated = false
        val schemaArray = conn.createArrayOf("text", cfg.schemas.toTypedArray())
        conn.prepareStatement(COLUMNS).use { ps ->
            ps.queryTimeout = (cfg.timeoutMillis + 999) / 1000
            ps.setArray(1, schemaArray)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val key = rs.getString(1) to rs.getString(2)
                    var entity = entities[key]
                    if (entity == null) {
                        if (entities.size >= MAX_ENTITIES) { truncated = true; break }
                        entity = Entity(key.first, key.second, if (rs.getString(3) == "VIEW") EntityKind.VIEW else EntityKind.TABLE).also { entities[key] = it }
                    }
                    if (fields >= MAX_FIELDS) { truncated = true; break }
                    entity.fields += DiscoveredField(rs.getString(4), typeOf(rs.getString(5)), rs.getString(7) == "YES", rs.getString(5)); fields++
                }
            }
        }

        // primary keys, in key order
        val pks = HashMap<Pair<String, String>, MutableList<String>>()
        conn.prepareStatement(PRIMARY_KEYS).use { ps ->
            ps.queryTimeout = (cfg.timeoutMillis + 999) / 1000
            ps.setArray(1, schemaArray)
            ps.executeQuery().use { rs -> while (rs.next()) pks.getOrPut(rs.getString(1) to rs.getString(2)) { ArrayList() } += rs.getString(3) }
        }
        // foreign keys → relations (only towards entities that were listed, so a relation never names something the caller cannot see)
        val relations = HashMap<Pair<String, String>, MutableList<DiscoveredRelation>>()
        conn.prepareStatement(FOREIGN_KEYS).use { ps ->
            ps.queryTimeout = (cfg.timeoutMillis + 999) / 1000
            ps.setArray(1, schemaArray)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val from = rs.getString(1) to rs.getString(2)
                    val toSchema = rs.getString(5); val toTable = rs.getString(6)
                    if (from !in entities || (toSchema to toTable) !in entities) continue
                    relations.getOrPut(from) { ArrayList() } += DiscoveredRelation(rs.getString(3), names(rs.getArray(4)), toTable, toSchema, names(rs.getArray(7)))
                }
            }
        }

        val warnings = ArrayList<String>()
        if (truncated) warnings += "listing truncated at $MAX_ENTITIES entities / $MAX_FIELDS fields"
        val samples = HashMap<Pair<String, String>, List<Map<String, JsonNode>>>()
        if (options.sampleRows > 0) {
            val started = System.nanoTime(); val budgetNanos = cfg.timeoutMillis.toLong() * 2 * 1_000_000
            var skipped = 0; var stopped = false
            for ((key, entity) in entities.entries.take(DiscoveryOptions.MAX_SAMPLED_ENTITIES)) {
                if (System.nanoTime() - started > budgetNanos) { stopped = true; break }
                val sp = conn.setSavepoint()
                try {
                    conn.prepareStatement("SELECT * FROM ${quote(entity.schema)}.${quote(entity.name)} LIMIT ?").use { st ->
                        st.queryTimeout = (cfg.timeoutMillis + 999) / 1000
                        st.maxRows = options.sampleRows
                        st.setInt(1, options.sampleRows)
                        val rows = st.executeQuery().use { rs -> PgRows.read(rs, options.sampleRows, minOf(cfg.maxResponseBytes, SAMPLE_BYTES)).rows }
                        samples[key] = SampleMasker.maskRows(rows, options.sampleRows)          // masked before it leaves this function
                    }
                } catch (e: SQLException) {
                    skipped++
                    runCatching { conn.rollback(sp) }
                } finally { runCatching { conn.releaseSavepoint(sp) } }
            }
            if (skipped > 0) warnings += "sample skipped for $skipped entities (not readable)"
            if (stopped) warnings += "sampling stopped at the time budget"
            if (entities.size > DiscoveryOptions.MAX_SAMPLED_ENTITIES) warnings += "samples limited to the first ${DiscoveryOptions.MAX_SAMPLED_ENTITIES} entities"
        }

        DiscoveredSchema(entities.map { (key, e) ->
            val pk = pks[key].orEmpty()
            DiscoveredEntity(e.name, e.schema, e.kind, e.fields.map { it.copy(primaryKey = it.name in pk) }, primaryKey = pk,
                relations = relations[key].orEmpty(), metadata = mapOf("source" to "postgres", "schema" to e.schema, "kind" to e.kind.name.lowercase()),
                sample = samples[key].orEmpty())
        }, truncated, warnings)
    }

    private fun names(array: java.sql.Array?): List<String> = try { (array?.array as? Array<*>)?.map { it.toString() }.orEmpty() } finally { runCatching { array?.free() } }

    /** identifiers come from the catalogue (and only from the configured schemas); they are quoted, never concatenated raw */
    internal fun quote(identifier: String) = "\"" + identifier.replace("\"", "\"\"") + "\""

    internal fun typeOf(dataType: String?): NormalizedType = when (dataType?.lowercase()) {
        "smallint", "integer", "bigint" -> NormalizedType.INTEGER
        "numeric", "decimal", "real", "double precision", "money" -> NormalizedType.NUMBER
        "boolean" -> NormalizedType.BOOLEAN
        "date" -> NormalizedType.DATE
        "time without time zone", "time with time zone" -> NormalizedType.TIME
        "timestamp without time zone", "timestamp with time zone" -> NormalizedType.TIMESTAMP
        "bytea" -> NormalizedType.BINARY
        "json", "jsonb" -> NormalizedType.JSON
        "character varying", "character", "text", "uuid", "citext", "name" -> NormalizedType.STRING
        else -> NormalizedType.OTHER
    }

    private companion object {
        const val MAX_ENTITIES = 500
        const val MAX_FIELDS = 10_000
        const val SAMPLE_BYTES = 200_000
        /**
         * Primary keys from the catalog, like [FOREIGN_KEYS]. NOT from `information_schema.table_constraints`/`key_column_usage`: those views list a
         * constraint only to a role that owns the table or holds a privilege other than SELECT on it, so the SELECT-only role discovery is meant to run
         * as would see no primary keys at all.
         */
        const val PRIMARY_KEYS = """SELECT ns.nspname, cl.relname, a.attname
            FROM pg_constraint con
            JOIN pg_class cl ON cl.oid = con.conrelid JOIN pg_namespace ns ON ns.oid = cl.relnamespace
            CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
            JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum
            WHERE con.contype = 'p' AND ns.nspname = ANY (?)
            ORDER BY ns.nspname, cl.relname, k.ord"""
        const val FOREIGN_KEYS = """SELECT ns.nspname, cl.relname, con.conname,
              (SELECT array_agg(a.attname ORDER BY k.ord) FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord) JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum),
              fns.nspname, fcl.relname,
              (SELECT array_agg(a.attname ORDER BY k.ord) FROM unnest(con.confkey) WITH ORDINALITY AS k(attnum, ord) JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.attnum)
            FROM pg_constraint con
            JOIN pg_class cl ON cl.oid = con.conrelid JOIN pg_namespace ns ON ns.oid = cl.relnamespace
            JOIN pg_class fcl ON fcl.oid = con.confrelid JOIN pg_namespace fns ON fns.oid = fcl.relnamespace
            WHERE con.contype = 'f' AND ns.nspname = ANY (?)
            ORDER BY ns.nspname, cl.relname, con.conname"""
        const val COLUMNS = """SELECT c.table_schema, c.table_name, t.table_type, c.column_name, c.data_type, c.udt_name, c.is_nullable
            FROM information_schema.columns c JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
            WHERE c.table_schema = ANY (?) AND t.table_type IN ('BASE TABLE', 'VIEW')
            ORDER BY c.table_schema, c.table_name, c.ordinal_position"""
    }
}
