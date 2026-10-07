package com.systemwebstudio.data.mapping

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.discovery.NormalizedType
import com.systemwebstudio.data.query.Column
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.QueryResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** T10: mapping contract — C2-shaped JSON in, UI-shaped rows out, nothing of the raw schema leaking, errors by field name only. */
class MappingEngineTests {
    private val engine = MappingEngine()
    private fun json(s: String) = DataJson.parse(s.toByteArray())
    private fun result(vararg rows: Map<String, Any?>, truncated: Boolean = false, cols: List<String>? = null) = QueryResult(
        (cols ?: rows.flatMap { it.keys }.distinct()).map { Column(it, NormalizedType.STRING) },
        rows.map { r -> r.mapValues { DataJson.toNode(it.value) } }, truncated
    )
    private fun mapping(s: String) = MappingJson.mapping(json(s))
    private fun vm(s: String) = MappingJson.viewModel(json(s))
    private fun code(block: () -> Unit): String { try { block() } catch (e: ConnectorFailure) { return e.code }; throw AssertionError("expected a ConnectorFailure") }

    private val customerMapping = """{"id":"m-cust","queryRef":"q-cust","fields":[
        {"from":"id","to":"id","nullable":false},
        {"to":"fullName","transform":{"type":"join","fields":["first_name","last_name"],"separator":" "}},
        {"from":"balance_cents","to":"balance","transform":[{"type":"toNumber"},{"type":"formula","expression":"value / 100"}]},
        {"from":"status","to":"status","transform":{"type":"enumMap","map":{"A":"Active","C":"Closed"},"default":"Unknown"}},
        {"from":"created","to":"createdOn","transform":{"type":"date","output":"date"}},
        {"from":"vip","to":"isVip","transform":{"type":"toBoolean"},"default":false}]}"""
    private val customerVm = """{"id":"vm-cust","queryRef":"q-cust","mappingRef":"m-cust","cardinality":"LIST","fields":[
        {"name":"id","type":"NUMBER"},{"name":"fullName","type":"STRING","label":"Name"},{"name":"balance","type":"NUMBER"},
        {"name":"status","type":"STRING"},{"name":"createdOn","type":"DATE"},{"name":"isVip","type":"BOOLEAN"}]}"""

    @Test fun `raw rows become view model rows with only the declared fields`() {
        val raw = result(
            mapOf("id" to 1, "first_name" to "Ada", "last_name" to "Lovelace", "balance_cents" to "12550", "status" to "A", "created" to "2024-03-05T10:00:00Z", "vip" to "yes", "internal_note" to "SECRET-NOTE", "password_hash" to "x"),
            mapOf("id" to 2, "first_name" to "Alan", "last_name" to null, "balance_cents" to 0, "status" to "Z", "created" to "2024-01-01", "vip" to null, "internal_note" to "n")
        )
        val out = engine.apply(raw, mapping(customerMapping), vm(customerVm))
        assertThat(out.viewModelId).isEqualTo("vm-cust")
        assertThat(out.rows).hasSize(2)
        assertThat(out.rows[0].keys.toList()).containsExactly("id", "fullName", "balance", "status", "createdOn", "isVip")   // declared order, no raw columns
        assertThat(out.rows[0].toString()).doesNotContain("SECRET-NOTE").doesNotContain("password_hash").doesNotContain("first_name")
        assertThat(out.rows[0]["fullName"].toString()).isEqualTo("\"Ada Lovelace\"")
        assertThat(out.rows[0]["balance"].toString()).isEqualTo("125.5")
        assertThat(out.rows[0]["status"].toString()).isEqualTo("\"Active\"")
        assertThat(out.rows[0]["createdOn"].toString()).isEqualTo("\"2024-03-05\"")
        assertThat(out.rows[0]["isVip"].toString()).isEqualTo("true")
        assertThat(out.rows[1]["fullName"].toString()).isEqualTo("\"Alan\"")
        assertThat(out.rows[1]["status"].toString()).isEqualTo("\"Unknown\"")
        assertThat(out.rows[1]["isVip"].toString()).isEqualTo("false")                       // default replaces the final null
        assertThat(out.rows[1]["balance"].toString()).isEqualTo("0")
        assertThat(out.fields.map { it.name }).containsExactly("id", "fullName", "balance", "status", "createdOn", "isVip")
        assertThat(out.fields.first { it.name == "id" }.nullable).isFalse()
        assertThat(out.truncated).isFalse()
        assertThat(out.skippedRows).isEqualTo(0)
    }

    @Test fun `the c2 minimal shape from and to alone is a valid mapping`() {
        val m = mapping("""{"id":"m","fields":[{"from":"a","to":"x"},{"from":"b.c","to":"y"}]}""")
        val out = engine.apply(result(mapOf("a" to 1, "b" to mapOf("c" to "deep"))), m)
        assertThat(out.rows[0]["x"].toString()).isEqualTo("1")
        assertThat(out.rows[0]["y"].toString()).isEqualTo("\"deep\"")
        assertThat(out.cardinality).isEqualTo(Cardinality.LIST)
    }

    @Test fun `a mapping and its view model must agree`() {
        val m = mapping("""{"id":"m-cust","fields":[{"from":"a","to":"x"},{"from":"b","to":"stray"}]}""")
        val v = vm("""{"id":"v","mappingRef":"m-cust","fields":[{"name":"x"},{"name":"missing"}]}""")
        val problems = MappingValidator.problems(m, v)
        assertThat(problems.joinToString(";")).contains("'stray' is not declared").contains("'missing' has no mapping")
        assertThat(code { engine.apply(result(mapOf("a" to 1)), m, v) }).isEqualTo(FailureCodes.INVALID_MAPPING)
        val m2 = mapping("""{"id":"other","fields":[{"from":"a","to":"x"}]}""")
        assertThat(MappingValidator.problems(m2, vm("""{"id":"v","mappingRef":"m-cust","fields":[{"name":"x"}]}"""))).isNotEmpty()
        val dup = mapping("""{"id":"d","fields":[{"from":"a","to":"x"},{"from":"b","to":"x"}]}""")
        assertThat(MappingValidator.problems(dup, null).joinToString()).contains("more than once")
    }

    @Test fun `error policy null field turns a bad value into null and says which field without the value`() {
        val secret = "TOPSECRET-VALUE"
        val m = mapping("""{"id":"m","fields":[{"from":"n","to":"n","transform":{"type":"toNumber"}},{"from":"k","to":"k"}]}""")
        val out = engine.apply(result(mapOf("n" to secret, "k" to 1), mapOf("n" to "5", "k" to 2)), m)
        assertThat(out.rows[0]["n"]!!.isNull).isTrue()
        assertThat(out.rows[1]["n"].toString()).isEqualTo("5")
        assertThat(out.warnings).hasSize(1)
        assertThat(out.warnings[0]).contains("n").contains("toNumber")
        assertThat(out.warnings.toString()).doesNotContain(secret)
    }

    @Test fun `a non nullable field that cannot be mapped drops the row under null field policy and counts it`() {
        val m = mapping("""{"id":"m","fields":[{"from":"n","to":"n","transform":{"type":"toNumber"},"nullable":false}]}""")
        val out = engine.apply(result(mapOf("n" to "x"), mapOf("n" to "1"), mapOf("n" to null)), m)
        assertThat(out.rows).hasSize(1)
        assertThat(out.skippedRows).isEqualTo(2)
    }

    @Test fun `error policy skip row and fail`() {
        val body = """"fields":[{"from":"n","to":"n","transform":{"type":"toNumber"}}]"""
        val skip = engine.apply(result(mapOf("n" to "x"), mapOf("n" to "1")), mapping("""{"id":"m","errorPolicy":"SKIP_ROW",$body}"""))
        assertThat(skip.rows).hasSize(1); assertThat(skip.skippedRows).isEqualTo(1)
        val secret = "TOPSECRET-VALUE"
        try {
            engine.apply(result(mapOf("n" to secret)), mapping("""{"id":"m","errorPolicy":"FAIL",$body}"""))
            throw AssertionError("expected failure")
        } catch (e: ConnectorFailure) {
            assertThat(e.code).isEqualTo(FailureCodes.MAPPING_FAILED)
            assertThat(e.safeMessage).contains("'n'").doesNotContain(secret)
        }
    }

    @Test fun `validation is a closed list of checks`() {
        val m = mapping("""{"id":"m","errorPolicy":"SKIP_ROW","fields":[
            {"from":"mail","to":"mail","validation":{"format":"EMAIL","maxLength":30}},
            {"from":"age","to":"age","validation":{"min":0,"max":150,"format":"INTEGER"}},
            {"from":"tier","to":"tier","validation":{"oneOf":["gold","silver"]}}]}""")
        val ok = mapOf("mail" to "a@b.co", "age" to 30, "tier" to "gold")
        val out = engine.apply(result(ok, ok + ("mail" to "not-mail"), ok + ("age" to 200), ok + ("age" to 1.5), ok + ("tier" to "bronze"), ok + ("mail" to "a".repeat(40) + "@b.co")), m)
        assertThat(out.rows).hasSize(1)
        assertThat(out.skippedRows).isEqualTo(5)
    }

    @Test fun `declared types coerce or fail`() {
        val m = mapping("""{"id":"m","errorPolicy":"SKIP_ROW","fields":[{"from":"a","to":"a"},{"from":"b","to":"b"},{"from":"c","to":"c"},{"from":"d","to":"d"}]}""")
        val v = vm("""{"id":"v","fields":[{"name":"a","type":"NUMBER"},{"name":"b","type":"BOOLEAN"},{"name":"c","type":"DATETIME"},{"name":"d","type":"OBJECT"}]}""")
        val out = engine.apply(result(
            mapOf("a" to "3.50", "b" to "false", "c" to "2024-03-05T10:00:00+07:00", "d" to mapOf("k" to 1)),
            mapOf("a" to "abc", "b" to true, "c" to "2024-03-05T10:00:00Z", "d" to mapOf("k" to 1)),
            mapOf("a" to 1, "b" to true, "c" to "2024-03-05T10:00:00Z", "d" to "not an object")
        ), m, v)
        assertThat(out.rows).hasSize(1)
        assertThat(out.rows[0]["a"].toString()).isEqualTo("3.5")
        assertThat(out.rows[0]["b"].toString()).isEqualTo("false")
        assertThat(out.rows[0]["c"].toString()).isEqualTo("\"2024-03-05T03:00:00Z\"")
        assertThat(out.rows[0]["d"].toString()).isEqualTo("""{"k":1}""")
    }

    @Test fun `a container cannot be fed to a transform`() {
        val m = mapping("""{"id":"m","errorPolicy":"FAIL","fields":[{"from":"o","to":"o","transform":{"type":"toString"}}]}""")
        assertThat(code { engine.apply(result(mapOf("o" to mapOf("k" to 1))), m) }).isEqualTo(FailureCodes.MAPPING_FAILED)
    }

    @Test fun `single cardinality keeps the first row and says so`() {
        val m = mapping("""{"id":"m","fields":[{"from":"a","to":"a"}]}""")
        val v = vm("""{"id":"v","cardinality":"SINGLE","fields":[{"name":"a","type":"NUMBER"}]}""")
        val out = engine.apply(result(mapOf("a" to 1), mapOf("a" to 2)), m, v)
        assertThat(out.rows).hasSize(1)
        assertThat(out.rows[0]["a"].toString()).isEqualTo("1")
        assertThat(out.warnings.joinToString()).contains("more than one row")
        assertThat(engine.apply(result(cols = listOf("a")), m, v).rows).isEmpty()
    }

    @Test fun `truncation passes through and a missing source column is reported`() {
        val m = mapping("""{"id":"m","fields":[{"from":"a","to":"a"},{"from":"gone","to":"g"}]}""")
        val out = engine.apply(result(mapOf("a" to 1), truncated = true), m)
        assertThat(out.truncated).isTrue()
        assertThat(out.warnings.joinToString()).contains("g: source column is not in the result")
        assertThat(out.rows[0]["g"]!!.isNull).isTrue()
    }

    @Test fun `column names with dots and spaces resolve as exact names first`() {
        val m = mapping("""{"id":"m","fields":[{"from":"a.b","to":"x"},{"from":"first name","to":"y"}]}""")
        val out = engine.apply(result(mapOf("a.b" to 1, "first name" to "Ada")), m)
        assertThat(out.rows[0]["x"].toString()).isEqualTo("1")
        assertThat(out.rows[0]["y"].toString()).isEqualTo("\"Ada\"")
    }

    @Test fun `output is bounded`() {
        val rows = (1..MappingEngine.MAX_ROWS + 5).map { mapOf<String, Any?>("a" to it) }
        val out = engine.apply(result(*rows.toTypedArray()), mapping("""{"id":"m","fields":[{"from":"a","to":"a"}]}"""))
        assertThat(out.rows).hasSize(MappingEngine.MAX_ROWS)
        assertThat(out.warnings.joinToString()).contains("cut")
    }

    // ------------------------------------------------------------------ JSON reader

    @Test fun `unknown keys and malformed definitions are rejected not ignored`() {
        listOf(
            """{"id":"m","fields":[{"from":"a","to":"x","script":"1"}]}""",
            """{"id":"m","fields":[{"from":"a","to":"x","transform":{"type":"javascript"}}]}""",
            """{"id":"m","fields":[{"from":"a","to":"x","transform":{"type":"formula","expression":"exec('x')"}}]}""",
            """{"id":"m","fields":[{"from":"a","to":"x","validation":{"pattern":".*"}}]}""",
            """{"id":"m","fields":[{"from":"a","to":"x","validation":{"format":"REGEX"}}]}""",
            """{"id":"m","fields":[{"from":"a;drop","to":"x"}]}""",
            """{"id":"m","fields":[{"from":"a","to":"1bad"}]}""",
            """{"id":"m","fields":[{"to":"x"}]}""",
            """{"id":"m","fields":[]}""",
            """{"id":"../m","fields":[{"from":"a","to":"x"}]}""",
            """{"id":"m","errorPolicy":"IGNORE","fields":[{"from":"a","to":"x"}]}""",
            """{"id":"m","fields":[{"from":"a","to":"x","default":{"o":1}}]}""",
            """{"id":"m","extra":1,"fields":[{"from":"a","to":"x"}]}""",
            """[]"""
        ).forEach { assertThat(code { MappingJson.mapping(json(it)) }).isEqualTo(FailureCodes.INVALID_MAPPING) }
        assertThat(code { MappingJson.viewModel(json("""{"id":"v","fields":[{"name":"a","type":"MONEY"}]}""")) }).isEqualTo(FailureCodes.INVALID_MAPPING)
        assertThat(code { MappingJson.viewModel(json("""{"id":"v","fields":[{"name":"a"},{"name":"a"}]}""")) }).isEqualTo(FailureCodes.INVALID_MAPPING)
        assertThat(code { MappingJson.parseMapping(ByteArray(300_000)) }).isEqualTo(FailureCodes.INVALID_MAPPING)
        assertThat(code { MappingJson.parseMapping("{not json".toByteArray()) }).isEqualTo(FailureCodes.INVALID_MAPPING)
    }

    @Test fun `enum names are accepted in any case and the parsed model round trips its meaning`() {
        val v = vm("""{"id":"v","cardinality":"single","fields":[{"name":"a","type":"datetime","label":"A"}]}""")
        assertThat(v.cardinality).isEqualTo(Cardinality.SINGLE)
        assertThat(v.fields[0].type).isEqualTo(FieldType.DATETIME)
        val m = mapping(customerMapping)
        assertThat(m.fields).hasSize(6)
        assertThat(m.fields[2].transforms.map { it.name }).containsExactly("toNumber", "formula")
        assertThat(m.errorPolicy).isEqualTo(MappingErrorPolicy.NULL_FIELD)
    }

    @Test fun `a formula cannot be used to reach other rows or run unbounded work`() {
        val m = mapping("""{"id":"m","errorPolicy":"FAIL","fields":[{"to":"x","from":"a","transform":{"type":"formula","expression":"replace(value, 'a', value)"}}]}""")
        val big = "a".repeat(9_000)
        assertThat(code { engine.apply(result(mapOf("a" to big)), m) }).isEqualTo(FailureCodes.MAPPING_FAILED)
    }
}
