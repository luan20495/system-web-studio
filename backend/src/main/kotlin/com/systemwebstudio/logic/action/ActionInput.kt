package com.systemwebstudio.logic.action

import com.systemwebstudio.logic.arrayItems
import tools.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Inputs that passed [ActionInputBinder]: only declared names, correct JSON types, within limits. */
class ActionInput(val values: Map<String, JsonNode>) {
    operator fun get(name: String): JsonNode? = values[name]
    fun string(name: String): String? = values[name]?.takeIf { it.isString }?.asString()
    val names: Set<String> get() = values.keys

    companion object { val EMPTY = ActionInput(emptyMap()) }
}

sealed interface BindResult {
    data class Bound(val input: ActionInput) : BindResult
    data class Rejected(val failure: ActionResult.Failed) : BindResult
}

/**
 * Checks a raw input map against a definition. Strict: undeclared names are rejected (the dispatch path filters
 * event payloads *before* calling this, so strictness here protects direct callers).
 * Size/depth are checked first and cheaply so an oversized payload is never walked field by field.
 */
object ActionInputBinder {
    fun bind(definition: ActionDefinition, raw: Map<String, JsonNode>, limits: ActionLimits): BindResult {
        val size = raw.entries.sumOf { it.key.toByteArray(StandardCharsets.UTF_8).size + it.value.toString().toByteArray(StandardCharsets.UTF_8).size }
        if (size > limits.maxInputBytes) {
            return BindResult.Rejected(failed(ActionErrorCodes.LIMIT_EXCEEDED, "Input exceeds ${limits.maxInputBytes} bytes"))
        }
        if (raw.values.any { exceedsDepth(it, limits.maxInputDepth, 1) }) {
            return BindResult.Rejected(failed(ActionErrorCodes.LIMIT_EXCEEDED, "Input nesting exceeds ${limits.maxInputDepth} levels"))
        }
        val declared = definition.inputs.associateBy { it.name }
        val problems = linkedMapOf<String, String>()
        for (name in raw.keys) if (name !in declared) problems[name] = "not a declared input"
        for (spec in definition.inputs) {
            val value = raw[spec.name]
            if (value == null || value.isNull) {
                if (spec.required) problems[spec.name] = "is required"
                continue
            }
            typeProblem(spec, value)?.let { problems[spec.name] = it }
        }
        if (problems.isNotEmpty()) {
            return BindResult.Rejected(failed(ActionErrorCodes.INVALID_INPUT, "Input does not match the action definition", details = problems))
        }
        return BindResult.Bound(ActionInput(raw.filterValues { !it.isNull }))
    }

    private fun typeProblem(spec: InputSpec, v: JsonNode): String? = when (spec.type) {
        InputType.ANY -> null
        InputType.STRING -> when {
            !v.isString -> "must be a string"
            spec.maxLength != null && v.asString().length > spec.maxLength -> "must be at most ${spec.maxLength} characters"
            else -> null
        }
        InputType.NUMBER -> if (v.isNumber) null else "must be a number"
        InputType.BOOLEAN -> if (v.isBoolean) null else "must be a boolean"
        InputType.OBJECT -> if (v.isObject) null else "must be an object"
        InputType.ARRAY -> if (v.isArray) null else "must be an array"
    }

    /** True when [node] nests deeper than [max] (root counts as depth [depth]). Stops early: no deep recursion on hostile input. */
    internal fun exceedsDepth(node: JsonNode, max: Int, depth: Int): Boolean {
        if (depth > max) return true
        if (node.isObject) return node.propertyNames().any { exceedsDepth(node.get(it), max, depth + 1) }
        if (node.isArray) return node.arrayItems().any { exceedsDepth(it, max, depth + 1) }
        return false
    }

    /** Order-independent digest of bound inputs, used to detect an idempotency key being reused with different data. */
    fun fingerprint(actionId: String, input: ActionInput): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(actionId.toByteArray(StandardCharsets.UTF_8))
        md.update(0)
        for (name in input.values.keys.sorted()) {
            md.update(name.toByteArray(StandardCharsets.UTF_8))
            md.update(0)
            md.update(canonical(input.values.getValue(name)).toByteArray(StandardCharsets.UTF_8))
            md.update(0)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun canonical(n: JsonNode): String = when {
        n.isObject -> n.propertyNames().sorted().joinToString(",", "{", "}") { "${it.length}:$it=${canonical(n.get(it))}" }
        n.isArray -> n.arrayItems().joinToString(",", "[", "]") { canonical(it) }
        else -> n.toString()
    }
}
