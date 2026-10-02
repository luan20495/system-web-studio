package com.systemwebstudio.identity.oidc

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ClientRegistrations

/**
 * Generic OpenID Connect client. Nothing vendor specific: any standards-compliant provider that publishes
 * /.well-known/openid-configuration under OIDC_ISSUER_URI works (Keycloak, Entra ID, Okta, Google, ...).
 * The registration is resolved lazily so an IdP outage at boot does not stop the API (local login keeps working);
 * a failed discovery is retried on the next login attempt.
 */
@Configuration
@ConditionalOnProperty("app.oidc.enabled", havingValue = "true")
class OidcConfiguration {
    @Bean
    fun clientRegistrationRepository(
        @Value("\${app.oidc.issuer-uri}") issuer: String,
        @Value("\${app.oidc.client-id}") clientId: String,
        @Value("\${app.oidc.client-secret}") clientSecret: String,
        @Value("\${app.oidc.scopes:openid,profile,email}") scopes: String,
        @Value("\${app.oidc.redirect-uri:}") redirectUri: String
    ): ClientRegistrationRepository {
        require(issuer.isNotBlank() && clientId.isNotBlank()) { "OIDC_ENABLED=true requires OIDC_ISSUER_URI and OIDC_CLIENT_ID" }
        val scopeList = scopes.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        require("openid" in scopeList) { "OIDC_SCOPES must include openid" }
        return object : ClientRegistrationRepository {
            @Volatile private var cached: ClientRegistration? = null
            private fun load(): ClientRegistration = cached ?: synchronized(this) {
                cached ?: ClientRegistrations.fromIssuerLocation(issuer).registrationId(REGISTRATION_ID)
                    .clientId(clientId).clientSecret(clientSecret).scope(scopeList)
                    .redirectUri(redirectUri.ifBlank { "{baseUrl}/login/oauth2/code/{registrationId}" })
                    .build().also { cached = it }
            }
            override fun findByRegistrationId(registrationId: String): ClientRegistration? =
                if (registrationId == REGISTRATION_ID) runCatching { load() }.getOrNull() else null
        }
    }

    companion object { const val REGISTRATION_ID = "oidc" }
}
