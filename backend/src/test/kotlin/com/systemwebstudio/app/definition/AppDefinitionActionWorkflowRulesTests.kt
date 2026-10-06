package com.systemwebstudio.app.definition

import com.systemwebstudio.app.definition.AppDefinitionTestSupport.check
import com.systemwebstudio.schema.Violation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Cross-reference rules for actions and workflows (the contract's C4 shapes, checked by C2 as gatekeeper). Pure unit tests. */
class AppDefinitionActionWorkflowRulesTests {
    private val base = """"dataSources":[{"id":"d","type":"postgres"}],
        "queries":[{"id":"r","dataSourceRef":"d","operationKey":"op.read"},{"id":"w","dataSourceRef":"d","mode":"WRITE","operationKey":"op.write"}]"""

    private fun run(extra: String) = check("$base,$extra")
    private fun List<Violation>.paths() = map { it.path }
    private fun List<Violation>.has(path: String, fragment: String) = any { it.path == path && it.message.contains(fragment) }

    // ---- data actions ----

    @Test
    fun `REFRESH_QUERY needs a declared READ query`() {
        assertThat(run(""""actions":[{"id":"a","type":"REFRESH_QUERY"}]""").has("actions[0].queryRef", "required")).isTrue()
        assertThat(run(""""actions":[{"id":"a","type":"REFRESH_QUERY","queryRef":"w"}]""").has("actions[0].queryRef", "READ query")).isTrue()
        assertThat(run(""""actions":[{"id":"a","type":"REFRESH_QUERY","queryRef":"r","trigger":{"sectionId":"hero-1","event":"onClick"}}]""")).isEmpty()
    }

    @Test
    fun `record actions need a WRITE query`() {
        for (type in listOf("SUBMIT_FORM", "CREATE_RECORD", "UPDATE_RECORD", "DELETE_RECORD")) {
            assertThat(run(""""actions":[{"id":"a","type":"$type","queryRef":"r"}]""").has("actions[0].queryRef", "WRITE query")).describedAs(type).isTrue()
            assertThat(run(""""actions":[{"id":"a","type":"$type","queryRef":"w"}]""")).describedAs(type).isEmpty()
        }
    }

    @Test
    fun `CALL_API names a data source and an approved operation, never a URL`() {
        assertThat(run(""""actions":[{"id":"a","type":"CALL_API"}]""").paths()).contains("actions[0].dataSourceRef", "actions[0].operationKey")
        assertThat(run(""""actions":[{"id":"a","type":"CALL_API","dataSourceRef":"d","operationKey":"crm.sync"}]""")).isEmpty()
        assertThat(run(""""actions":[{"id":"a","type":"CALL_API","dataSourceRef":"d","operationKey":"https://evil.example/x"}]""")).isNotEmpty()
        assertThat(run(""""actions":[{"id":"a","type":"CALL_API","dataSourceRef":"d","operationKey":"x","url":"https://evil.example"}]""").has("actions[0].url", "unknown")).isTrue()
    }

    // ---- NOTIFY ----

    @Test
    fun `NOTIFY needs a channel and an approved template`() {
        val v = run(""""actions":[{"id":"n","type":"NOTIFY"}]""")
        assertThat(v.paths()).contains("actions[0].channel", "actions[0].templateRef")
        assertThat(run(""""actions":[{"id":"n","type":"NOTIFY","channel":"EMAIL","templateRef":"tpl-1"}]""")).isEmpty()
        assertThat(run(""""actions":[{"id":"n","type":"NOTIFY","channel":"PIGEON","templateRef":"tpl-1"}]""")).isNotEmpty()
    }

    @Test
    fun `a WEBHOOK notification needs a registered endpoint and no other channel may carry one`() {
        assertThat(run(""""actions":[{"id":"n","type":"NOTIFY","channel":"WEBHOOK","templateRef":"t"}]""").paths()).contains("actions[0].endpointRef")
        assertThat(run(""""actions":[{"id":"n","type":"NOTIFY","channel":"WEBHOOK","templateRef":"t","endpointRef":"ep-1"}]""")).isEmpty()
        assertThat(run(""""actions":[{"id":"n","type":"NOTIFY","channel":"IN_APP","templateRef":"t","endpointRef":"ep-1"}]""").paths()).contains("actions[0].endpointRef")
        assertThat(run(""""actions":[{"id":"n","type":"NOTIFY","channel":"WEBHOOK","templateRef":"t","endpointRef":"https://x.example/hook"}]""")).isNotEmpty()
    }

    @Test
    fun `notification members are only valid on a NOTIFY action`() {
        val v = run(""""actions":[{"id":"a","type":"NAVIGATE","pageRef":"home","channel":"IN_APP","templateRef":"t","recipients":[{"kind":"ROLE","role":"admin"}]}]""")
        assertThat(v.paths()).contains("actions[0].channel", "actions[0].templateRef", "actions[0].recipients")
    }

    @Test
    fun `principals follow the C4 principal shapes`() {
        fun notify(principal: String) = run(""""actions":[{"id":"n","type":"NOTIFY","channel":"IN_APP","templateRef":"t","recipients":[$principal]}]""")
        assertThat(notify("""{"kind":"USER"}""").paths()).contains("actions[0].recipients[0].userId")
        assertThat(notify("""{"kind":"GROUP"}""").paths()).contains("actions[0].recipients[0].groupId")
        assertThat(notify("""{"kind":"ROLE"}""").paths()).contains("actions[0].recipients[0].role")
        assertThat(notify("""{"kind":"DEPARTMENT_MANAGER"}""")).isEmpty()
        assertThat(notify("""{"kind":"USER","userId":"not-a-uuid"}""")).isNotEmpty()
    }

    // ---- idempotency, inputs, chains ----

    @Test
    fun `state changing actions cannot opt out of idempotency and START_WORKFLOW must require it`() {
        assertThat(run(""""actions":[{"id":"a","type":"CREATE_RECORD","queryRef":"w","idempotency":"NONE"}]""").paths()).contains("actions[0].idempotency")
        assertThat(run(""""actions":[{"id":"a","type":"NAVIGATE","pageRef":"home","idempotency":"NONE"}]""")).isEmpty()
        val wf = """"workflows":[{"id":"f","steps":[{"id":"s","actionRef":"x"}]}]"""
        assertThat(run("""$wf,"actions":[{"id":"x","type":"CALL_API","dataSourceRef":"d","operationKey":"o"},{"id":"a","type":"START_WORKFLOW","workflowRef":"f","idempotency":"OPTIONAL"}]""").paths())
            .contains("actions[1].idempotency")
        assertThat(run("""$wf,"actions":[{"id":"x","type":"CALL_API","dataSourceRef":"d","operationKey":"o"},{"id":"a","type":"START_WORKFLOW","workflowRef":"f","idempotency":"REQUIRED"}]""")).isEmpty()
    }

    @Test
    fun `an input mapping may only fill declared inputs and each source needs its own member`() {
        val declared = """"inputs":[{"name":"customer","type":"STRING"}]"""
        assertThat(run(""""actions":[{"id":"a","type":"CREATE_RECORD","queryRef":"w",$declared,"inputMapping":{"other":{"source":"LITERAL","value":1}}}]""").paths())
            .contains("actions[0].inputMapping.other")
        assertThat(run(""""actions":[{"id":"a","type":"CREATE_RECORD","queryRef":"w",$declared,"inputMapping":{"customer":{"source":"FORM_FIELD"}}}]""").paths())
            .contains("actions[0].inputMapping.customer.name")
        assertThat(run(""""actions":[{"id":"a","type":"CREATE_RECORD","queryRef":"w",$declared,"inputMapping":{"customer":{"source":"LITERAL","value":"x","path":"a"}}}]""").paths())
            .contains("actions[0].inputMapping.customer.path")
        assertThat(run(""""actions":[{"id":"a","type":"CREATE_RECORD","queryRef":"w",$declared,"inputMapping":{"customer":{"source":"CONTEXT","key":"TENANT_ID"}}}]""")).isEmpty()
        assertThat(run(""""actions":[{"id":"a","type":"CREATE_RECORD","queryRef":"w",$declared,"inputMapping":{"customer":{"source":"CONTEXT","key":"SECRET"}}}]""")).isNotEmpty()
    }

    @Test
    fun `action chains name known actions and never themselves`() {
        assertThat(run(""""actions":[{"id":"a","type":"NAVIGATE","pageRef":"home","onSuccess":["a"]}]""").paths()).contains("actions[0].onSuccess[0]")
        assertThat(run(""""actions":[{"id":"a","type":"NAVIGATE","pageRef":"home","onError":["ghost"]}]""").paths()).contains("actions[0].onError[0]")
        assertThat(run(""""actions":[{"id":"a","type":"NAVIGATE","pageRef":"home","onSuccess":["b"]},{"id":"b","type":"NAVIGATE","pageRef":"home"}]""")).isEmpty()
    }

    // ---- triggers ----

    @Test
    fun `a trigger uses an event the component declares and an action type that event supports`() {
        assertThat(run(""""actions":[{"id":"a","type":"NAVIGATE","pageRef":"home","trigger":{"sectionId":"hero-1","event":"onHover"}}]""").paths()).contains("actions[0].trigger.event")
        assertThat(run(""""actions":[{"id":"a","type":"NAVIGATE","pageRef":"home","trigger":{"sectionId":"hero-1","event":"onSubmit"}}]""").paths()).contains("actions[0].trigger.event")
        assertThat(run(""""actions":[{"id":"a","type":"CREATE_RECORD","queryRef":"w","trigger":{"sectionId":"hero-1","event":"onClick"}}]""").paths()).contains("actions[0].trigger.event")
        assertThat(run(""""actions":[{"id":"a","type":"CREATE_RECORD","queryRef":"w","trigger":{"sectionId":"contact-1","event":"onSubmit"}}]""")).isEmpty()
    }

    // ---- ids ----

    @Test
    fun `local ids never look like runtime UUIDs`() {
        val uuid = "0b8f2a3e-5c1d-4b7a-9e2f-3d4c5b6a7e80"
        assertThat(check(""""dataSources":[{"id":"$uuid","type":"rest"}]""").paths().any { it.startsWith("dataSources") }).isTrue()
        assertThat(check(""""dataSources":[{"id":"d","type":"rest"}],"queries":[{"id":"$uuid","dataSourceRef":"d"}]""").paths().any { it.startsWith("queries") }).isTrue()
    }

    // ---- workflows ----

    private fun workflow(body: String, actions: String = """{"id":"x","type":"NOTIFY","channel":"IN_APP","templateRef":"t"}""") =
        run(""""actions":[$actions],"workflows":[{"id":"f",$body}]""")

    @Test
    fun `workflow steps name known actions and known steps`() {
        assertThat(workflow(""""steps":[{"id":"s1","actionRef":"ghost"}]""").paths()).contains("workflows[0].steps[0].actionRef")
        assertThat(workflow(""""steps":[{"id":"s1","actionRef":"x","next":"ghost"}]""").paths()).contains("workflows[0].steps[0].next")
        assertThat(workflow(""""startStepId":"ghost","steps":[{"id":"s1","actionRef":"x"}]""").paths()).contains("workflows[0].startStepId")
        assertThat(workflow(""""steps":[{"id":"s1","actionRef":"x","compensationActionRef":"ghost"}]""").paths()).contains("workflows[0].steps[0].compensationActionRef")
        assertThat(workflow(""""steps":[{"id":"s1","actionRef":"x","onError":"ghost"}]""").paths()).contains("workflows[0].steps[0].onError")
        assertThat(workflow(""""steps":[{"id":"s1","actionRef":"x","inputs":{"v":{"from":"STEP","stepId":"ghost"}}}]""").paths()).contains("workflows[0].steps[0].inputs.v.stepId")
    }

    @Test
    fun `step ids are unique and the step graph has no cycle`() {
        assertThat(workflow(""""steps":[{"id":"s1","actionRef":"x"},{"id":"s1","actionRef":"x"}]""").paths()).contains("workflows[0].steps[1].id")
        assertThat(workflow(""""steps":[{"id":"s1","actionRef":"x","next":"s2"},{"id":"s2","actionRef":"x","next":"s1"}]""").paths()).contains("workflows[0].steps")
        assertThat(workflow(""""steps":[{"id":"s1","actionRef":"x","onError":"s1"}]""").paths()).contains("workflows[0].steps")
    }

    @Test
    fun `each step kind has the member it needs`() {
        assertThat(workflow(""""steps":[{"id":"s1","kind":"WAIT"}]""").paths()).contains("workflows[0].steps[0].waitSeconds")
        assertThat(workflow(""""steps":[{"id":"s1","kind":"APPROVAL"}]""").paths()).contains("workflows[0].steps[0].approval")
        assertThat(workflow(""""steps":[{"id":"s1","kind":"BRANCH"}]""").paths()).contains("workflows[0].steps[0].branches")
        assertThat(workflow(""""steps":[{"id":"s1","kind":"ACTION"}]""").paths()).contains("workflows[0].steps[0].actionRef")
        assertThat(workflow(""""steps":[{"id":"s1","kind":"WAIT","waitSeconds":5,"actionRef":"x"}]""").paths()).contains("workflows[0].steps[0].actionRef")
        assertThat(workflow(""""steps":[{"id":"s1","kind":"WAIT","waitSeconds":5},{"id":"s2","kind":"END"}]""")).isEmpty()
    }

    @Test
    fun `branch conditions are bounded C4 condition trees`() {
        fun branch(cond: String) = workflow(""""steps":[{"id":"s1","kind":"BRANCH","branches":[{"condition":$cond,"next":"s2"}]},{"id":"s2"}]""")
        val eq = """{"op":"EQ","left":{"from":"INPUT","path":"a"},"right":{"from":"LITERAL","value":1}}"""
        assertThat(branch(eq)).isEmpty()
        assertThat(branch("""{"all":[$eq,{"not":$eq}]}""")).isEmpty()
        assertThat(branch("""{"op":"LIKE","left":{"from":"INPUT"},"right":{"from":"INPUT"}}""")).isNotEmpty()
        assertThat(branch("""{"op":"EQ","left":{"from":"INPUT"}}""")).isNotEmpty()
        assertThat(branch("""{"all":[]}""")).isNotEmpty()
        var deep = eq
        repeat(6) { deep = """{"not":$deep}""" }
        assertThat(branch(deep)).isNotEmpty()
    }

    @Test
    fun `a schedule is required for SCHEDULE triggers and only allowed there`() {
        assertThat(workflow(""""trigger":"SCHEDULE","steps":[{"id":"s1","actionRef":"x"}]""").paths()).contains("workflows[0].schedule")
        assertThat(workflow(""""trigger":"SCHEDULE","schedule":"0 8 * * *","timezone":"Asia/Ho_Chi_Minh","steps":[{"id":"s1","actionRef":"x"}]""")).isEmpty()
        assertThat(workflow(""""trigger":"MANUAL","schedule":"0 8 * * *","steps":[{"id":"s1","actionRef":"x"}]""").paths()).contains("workflows[0].schedule")
        assertThat(workflow(""""trigger":"SCHEDULE","schedule":"every day","steps":[{"id":"s1","actionRef":"x"}]""")).isNotEmpty()
    }

    @Test
    fun `an approval needs a title and approvers and its exits name known steps`() {
        fun approval(body: String) = workflow(""""steps":[{"id":"s1","kind":"APPROVAL","approval":$body}]""")
        assertThat(approval("""{"approvers":[{"kind":"ROLE","role":"mgr"}]}""").paths()).contains("workflows[0].steps[0].approval.title")
        assertThat(approval("""{"title":"OK?"}""").paths()).contains("workflows[0].steps[0].approval.approvers")
        assertThat(approval("""{"title":"OK?","approvers":[{"kind":"ROLE","role":"mgr"}],"onReject":"ghost"}""").paths()).contains("workflows[0].steps[0].approval.onReject")
        assertThat(approval("""{"title":"OK?","approvers":[{"kind":"ROLE","role":"mgr"}]}""")).isEmpty()
    }

    @Test
    fun `an action and a workflow that start each other form a rejected cycle`() {
        val v = run(""""actions":[{"id":"a","type":"START_WORKFLOW","workflowRef":"f","idempotency":"REQUIRED"}],"workflows":[{"id":"f","steps":[{"id":"s1","actionRef":"a"}]}]""")
        assertThat(v.any { it.message.contains("reference cycle") }).isTrue()
    }

    // ---- permissions ----

    @Test
    fun `a permission resource must exist`() {
        fun perm(type: String, ref: String) = run(""""permissions":[{"id":"p","permission":"QUERY_EXECUTE","resourceType":"$type","resourceRef":"$ref"}]""")
        assertThat(perm("QUERY", "r")).isEmpty()
        assertThat(perm("QUERY", "ghost").paths()).contains("permissions[0].resourceRef")
        assertThat(perm("DATA_SOURCE", "d")).isEmpty()
        assertThat(perm("WORKFLOW", "ghost").paths()).contains("permissions[0].resourceRef")
    }
}
