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
        if (problems.isNotEmpty()) throw IllegalStateException("Unsafe production configuration:\n - " + problems.joinToString("\n - "))
    }
}
