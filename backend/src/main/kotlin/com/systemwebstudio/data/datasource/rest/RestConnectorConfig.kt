package com.systemwebstudio.data.datasource.rest

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.HostNamePolicy
import com.systemwebstudio.data.query.QueryLimits
import java.net.URI

/**
 * Non-secret configuration of a REST data source (`DataSourceRef.configNonSecret`):
 *
 * | key | meaning |
 * |---|---|
 * | `baseUrl` | `https://public.host[:port][/prefix]` — no credentials, no query |
 * | `authHeader` | optional header name that carries the credential (`authValue` in the sealed credential) |
 * | `testPath` | path probed by the connection test (default `/`) |
 * | `timeoutMs`, `maxResponseBytes`, `maxRows` | per-call limits, clamped to [QueryLimits] ceilings |
 *
 * Unknown keys are rejected: a secret typed into the wrong field must fail loudly instead of being stored as "config".
 */
class RestConnectorConfig private constructor(
    val host: String, val port: Int, val basePath: String, val authHeader: String?, val testPath: String,
    val timeoutMillis: Int, val maxResponseBytes: Int, val maxRows: Int
) {
    /** `https://host[:port]` + [basePath] with no trailing slash */
    val origin: String get() = "https://" + host + (if (port == 443) "" else ":$port")

    companion object {
        private val KEYS = setOf("baseUrl", "authHeader", "testPath", "timeoutMs", "maxResponseBytes", "maxRows")
        private val BASE_PATH = Regex("^(/[A-Za-z0-9._~-]+)*/?$")
        private val TEST_PATH = Regex("^/([A-Za-z0-9._~-]+/?)*$")
        private val HEADER = Regex("^[A-Za-z][A-Za-z0-9-]{0,63}$")
        private val FORBIDDEN_AUTH_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "upgrade", "te", "trailer", "expect",
            "proxy-authorization", "proxy-connection", "keep-alive", "accept-encoding", "user-agent", "accept")

        fun parse(config: Map<String, String>): RestConnectorConfig {
            if (config.keys.any { it !in KEYS }) bad("unknown configuration key")
            val raw = config["baseUrl"]?.trim()
            if (raw.isNullOrEmpty() || raw.length > 500) bad("baseUrl")
            val uri = try { URI(raw) } catch (e: Exception) { bad("baseUrl") }
            if (uri.scheme != "https" || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.rawAuthority.isNullOrEmpty()) bad("baseUrl must be https://host[/path] without credentials or query")
            HostNamePolicy.reject(uri.host)?.let { bad("baseUrl host: $it") }
            val port = if (uri.port == -1) 443 else uri.port
            if (port !in 1..65535) bad("baseUrl port")
            val path = (uri.rawPath ?: "").trimEnd('/')
            if (!BASE_PATH.matches(path) || path.split('/').any { it == ".." || it == "." }) bad("baseUrl path")
            val authHeader = config["authHeader"]?.takeIf { it.isNotEmpty() }
            if (authHeader != null && (!HEADER.matches(authHeader) || authHeader.lowercase() in FORBIDDEN_AUTH_HEADERS)) bad("authHeader")
            val testPath = config["testPath"]?.takeIf { it.isNotEmpty() } ?: "/"
            if (!TEST_PATH.matches(testPath) || testPath.split('/').any { it == ".." || it == "." }) bad("testPath")
            return RestConnectorConfig(
                uri.host.lowercase().trimEnd('.'), port, path, authHeader, testPath,
                int(config, "timeoutMs", QueryLimits.DEFAULT_TIMEOUT_MS, QueryLimits.MIN_TIMEOUT_MS, QueryLimits.MAX_TIMEOUT_MS),
                int(config, "maxResponseBytes", QueryLimits.DEFAULT_RESPONSE_BYTES, 1_000, QueryLimits.MAX_RESPONSE_BYTES),
                int(config, "maxRows", QueryLimits.DEFAULT_ROWS, 1, QueryLimits.MAX_ROWS)
            )
        }

        private fun int(c: Map<String, String>, key: String, default: Int, min: Int, max: Int): Int {
            val v = c[key] ?: return default
            return v.toIntOrNull()?.takeIf { it in min..max } ?: bad(key)
        }

        /** the message names the field only — a rejected value may itself be a credential (userinfo, a pasted token) */
        private fun bad(what: String): Nothing = throw ConnectorFailure(FailureCodes.INVALID_CONFIG, "invalid configuration: $what")
    }
}
