package com.systemwebstudio.ai.planner

import com.systemwebstudio.app.definition.QueryMode
import com.systemwebstudio.integration.llm.AiCall
import com.systemwebstudio.schema.SchemaOperation
import tools.jackson.databind.JsonNode

/*
 * T17 AI App Planner (D-C2-10). The planner turns a sentence into TYPED SchemaOperations and nothing else:
 *
 *   prompt → tenant / model selection (AiSourceResolver) → AppPlanner → structured plan → PlanGuard → SchemaPatchEngine (typed operations)
 *          → validators (Page Schema + AppDefinition V2) → [apply: SchemaCommitService = CAS + immutable version + audit] → preview
 *
 * The model never writes the document, never publishes, never creates or alters a data source, never loosens a permission and never sees a
 * credential or a connector id. The application validates and authorizes everything it proposes, through the same operation model and
 * validators as the Design UI (D-C2-07).
 */

/** An approved registry component the model may use (a copy of the registry entry, so this package does not depend on the LLM client). */
data class PlannerComponent(val id: String, val category: String, val version: String, val propsSchema: JsonNode? = null)

/** An approved template the model may suggest as a starting point (name and category only; the content is never sent). */
data class TemplateHint(val id: String, val name: String, val category: String)

/**
 * Masked schema-discovery metadata of ONE operation a data source offers to this user (C3 catalogue; PROVISIONAL, B-C2-01). Names of
 * parameters and fields only: no SQL, no URL, no sample values, no credentials.
 */
data class GrantedOperation(val key: String, val mode: QueryMode, val params: List<String> = emptyList(), val fields: List<String> = emptyList())

/** A data source registered in the data platform that the current user may use (granted). [sourceRef] is opaque and is never sent to a model. */
data class GrantedDataSource(val sourceRef: String, val type: String, val name: String? = null, val operations: List<GrantedOperation> = emptyList())

/** Everything the planner may look at. Nothing else reaches the model. */
data class PlannerContext(
    val current: JsonNode,
    val components: List<PlannerComponent>,
    val templates: List<TemplateHint> = emptyList(),
    val granted: List<GrantedDataSource> = emptyList()
)

/** Where a path of the proposal went wrong; same shape as the validators' violations. */
data class PlanViolation(val path: String, val message: String)

/** A well-formed model answer: operations (typed, not yet judged) and the one-line message for the user. */
data class ParsedPlan(val message: String, val operations: List<SchemaOperation>, val claimedSources: Map<String, List<String>>? = null)

/** The model answered but not in the agreed format (becomes BAD_OUTPUT and fail-over, exactly like the page editor). */
class BadPlanOutput(message: String) : RuntimeException(message)

enum class PlanStatus {
    /** valid; [PlanOutcome.document] is the resulting document (applied only by the caller, never by the planner) */
    PROPOSED,
    /** the model understood and decided nothing needs to change */
    NO_CHANGE,
    /** the model proposed something this application refuses (forbidden operation / field, not granted, invalid result); nothing is applied */
    REJECTED,
    /** no usable answer (model error, cancelled, timeout, page too large) */
    FAILED
}

data class PlanOutcome(
    val status: PlanStatus, val message: String, val operations: List<SchemaOperation> = emptyList(), val document: JsonNode? = null,
    val violations: List<PlanViolation> = emptyList(), val provider: String? = null, val model: String? = null, val calls: List<AiCall> = emptyList(),
    val stopped: String? = null, val partial: String? = null, val claimedSources: Map<String, List<String>>? = null
)

/** Result of one model call made for the planner; same facts as ExternalLLMProvider.Completion. */
data class PlannerCompletion<T>(
    val result: T?, val provider: String?, val model: String?, val calls: List<AiCall> = emptyList(), val error: String? = null,
    val stopped: String? = null, val partial: String? = null
)

/**
 * Port to the model backend. The Spring adapter ([ExternalPlannerLlm]) sits on ExternalLLMProvider.complete with the model chosen by
 * AiSourceResolver and keeps the AI Gateway governance (limits, budgets, accounting) unchanged; tests use a scripted fake.
 */
interface PlannerLlm {
    fun <T> complete(system: String, user: String, parse: (String) -> T): PlannerCompletion<T>
}
