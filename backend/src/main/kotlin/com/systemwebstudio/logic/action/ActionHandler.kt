package com.systemwebstudio.logic.action

import tools.jackson.databind.JsonNode

data class DefinitionIssue(val path: String, val message: String)

/**
 * Executes one [ActionType]. By the time [execute] is called the runtime has already: resolved the definition within
 * the caller's tenant and app, checked the tenant gate and the caller's permissions, validated the definition ([validate]) and
 * resolved/bound/limited the input. A handler therefore only translates a validated, typed action into one port call and returns a typed
 * result. It must not read global state, spawn work that outlives the call, or execute user-supplied code.
 */
interface ActionHandler {
    val type: ActionType

    /** Type-specific checks of [ActionDefinition.config]/inputs, beyond the generic ones in [ActionDefinitionValidator]. */
    fun validate(definition: ActionDefinition): List<DefinitionIssue> = emptyList()

    fun execute(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult

    /**
     * TEST mode. **Must have no side effect**: no write, no notification, no workflow start. Returns [ActionResult.WouldRun] saying what
     * would run — or a [ActionResult.Failed] when it would fail. It may ask a downstream system to validate/sandbox *only* through an
     * explicit dry-run capability of that system; otherwise it answers [DryRunLevel.NOT_EXECUTED] (it never pretends).
     */
    fun preview(ctx: ActionContext, definition: ActionDefinition, input: ActionInput, run: ActionRun): ActionResult
}

/** Immutable once built; one handler per type. Unknown type ⇒ the runtime answers UNSUPPORTED_ACTION_TYPE. */
class ActionHandlerRegistry private constructor(private val byType: Map<ActionType, ActionHandler>) {
    operator fun get(type: ActionType): ActionHandler? = byType[type]
    val types: Set<ActionType> get() = byType.keys

    class Builder {
        private val handlers = linkedMapOf<ActionType, ActionHandler>()
        fun register(handler: ActionHandler) = apply {
            require(handlers.put(handler.type, handler) == null) { "A handler for ${handler.type} is already registered" }
        }
        fun build() = ActionHandlerRegistry(handlers.toMap())
    }

    companion object {
        fun builder() = Builder()
        fun of(vararg handlers: ActionHandler) = builder().apply { handlers.forEach { register(it) } }.build()
    }
}

/** Config accessors shared by handlers. */
internal fun ActionDefinition.configString(key: String): String? = config[key]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
internal fun JsonNode?.isNullish() = this == null || this.isNull
