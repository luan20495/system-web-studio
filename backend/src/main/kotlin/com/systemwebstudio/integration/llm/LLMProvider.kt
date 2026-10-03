package com.systemwebstudio.integration.llm

import com.systemwebstudio.schema.SchemaOperation
import tools.jackson.databind.JsonNode

/** What the model may see: the prompt, the current page and the approved registry. Nothing else. */
data class LLMRequest(
    val prompt: String, val pageSchema: JsonNode, val components: List<ComponentInfo>, val model: String? = null,
    /** free models "auto" must skip for this user (model access rules) */
    val exclude: Set<String> = emptySet(),
    /** set for streamed prompts: receives partial output, carries cancel and deadline */
    val sink: StreamSink? = null,
    /** server-side tools the model may call (authorized and audited by the caller) */
    val tools: AiTools? = null,
    /** approved blocks offered as presets (retrieval priority: components, then blocks) */
    val blocks: List<BlockInfo> = emptyList()
)
data class BlockInfo(val id: String, val name: String, val baseComponent: String, val props: JsonNode)

/** Streaming target. `cancelled`/`deadline` are checked while reading; the controller interrupts a blocked read when either fires. */
interface StreamSink {
    fun delta(text: String)
    fun status(text: String) {}
    val cancelled: Boolean
    val deadline: java.time.Instant?
    /** true while the worker blocks on a provider stream (only then may it be interrupted) */
    var inModelCall: Boolean
    fun stopReason(): String? = when { cancelled -> "CANCELLED"; deadline?.isBefore(java.time.Instant.now()) == true -> "TIMEOUT"; else -> null }
}

/** A streamed call ended early by the user (CANCELLED) or the deadline (TIMEOUT); `partial` is what had arrived. */
class AiStopped(val reason: String, val partial: String, val usage: ChatUsage?) : RuntimeException(reason)

/** Runs one tool for the model; returns the result text (data for the model) or throws [ToolRefused]. */
fun interface ToolRunner { fun run(name: String, arguments: JsonNode): String }
class ToolRefused(val outcome: String, message: String) : RuntimeException(message)
/** `specs` = tool descriptions for the system prompt */
data class AiTools(val specs: String, val runner: ToolRunner)
data class ComponentInfo(val id: String, val category: String, val latestVersion: String, val propsSchema: JsonNode? = null)

/** Structured output only. The application validates and authorizes it; the model never touches storage. */
data class LLMResponse(
    val intent: String, val operations: List<SchemaOperation>, val message: String, val provider: String? = null, val model: String? = null,
    /** every upstream call made to produce this answer, failed fail-over attempts included (empty for the simulator) */
    val calls: List<AiCall> = emptyList(),
    /** CANCELLED | TIMEOUT when a streamed prompt ended early; `partial` holds the output received so far */
    val stopped: String? = null, val partial: String? = null,
    /** ids the model says it reused ({"blocks":[..],"templates":[..]}); verified by the caller before being recorded */
    val claimedSources: Map<String, List<String>>? = null
)

/** One upstream model call. outcome: OK | BAD_OUTPUT (answered, unusable) | ERROR (no usable answer, e.g. HTTP 429/timeout). */
data class AiCall(
    val provider: String, val model: String, val outcome: String, val httpStatus: Int?, val usage: ChatUsage?, val latencyMs: Long,
    /** set by AiUsageService.price: PROVIDER (reported) or CATALOG (tokens × pricing row `pricingId`); null = cost unknown */
    val costSource: String? = null, val pricingId: java.util.UUID? = null
)

/** Port for any model backend (mock, OpenAI, Claude, Gemini, local). Core code depends only on this. */
interface LLMProvider {
    val name: String
    fun plan(request: LLMRequest): LLMResponse
}
