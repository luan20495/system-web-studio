package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.ActionType
import com.systemwebstudio.logic.action.DryRunLevel
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * `action_runs.result`: the outcome of a run, exactly as a replay must answer with it. `{"t":"OK","output":...}`, `{"t":"FAILED","code","retryable","message","details"}`
 * (the code and `retryable` are what keep an ambiguous write `IDEMPOTENCY_OUTCOME_UNKNOWN` / not retryable across a restart) and, for completeness,
 * `{"t":"WOULD_RUN",...}` (TEST mode never reaches the store; the shape exists so that no [ActionResult] can fail to be stored).
 */
class ActionResultCodec(private val json: JsonMapper) {
    fun encode(result: ActionResult): String {
        val o: ObjectNode = json.createObjectNode()
        when (result) {
            is ActionResult.Ok -> { o.put("t", "OK"); o.set("output", result.output) }
            is ActionResult.Failed -> {
                o.put("t", "FAILED"); o.put("code", result.code); o.put("retryable", result.retryable)
                result.message?.let { o.put("message", it) }
                val d = json.createObjectNode()
                result.details.forEach { (k, v) -> d.put(k, v) }
                o.set("details", d)
            }
            is ActionResult.WouldRun -> {
                o.put("t", "WOULD_RUN"); o.put("actionId", result.actionId); o.put("type", result.type.name); o.put("level", result.level.name)
                o.set("plan", result.plan)
                result.output?.let { o.set("output", it) }
                result.reason?.let { o.put("reason", it) }
            }
        }
        return json.writeValueAsString(o)
    }

    fun decode(text: String): ActionResult {
        val n = json.readTree(text)
        return when (val t = n.get("t")?.asString()) {
            "OK" -> ActionResult.Ok(n.get("output") ?: throw IllegalStateException("stored action result has no output"))
            "FAILED" -> {
                val details = LinkedHashMap<String, String>()
                n.get("details")?.takeIf { it.isObject }?.let { d -> for (k in d.propertyNames()) details[k] = d.get(k).asString() }
                ActionResult.Failed(n.get("code").asString(), n.get("retryable").asBoolean(), n.get("message")?.takeIf { !it.isNull }?.asString(), details)
            }
            "WOULD_RUN" -> ActionResult.WouldRun(
                n.get("actionId").asString(), ActionType.valueOf(n.get("type").asString()), DryRunLevel.valueOf(n.get("level").asString()),
                n.get("plan"), n.get("output")?.takeIf { !it.isNull }, n.get("reason")?.takeIf { !it.isNull }?.asString()
            )
            else -> throw IllegalStateException("unknown stored action result type: $t")
        }
    }
}
