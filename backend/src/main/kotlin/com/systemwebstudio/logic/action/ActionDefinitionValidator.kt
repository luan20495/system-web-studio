package com.systemwebstudio.logic.action

import tools.jackson.databind.JsonNode

/**
 * Structural validation of a declarative [ActionDefinition]. No I/O, never throws. It is used by the runtime
 * (defense in depth, every execute) and is meant to be called by C2's `AppDefinitionValidator` when it checks that
 * `actions[]` point at well-formed actions (that call is made from an adapter, not from `logic.action`).
 *
 * "Declarative, typed, no raw code" is enforced here: config may not contain keys that denote code, SQL or URLs, and
 * every reference must be a plain id ([REF_ID]).
 */
object ActionDefinitionValidator {
    /** Plain identifier: no scheme (`:`), no path (`/`), no whitespace. */
    val REF_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val INPUT_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    const val MAX_INPUTS = 64
    const val MAX_CONFIG_BYTES = 16 * 1024
    const val MAX_CONFIG_DEPTH = 6

    /** Case-insensitive property names that may never appear (at any depth) in `config`. */
    val FORBIDDEN_CONFIG_KEYS = setOf(
        "sql", "script", "code", "js", "javascript", "eval", "expression", "command", "shell", "exec",
        "url", "uri", "endpoint", "host", "headers", "authorization", "password", "secret", "token", "apikey", "credential", "credentials"
    )

    fun validate(definition: ActionDefinition, handler: ActionHandler? = null): List<DefinitionIssue> {
        val issues = mutableListOf<DefinitionIssue>()
        if (!REF_ID.matches(definition.id)) issues += DefinitionIssue("id", "must match ${REF_ID.pattern}")

        if (definition.inputs.size > MAX_INPUTS) issues += DefinitionIssue("inputs", "at most $MAX_INPUTS inputs")
        val seen = mutableSetOf<String>()
        definition.inputs.forEachIndexed { i, spec ->
            if (!INPUT_NAME.matches(spec.name)) issues += DefinitionIssue("inputs[$i].name", "must match ${INPUT_NAME.pattern}")
            if (!seen.add(spec.name)) issues += DefinitionIssue("inputs[$i].name", "duplicate input '${spec.name}'")
            if (spec.maxLength != null && (spec.type != InputType.STRING || spec.maxLength <= 0)) {
                issues += DefinitionIssue("inputs[$i].maxLength", "only valid for STRING and must be positive")
            }
        }

        var configBytes = 0
        for ((key, value) in definition.config) {
            configBytes += key.length + value.toString().length
            if (key.lowercase() in FORBIDDEN_CONFIG_KEYS) issues += DefinitionIssue("config.$key", "key is not allowed in a declarative action")
            forbiddenNested(value, "config.$key", 2, issues)
        }
        if (configBytes > MAX_CONFIG_BYTES) issues += DefinitionIssue("config", "exceeds $MAX_CONFIG_BYTES bytes")

        if (handler != null) {
            if (handler.type != definition.type) issues += DefinitionIssue("type", "handler is for ${handler.type}, definition is ${definition.type}")
            else issues += handler.validate(definition)
        }
        return issues
    }

    private fun forbiddenNested(node: JsonNode, path: String, depth: Int, out: MutableList<DefinitionIssue>) {
        if (depth > MAX_CONFIG_DEPTH) {
            out += DefinitionIssue(path, "nesting deeper than $MAX_CONFIG_DEPTH levels")
            return
        }
        if (node.isObject) {
            for (name in node.propertyNames()) {
                if (name.lowercase() in FORBIDDEN_CONFIG_KEYS) out += DefinitionIssue("$path.$name", "key is not allowed in a declarative action")
                forbiddenNested(node.get(name), "$path.$name", depth + 1, out)
            }
        } else if (node.isArray) {
            node.forEachIndexed { i, child -> forbiddenNested(child, "$path[$i]", depth + 1, out) }
        }
    }
}
