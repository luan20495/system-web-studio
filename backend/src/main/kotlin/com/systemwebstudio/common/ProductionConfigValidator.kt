package com.systemwebstudio.common

import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Refuses to start the "prod" profile with development leftovers. Missing variables are already fatal through the
 * placeholders in application-prod.yml; this catches values that are present but unsafe.
 */
@Component
@Profile("prod")
class ProductionConfigValidator(env: Environment) {
    init {
        val problems = mutableListOf<String>()
        fun value(key: String) = env.getProperty(key).orEmpty()
        if (env.activeProfiles.contains("local")) problems += "profile 'local' (dev seed users, Swagger) must not be active together with 'prod'"
        listOf("spring.datasource.password", "spring.data.redis.password", "spring.rabbitmq.password", "app.storage.secret-key").forEach {
            val v = value(it)
            if (v.length < 12) problems += "$it must be at least 12 characters"
            if (v.contains("local-only") || v.contains("studio-local") || v == "changeme") problems += "$it still looks like a development default"
        }
        if (value("server.servlet.session.cookie.secure") != "true") problems += "session cookie must be Secure"
        val origins = value("app.cors.allowed-origins").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (origins.isEmpty()) problems += "CORS_ALLOWED_ORIGINS must list the public UI origin(s)"
        origins.forEach { if (!it.startsWith("https://")) problems += "CORS origin '$it' must be https in production" else if (it.contains("localhost") || it.contains("127.0.0.1")) problems += "CORS origin '$it' points at localhost" }
        if (value("app.proxy.trust") == "true" && value("app.proxy.trusted-cidrs").isBlank()) problems += "TRUST_PROXY=true requires TRUSTED_PROXY_CIDRS"
        if (value("app.storage.public-endpoint").startsWith("http://") ) problems += "MINIO_PUBLIC_ENDPOINT must be https (presigned URLs carry signatures)"
        if (value("springdoc.api-docs.enabled") == "true") problems += "OpenAPI/Swagger must be disabled in production"
        if (value("app.oidc.enabled") == "true") {
            if (value("app.oidc.client-secret").length < 12) problems += "OIDC_CLIENT_SECRET missing or too short"
            if (!value("app.oidc.issuer-uri").startsWith("https://")) problems += "OIDC_ISSUER_URI must be https"
        }
        // --- features added in stages A–L: unsafe values must stop a production start, off-by-default features must stay safe when switched on
        if (value("app.signup.enabled") == "true" && value("app.signup.allow-in-prod") != "true")
            problems += "public sign-up is enabled: set it off (PUBLIC_SIGNUP_ENABLED=false) or acknowledge it explicitly with SIGNUP_ALLOW_IN_PROD=true"
        value("app.data-platform.postgres-targets.allowed-private").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach {
            if (it.startsWith("127.") || it.startsWith("localhost") || it.startsWith("[::1]") || it.startsWith("::1") || it.startsWith("0.")) problems += "app.data-platform.postgres-targets.allowed-private must not allow a loopback address in production"
        }
        // D-C0-34 · browser-facing and service addresses are stated, never inherited from a development default
        for (k in listOf("app.sites.origin", "app.sites.studio-origin", "app.render.url")) if (value(k).isBlank()) problems += "$k is required in production (SITES_ORIGIN / STUDIO_ORIGIN / RENDER_URL)"
        for (k in listOf("app.sites.origin", "app.sites.studio-origin")) value(k).takeIf { it.isNotBlank() }?.let { v ->
            if (!v.startsWith("https://")) problems += "$k must be https in production"
            else if (BROWSER_HOST_DENY.any { d -> v.lowercase().substringAfter("://").substringBefore('/').substringBefore(':').let { h -> h == d || h.endsWith(".$d") } }) problems += "$k must be a public address, not '$v'"
        }
        value("app.sites.data-api-base").takeIf { it.isNotBlank() }?.let { v ->
            val host = v.lowercase().substringAfter("://", "").substringBefore('/').substringBefore(':')
            if (!v.startsWith("https://")) problems += "SITES_DATA_API_BASE (the apiBase browsers call) must be https in production"
            else if (host.isEmpty() || BROWSER_HOST_DENY.any { d -> host == d || host.endsWith(".$d") } || Regex("^(10|127|192\\.168|172\\.(1[6-9]|2[0-9]|3[01]))\\.").containsMatchIn(host)) problems += "SITES_DATA_API_BASE must be a browser-reachable public address, not an internal or loopback host"
        }
        if (value("app.deploy.provider") == "mock") problems += "DEPLOY_PROVIDER=mock must not be used in production (labelled demo deployments only)"
        if (value("app.bootstrap.admin-username").isNotBlank()) {
            val pw = value("app.bootstrap.admin-password")
            if (pw.length < 14 || pw.contains("local") || pw.contains("changeme") || pw.lowercase() == pw || pw.uppercase() == pw) problems += "BOOTSTRAP_ADMIN_PASSWORD is weak (14+ characters, mixed case)"
        }
        if (value("app.forms.ip-salt").isBlank()) problems += "FORMS_IP_SALT must be set (visitor IP hashes must stay stable across restarts)"
        if (value("app.scim.enabled") == "true" && value("app.scim.token").length < 32) problems += "SCIM_ENABLED=true requires SCIM_TOKEN of at least 32 characters"
        if (value("app.saml.enabled") == "true" && value("app.oidc.enabled") != "true") problems += "SAML_ENABLED=true needs OIDC_ENABLED=true (SAML is brokered by the OIDC provider)"
        if (value("app.runtime.enabled") == "true") {
            if (runCatching { java.util.Base64.getDecoder().decode(value("app.secrets.master-key").trim()).size }.getOrDefault(0) != 32) problems += "SERVER_APPS_ENABLED=true requires SECRETS_MASTER_KEY (base64 of 32 bytes)"
            if (value("app.runtime.gateway-token").length < 32) problems += "SERVER_APPS_ENABLED=true requires APPS_GATEWAY_TOKEN of at least 32 characters"
            if (value("app.runtime.appdb-admin-password").length < 16) problems += "SERVER_APPS_ENABLED=true requires APPDB_ADMIN_PASSWORD of at least 16 characters"
        }
        if (value("app.build.runner-token").isNotBlank() && value("app.build.runner-token").length < 24) problems += "BUILD_RUNNER_TOKEN must be at least 24 characters"
        if (problems.isNotEmpty()) throw IllegalStateException("Unsafe production configuration:\n - " + problems.joinToString("\n - "))
    }

    private companion object {
        /** names a browser can never reach from the internet (an internal host or the docker bridge is service-to-service, not a browser address) */
        val BROWSER_HOST_DENY = setOf("localhost", "host.docker.internal", "internal", "local", "svc", "cluster.local")
    }
}
