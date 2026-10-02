package com.systemwebstudio.integration.llm

import com.systemwebstudio.schema.SchemaOperation
import tools.jackson.databind.JsonNode

/** What the model may see: the prompt, the current page and the approved registry. Nothing else. */
data class LLMRequest(val prompt: String, val pageSchema: JsonNode, val components: List<ComponentInfo>, val model: String? = null)
data class ComponentInfo(val id: String, val category: String, val latestVersion: String, val propsSchema: JsonNode? = null)

/** Structured output only. The application validates and authorizes it; the model never touches storage. */
data class LLMResponse(
    val intent: String, val operations: List<SchemaOperation>, val message: String, val provider: String? = null, val model: String? = null,
    /** every upstream call made to produce this answer, failed fail-over attempts included (empty for the simulator) */
    val calls: List<AiCall> = emptyList()
)

/** One upstream model call. outcome: OK | BAD_OUTPUT (answered, unusable) | ERROR (no usable answer, e.g. HTTP 429/timeout). */
data class AiCall(val provider: String, val model: String, val outcome: String, val httpStatus: Int?, val usage: ChatUsage?, val latencyMs: Long)

/** Port for any model backend (mock, OpenAI, Claude, Gemini, local). Core code depends only on this. */
interface LLMProvider {
    val name: String
    fun plan(request: LLMRequest): LLMResponse
}
