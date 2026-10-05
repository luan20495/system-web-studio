package com.systemwebstudio.integration.llm

import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * The provider the rest of the application sees: an external model (OpenRouter free models for "auto", or an explicitly chosen
 * enabled model of another provider) when one is configured, otherwise the deterministic simulator, so the app works end to end
 * before any key exists.
 */
@Primary
@Component
class LLMRouter(private val mock: MockLLMProvider, private val external: ExternalLLMProvider, private val ai: AiService) : LLMProvider {
    override val name: String get() = if (ai.externalEnabled) "openrouter" else "mock"

    override fun plan(request: LLMRequest): LLMResponse {
        if (!ai.isExternal(request.model)) return mock.plan(request).let { it.copy(provider = "mock", model = "mock") }
        return external.plan(request)
    }
}
