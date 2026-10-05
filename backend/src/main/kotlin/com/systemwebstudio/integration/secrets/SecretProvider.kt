package com.systemwebstudio.integration.secrets

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/** Port for secret lookup. Local: environment. Production adapters (Vault, cloud KMS/secret managers) plug in here. */
interface SecretProvider {
    fun get(name: String): String?
    fun require(name: String): String = get(name)?.takeIf { it.isNotBlank() } ?: error("Secret '$name' is not configured")
}

@Component
class EnvironmentSecretProvider(private val env: Environment) : SecretProvider {
    override fun get(name: String): String? = env.getProperty(name)
}
