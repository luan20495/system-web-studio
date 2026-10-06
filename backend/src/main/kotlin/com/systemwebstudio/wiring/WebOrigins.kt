package com.systemwebstudio.wiring

import com.systemwebstudio.identity.SecurityConfiguration

/**
 * C0 · the three frontend deployments (docs/adr/0022-frontend-monorepo-three-deployments.md): platform, admin, studio. One exact origin each.
 *
 * `wiring.*` is the ONLY package that may import across modules (docs/contracts/v2/integration-contract.md §1). This file is the one piece of it that
 * depends on nothing but the base code, so it exists today; the adapters that need C1–C4 types are in `backend/src/wiring-skeleton/` (not compiled)
 * and move here at the import steps in docs/parallel/MAC_INTEGRATION_CHECKLIST.md. Nothing reads this class yet: the live CORS list is still
 * `app.cors.allowed-origins` (docs/parallel/WEB_SECURITY_CONFIG.md §2). Not compiled when written; run by the Mac gate (`WebOriginsTests`).
 */
data class WebOrigins(val platform: String, val admin: String, val studio: String) {
    init {
        val all = listOf(platform, admin, studio)
        SecurityConfiguration.requireExactOrigins(all)                          // scheme://host[:port], no wildcard, no path
        require(all.toSet().size == 3) { "platform, admin and studio must be three different origins: $all" }
    }

    /** the exact CORS allow-list (credentials are allowed, so never a wildcard) */
    fun cors(): List<String> = listOf(platform, admin, studio)

    /** every redirect URI to register at the IdP client: one per portal (Spring's callback is `/login/oauth2/code/{registrationId}`, registration id `oidc`) */
    fun oidcRedirectUris(): List<String> = cors().map { "$it$OIDC_CALLBACK" }

    /** RP-initiated logout targets to register at the IdP: each portal's own login page */
    fun oidcPostLogoutUris(): List<String> = cors().map { "$it/login" }

    companion object {
        const val OIDC_CALLBACK = "/login/oauth2/code/oidc"
    }
}
