package com.systemwebstudio.wiring

import com.systemwebstudio.app.definition.AppDefinitionTestSupport
import com.systemwebstudio.logic.action.ActionExecution
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.ActionType
import com.systemwebstudio.logic.action.ChainOn
import com.systemwebstudio.logic.action.DryRunLevel
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.FollowUp
import com.systemwebstudio.logic.action.Fx
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Strict request parsing, result -> HTTP mapping and the mapping picker (docs/contracts/v2/runtime-api.md §2-§4). No Spring, no database. */
class RuntimeApiPureTests {
    private val json: JsonMapper = Fx.json
    private fun body(text: String): JsonNode = json.readTree(text)
    private fun bad(block: () -> Unit): BadRuntimeRequest = assertThrows(BadRuntimeRequest::class.java) { block() }

    // --- requests ---------------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an empty body is a LIVE request with nothing in it`() {
        val q = RuntimeRequests.queryRun(null)
        assertThat(q.mode).isEqualTo(ExecutionMode.LIVE)
        assertThat(q.params).isEmpty()
        assertThat(q.page).isNull()
        assertThat(RuntimeRequests.actionRun(body("{}")).inputs).isEmpty()
    }

    @Test
    fun `fields the contract does not list are refused, whatever their name`() {
        for (field in listOf("tenantId", "userId", "actorId", "dataSourceId", "sql", "url", "operation", "workspaceId", "appId", "eventId")) {
            assertThat(bad { RuntimeRequests.queryRun(body("""{"$field":"x"}""")) }.code).describedAs(field).isEqualTo("INVALID_REQUEST")
            assertThat(bad { RuntimeRequests.actionRun(body("""{"$field":"x"}""")) }.code).describedAs(field).isEqualTo("INVALID_REQUEST")
            assertThat(bad { RuntimeRequests.workflowStart(body("""{"idempotencyKey":"k-1","$field":"x"}""")) }.code).describedAs(field).isEqualTo("INVALID_REQUEST")
        }
        assertThat(bad { RuntimeRequests.actionRun(body("""{"trigger":{"eventName":"a.onClick","eventId":"e1"}}""")) }.code).isEqualTo("INVALID_REQUEST")
    }

    @Test
    fun `mode must be LIVE or TEST exactly`() {
        assertThat(RuntimeRequests.actionRun(body("""{"mode":"TEST"}""")).mode).isEqualTo(ExecutionMode.TEST)
        for (m in listOf("\"live\"", "\"PROD\"", "1", "true")) assertThat(bad { RuntimeRequests.actionRun(body("""{"mode":$m}""")) }.code).isEqualTo("INVALID_REQUEST")
    }

    @Test
    fun `query params are plain values within the limit and the page is range checked`() {
        val ok = RuntimeRequests.queryRun(body("""{"params":{"status":"open","n":3},"page":{"limit":50,"offset":10},"mappingRef":"orders-map"}"""))
        assertThat(ok.params.keys).containsExactly("status", "n")
        assertThat(ok.page!!.limit).isEqualTo(50)
        assertThat(ok.mappingRef).isEqualTo("orders-map")
        bad { RuntimeRequests.queryRun(body("""{"params":{"a":{"b":1}}}""")) }
        bad { RuntimeRequests.queryRun(body("""{"params":{"a":[1]}}""")) }
        bad { RuntimeRequests.queryRun(body("""{"page":{"limit":0}}""")) }
        bad { RuntimeRequests.queryRun(body("""{"page":{"limit":10,"offset":-1}}""")) }
        bad { RuntimeRequests.queryRun(body("""{"page":{"limit":"ten"}}""")) }
        bad { RuntimeRequests.queryRun(body("""{"mappingRef":"../x"}""")) }
        val many = (1..RuntimeRequests.MAX_PARAMS + 1).joinToString(",") { "\"p$it\":1" }
        bad { RuntimeRequests.queryRun(body("""{"params":{$many}}""")) }
    }

    @Test
    fun `a body that is not an object is refused`() {
        for (b in listOf("[]", "\"x\"", "5", "true")) assertThat(bad { RuntimeRequests.actionRun(body(b)) }.code).isEqualTo("INVALID_REQUEST")
    }

    @Test
    fun `a workflow start needs an idempotency key and an object input`() {
        assertThat(bad { RuntimeRequests.workflowStart(body("{}")) }.code).isEqualTo("IDEMPOTENCY_KEY_REQUIRED")
        assertThat(bad { RuntimeRequests.workflowStart(null) }.code).isEqualTo("IDEMPOTENCY_KEY_REQUIRED")
        assertThat(bad { RuntimeRequests.workflowStart(body("""{"idempotencyKey":"k-1","input":[1]}""")) }.code).isEqualTo("INVALID_REQUEST")
        val ok = RuntimeRequests.workflowStart(body("""{"idempotencyKey":"k-1","input":{"a":1},"mode":"TEST"}"""))
        assertThat(ok.idempotencyKey).isEqualTo("k-1")
        assertThat(ok.mode).isEqualTo(ExecutionMode.TEST)
        assertThat(ok.input!!.get("a").asInt()).isEqualTo(1)
    }

    // --- mapping picker --------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the mapping of a query is the single mapping that names it`() {
        val doc = AppDefinitionTestSupport.resource("valid-v2-sample.json")
        assertThat(MappingPicker.pick(doc, "orders-list")).isEqualTo("orders-map")
        assertThat(MappingPicker.pick(doc, "order-create")).isNull()
        assertThat(MappingPicker.pick(body("""{"mappings":[{"id":"a","queryRef":"q"},{"id":"b","queryRef":"q"}]}"""), "q")).isNull()
        assertThat(MappingPicker.pick(body("""{"page":"p"}"""), "q")).isNull()
    }

    // --- responses -------------------------------------------------------------------------------------------------------------------------------------

    private val responses = RuntimeResponses(json)

    @Test
    fun `status codes follow the contract table`() {
        val table = mapOf(
            "INVALID_INPUT" to 400, "IDEMPOTENCY_KEY_INVALID" to 400, "FORBIDDEN" to 403, "TENANT_DISABLED" to 403, "UNKNOWN_ACTION" to 404,
            "IDEMPOTENCY_OUTCOME_UNKNOWN" to 409, "ACTION_IN_PROGRESS" to 409, "IDEMPOTENCY_KEY_REUSED" to 409, "MUTATION_REJECTED" to 422, "INVALID_DEFINITION" to 422,
            "RATE_LIMITED" to 429, "NOT_IMPLEMENTED" to 501, "DEPENDENCY_UNAVAILABLE" to 503, "AUDIT_UNAVAILABLE" to 503, "DATA_RUNTIME_UNAVAILABLE" to 503,
            "RUNTIME_STORES_VOLATILE" to 503, "TIMEOUT" to 504, "HANDLER_ERROR" to 500, "SOMETHING_ELSE" to 500
        )
        for ((code, status) in table) assertThat(responses.status(code)).describedAs(code).isEqualTo(status)
    }

    @Test
    fun `an unknown outcome is 409 and never retryable even if a caller claimed it was`() {
        val r = responses.action("m1", ExecutionMode.LIVE, ActionExecution(ActionResult.Failed("IDEMPOTENCY_OUTCOME_UNKNOWN", true, "It may have been applied.")))
        assertThat(r.status).isEqualTo(409)
        assertThat(r.body.get("status").asString()).isEqualTo("FAILED")
        assertThat(r.body.get("error").get("code").asString()).isEqualTo("IDEMPOTENCY_OUTCOME_UNKNOWN")
        assertThat(r.body.get("error").get("retryable").asBoolean()).isFalse()
        assertThat(r.body.get("followUps").size()).isZero()
    }

    @Test
    fun `a rejected mutation is 422 and may carry its onError follow-ups`() {
        val failed = ActionResult.Failed("MUTATION_REJECTED", false, "Nothing was applied.")
        val fu = FollowUp("show-error", ChainOn.ERROR, ActionResult.Ok(Fx.obj()))
        val r = responses.action("m1", ExecutionMode.LIVE, ActionExecution(failed, listOf(fu)))
        assertThat(r.status).isEqualTo(422)
        assertThat(r.body.get("followUps").get(0).get("actionId").asString()).isEqualTo("show-error")
        assertThat(r.body.get("followUps").get(0).get("on").asString()).isEqualTo("ERROR")
    }

    @Test
    fun `rate limited carries Retry-After from the details`() {
        val r = responses.action("m1", ExecutionMode.LIVE, ActionExecution(ActionResult.Failed("RATE_LIMITED", true, "slow", mapOf("retryAfterMillis" to "2500"))))
        assertThat(r.status).isEqualTo(429)
        assertThat(r.retryAfterSeconds).isEqualTo(3L)
        assertThat(r.body.get("error").get("retryable").asBoolean()).isTrue()
    }

    @Test
    fun `ok and would-run are 200 and a would-run says nothing was executed`() {
        val ok = responses.action("a", ExecutionMode.LIVE, ActionExecution(ActionResult.Ok(Fx.obj("x" to Fx.num(1)))))
        assertThat(ok.status).isEqualTo(200)
        assertThat(ok.body.get("status").asString()).isEqualTo("OK")
        assertThat(ok.body.get("output").get("x").asInt()).isEqualTo(1)
        val wr = responses.action("a", ExecutionMode.TEST, ActionExecution(ActionResult.WouldRun("a", ActionType.CREATE_RECORD, DryRunLevel.NOT_EXECUTED, Fx.obj())))
        assertThat(wr.status).isEqualTo(200)
        assertThat(wr.body.get("status").asString()).isEqualTo("WOULD_RUN")
        assertThat(wr.body.get("level").asString()).isEqualTo("NOT_EXECUTED")
        assertThat(wr.body.get("type").asString()).isEqualTo("CREATE_RECORD")
    }

    @Test
    fun `the generic error body has the ApiError shape`() {
        val e = responses.error(404, "UNKNOWN_WORKFLOW", "nope", requestId = "r-1")
        assertThat(e.status).isEqualTo(404)
        assertThat(e.body.get("code").asString()).isEqualTo("UNKNOWN_WORKFLOW")
        assertThat(e.body.get("requestId").asString()).isEqualTo("r-1")
        assertThat(e.body.has("details")).isTrue()
    }
}
