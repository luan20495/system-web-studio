package com.systemwebstudio.wiring

import com.systemwebstudio.data.datasource.postgres.PostgresTargetPolicy

/**
 * C0 · B-C0-W-06 · the production `postgres` target policy from configuration (D-C0-31). Pure, so the whole decision is unit-testable without Spring.
 *
 * - Default (nothing configured, every profile except `local`): public addresses only, platform and apps databases denied, **nothing private allowed**.
 * - `app.data-platform.postgres-targets.allowed-private`: the explicit allow-list, a comma-separated list of exact `host:port` endpoints (no wildcard, no CIDR,
 *   no host-wide entry; the policy rejects a bad entry and the application does not start). Empty in the base and the production profile; the `local` profile
 *   sets only the V1 local data target. V2: the remote host name of an internal replica, by configuration only.
 * - `app.data-platform.postgres-targets.denied`: extra targets that are refused even if allow-listed (`host` or `host:port`). The platform's own databases
 *   (`spring.datasource.url`, `app.runtime.appdb-url`) are always denied: a loopback platform database as `host:port`, any other host entirely.
 * - Deny wins over allow.
 */
object PostgresTargetPolicies {
    fun fromConfig(platformJdbcUrl: String?, appDbJdbcUrl: String?, allowedPrivate: List<String>, extraDenied: List<String>): PostgresTargetPolicy {
        fun entries(l: List<String>) = l.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val urls = listOfNotNull(platformJdbcUrl?.takeIf { it.isNotBlank() }, appDbJdbcUrl?.takeIf { it.isNotBlank() })
        return PostgresTargetPolicy.denyingPlatformDatabases(*urls.toTypedArray(), allowedPrivateHosts = entries(allowedPrivate), extraDenied = entries(extraDenied))
    }
}
