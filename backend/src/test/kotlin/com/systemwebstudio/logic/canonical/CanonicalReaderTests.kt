package com.systemwebstudio.logic.canonical

import com.systemwebstudio.logic.action.ActionPorts
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.ActionType
import com.systemwebstudio.logic.action.DefaultActionRuntime
import com.systemwebstudio.logic.action.DryRunLevel
import com.systemwebstudio.logic.action.Event
import com.systemwebstudio.logic.action.EventPayload
import com.systemwebstudio.logic.action.EventType
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.FakeAccess
import com.systemwebstudio.logic.action.FakeDataPort
import com.systemwebstudio.logic.action.FakeNotifyPort
import com.systemwebstudio.logic.action.FakeTenants
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.IdempotencyPolicy
import com.systemwebstudio.logic.action.InMemoryActionRunStore
import com.systemwebstudio.logic.action.InputResolver
import com.systemwebstudio.logic.action.InputSource
import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.action.RecordingAudit
import com.systemwebstudio.logic.action.WriteKind
import com.systemwebstudio.logic.action.canonical.AppDefinitionSource
import com.systemwebstudio.logic.action.canonical.CanonicalActionCatalog
import com.systemwebstudio.logic.action.canonical.CanonicalActionReader
import com.systemwebstudio.logic.action.handlers.DefaultActionHandlers
import com.systemwebstudio.logic.workflow.StepKind
import com.systemwebstudio.logic.workflow.canonical.CanonicalWorkflowCatalog
import com.systemwebstudio.logic.workflow.canonical.CanonicalWorkflowReader
import com.systemwebstudio.logic.workflow.canonical.DeclaredSchedule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors

class CanonicalReaderTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    private fun doc(s: String): JsonNode = Fx.json.readTree(s)

    /** A document shaped like C2's AppDefinition: provisional action types on purpose (RUN_QUERY, WRITE_DATA, ...). */
    private val c2 = doc("""
    {
      "queries": [ {"id":"orders.list","mode":"READ"}, {"id":"orders.create","mode":"WRITE"} ],
      "dataSources": [ {"id":"crm"} ],
      "permissions": [ {"id":"p-submit","resourceType":"ACTION","resourceRef":"submit","permission":"orders.write"},
                       {"id":"p-other","resourceType":"PAGE","resourceRef":"submit","permission":"x.y"} ],
      "workflows": [ {"id":"onboarding","trigger":"MANUAL","steps":[{"id":"s1","actionRef":"submit","next":"s2"},{"id":"s2","actionRef":"lookup"}]} ],
      "actions": [
        {"id":"refresh","type":"RUN_QUERY","queryRef":"orders.list"},
        {"id":"submit","type":"WRITE_DATA","queryRef":"orders.create","permissionRef":"p-submit",
         "inputs":[{"name":"title","type":"STRING","required":true}],
         "inputMapping":{"title":{"source":"FORM_FIELD","name":"title"}},
         "trigger":{"sectionId":"form1","event":"onSubmit"}},
        {"id":"lookup","type":"CALL_CONNECTOR_OPERATION","dataSourceRef":"crm","operationKey":"getCustomer","inputs":[{"name":"q","type":"STRING"}]},
        {"id":"go","type":"NAVIGATE","pageRef":"home"},
        {"id":"start","type":"START_WORKFLOW","workflowRef":"onboarding","inputs":[{"name":"who","type":"STRING"}]},
        {"id":"setv","type":"SET_VALUE"}
      ]
    }""")

    private fun parse(d: JsonNode = c2) = CanonicalActionReader.parse(Fx.tenantA, Fx.appA, d)

    // ---- action types and references -------------------------------------------------------------------------------

    @Test fun `C2's provisional action types map to the finalized ones`() {
        val p = parse()
        assertEquals(ActionType.REFRESH_QUERY, p.actions["refresh"]!!.type)
        assertEquals(ActionType.SUBMIT_FORM, p.actions["submit"]!!.type)
        assertEquals(ActionType.CALL_API, p.actions["lookup"]!!.type)
        assertEquals(ActionType.NAVIGATE, p.actions["go"]!!.type)
        assertEquals(ActionType.START_WORKFLOW, p.actions["start"]!!.type)
    }

    @Test fun `SET_VALUE is client side state and is not offered as a server action`() {
        val p = parse()
        assertFalse(p.actions.containsKey("setv"))
        assertTrue(p.issues["setv"]!!.single().message.contains("client-only"))
    }

    @Test fun `finalized names and C4's additive fields are accepted`() {
        val d = doc("""{"queries":[{"id":"q","mode":"WRITE"}],"actions":[
            {"id":"a","type":"CREATE_RECORD","queryRef":"q","idempotency":"REQUIRED","limits":{"timeoutMillis":5000},"inputs":[{"name":"title","type":"STRING"}]},
            {"id":"n","type":"NOTIFY","channel":"IN_APP","templateRef":"welcome","recipients":[{"kind":"ROLE","role":"admins"}],"onSuccess":["a"]}]}""")
        val p = parse(d)
        assertEquals(ActionType.CREATE_RECORD, p.actions["a"]!!.type)
        assertEquals(Duration.ofSeconds(5), p.actions["a"]!!.limits.timeout)
        assertEquals(listOf("a"), p.actions["n"]!!.onSuccess)
        assertEquals("welcome", p.actions["n"]!!.config["templateRef"]!!.asString())
    }

    @Test fun `references are resolved inside the document and dangling ones are not offered`() {
        val d = doc("""{"queries":[{"id":"r","mode":"READ"},{"id":"w","mode":"WRITE"}],"dataSources":[{"id":"crm"}],"actions":[
            {"id":"a1","type":"RUN_QUERY","queryRef":"missing"},
            {"id":"a2","type":"WRITE_DATA","queryRef":"r"},
            {"id":"a3","type":"RUN_QUERY","queryRef":"w"},
            {"id":"a4","type":"CALL_CONNECTOR_OPERATION","dataSourceRef":"ghost","operationKey":"op"},
            {"id":"a5","type":"CALL_CONNECTOR_OPERATION","dataSourceRef":"crm"},
            {"id":"a6","type":"START_WORKFLOW","workflowRef":"nope"},
            {"id":"a7","type":"NAVIGATE"}]}""")
        val p = parse(d)
        assertTrue(p.actions.isEmpty(), "offered: ${p.actions.keys}")
        assertEquals(setOf("a1", "a2", "a3", "a4", "a5", "a6", "a7"), p.issues.keys)
    }

    @Test fun `CALL_API is an approved operation and a raw url never becomes an action`() {
        val d = doc("""{"dataSources":[{"id":"crm"}],"actions":[{"id":"x","type":"CALL_CONNECTOR_OPERATION","dataSourceRef":"crm","url":"https://evil.example/steal","headers":{"Authorization":"x"}}]}""")
        val p = parse(d)
        assertFalse(p.actions.containsKey("x"))
        assertTrue(p.issues["x"]!!.any { it.path == "operationKey" })
    }

    @Test fun `permissionRef must point at an ACTION permission of the same action`() {
        assertEquals("orders.write", parse().actions["submit"]!!.requiredPermission)
        val d = doc("""{"queries":[{"id":"w","mode":"WRITE"}],"permissions":[{"id":"p","resourceType":"PAGE","resourceRef":"a","permission":"x"}],"actions":[{"id":"a","type":"WRITE_DATA","queryRef":"w","permissionRef":"p"}]}""")
        val p = parse(d)
        assertFalse(p.actions.containsKey("a")); assertTrue(p.issues["a"]!!.any { it.path == "permissionRef" })
    }

    @Test fun `inputMapping reads typed sources and rejects anything else`() {
        val m = parse().actions["submit"]!!.inputMapping
        assertEquals(InputSource.FormField("title"), m["title"])
        val d = doc("""{"queries":[{"id":"w","mode":"WRITE"}],"actions":[{"id":"a","type":"WRITE_DATA","queryRef":"w","inputs":[{"name":"t","type":"STRING"}],"inputMapping":{"t":{"source":"EVAL","code":"alert(1)"}}}]}""")
        val p = parse(d)
        assertFalse(p.actions.containsKey("a")); assertTrue(p.issues["a"]!!.any { it.path == "inputMapping.t" })
    }

    @Test fun `writes default to REQUIRED idempotency and cannot opt out`() {
        assertEquals(IdempotencyPolicy.REQUIRED, parse().actions["submit"]!!.idempotency)
        assertEquals(IdempotencyPolicy.NONE, parse().actions["go"]!!.idempotency)
        val d = doc("""{"queries":[{"id":"w","mode":"WRITE"}],"actions":[{"id":"a","type":"WRITE_DATA","queryRef":"w","idempotency":"NONE"},{"id":"b","type":"WRITE_DATA","queryRef":"w","idempotency":"MAYBE"}]}""")
        val p = parse(d)
        assertTrue(p.actions.isEmpty())
        assertTrue(p.issues["a"]!!.any { it.path == "idempotency" }); assertTrue(p.issues["b"]!!.any { it.path == "idempotency" })
    }

    @Test fun `an action whose chain dangles is not offered, and neither is one that chains to it`() {
        val d = doc("""{"queries":[{"id":"r","mode":"READ"}],"actions":[
            {"id":"a","type":"RUN_QUERY","queryRef":"r","onSuccess":["ghost"],"trigger":{"sectionId":"s","event":"onClick"}},
            {"id":"b","type":"RUN_QUERY","queryRef":"r","onError":["a"]},
            {"id":"c","type":"RUN_QUERY","queryRef":"r"}]}""")
        val p = parse(d)
        assertEquals(setOf("c"), p.actions.keys)
        assertTrue(p.refs.isEmpty())
        assertEquals(setOf("a", "b"), p.issues.keys)
    }

    @Test fun `triggers become event bindings and unknown events are reported`() {
        val p = parse()
        val ref = p.refs.single()
        assertEquals("submit", ref.actionId); assertEquals("form1", ref.sectionId); assertEquals(EventType.ON_SUBMIT, ref.event)
        val d = doc("""{"queries":[{"id":"r","mode":"READ"}],"actions":[{"id":"a","type":"RUN_QUERY","queryRef":"r","trigger":{"sectionId":"s","event":"onHover"}}]}""")
        assertTrue(parse(d).refs.isEmpty())
        assertTrue(parse(d).issues["a"]!!.any { it.path == "trigger" })
    }

    @Test fun `junk in the document never throws`() {
        for (s in listOf("{}", "[]", "null", """{"actions":"x"}""", """{"actions":[1,null,{"id":5},{"type":"NAVIGATE"}]}""", """{"actions":[{"id":"a","type":"NAVIGATE","pageRef":{"x":1}}]}""")) {
            parse(doc(s))
            CanonicalWorkflowReader.parse(Fx.tenantA, Fx.appA, doc(s))
        }
    }

    // ---- catalog: tenant scope and end to end -------------------------------------------------------------------------

    private class Source(val tenant: UUID, val app: UUID, val live: JsonNode, val draft: JsonNode = live) : AppDefinitionSource {
        override fun load(tenantId: UUID, appId: UUID, mode: ExecutionMode): JsonNode? =
            if (tenantId == tenant && appId == app) (if (mode == ExecutionMode.TEST) draft else live) else null
    }

    @Test fun `the catalog only serves the tenant and app that own the document`() {
        val cat = CanonicalActionCatalog(Source(Fx.tenantA, Fx.appA, c2))
        assertEquals(Fx.tenantA, cat.find(Fx.tenantA, Fx.appA, "go", ExecutionMode.LIVE)!!.tenantId)
        assertNull(cat.find(Fx.tenantB, Fx.appA, "go", ExecutionMode.LIVE))
        assertNull(cat.find(Fx.tenantA, Fx.appB, "go", ExecutionMode.LIVE))
        assertNull(cat.find(Fx.tenantA, Fx.appA, "setv", ExecutionMode.LIVE))
        assertTrue(cat.refsFor(Fx.tenantB, Fx.appA, "form1", EventType.ON_SUBMIT, ExecutionMode.LIVE).isEmpty())
        assertEquals(1, cat.refsFor(Fx.tenantA, Fx.appA, "form1", EventType.ON_SUBMIT, ExecutionMode.LIVE).size)
        assertTrue(cat.diagnose(Fx.tenantA, Fx.appA, ExecutionMode.LIVE).containsKey("setv"))
    }

    @Test fun `TEST reads the draft and LIVE reads the published version`() {
        val draft = doc("""{"queries":[{"id":"r","mode":"READ"}],"actions":[{"id":"onlyInDraft","type":"RUN_QUERY","queryRef":"r"}]}""")
        val cat = CanonicalActionCatalog(Source(Fx.tenantA, Fx.appA, c2, draft))
        assertNull(cat.find(Fx.tenantA, Fx.appA, "onlyInDraft", ExecutionMode.LIVE))
        assertEquals("onlyInDraft", cat.find(Fx.tenantA, Fx.appA, "onlyInDraft", ExecutionMode.TEST)!!.id)
    }

    private class Rig(cat: CanonicalActionCatalog, executor: java.util.concurrent.ExecutorService) {
        val data = FakeDataPort(); val notify = FakeNotifyPort(); val audit = RecordingAudit(); val access = FakeAccess()
        val runtime = DefaultActionRuntime(
            cat, DefaultActionHandlers.registry(Fx.json, ActionPorts(data, notify, null)), access, FakeTenants(), InMemoryActionRunStore(), audit,
            InputResolver(Fx.json), cat, executor = executor
        )
    }

    private fun event(id: String = "evt-1", mode: ExecutionMode = ExecutionMode.LIVE) =
        Event(id, EventType.ON_SUBMIT, "form1", EventPayload(form = mapOf("title" to Fx.str("Hello"), "evil" to Fx.str("x"))), Fx.now, mode)

    @Test fun `a UI event reaches the data port end to end through the C2 document`() {
        val r = Rig(CanonicalActionCatalog(Source(Fx.tenantA, Fx.appA, c2)), executor)
        val out = r.runtime.dispatch(Fx.ctx(), event())
        assertInstanceOf(ActionResult.Ok::class.java, out.single().result)
        val (_, w) = r.data.writes.single()
        assertEquals("orders.create", w.queryRef); assertEquals(WriteKind.SUBMIT, w.kind)
        assertEquals(setOf("title"), w.params.keys)                       // the undeclared form field "evil" never gets through
        assertEquals("Hello", w.params["title"]!!.asString())
        assertTrue(r.access.permissions.contains("orders.write"))        // the declared permission was checked on the server
    }

    @Test fun `the same event delivered twice writes once`() {
        val r = Rig(CanonicalActionCatalog(Source(Fx.tenantA, Fx.appA, c2)), executor)
        r.runtime.dispatch(Fx.ctx(), event("e1")); r.runtime.dispatch(Fx.ctx(), event("e1"))
        assertEquals(1, r.data.writes.size)
        r.runtime.dispatch(Fx.ctx(), event("e2"))
        assertEquals(2, r.data.writes.size)
    }

    @Test fun `a missing declared permission stops the event and nothing is written`() {
        val r = Rig(CanonicalActionCatalog(Source(Fx.tenantA, Fx.appA, c2)), executor)
        r.access.denyPermissions = setOf("orders.write")
        val res = r.runtime.dispatch(Fx.ctx(), event()).single().result
        assertEquals("FORBIDDEN", assertInstanceOf(ActionResult.Failed::class.java, res).code)
        assertTrue(r.data.writes.isEmpty())
    }

    @Test fun `a TEST event says what would run and writes nothing`() {
        val r = Rig(CanonicalActionCatalog(Source(Fx.tenantA, Fx.appA, c2)), executor)
        val res = r.runtime.dispatch(Fx.ctx(), event(mode = ExecutionMode.TEST)).single().result
        val w = assertInstanceOf(ActionResult.WouldRun::class.java, res)
        assertEquals("submit", w.actionId); assertEquals(DryRunLevel.NOT_EXECUTED, w.level)
        assertTrue(r.data.writes.isEmpty())
    }

    @Test fun `another tenant dispatching the same event runs nothing`() {
        val r = Rig(CanonicalActionCatalog(Source(Fx.tenantA, Fx.appA, c2)), executor)
        assertTrue(r.runtime.dispatch(Fx.ctx(tenant = Fx.tenantB), event()).isEmpty())
        assertTrue(r.data.writes.isEmpty())
    }

    // ---- workflows ---------------------------------------------------------------------------------------------------

    private fun wfs(d: JsonNode = c2) = CanonicalWorkflowReader.parse(Fx.tenantA, Fx.appA, d)

    @Test fun `a C2 WorkflowDef becomes a chain of action steps`() {
        val w = wfs().workflows["onboarding"]!!
        assertEquals(listOf("s1", "s2"), w.steps.map { it.id })
        assertTrue(w.steps.all { it.kind == StepKind.ACTION })
        assertEquals(Fx.tenantA, w.tenantId); assertEquals(Fx.appA, w.appId)
    }

    @Test fun `a workflow that references an action the app does not offer is not offered`() {
        val d = doc("""{"actions":[{"id":"go","type":"NAVIGATE","pageRef":"home"}],"workflows":[
            {"id":"ok","steps":[{"id":"s","actionRef":"go"}]},
            {"id":"bad","steps":[{"id":"s","actionRef":"ghost"}]},
            {"id":"client","steps":[{"id":"s","actionRef":"setv"}]},
            {"id":"loose","steps":[{"id":"s","actionRef":"go","next":"nowhere"}]}]}""")
        val p = wfs(d)
        assertEquals(setOf("ok"), p.workflows.keys)
        assertEquals(setOf("bad", "client", "loose"), p.issues.keys)
    }

    @Test fun `extended steps WAIT APPROVAL BRANCH with conditions retries and limits`() {
        val d = doc("""{"actions":[{"id":"go","type":"NAVIGATE","pageRef":"home"}],"workflows":[{"id":"w","compensateOnCancel":true,
            "limits":{"maxSteps":10,"maxDurationSeconds":3600},
            "steps":[
              {"id":"a","kind":"ACTION","actionRef":"go","retry":{"maxAttempts":3,"initialBackoffMillis":500},"timeoutMillis":2000,"inputs":{"x":{"from":"INPUT","path":"order.id"}}},
              {"id":"br","kind":"BRANCH","branches":[{"condition":{"all":[{"op":"GT","left":{"from":"INPUT","path":"amount"},"right":{"from":"LITERAL","value":100}},{"not":{"exists":{"from":"INPUT","path":"waived"}}}]},"next":"ap"}],"defaultNext":"fin"},
              {"id":"ap","kind":"APPROVAL","approval":{"title":"Big order","approvers":[{"kind":"ROLE","role":"managers"},{"kind":"DEPARTMENT_MANAGER"}],"requiredApprovals":1,"expiresInSeconds":7200,"onReject":"fin"}},
              {"id":"pause","kind":"WAIT","waitSeconds":60},
              {"id":"fin","kind":"END"}]}]}""")
        val p = wfs(d)
        assertTrue(p.issues.isEmpty(), "issues: ${p.issues}")
        val w = p.workflows["w"]!!
        assertTrue(w.compensateOnCancel)
        assertEquals(3, w.step("a")!!.retry.maxAttempts)
        assertEquals(Duration.ofSeconds(2), w.step("a")!!.timeout)
        assertEquals(2, w.step("ap")!!.approval!!.approvers.size)
        assertEquals(PrincipalSpec.Role("managers"), w.step("ap")!!.approval!!.approvers[0])
        assertEquals(Duration.ofHours(1), w.limits.maxDuration)
        assertEquals(StepKind.WAIT, w.step("pause")!!.kind)
    }

    @Test fun `invalid extended steps are reported with a path and the workflow is not offered`() {
        val d = doc("""{"actions":[{"id":"go","type":"NAVIGATE","pageRef":"home"}],"workflows":[
            {"id":"w1","steps":[{"id":"a","kind":"WARP","actionRef":"go"}]},
            {"id":"w2","steps":[{"id":"a","kind":"ACTION","actionRef":"go","retry":{"maxAttempts":0}}]},
            {"id":"w3","steps":[{"id":"a","kind":"BRANCH","branches":[{"condition":{"op":"EVAL","left":{"from":"INPUT"},"right":{"from":"INPUT"}},"next":"a"}]}]},
            {"id":"w4","steps":[{"id":"a","kind":"APPROVAL","approval":{"title":"t","approvers":[{"kind":"WIZARD"}]}}]},
            {"id":"w5","steps":[{"id":"a","kind":"ACTION","actionRef":"go","inputs":{"x":{"from":"SHELL","cmd":"ls"}}}]}]}""")
        val p = wfs(d)
        assertTrue(p.workflows.isEmpty(), "offered: ${p.workflows.keys}")
        assertEquals(setOf("w1", "w2", "w3", "w4", "w5"), p.issues.keys)
    }

    @Test fun `SCHEDULE workflows declare their schedule and bad ones are rejected`() {
        val d = doc("""{"actions":[{"id":"go","type":"NAVIGATE","pageRef":"home"}],"workflows":[
            {"id":"nightly","trigger":"SCHEDULE","schedule":"0 2 * * *","timezone":"Asia/Ho_Chi_Minh","steps":[{"id":"s","actionRef":"go"}]},
            {"id":"badcron","trigger":"SCHEDULE","schedule":"every day","steps":[{"id":"s","actionRef":"go"}]},
            {"id":"nocron","trigger":"SCHEDULE","steps":[{"id":"s","actionRef":"go"}]},
            {"id":"badtz","trigger":"SCHEDULE","schedule":"0 2 * * *","timezone":"Mars/Base","steps":[{"id":"s","actionRef":"go"}]},
            {"id":"manual","trigger":"MANUAL","schedule":"0 2 * * *","steps":[{"id":"s","actionRef":"go"}]},
            {"id":"weird","trigger":"WHENEVER","steps":[{"id":"s","actionRef":"go"}]}]}""")
        val p = wfs(d)
        assertEquals(setOf("nightly"), p.workflows.keys)
        assertEquals(listOf(DeclaredSchedule("nightly", "0 2 * * *", "Asia/Ho_Chi_Minh")), p.schedules)
        assertEquals(setOf("badcron", "nocron", "badtz", "manual", "weird"), p.issues.keys)
    }

    @Test fun `the workflow catalog is tenant and mode scoped`() {
        val cat = CanonicalWorkflowCatalog(Source(Fx.tenantA, Fx.appA, c2))
        assertEquals("onboarding", cat.find(Fx.tenantA, Fx.appA, "onboarding", ExecutionMode.LIVE)!!.id)
        assertNull(cat.find(Fx.tenantB, Fx.appA, "onboarding", ExecutionMode.LIVE))
        assertNull(cat.find(Fx.tenantA, Fx.appB, "onboarding", ExecutionMode.LIVE))
        assertTrue(cat.declaredSchedules(Fx.tenantB, Fx.appA, ExecutionMode.LIVE).isEmpty())
    }
}
