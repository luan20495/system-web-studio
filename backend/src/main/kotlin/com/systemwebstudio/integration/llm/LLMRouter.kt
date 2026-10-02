package com.systemwebstudio.integration.llm

import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * The provider the rest of the application sees. Uses OpenRouter when an API key is configured (and the caller did not ask for
 * "mock"), otherwise the deterministic simulator, so the app works end to end before a key exists.
 */
@Primary
@Component
class LLMRouter(private val mock: MockLLMProvider, private val openRouter: OpenRouterLLMProvider, private val ai: AiService) : LLMProvider {
    override val name: String get() = if (ai.externalEnabled) "openrouter" else "mock"

    override fun plan(request: LLMRequest): LLMResponse {
        if (!ai.isExternal(request.model)) return mock.plan(request).let { it.copy(provider = "mock", model = "mock") }
        return openRouter.plan(request)
    }
}
