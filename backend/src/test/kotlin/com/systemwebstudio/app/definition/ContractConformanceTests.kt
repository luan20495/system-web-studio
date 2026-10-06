package com.systemwebstudio.app.definition

import com.systemwebstudio.app.definition.AppDefinitionTestSupport.codec
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.doc
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.json
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.resource
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.validator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * Conformance to docs/contracts/v2 (app-definition.md, data-runtime.md, action-workflow.md, tenant-permission.md): the fixtures in
 * `app-definition/conformance/` (listed in manifest.json) are valid or invalid for the reasons the manifest states, and the enums C2 mirrors
 * carry exactly the contract's names in the contract's order. C0 can copy the fixtures into docs/contracts/v2/fixtures/.
 */
class ContractConformanceTests {
    private fun fixture(name: String): JsonNode = resource("conformance/$name")
    private val manifest: List<JsonNode> get() = fixture("manifest.json").get("fixtures").toList()

    @Test
    fun `every fixture of the manifest behaves as declared`() {
        assertThat(manifest.size).isGreaterThanOrEqualTo(16)
        for (entry in manifest) {
            val file = entry.get("file").asString()
            val doc = fixture(file)
            val violations = validator.validateDocument(doc).violations
            when (entry.get("expect").asString()) {
                "VALID" -> {
                    assertThat(violations).describedAs("violations of $file").isEmpty()
                    if (entry.get("roundTrip")?.asBoolean() == true)
                        assertThat(codec.toJson(codec.fromJson(doc))).describedAs("round trip of $file").isEqualTo(doc)
                    // a legacy spelling is accepted and written back in the canonical spelling named by the manifest
                    entry.get("normalizesTo")?.let { canonical ->
                        assertThat(codec.toJson(codec.fromJson(doc))).describedAs("normalisation of $file").isEqualTo(fixture(canonical.asString()))
                    }
                }
                "INVALID" -> {
                    val paths = violations.map { it.path }
                    assertThat(paths).describedAs("violations of $file: $violations").isNotEmpty()
                    for (expected in entry.get("paths").toList().map { it.asString() })
                        assertThat(paths).describedAs("expected path $expected in $file, got $violations").contains(expected)
                }
                else -> error("unknown expectation in manifest for $file")
            }
        }
    }

    @Test
    fun `the manifest lists every conformance case the contract round asks for`() {
        val covers = manifest.map { it.get("file").asString() }
        assertThat(covers).contains(
            "legacy-single-page.json", "valid-v2-full.json", "valid-action-refresh-query.json", "valid-param-date.json", "valid-required-omitted.json",
            "valid-unknown-fields-roundtrip.json", "valid-action-def-full.json", "valid-workflow-def-full.json", "valid-mapping-def-full.json",
            "invalid-permission-code.json", "invalid-reference.json", "valid-mapping-transforms.json", "valid-mapping-legacy-transform.json",
            "invalid-action-run-query-alias.json"
        )
    }

    @Test
    fun `a legacy fixture has no V2 key and reads as legacy-only`() {
        val def = codec.fromJson(fixture("legacy-single-page.json"))
        assertThat(def.isLegacyOnly).isTrue()
        assertThat(def.schemaVersion).isNull()
    }

    @Test
    fun `a complete V2 fixture reads every persisted collection`() {
        val def = codec.fromJson(fixture("valid-v2-full.json"))
        assertThat(def.schemaVersion).isEqualTo(2)
        assertThat(def.kind).isEqualTo(AppDefinitionKind.PAGE_SCHEMA)
        assertThat(def.theme).isNotNull()
        assertThat(def.dataSources.map { it.id }).containsExactly("crm", "erp-db")
        assertThat(def.viewModels.map { it.id }).containsExactly("orders-vm")
        assertThat(def.queries.map { it.id }).containsExactly("orders-list", "order-create")
        assertThat(def.mappings.map { it.id }).containsExactly("orders-map")
        assertThat(def.dataBindings.map { it.id }).containsExactly("bind-orders")
        assertThat(def.actions.map { it.id }).containsExactly("go-home", "create-order", "notify-team", "start-notify")
        assertThat(def.workflows.map { it.id }).containsExactly("notify-flow")
        assertThat(def.permissions.map { it.id }).containsExactly("perm-create", "perm-list")
        assertThat(def.publishConfig).isNotNull()
        assertThat(def.extensions.keys).containsExactly("acme.crm")
        // legacy members stay in the page schema; pages / components / navigation are DERIVED views, never separate keys of the document
        assertThat(def.pageSchema.keys).containsExactly("page", "seo", "sections", "pages", "site")
        assertThat(def.sections().map { it.id }).contains("hero-1", "footer-1")
    }

    @Test
    fun `REFRESH_QUERY is an action type and the old RUN_QUERY alias is not`() {
        val def = codec.fromJson(fixture("valid-action-refresh-query.json"))
        assertThat(def.actions.single().type).isEqualTo(ActionType.REFRESH_QUERY)
        assertThat(ActionType.entries.map { it.name }).doesNotContain("RUN_QUERY", "WRITE_DATA", "CALL_CONNECTOR_OPERATION", "SET_VALUE")
        val problems = validator.validateDocument(fixture("invalid-action-run-query-alias.json")).violations
        assertThat(problems.filter { it.path.endsWith(".type") }).hasSize(4)
    }

    @Test
    fun `DATE is a parameter type`() {
        val def = codec.fromJson(fixture("valid-param-date.json"))
        assertThat(def.queries.single().params.map { it.type }).containsExactly(ParamType.DATE, ParamType.DATE)
    }

    @Test
    fun `a parameter that omits required is required and writing omits it again`() {
        val doc = fixture("valid-required-omitted.json")
        val params = codec.fromJson(doc).queries.single().params
        assertThat(params.map { it.required }).containsExactly(true, false)
        val written = codec.toJson(codec.fromJson(doc)).get("queries").get(0).get("params")
        assertThat(written.get(0).has("required")).isFalse()
        assertThat(written.get(1).get("required").asBoolean()).isFalse()
    }

    @Test
    fun `members C2 does not own survive read then write`() {
        val doc = fixture("valid-unknown-fields-roundtrip.json")
        val def = codec.fromJson(doc)
        assertThat(def.pageSchema.keys).contains("legacyUnknownKey")
        val out = codec.toJson(def)
        assertThat(out.get("legacyUnknownKey")).isEqualTo(doc.get("legacyUnknownKey"))
        assertThat(out.get("seo")).isEqualTo(doc.get("seo"))
        assertThat(out.get("sections").get(0).get("futureSectionKey").asString()).isEqualTo("kept")
        assertThat(out.get("extensions")).isEqualTo(doc.get("extensions"))
        assertThat(out).isEqualTo(doc)
    }

    @Test
    fun `an ActionDef keeps every canonical member`() {
        val def = codec.fromJson(fixture("valid-action-def-full.json"))
        val a = def.actions.first { it.id == "call-erp" }
        assertThat(a.name).isEqualTo("Đồng bộ ERP")
        assertThat(a.type).isEqualTo(ActionType.CALL_API)
        assertThat(a.enabled).isFalse()
        assertThat(a.trigger).isEqualTo(ActionTriggerDef("contact-1", "onSubmit"))
        assertThat(a.viewModelRef).isEqualTo("orders-vm")
        assertThat(a.dataSourceRef).isEqualTo("erp-db")
        assertThat(a.operationKey).isEqualTo("orders.sync")
        assertThat(a.permissionRef).isEqualTo("perm-act")
        assertThat(a.inputs.map { it.type }).containsExactly(*ActionInputType.entries.toTypedArray())
        assertThat(a.inputs.first().maxLength).isEqualTo(100)
        assertThat(a.inputs.first().required).isTrue()
        assertThat(a.inputMapping.values.map { it.source }).containsExactlyInAnyOrder(
            InputSourceKind.FORM_FIELD, InputSourceKind.ROUTE_PARAM, InputSourceKind.COMPONENT_STATE, InputSourceKind.LITERAL,
            InputSourceKind.CONTEXT, InputSourceKind.PREVIOUS_RESULT, InputSourceKind.VIEW_MODEL)
        assertThat(a.idempotency).isEqualTo(IdempotencyPolicyName.OPTIONAL)
        assertThat(a.limits?.timeoutMillis).isEqualTo(15000)
        assertThat(a.onSuccess).containsExactly("notify-all", "notify-mail")
        assertThat(a.onError).containsExactly("notify-hook")
        val notify = def.actions.first { it.id == "notify-all" }
        assertThat(notify.channel).isEqualTo(NotifyChannelName.IN_APP)
        assertThat(notify.templateRef).isEqualTo("tpl-order-synced")
        assertThat(notify.recipients.map { it.kind }).containsExactly(*PrincipalKind.entries.toTypedArray())
        assertThat(def.actions.first { it.id == "notify-hook" }.endpointRef).isEqualTo("ep-erp-events")
        assertThat(def.actions.map { it.type }.toSet()).isEqualTo(ActionType.entries.toSet())
    }

    @Test
    fun `a WorkflowDef keeps every canonical member`() {
        val w = codec.fromJson(fixture("valid-workflow-def-full.json")).workflows.first { it.id == "wf-full" }
        assertThat(w.name).isEqualTo("Duyệt đơn")
        assertThat(w.enabled).isFalse()
        assertThat(w.trigger).isEqualTo(WorkflowTriggerType.SCHEDULE)
        assertThat(w.schedule).isEqualTo("0 8 * * 1-5")
        assertThat(w.timezone).isEqualTo("Asia/Ho_Chi_Minh")
        assertThat(w.startStepId).isEqualTo("s-start")
        assertThat(w.compensateOnCancel).isTrue()
        assertThat(w.limits).isEqualTo(WorkflowLimitsDef(20, 50, 5, 86400, 65536, 3, 60000))
        val start = w.steps.first { it.id == "s-start" }
        assertThat(start.inputs.keys).containsExactly("customer", "mode")
        assertThat(start.inputs.getValue("customer").from).isEqualTo(ValueRefFrom.INPUT)
        assertThat(start.retry?.maxAttempts).isEqualTo(3)
        assertThat(start.timeoutMillis).isEqualTo(10000)
        assertThat(start.onError).isEqualTo("s-fail")
        assertThat(start.compensationActionRef).isEqualTo("act-undo")
        assertThat(w.steps.first { it.id == "s-wait" }.waitSeconds).isEqualTo(60)
        val approval = w.steps.first { it.id == "s-approve" }.approval!!
        assertThat(approval.approvers).hasSize(2)
        assertThat(approval.requiredApprovals).isEqualTo(2)
        assertThat(approval.onReject).isEqualTo("s-fail")
        val branch = w.steps.first { it.id == "s-branch" }
        assertThat(branch.branches).hasSize(2)
        assertThat(branch.defaultNext).isEqualTo("s-end")
        assertThat(w.steps.map { it.kind }.toSet()).contains(StepKind.WAIT, StepKind.APPROVAL, StepKind.BRANCH, StepKind.END)
    }

    @Test
    fun `a MappingDef keeps every canonical member`() {
        val m = codec.fromJson(fixture("valid-mapping-def-full.json")).mappings.first { it.id == "orders-full" }
        assertThat(m.name).isEqualTo("Orders mapping")
        assertThat(m.description).isNotBlank()
        assertThat(m.errorPolicy).isEqualTo(MappingErrorPolicy.SKIP_ROW)
        assertThat(m.version).isEqualTo(3L)
        assertThat(m.fields).hasSize(7)
        assertThat(m.fields[0].transforms.map { it.get("type").asString() }).containsExactly("trim")
        assertThat(m.fields[0].validation?.minLength).isEqualTo(1)
        assertThat(m.fields[1].transforms.map { it.get("type").asString() }).containsExactly("toNumber")
        assertThat(m.fields[1].nullable).isFalse()
        assertThat(m.fields[1].defaultValue!!.asInt()).isEqualTo(0)
        assertThat(m.fields[2].validation?.oneOf).hasSize(3)
        assertThat(m.fields[3].validation?.format).isEqualTo(ValueFormat.DATETIME)
        assertThat(m.fields[4].from).isNull()
        assertThat(m.fields[5].defaultValue!!.asString()).isEqualTo("erp")
        assertThat(m.fields[6].validation?.format).isEqualTo(ValueFormat.EMAIL)
        // the minimal C2 shape {from, to} still works and keeps its defaults
        val min = codec.fromJson(fixture("valid-mapping-def-full.json")).mappings.first { it.id == "orders-min" }
        assertThat(min.errorPolicy).isEqualTo(MappingErrorPolicy.NULL_FIELD)
        assertThat(min.version).isEqualTo(1L)
        assertThat(min.fields.single().nullable).isTrue()
    }

    @Test
    fun `mapping transforms are the canonical transforms array, in order, with nothing lost`() {
        val doc = fixture("valid-mapping-transforms.json")
        val fields = codec.fromJson(doc).mappings.single().fields
        assertThat(fields.map { it.transforms.size }).containsExactly(1, 1, 2, 1, 1, 0)
        assertThat(fields[2].transforms.map { it.get("type").asString() }).containsExactly("trim", "upper")
        assertThat(fields[3].transforms.single().get("map").get("C").asString()).isEqualTo("closed")
        assertThat(fields[3].transforms.single().get("default").asString()).isEqualTo("unknown")
        assertThat(fields[4].transforms.single().get("fields").size()).isEqualTo(2)
        val out = codec.toJson(codec.fromJson(doc)).get("mappings").get(0).get("fields")
        assertThat(out.get(2).get("transforms")).isEqualTo(doc.get("mappings").get(0).get("fields").get(2).get("transforms"))
        assertThat(out.get(5).has("transforms")).isFalse()          // no transform: nothing written
        for (i in 0 until out.size()) assertThat(out.get(i).has("transform")).describedAs("legacy key written at $i").isFalse()
    }

    @Test
    fun `the legacy transform key is read, normalised to transforms and never written`() {
        val legacy = fixture("valid-mapping-legacy-transform.json")
        val fields = codec.fromJson(legacy).mappings.single().fields
        assertThat(fields.map { it.transforms.size }).containsExactly(1, 1, 2, 1, 1, 0)
        assertThat(fields[0].transforms.single().get("type").asString()).isEqualTo("trim")
        assertThat(fields[3].transforms.single().get("type").asString()).isEqualTo("enumMap")
        val written = codec.toJson(codec.fromJson(legacy))
        assertThat(written).isEqualTo(fixture("valid-mapping-transforms.json"))
        assertThat(written.toString()).doesNotContain("\"transform\":")
        // legacy then canonical give the same definition
        assertThat(codec.fromJson(legacy)).isEqualTo(codec.fromJson(fixture("valid-mapping-transforms.json")))
    }

    @Test
    fun `transforms are bounded and shaped`() {
        fun field(f: String) = validator.validateDocument(doc(
            """"dataSources":[{"id":"d","type":"postgres"}],"queries":[{"id":"q","dataSourceRef":"d","operationKey":"op"}],
               "mappings":[{"id":"m","queryRef":"q","fields":[{"from":"a","to":"b",$f}]}]""")).violations
        assertThat(field(""""transforms":[{"type":"trim"}]""")).isEmpty()
        assertThat(field(""""transforms":{"type":"trim"}""").map { it.path }).contains("mappings[0].fields[0].transforms")
        assertThat(field(""""transforms":["trim"]""").map { it.path }).contains("mappings[0].fields[0].transforms[0]")
        assertThat(field(""""transforms":[{"separator":" "}]""").map { it.path }).contains("mappings[0].fields[0].transforms[0].type")
        assertThat(field(""""transforms":[{"type":"trim"},{"type":"trim"},{"type":"trim"},{"type":"trim"},{"type":"trim"},{"type":"trim"},{"type":"trim"},{"type":"trim"},{"type":"trim"}]""")
            .map { it.path }).contains("mappings[0].fields[0].transforms")
        assertThat(field(""""transform":{"type":"trim"}""")).isEmpty()                          // legacy spelling still accepted
        assertThat(field(""""transform":"trim"""").map { it.path }).contains("mappings[0].fields[0].transform")
        assertThat(field(""""transforms":[{"type":"join","url":"https://x.example"}]""")).isNotEmpty()  // free-form scan still applies
    }

    @Test
    fun `permission codes equal the table of the frozen contract`() {
        // docs/contracts/v2/tenant-permission.md section 5 is the source; this reads it when the file is reachable (gradle runs in backend/)
        val file = listOf("../docs/contracts/v2/tenant-permission.md", "docs/contracts/v2/tenant-permission.md").map { java.io.File(it) }.firstOrNull { it.isFile } ?: return
        val section = file.readText().substringAfter("## 5. Canonical permission vocabulary").substringBefore("\nRules:")
        val firstColumns = section.lines().filter { it.startsWith("| `") }.map { it.split("|")[1] }
        val codes = firstColumns.flatMap { cell -> Regex("`([A-Z][A-Z_]+)`").findAll(cell).map { it.groupValues[1] }.toList() }.toSet()
        assertThat(codes).isNotEmpty()
        assertThat(PermissionCodes.ALL.toSet()).isEqualTo(codes)
    }

    @Test
    fun `only canonical permission codes are accepted`() {
        val problems = validator.validateDocument(fixture("invalid-permission-code.json")).violations
        assertThat(problems.map { it.path }).contains("permissions[0].permission", "permissions[1].permission")
        for (code in PermissionCodes.ALL) {
            val doc = json.readTree(
                """{"schemaVersion":2,"kind":"PAGE_SCHEMA","page":"p","sections":[],"dataSources":[{"id":"d","type":"postgres"}],
                    "queries":[{"id":"q","dataSourceRef":"d","operationKey":"op"}],
                    "permissions":[{"id":"p1","permission":"$code","resourceType":"QUERY","resourceRef":"q"}]}"""
            )
            assertThat(validator.validateDocument(doc).violations).describedAs(code).isEmpty()
        }
    }

    // ---- enums the contract defines, mirrored by C2: names and order must match (INTEGRATION_V2: duplicated enums need a conformance test) ----

    @Test
    fun `mirrored enums carry exactly the contract values`() {
        fun <E : Enum<E>> names(values: Array<E>) = values.map { it.name }
        assertThat(names(ActionType.entries.toTypedArray())).containsExactly("NAVIGATE", "REFRESH_QUERY", "SUBMIT_FORM", "CREATE_RECORD", "UPDATE_RECORD", "DELETE_RECORD", "CALL_API", "NOTIFY", "START_WORKFLOW")
        assertThat(names(ParamType.entries.toTypedArray())).containsExactly("STRING", "INTEGER", "NUMBER", "BOOLEAN", "TIMESTAMP", "DATE")
        assertThat(names(FieldType.entries.toTypedArray())).containsExactly("STRING", "NUMBER", "BOOLEAN", "DATE", "DATETIME", "OBJECT", "ARRAY")
        assertThat(names(Cardinality.entries.toTypedArray())).containsExactly("SINGLE", "LIST")
        assertThat(names(MappingErrorPolicy.entries.toTypedArray())).containsExactly("FAIL", "NULL_FIELD", "SKIP_ROW")
        assertThat(names(ValueFormat.entries.toTypedArray())).containsExactly("EMAIL", "URL", "UUID", "DATE", "DATETIME", "INTEGER")
        assertThat(names(InputSourceKind.entries.toTypedArray())).containsExactly("COMPONENT_STATE", "ROUTE_PARAM", "FORM_FIELD", "VIEW_MODEL", "PREVIOUS_RESULT", "LITERAL", "CONTEXT")
        assertThat(names(ContextKeyName.entries.toTypedArray())).containsExactly("USER_ID", "TENANT_ID", "WORKSPACE_ID", "APP_ID", "REQUEST_ID", "NOW")
        assertThat(names(IdempotencyPolicyName.entries.toTypedArray())).containsExactly("NONE", "OPTIONAL", "REQUIRED")
        assertThat(names(NotifyChannelName.entries.toTypedArray())).containsExactly("IN_APP", "EMAIL", "WEBHOOK", "SMS")
        assertThat(names(PrincipalKind.entries.toTypedArray())).containsExactly("USER", "GROUP", "ROLE", "DEPARTMENT_MANAGER")
        assertThat(names(StepKind.entries.toTypedArray())).containsExactly("ACTION", "WAIT", "APPROVAL", "BRANCH", "END")
        assertThat(names(WorkflowTriggerType.entries.toTypedArray())).containsExactly("MANUAL", "SCHEDULE", "ACTION")
        assertThat(ActionEvents.ALL).containsExactly("onLoad", "onClick", "onChange", "onSubmit", "onSuccess", "onError")
        assertThat(PermissionCodes.ALL).containsExactlyInAnyOrder(
            "APP_VIEW", "APP_USE", "APP_EDIT", "APP_PUBLISH", "APP_SHARE", "DATA_SOURCE_VIEW", "DATA_SOURCE_MANAGE", "QUERY_EXECUTE",
            "DATA_MUTATE", "ACTION_EXECUTE", "WORKFLOW_EXECUTE", "WORKFLOW_MANAGE", "TENANT_MANAGE", "TENANT_MEMBERS")
    }

    @Test
    fun `derived views are never written as keys of the document`() {
        val allowed = AppDefinitionKeys.V2_KEYS + AppDefinitionKeys.LEGACY_KEYS
        for (file in listOf("valid-v2-full.json", "valid-action-def-full.json", "valid-workflow-def-full.json", "valid-mapping-def-full.json")) {
            val doc = fixture(file)
            val def = codec.fromJson(doc)
            def.pages(); def.navigation(); def.components()                      // computing the views must not change what is written
            val keys = codec.toJson(def).propertyNames().toList()
            assertThat(keys.filter { it !in allowed }).describedAs("keys of $file").isEmpty()
            assertThat(keys.filter { it in listOf("components", "navigation", "assets", "actionRefs") }).isEmpty()
        }
    }

    @Test
    fun `the persisted keys of a V2 document are exactly the contract keys`() {
        assertThat(AppDefinitionKeys.V2_KEYS).containsExactlyInAnyOrder(
            "schemaVersion", "kind", "theme", "dataSources", "viewModels", "queries", "mappings", "dataBindings", "actions", "workflows",
            "permissions", "publishConfig", "extensions")
    }
}
