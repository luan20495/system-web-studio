package com.systemwebstudio.app.definition

import com.systemwebstudio.app.definition.AppDefinitionTestSupport.check
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.codec
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.doc
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.json
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.resource
import com.systemwebstudio.app.definition.AppDefinitionTestSupport.validator
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.schema.Violation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Pure unit tests (no Spring, no database): the real PageSchemaValidator and AppDefinitionValidator over the seeded registry. */
class AppDefinitionValidatorTests {
    private fun List<Violation>.paths() = map { it.path }
    private fun List<Violation>.has(path: String, fragment: String) = any { it.path == path && it.message.contains(fragment) }

    // ---- valid ----

    @Test
    fun `the sample V2 document is valid`() {
        val result = validator.validateDocument(resource("valid-v2-sample.json"))
        assertThat(result.violations).isEmpty()
        assertThat(result.valid).isTrue()
    }

    @Test
    fun `an empty definition set is valid and each optional key may be used on its own`() {
        assertThat(check("")).isEmpty()
        assertThat(check(""""schemaVersion":2""")).isEmpty()
        assertThat(check(""""kind":"PAGE_SCHEMA"""")).isEmpty()
        assertThat(check(""""dataSources":[{"id":"a","type":"rest"}]""")).isEmpty()
        assertThat(check(""""publishConfig":{}""")).isEmpty()
        assertThat(check(""""extensions":{"acme":{"x":1}}""")).isEmpty()
    }

    // ---- structure ----

    @Test
    fun `unknown fields inside definitions are rejected with a path`() {
        assertThat(check(""""dataSources":[{"id":"a","type":"rest","url":"x"}]""").has("dataSources[0].url", "unknown field")).isTrue()
        assertThat(check(""""queries":[{"id":"q","dataSourceRef":"a","sql":"select 1"}]""").has("queries[0].sql", "unknown field")).isTrue()
        assertThat(check(""""publishConfig":{"mode":"STATIC","extra":1}""").has("publishConfig.extra", "unknown field")).isTrue()
    }

    @Test
    fun `a V2 document that declares schemaVersion may not carry unknown top-level keys but a legacy one may`() {
        assertThat(check(""""schemaVersion":2,"surprise":1""").has("surprise", "unknown top-level field")).isTrue()
        assertThat(check(""""surprise":1""")).isEmpty()
    }

    @Test
    fun `schemaVersion and kind must be known values`() {
        assertThat(check(""""schemaVersion":3""").has("schemaVersion", "must be 2")).isTrue()
        assertThat(check(""""schemaVersion":"2"""").has("schemaVersion", "must be 2")).isTrue()
        assertThat(check(""""kind":"STATIC_APP"""").has("kind", "must be one of PAGE_SCHEMA")).isTrue()
        assertThat(check(""""kind":"PAGE_SCHEMA"""")).isEmpty()
    }

    @Test
    fun `ids and names must follow their patterns`() {
        assertThat(check(""""dataSources":[{"id":"Bad Id","type":"rest"}]""").paths()).contains("dataSources[0].id")
        assertThat(check(""""dataSources":[{"type":"rest"}]""").has("dataSources[0].id", "is required")).isTrue()
        assertThat(check(""""dataSources":[{"id":"a","type":"REST"}]""").paths()).contains("dataSources[0].type")
        assertThat(check(""""dataSources":[{"id":"a","type":"rest","sourceRef":"not-a-uuid"}]""").paths()).contains("dataSources[0].sourceRef")
        assertThat(check(""""mappings":[{"id":"m","queryRef":"q","fields":[{"from":"a/b","to":"x"}]}]""").paths()).contains("mappings[0].fields[0].from")
        assertThat(check(""""mappings":[{"id":"m","queryRef":"q","fields":[{"from":"a","to":"1x"}]}]""").paths()).contains("mappings[0].fields[0].to")
        assertThat(check(""""queries":[{"id":"q","dataSourceRef":"a","maxRows":0}]""").paths()).contains("queries[0].maxRows")
        assertThat(check(""""queries":[{"id":"q","dataSourceRef":"a","mode":"DELETE"}]""").paths()).contains("queries[0].mode")
        assertThat(check(""""permissions":[{"id":"p","permission":"lower","resourceType":"QUERY","resourceRef":"q"}]""").paths()).contains("permissions[0].permission")
    }

    @Test
    fun `explicit nulls and wrong json types are rejected`() {
        assertThat(check(""""dataSources":null""").has("dataSources", "must not be null")).isTrue()
        assertThat(check(""""dataSources":{}""").has("dataSources", "must be an array")).isTrue()
        assertThat(check(""""dataSources":["x"]""").has("dataSources[0]", "must be an object")).isTrue()
        assertThat(check(""""extensions":[]""").has("extensions", "must be an object")).isTrue()
        assertThat(check(""""publishConfig":{"requiresAuth":"yes"}""").has("publishConfig.requiresAuth", "must be a boolean")).isTrue()
        assertThat(check(""""publishConfig":{"cacheSeconds":99999}""").has("publishConfig.cacheSeconds", "between 0 and")).isTrue()
    }

    @Test
    fun `parameter defaults must be plain scalars of the declared type`() {
        val q = { p: String -> check(""""queries":[{"id":"q","dataSourceRef":"a","params":[$p]}],"dataSources":[{"id":"a","type":"rest"}]""") }
        assertThat(q("""{"name":"n","type":"NUMBER","default":"1"}""").has("queries[0].params[0].default", "must be a number")).isTrue()
        assertThat(q("""{"name":"n","type":"TIMESTAMP","default":"yesterday"}""").has("queries[0].params[0].default", "timestamp")).isTrue()
        assertThat(q("""{"name":"n","type":"BOOLEAN","default":"true"}""").has("queries[0].params[0].default", "boolean")).isTrue()
        assertThat(q("""{"name":"n","type":"STRING","default":{"a":1}}""").paths()).contains("queries[0].params[0].default")
        assertThat(q("""{"name":"n","type":"STRING","default":"https://evil.example/x"}""").paths()).contains("queries[0].params[0].default")
        assertThat(q("""{"name":"n","type":"INTEGER","default":3.5}""").has("queries[0].params[0].default", "must be an integer")).isTrue()
        assertThat(q("""{"name":"n","type":"INTEGER","default":3}""")).isEmpty()
        assertThat(q("""{"name":"n","type":"TIMESTAMP","default":"2026-10-05T08:30:00Z"}""")).isEmpty()
        assertThat(q("""{"name":"N","type":"STRING"}""").paths()).contains("queries[0].params[0].name")      // C3: parameter names start lowercase
        assertThat(q("""{"name":"n","type":"DATETIME"}""").paths()).contains("queries[0].params[0].type")       // DATE exists (C3), DATETIME does not
        assertThat(q("""{"name":"n","type":"DATE","default":"2026-10-05"}""")).isEmpty()
        assertThat(q("""{"name":"n","type":"DATE","default":"05/10/2026"}""").has("queries[0].params[0].default", "date like")).isTrue()
        assertThat(q("""{"name":"n","type":"NUMBER","default":3.5}""")).isEmpty()
        assertThat(q("""{"name":"n","type":"STRING"},{"name":"n","type":"STRING"}""").has("queries[0].params[1].name", "duplicate")).isTrue()
    }

    // ---- no raw URLs, SQL, credentials or code ----

    @Test
    fun `raw URLs are rejected in every text field`() {
        assertThat(check(""""dataSources":[{"id":"a","type":"rest","name":"see https://x.example/api"}]""").paths()).contains("dataSources[0].name")
        assertThat(check(""""dataSources":[{"id":"a","type":"rest","description":"jdbc:postgresql://db/x"}]""").paths()).contains("dataSources[0].description")
        assertThat(check(""""dataSources":[{"id":"a","type":"rest","name":"javascript:alert(1)"}]""").paths()).contains("dataSources[0].name")
        assertThat(check(""""dataSources":[{"id":"a","type":"rest","name":"data:text/html;base64,AAAA"}]""").paths()).contains("dataSources[0].name")
        assertThat(check(""""dataSources":[{"id":"a","type":"rest","name":"Data: orders"}]""")).isEmpty()
    }

    @Test
    fun `the extension area rejects URLs credentials SQL and code-like keys at any depth`() {
        assertThat(check(""""extensions":{"acme":{"endpoint":"x"}}""").paths()).contains("extensions.acme.endpoint")
        assertThat(check(""""extensions":{"acme":{"nested":{"Api_Key":"x"}}}""").paths()).contains("extensions.acme.nested.Api_Key")
        assertThat(check(""""extensions":{"acme":{"list":[{"password":"x"}]}}""").paths()).contains("extensions.acme.list[0].password")
        assertThat(check(""""extensions":{"acme":{"note":"http://x.example"}}""").paths()).contains("extensions.acme.note")
        assertThat(check(""""extensions":{"acme":{"sql":"select 1"}}""").paths()).contains("extensions.acme.sql")
        assertThat(check(""""extensions":{"Not A Namespace":{}}""").paths()).contains("extensions.Not A Namespace")
        assertThat(check(""""extensions":{"acme":"text"}""").has("extensions.acme", "must be an object")).isTrue()
        assertThat(check(""""extensions":{"acme":{"a":{"b":{"c":{"d":{"e":{"f":1}}}}}}}""").paths()).contains("extensions.acme.a.b.c.d.e.f")
    }

    // ---- limits ----

    @Test
    fun `collection sizes are limited`() {
        val many = (1..21).joinToString(",") { """{"id":"ds-$it","type":"rest"}""" }
        assertThat(check(""""dataSources":[$many]""").has("dataSources", "at most 20 entries")).isTrue()
        val params = (1..21).joinToString(",") { """{"name":"p$it","type":"STRING"}""" }
        assertThat(check(""""dataSources":[{"id":"a","type":"rest"}],"queries":[{"id":"q","dataSourceRef":"a","params":[$params]}]""").has("queries[0].params", "at most 20")).isTrue()
        val longName = "x".repeat(81)
        assertThat(check(""""dataSources":[{"id":"a","type":"rest","name":"$longName"}]""").has("dataSources[0].name", "longer than 80")).isTrue()
    }

    // ---- references ----

    @Test
    fun `duplicate ids are reported per collection`() {
        assertThat(check(""""dataSources":[{"id":"a","type":"rest"},{"id":"a","type":"rest"}]""").has("dataSources[1].id", "duplicate id 'a'")).isTrue()
        val vm = check(""""viewModels":[{"id":"v"},{"id":"v"}]""")
        assertThat(vm.has("viewModels[1].id", "duplicate id 'v'")).isTrue()
        // the same id in different collections is fine
        assertThat(check(""""dataSources":[{"id":"x","type":"rest"}],"viewModels":[{"id":"x"}]""")).isEmpty()
    }

    @Test
    fun `dangling references are reported with the path of the reference`() {
        assertThat(check(""""queries":[{"id":"q","dataSourceRef":"nope"}]""").has("queries[0].dataSourceRef", "unknown data source 'nope'")).isTrue()
        assertThat(check(""""mappings":[{"id":"m","queryRef":"nope","fields":[{"from":"a","to":"t"}]}]""").has("mappings[0].queryRef", "unknown query 'nope'")).isTrue()
        assertThat(check(""""viewModels":[{"id":"v","queryRef":"nope","mappingRef":"nada"}]""").paths()).contains("viewModels[0].queryRef", "viewModels[0].mappingRef")
        assertThat(check(""""actions":[{"id":"a","type":"NOTIFY","channel":"IN_APP","templateRef":"tpl-1","viewModelRef":"x","workflowRef":"y","dataSourceRef":"z","permissionRef":"w"}]""").paths())
            .contains("actions[0].viewModelRef", "actions[0].workflowRef", "actions[0].dataSourceRef", "actions[0].permissionRef")
        assertThat(check(""""actions":[{"id":"a","type":"NOTIFY","channel":"IN_APP","templateRef":"tpl-1","trigger":{"sectionId":"ghost-1","event":"onClick"}}]""")
            .has("actions[0].trigger.sectionId", "unknown section 'ghost-1'")).isTrue()
        assertThat(check(""""actions":[{"id":"a","type":"NOTIFY","channel":"IN_APP","templateRef":"tpl-1","trigger":{"sectionId":"hero-1","event":"onClick"}}]""")).isEmpty()
        assertThat(check(""""permissions":[{"id":"p","permission":"QUERY_EXECUTE","resourceType":"QUERY","resourceRef":"nope"}]""")
            .has("permissions[0].resourceRef", "unknown query 'nope'")).isTrue()
        assertThat(check(""""workflows":[{"id":"w","steps":[{"id":"s1","actionRef":"ghost"}]}]""").has("workflows[0].steps[0].actionRef", "unknown action 'ghost'")).isTrue()
        assertThat(check(""""workflows":[{"id":"w","steps":[{"id":"s1","actionRef":"a","next":"s9"}]}],"actions":[{"id":"a","type":"NOTIFY","channel":"IN_APP","templateRef":"tpl-1"}]""")
            .has("workflows[0].steps[0].next", "unknown step 's9'")).isTrue()
    }

    @Test
    fun `a mapping must fit the view model that uses it`() {
        val base = """"dataSources":[{"id":"d","type":"rest"}],"queries":[{"id":"q1","dataSourceRef":"d"},{"id":"q2","dataSourceRef":"d"}],"""
        val otherQuery = check(base + """"mappings":[{"id":"m","queryRef":"q2","fields":[{"from":"a","to":"t"}]}],"viewModels":[{"id":"v","queryRef":"q1","mappingRef":"m"}]""")
        assertThat(otherQuery.has("viewModels[0].mappingRef", "reads query 'q2', not 'q1'")).isTrue()
        val unknownField = check(base + """"mappings":[{"id":"m","queryRef":"q1","fields":[{"from":"a","to":"title"}]}],"viewModels":[{"id":"v","queryRef":"q1","mappingRef":"m","fields":[{"name":"name"}]}]""")
        assertThat(unknownField.has("viewModels[0].mappingRef", "'title', which is not a field")).isTrue()
        val dupField = check(base + """"viewModels":[{"id":"v","fields":[{"name":"a"},{"name":"a"}]}]""")
        assertThat(dupField.has("viewModels[0].fields[1].name", "duplicate")).isTrue()
        val dupTarget = check(base + """"mappings":[{"id":"m","queryRef":"q1","fields":[{"from":"a","to":"t"},{"from":"b","to":"t"}]}]""")
        assertThat(dupTarget.has("mappings[0].fields[1].to", "duplicate")).isTrue()
    }

    @Test
    fun `actions need the references their type requires`() {
        val base = """"dataSources":[{"id":"d","type":"rest"}],"queries":[{"id":"read","dataSourceRef":"d"},{"id":"write","dataSourceRef":"d","mode":"WRITE"}],"""
        for (type in listOf("SUBMIT_FORM", "CREATE_RECORD", "UPDATE_RECORD", "DELETE_RECORD")) {
            assertThat(check(base + """"actions":[{"id":"a","type":"$type"}]""").has("actions[0].queryRef", "required for a $type")).isTrue()
            assertThat(check(base + """"actions":[{"id":"a","type":"$type","queryRef":"read"}]""").has("actions[0].queryRef", "needs a WRITE query")).isTrue()
            assertThat(check(base + """"actions":[{"id":"a","type":"$type","queryRef":"write"}]""")).isEmpty()
        }
        assertThat(check(base + """"actions":[{"id":"a","type":"CALL_API"}]""").paths()).contains("actions[0].dataSourceRef", "actions[0].operationKey")
        assertThat(check(base + """"actions":[{"id":"a","type":"START_WORKFLOW"}]""").has("actions[0].workflowRef", "required for a START_WORKFLOW")).isTrue()
        assertThat(check(base + """"actions":[{"id":"a","type":"NAVIGATE"}]""").has("actions[0].pageRef", "required for a NAVIGATE")).isTrue()
        assertThat(check(base + """"actions":[{"id":"a","type":"NAVIGATE","pageRef":"nowhere"}]""").has("actions[0].pageRef", "unknown page 'nowhere'")).isTrue()
        assertThat(check(base + """"actions":[{"id":"a","type":"NAVIGATE","pageRef":"https://evil.example"}]""").paths()).contains("actions[0].pageRef")
        assertThat(check(base + """"actions":[{"id":"a","type":"CALL_API","dataSourceRef":"d","operationKey":"orders.sync"},{"id":"n","type":"NAVIGATE","pageRef":"home"},{"id":"f","type":"NOTIFY","channel":"IN_APP","templateRef":"tpl-1"}]""")).isEmpty()
        // the old / free-form kinds do not exist: no raw SQL, no scripts, no arbitrary request
        for (gone in listOf("RUN_QUERY", "WRITE_DATA", "SET_VALUE", "CALL_CONNECTOR_OPERATION", "DROP_DATABASE", "RUN_SCRIPT", "HTTP_REQUEST"))
            assertThat(check(base + """"actions":[{"id":"a","type":"$gone"}]""").paths()).contains("actions[0].type")
        assertThat(check(base + """"actions":[{"id":"a"}]""").has("actions[0].type", "is required")).isTrue()
    }

    @Test
    fun `an action cannot carry a URL SQL or code`() {
        val base = """"actions":[{"id":"a","type":"NOTIFY","channel":"IN_APP","templateRef":"tpl-1","""
        assertThat(check(base + """"url":"https://x.example"}]""").has("actions[0].url", "unknown field")).isTrue()
        assertThat(check(base + """"sql":"drop table x"}]""").has("actions[0].sql", "unknown field")).isTrue()
        assertThat(check(base + """"script":"alert(1)"}]""").has("actions[0].script", "unknown field")).isTrue()
        assertThat(check(base + """"name":"see https://x.example"}]""").paths()).contains("actions[0].name")
        assertThat(check(""""actions":[{"id":"a","type":"CALL_API","dataSourceRef":"d","operationKey":"https://x.example/y"}],"dataSources":[{"id":"d","type":"rest"}]""").paths()).contains("actions[0].operationKey")
    }

    @Test
    fun `reads are bound to data and never run as writes`() {
        val base = """"dataSources":[{"id":"d","type":"rest"}],"queries":[{"id":"w","dataSourceRef":"d","mode":"WRITE"}],"""
        assertThat(check(base + """"viewModels":[{"id":"v","queryRef":"w"}]""").has("viewModels[0].queryRef", "is a WRITE query")).isTrue()
        assertThat(check(base + """"dataBindings":[{"id":"b","sectionId":"products-1","prop":"items","queryRef":"w"}]""").has("dataBindings[0].queryRef", "is a WRITE query")).isTrue()
    }

    @Test
    fun `theme and publish draft are bounded and typed`() {
        assertThat(check(""""theme":{"colors":{"primary":"#1A2B3C"},"fontFamily":"SERIF","radius":"LG"}""")).isEmpty()
        assertThat(check(""""theme":{"colors":{"primary":"red"}}""").paths()).contains("theme.colors.primary")
        assertThat(check(""""theme":{"colors":{"primary":"url(https://x)"}}""").paths()).contains("theme.colors.primary")
        assertThat(check(""""theme":{"colors":{"Bad Name":"#000000"}}""").paths()).contains("theme.colors.Bad Name")
        assertThat(check(""""theme":{"fontFamily":"Comic Sans"}""").paths()).contains("theme.fontFamily")
        assertThat(check(""""theme":{"css":"body{}"}""").has("theme.css", "unknown field")).isTrue()
        assertThat(check(""""publishConfig":{"mode":"DYNAMIC","visibility":"PRIVATE_LINK"}""")).isEmpty()
        assertThat(check(""""publishConfig":{"mode":"SERVER_APP"}""").has("publishConfig.mode", "not available for an app definition")).isTrue()
        assertThat(check(""""publishConfig":{"mode":"DATA_DRIVEN"}""").paths()).contains("publishConfig.mode")
        assertThat(check(""""publishConfig":{"visibility":"EVERYONE"}""").paths()).contains("publishConfig.visibility")
        assertThat(check(""""publishConfig":{"visibility":"PUBLIC","requiresAuth":true}""").has("publishConfig.requiresAuth", "contradicts")).isTrue()
    }

    @Test
    fun `workflow trigger and schedule must agree`() {
        assertThat(check(""""workflows":[{"id":"w","trigger":"SCHEDULE"}]""").has("workflows[0].schedule", "required for a SCHEDULE")).isTrue()
        assertThat(check(""""workflows":[{"id":"w","trigger":"MANUAL","schedule":"0 * * * *"}]""").has("workflows[0].schedule", "only allowed for a SCHEDULE")).isTrue()
        assertThat(check(""""workflows":[{"id":"w","trigger":"SCHEDULE","schedule":"every minute"}]""").paths()).contains("workflows[0].schedule")
        assertThat(check(""""workflows":[{"id":"w","trigger":"SCHEDULE","schedule":"*/15 8-18 * * 1-5"}]""")).isEmpty()
    }

    @Test
    fun `reference cycles are detected`() {
        // action a starts workflow w whose step runs action a again
        val loop = check(""""actions":[{"id":"a","type":"START_WORKFLOW","workflowRef":"w"}],"workflows":[{"id":"w","steps":[{"id":"s1","actionRef":"a"}]}]""")
        assertThat(loop.any { it.message.startsWith("reference cycle: ") && it.message.contains("action 'a'") && it.message.contains("workflow 'w'") }).isTrue()
        assertThat(loop.paths()).contains("actions[0]")
        // longer chain through two workflows
        val chain = check(""""actions":[{"id":"a","type":"START_WORKFLOW","workflowRef":"w1"},{"id":"b","type":"START_WORKFLOW","workflowRef":"w2"}],
            "workflows":[{"id":"w1","steps":[{"id":"s","actionRef":"b"}]},{"id":"w2","steps":[{"id":"s","actionRef":"a"}]}]""")
        assertThat(chain.any { it.message.startsWith("reference cycle: ") }).isTrue()
        // steps pointing back at each other
        val steps = check(""""actions":[{"id":"a","type":"NOTIFY","channel":"IN_APP","templateRef":"tpl-1"}],"workflows":[{"id":"w","steps":[{"id":"s1","actionRef":"a","next":"s2"},{"id":"s2","actionRef":"a","onError":"s1"}]}]""")
        assertThat(steps.any { it.path == "workflows[0].steps" && it.message.startsWith("step cycle: ") }).isTrue()
        // a diamond (shared target) is not a cycle
        val diamond = check(""""actions":[{"id":"a","type":"NOTIFY","channel":"IN_APP","templateRef":"tpl-1"}],"workflows":[{"id":"w","steps":[{"id":"s1","actionRef":"a","next":"s3","onError":"s2"},{"id":"s2","actionRef":"a","next":"s3"},{"id":"s3","actionRef":"a"}]}]""")
        assertThat(diamond).isEmpty()
        // an action that merely starts a workflow, which runs other actions, is fine
        assertThat(check(""""actions":[{"id":"a","type":"START_WORKFLOW","workflowRef":"w"},{"id":"n","type":"NOTIFY","channel":"IN_APP","templateRef":"tpl-1"}],"workflows":[{"id":"w","steps":[{"id":"s","actionRef":"n"}]}]""")).isEmpty()
    }

    @Test
    fun `data bindings are checked against the sections and the component props`() {
        val base = """"dataSources":[{"id":"d","type":"rest"}],"queries":[{"id":"q","dataSourceRef":"d"},{"id":"q2","dataSourceRef":"d"}],"mappings":[{"id":"m","queryRef":"q","fields":[{"from":"a","to":"t"}]}],"viewModels":[{"id":"v","queryRef":"q"}],"""
        assertThat(check(base + """"dataBindings":[{"id":"b","sectionId":"products-1","prop":"items","queryRef":"q"}]""")).isEmpty()
        assertThat(check(base + """"dataBindings":[{"id":"b","sectionId":"products-1","prop":"items","viewModelRef":"v"}]""")).isEmpty()
        assertThat(check(base + """"dataBindings":[{"id":"b","sectionId":"products-1","prop":"items","queryRef":"q","mappingRef":"m"}]""")).isEmpty()
        assertThat(check(base + """"dataBindings":[{"id":"b","sectionId":"ghost-1","prop":"items","queryRef":"q"}]""").has("dataBindings[0].sectionId", "unknown section 'ghost-1'")).isTrue()
        assertThat(check(base + """"dataBindings":[{"id":"b","sectionId":"products-1","prop":"nope","queryRef":"q"}]""").has("dataBindings[0].prop", "has no prop 'nope'")).isTrue()
        assertThat(check(base + """"dataBindings":[{"id":"b","sectionId":"products-1","prop":"items"}]""").has("dataBindings[0]", "exactly one of viewModelRef or queryRef")).isTrue()
        assertThat(check(base + """"dataBindings":[{"id":"b","sectionId":"products-1","prop":"items","queryRef":"q","viewModelRef":"v"}]""").has("dataBindings[0]", "exactly one")).isTrue()
        assertThat(check(base + """"dataBindings":[{"id":"b","sectionId":"products-1","prop":"items","queryRef":"nope"}]""").paths()).contains("dataBindings[0].queryRef")
        assertThat(check(base + """"dataBindings":[{"id":"b","sectionId":"products-1","prop":"items","viewModelRef":"v","mappingRef":"m"}]""").paths()).contains("dataBindings[0].mappingRef")
        assertThat(check(base + """"dataBindings":[{"id":"b","sectionId":"products-1","prop":"items","queryRef":"q2","mappingRef":"m"}]""").has("dataBindings[0].mappingRef", "reads query 'q', not 'q2'")).isTrue()
        assertThat(check(base + """"dataBindings":[{"id":"b","sectionId":"products-1","prop":"items","queryRef":"q"},{"id":"c","sectionId":"products-1","prop":"items","queryRef":"q2"}]""")
            .has("dataBindings[1]", "already bound")).isTrue()
    }

    // ---- page part is delegated, not duplicated ----

    @Test
    fun `page and section rules still come from PageSchemaValidator`() {
        val bad = json.readTree("""{"schemaVersion":2,"page":"p","sections":[{"id":"x","type":"EvilScript","componentVersion":"1.0.0","props":{}}]}""")
        val result = validator.validateDocument(bad)
        assertThat(result.violations.has("sections[0].type", "unknown component")).isTrue()
        // the same document judged by the V2 rules alone is fine
        assertThat(validator.validateExtensions(bad).violations).isEmpty()
        // and the context can switch the delegation off
        assertThat(validator.validateDocument(bad, ValidationContext(checkPageSchema = false)).violations).isEmpty()
    }

    @Test
    fun `typed validate delegates the same way`() {
        val def = codec.fromJson(resource("valid-v2-sample.json"))
        assertThat(validator.validate(def).violations).isEmpty()
        val broken = def.copy(queries = def.queries + QueryDef("extra", dataSourceRef = "nope"))
        assertThat(validator.validate(broken).violations.has("queries[2].dataSourceRef", "unknown data source")).isTrue()
    }

    // ---- requireValid ----

    @Test
    fun `requireValid throws the same ApiException as PageSchemaValidator`() {
        val e = assertThrows(ApiException::class.java) { validator.requireValidDocument(doc(""""queries":[{"id":"q","dataSourceRef":"nope"}]""")) }
        assertThat(e.status.value()).isEqualTo(422)
        assertThat(e.code).isEqualTo("SCHEMA_INVALID")
        @Suppress("UNCHECKED_CAST")
        val violations = e.details["violations"] as List<Map<String, String>>
        assertThat(violations.map { it["path"] }).contains("queries[0].dataSourceRef")

        val e2 = assertThrows(ApiException::class.java) { validator.requireValidExtensions(doc(""""kind":"NOPE"""")) }
        assertThat(e2.code).isEqualTo("SCHEMA_INVALID")
        validator.requireValidExtensions(doc(""))                                                  // legacy: no-op
        validator.requireValid(codec.fromJson(resource("valid-v2-sample.json")))
    }

    @Test
    fun `non-object documents do not blow up`() {
        assertThat(validator.validateExtensions(json.readTree("[]")).violations).isEmpty()
        assertThat(validator.validateDocument(json.readTree("[]")).violations.paths()).contains("$")
        assertThat(validator.validateDocument(json.readTree("\"x\"")).valid).isFalse()
    }
}
