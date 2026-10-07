package com.systemwebstudio.data.datasource.postgres

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.data.query.QueryLimits

/**
 * Non-secret configuration of a PostgreSQL data source (`DataSourceRef.configNonSecret`):
 *
 * | key | meaning |
 * |---|---|
 * | `host` | DNS name (or IPv4 literal) of the server — checked by [PostgresTargetPolicy] |
 * | `port` | default 5432 |
 * | `database` | database name |
 * | `sslmode` | only `verify-full` (also the default): TLS with certificate-chain **and host-name** verification against `host`. `require`/`verify-ca` (no host-name check, or no check at all), `prefer`, `allow` and `disable` are rejected: there is no way to weaken or turn TLS off |
 * | `schemas` | comma-separated schemas schema discovery may list (default `public`) |
 * | `timeoutMs`, `maxRows`, `maxResponseBytes` | per-call limits, clamped to [QueryLimits] ceilings |
 * | `writable` | `true` lets the approved mutations of this data source INSERT/UPDATE/DELETE (B-C0-W-04); default `false` = read-only, exactly as before. Queries and discovery always run in a read-only session, whatever this says |
 * | `maxAffectedRows` | most rows one mutation may change (default 1000, at most 100000); a statement that would change more is rolled back and refused |
 *
 * The role name and password are **credential** (`username`, `password`), never configuration.
 */
class PostgresConnectorConfig private constructor(
    val host: String, val port: Int, val database: String, val sslMode: String, val schemas: List<String>,
    val timeoutMillis: Int, val maxRows: Int, val maxResponseBytes: Int,
    val writable: Boolean = false, val maxAffectedRows: Int = DEFAULT_MAX_AFFECTED
) {
    companion object {
        const val DEFAULT_MAX_AFFECTED = 1_000
        const val MAX_AFFECTED = 100_000
        private val KEYS = setOf("host", "port", "database", "sslmode", "schemas", "timeoutMs", "maxRows", "maxResponseBytes", "writable", "maxAffectedRows")
        private val DB = Regex("^[A-Za-z0-9_.-]{1,63}$")
        private val SCHEMA = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")
        private val SSL_MODES = setOf("verify-full")
        private val HOST_CHARS = Regex("^[A-Za-z0-9.-]{1,253}$")
        private val ROLE = Regex("^[A-Za-z0-9_.@-]{1,63}$")

        fun parse(config: Map<String, String>): PostgresConnectorConfig {
            if (config.keys.any { it !in KEYS }) bad("unknown configuration key")
            val host = config["host"]?.trim()?.lowercase()?.trimEnd('.')
            // only characters of a DNS name / IPv4 literal reach the JDBC URL: no ':', '/', '?', ',' or '@' that could add hosts or driver options
            if (host.isNullOrEmpty() || !HOST_CHARS.matches(host)) bad("host")
            val port = config["port"]?.let { it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: bad("port") } ?: 5432
            val db = config["database"]?.takeIf { DB.matches(it) } ?: bad("database")
            val ssl = (config["sslmode"] ?: "verify-full").also { if (it !in SSL_MODES) bad("sslmode") }
            val schemas = (config["schemas"]?.takeIf { it.isNotBlank() } ?: "public").split(',').map { it.trim() }
            if (schemas.isEmpty() || schemas.size > 20 || schemas.any { !SCHEMA.matches(it) }) bad("schemas")
            return PostgresConnectorConfig(
                host, port, db, ssl, schemas.distinct(),
                int(config, "timeoutMs", QueryLimits.DEFAULT_TIMEOUT_MS, QueryLimits.MIN_TIMEOUT_MS, QueryLimits.MAX_TIMEOUT_MS),
                int(config, "maxRows", QueryLimits.DEFAULT_ROWS, 1, QueryLimits.MAX_ROWS),
                int(config, "maxResponseBytes", QueryLimits.DEFAULT_RESPONSE_BYTES, 1_000, QueryLimits.MAX_RESPONSE_BYTES),
                when (config["writable"]) { null, "false" -> false; "true" -> true; else -> bad("writable") },
                int(config, "maxAffectedRows", DEFAULT_MAX_AFFECTED, 1, MAX_AFFECTED)
            )
        }

        /** the role/password pair; refuses values that could be mistaken for driver URL syntax or contain control characters */
        fun credentialOf(cred: ResolvedCredential): Pair<String, String> {
            val user = cred.require("username"); val pass = cred.require("password")
            if (!ROLE.matches(user) || pass.length > 1_000 || pass.any { it.code < 0x20 }) throw ConnectorFailure(FailureCodes.INVALID_CREDENTIAL, "credential has an invalid shape")
            return user to pass
        }

        private fun int(c: Map<String, String>, key: String, default: Int, min: Int, max: Int): Int {
            val v = c[key] ?: return default
            return v.toIntOrNull()?.takeIf { it in min..max } ?: bad(key)
        }

        private fun bad(what: String): Nothing = throw ConnectorFailure(FailureCodes.INVALID_CONFIG, "invalid configuration: $what")
    }
}
