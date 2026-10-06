package com.systemwebstudio.data.datasource

/** Stable, machine-readable failure codes of the Data Platform connectors. The set is closed on purpose: callers branch on it, messages are for humans. */
object FailureCodes {
    const val INVALID_CONFIG = "INVALID_CONFIG"
    const val INVALID_CREDENTIAL = "INVALID_CREDENTIAL"
    const val SECRETS_UNAVAILABLE = "SECRETS_UNAVAILABLE"
    const val NOT_FOUND = "NOT_FOUND"
    const val DISABLED = "DISABLED"
    const val TENANT_MISMATCH = "TENANT_MISMATCH"
    const val UNSUPPORTED_TYPE = "UNSUPPORTED_TYPE"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val ADDRESS_BLOCKED = "ADDRESS_BLOCKED"
    const val HOST_UNRESOLVED = "HOST_UNRESOLVED"
    const val CONNECT_FAILED = "CONNECT_FAILED"
    const val TLS_FAILED = "TLS_FAILED"
    const val TIMEOUT = "TIMEOUT"
    const val AUTH_REJECTED = "AUTH_REJECTED"
    const val REDIRECT_BLOCKED = "REDIRECT_BLOCKED"
    const val UPSTREAM_STATUS = "UPSTREAM_STATUS"
    const val RESPONSE_INVALID = "RESPONSE_INVALID"
    const val RESPONSE_TOO_LARGE = "RESPONSE_TOO_LARGE"
    const val RESPONSE_NOT_JSON = "RESPONSE_NOT_JSON"
    const val QUERY_NOT_FOUND = "QUERY_NOT_FOUND"
    const val INVALID_QUERY = "INVALID_QUERY"
    const val INVALID_PARAMS = "INVALID_PARAMS"
    const val QUERY_FAILED = "QUERY_FAILED"
    const val READ_ONLY_VIOLATION = "READ_ONLY_VIOLATION"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val ROLE_TOO_PRIVILEGED = "ROLE_TOO_PRIVILEGED"
    const val INTERNAL = "INTERNAL"

    // --- T9–T11 additions (additive; existing codes keep their meaning)
    const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
    const val CONFLICT = "CONFLICT"
    const val MUTATION_NOT_FOUND = "MUTATION_NOT_FOUND"
    const val MUTATION_UNSUPPORTED = "MUTATION_UNSUPPORTED"
    const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
    const val IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS"
    /** an earlier attempt with this key failed ambiguously (timeout, connection lost, …): it may or may not have been applied, so the key stays reserved */
    const val IDEMPOTENCY_OUTCOME_UNKNOWN = "IDEMPOTENCY_OUTCOME_UNKNOWN"
    /** the data source refused the change and applied nothing (a definite, non-ambiguous failure of a write) */
    const val MUTATION_REJECTED = "MUTATION_REJECTED"
    const val INVALID_MAPPING = "INVALID_MAPPING"
    const val MAPPING_FAILED = "MAPPING_FAILED"
    const val INVALID_EXPRESSION = "INVALID_EXPRESSION"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val SYNC_ORDER_VIOLATION = "SYNC_ORDER_VIOLATION"
    const val SYNC_JOB_NOT_FOUND = "SYNC_JOB_NOT_FOUND"
    const val REFRESH_TOO_SOON = "REFRESH_TOO_SOON"
}

/**
 * The only exception connectors let escape. [safeMessage] is built from fixed text (never from an exception message, URL, host, address,
 * connection string, SQL or credential), so it can be returned to a caller, logged and audited. There is deliberately no `cause`: driver
 * and JDK exceptions routinely embed hosts, IPs and sometimes credentials, and they must not travel with this exception into a log line.
 */
class ConnectorFailure(val code: String, val safeMessage: String) : RuntimeException("$code: $safeMessage", null, false, false) {
    override fun toString() = "ConnectorFailure($code: $safeMessage)"
}

/**
 * Second line of defence for any dynamic text that has to be shown: every known secret value is replaced by `***`. The first line is that
 * messages are fixed text; this exists so that a future change that interpolates something cannot leak a credential it knows about.
 */
object Redactor {
    private const val MIN_SECRET = 3
    fun redact(text: String, secrets: Collection<String>): String =
        secrets.filter { it.length >= MIN_SECRET }.sortedByDescending { it.length }.fold(text) { acc, s -> acc.replace(s, "***") }
}
