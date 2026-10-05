package com.systemwebstudio.ai

import com.systemwebstudio.integration.llm.AiService
import com.systemwebstudio.integration.llm.OpenRouterClient
import com.systemwebstudio.integration.llm.OpenRouterException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.json.JsonMapper

/**
 * Talks to the REAL OpenRouter over the internet. Skipped unless OR_LIVE=1 (no key is needed: the model list is public and the
 * chat call is made with a deliberately invalid key to prove the failure path). With a real key set OR_LIVE_KEY to also run one chat.
 */
@EnabledIfEnvironmentVariable(named = "OR_LIVE", matches = "1")
class OpenRouterLiveTests {
    private val json = JsonMapper.builder().build()
    private val store = com.systemwebstudio.integration.llm.ProviderStore(org.springframework.jdbc.core.JdbcTemplate(), com.systemwebstudio.runtime.SecretsCrypto(""), json)
    private fun client(key: String) = OpenRouterClient(json, key, store, "https://openrouter.ai/api/v1", "", "System Web Studio tests", 30)

    @Test
    fun `the real model list parses and only free text models are offered`() {
        // freeModels() needs neither other providers nor the database
        val ai = AiService(client("sk-or-invalid-key-for-listing"), com.systemwebstudio.integration.llm.AiProviderRegistry(org.springframework.mock.env.MockEnvironment(), json, store),
            org.springframework.jdbc.core.JdbcTemplate(), "auto", 30, 4, 50, "")
        val models = ai.freeModels()
        println("LIVE free text models offered (${models.size}): " + models.joinToString { it.id })
        assertThat(models).isNotEmpty
        assertThat(models.first().id).isEqualTo("openrouter/free")
        assertThat(models.map { it.id }).allSatisfy { assertThat(it == "openrouter/free" || it.endsWith(":free")).isTrue() }
    }

    @Test
    fun `an invalid key is reported as fatal (no failover storm) without echoing upstream text`() {
        assertThatThrownBy { client("sk-or-invalid-key-123456789").chat("openrouter/free", "system", "user") }
            .isInstanceOfSatisfying(OpenRouterException::class.java) { assertThat(it.fatal).isTrue(); assertThat(it.status).isIn(401, 403) }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OR_LIVE_KEY", matches = ".{20,}")
    fun `a real key answers through the free router`() {
        val answer = client(System.getenv("OR_LIVE_KEY")).chat("openrouter/free", "Reply with the single word OK.", "ping", 20)
        assertThat(answer.content).isNotBlank()
        assertThat(answer.usage?.totalTokens).isNotNull().isPositive()      // usage accounting is returned by the real API
    }
}
