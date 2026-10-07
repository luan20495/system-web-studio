package com.systemwebstudio.data.mapping

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.query.DataJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The mapping wire contract of `docs/contracts/v2/app-definition.md` §3: the canonical key is `fields[].transforms[]`; the legacy `transform` is
 * read and normalised; both together are ambiguous; the writer only ever produces `transforms[]`. The two documents below are the `mappings[0]`
 * of C2's conformance fixtures `valid-mapping-transforms.json` / `valid-mapping-legacy-transform.json` (fix/c2-v2), so C2's reader and this one
 * are held to the same input.
 */
class MappingContractTests {
    private val canonical = """{"id": "orders-map", "queryRef": "orders-list", "fields": [
      {"from": "order_no", "to": "orderNo", "transforms": [{"type": "trim"}]},
      {"from": "total", "to": "total", "transforms": [{"type": "toNumber"}]},
      {"from": "code", "to": "code", "transforms": [{"type": "trim"}, {"type": "upper"}]},
      {"from": "status", "to": "status", "transforms": [{"type": "enumMap", "map": {"O": "open", "C": "closed"}, "default": "unknown"}]},
      {"to": "fullName", "transforms": [{"type": "join", "fields": ["first", "last"], "separator": " "}]},
      {"from": "plain", "to": "plain"}
    ]}"""
    private val legacy = """{"id": "orders-map", "queryRef": "orders-list", "fields": [
      {"from": "order_no", "to": "orderNo", "transform": {"type": "trim"}},
      {"from": "total", "to": "total", "transform": [{"type": "toNumber"}]},
      {"from": "code", "to": "code", "transform": [{"type": "trim"}, {"type": "upper"}]},
      {"from": "status", "to": "status", "transform": {"type": "enumMap", "map": {"O": "open", "C": "closed"}, "default": "unknown"}},
      {"to": "fullName", "transform": {"type": "join", "fields": ["first", "last"], "separator": " "}},
      {"from": "plain", "to": "plain"}
    ]}"""

    private fun parse(json: String) = MappingJson.mapping(DataJson.parse(json.toByteArray()))
    private fun field(f: String) = """{"id":"m","fields":[{"from":"a","to":"b",$f}]}"""
    private fun reject(json: String): String {
        try { parse(json) } catch (e: ConnectorFailure) { assertThat(e.code).isEqualTo(FailureCodes.INVALID_MAPPING); return e.safeMessage }
        throw AssertionError("expected INVALID_MAPPING")
    }

    @Test fun `the canonical transforms array is read in order with nothing lost`() {
        val fields = parse(canonical).fields
        assertThat(fields.map { it.transforms.size }).containsExactly(1, 1, 2, 1, 1, 0)
        assertThat(fields[2].transforms.map { it.name }).containsExactly("trim", "upper")
        val em = fields[3].transforms.single() as Transform.EnumMap
        assertThat(em.map["C"].toString()).isEqualTo("\"closed\""); assertThat(em.default.toString()).isEqualTo("\"unknown\"")
        assertThat((fields[4].transforms.single() as Transform.Join).fields).containsExactly("first", "last")
    }

    @Test fun `the legacy transform key is read and normalises to exactly the canonical definition`() {
        assertThat(parse(legacy).fields.map { it.transforms.size }).containsExactly(1, 1, 2, 1, 1, 0)   // object and array spellings both accepted
        assertThat(parse(legacy)).isEqualTo(parse(canonical))
    }

    @Test fun `a field with both keys is ambiguous and rejected`() {
        val msg = reject(field(""""transform":{"type":"trim"},"transforms":[{"type":"trim"}]"""))
        assertThat(msg).contains("ambiguous").contains("$.fields[0]")
        assertThat(reject(field(""""transform":[],"transforms":[]"""))).contains("ambiguous")          // empty values do not make it less ambiguous
    }

    @Test fun `the writer emits only transforms and a rewritten legacy document is the canonical one`() {
        val written = MappingJson.toNode(parse(legacy))
        val fields = written.get("fields")
        for (i in 0 until fields.size()) assertThat(fields.get(i).has("transform")).isFalse()
        assertThat(written.toString()).doesNotContain("\"transform\":")
        assertThat(fields.get(2).get("transforms").size()).isEqualTo(2)
        assertThat(fields.get(5).has("transforms")).isFalse()                                          // no transform: nothing written
        assertThat(MappingJson.toNode(parse(legacy))).isEqualTo(MappingJson.toNode(parse(canonical)))
    }

    @Test fun `what the writer writes the reader reads back to the same definition`() {
        for (src in listOf(canonical, legacy)) { val d = parse(src); assertThat(MappingJson.mapping(MappingJson.toNode(d))).isEqualTo(d) }
    }

    @Test fun `every transform kind survives write then read`() {
        val all = """[{"type":"toString"},{"type":"toNumber"},{"type":"trim"},{"type":"lower"},{"type":"upper"},
            {"type":"toBoolean","trueValues":["si","yes"],"falseValues":["no"]},{"type":"date","input":"epochSeconds","output":"date","zone":"Asia/Ho_Chi_Minh"},
            {"type":"enumMap","map":{"A":1,"B":null},"default":null,"strict":true,"ignoreCase":true},{"type":"join","fields":["a","b.c"],"separator":"-","skipNull":false},
            {"type":"split","separator":",","index":2},{"type":"formula","expression":"a + b * 2"}]"""
        for (chunk in listOf(0..7, 8..10)) {                       // the limit is 8 per field, so the eleven kinds go through two fields
            val items = DataJson.elements(DataJson.parse(all.toByteArray())).slice(chunk).joinToString(",") { it.toString() }
            val d = parse(field(""""transforms":[$items]"""))
            assertThat(MappingJson.mapping(MappingJson.toNode(d))).isEqualTo(d)
            assertThat(d.fields.single().transforms.size).isEqualTo(chunk.count())
        }
    }

    @Test fun `transforms is bounded and shaped like C2 requires`() {
        assertThat(parse(field(""""transforms":[{"type":"trim"}]""")).fields.single().transforms.size).isEqualTo(1)
        assertThat(reject(field(""""transforms":{"type":"trim"}"""))).contains("$.fields[0].transforms").contains("array")
        assertThat(reject(field(""""transforms":["trim"]"""))).contains("$.fields[0].transforms[0]")
        assertThat(reject(field(""""transforms":[{"separator":" "}]"""))).contains("$.fields[0].transforms[0]").contains("type")
        assertThat(reject(field(""""transforms":${(1..9).joinToString(",", "[", "]") { """{"type":"trim"}""" }}"""))).contains("at most 8")
        assertThat(reject(field(""""transform":${(1..9).joinToString(",", "[", "]") { """{"type":"trim"}""" }}"""))).contains("at most 8")
        assertThat(parse(field(""""transform":{"type":"trim"}""")).fields.single().transforms.size).isEqualTo(1)   // legacy spelling still accepted
        assertThat(reject(field(""""transform":"trim""""))).contains("$.fields[0].transform")
        assertThat(reject(field(""""transforms":null"""))).contains("null"); assertThat(reject(field(""""transform":null"""))).contains("null")
    }

    @Test fun `the closed set of transform types and their members still apply under both keys`() {
        for (key in listOf("transforms", "transform")) {
            assertThat(reject(field(""""$key":[{"type":"eval","code":"1"}]"""))).contains("unknown transform")
            assertThat(reject(field(""""$key":[{"type":"join","fields":["a"],"url":"https://x.example"}]"""))).contains("unknown key")
            assertThat(reject(field(""""$key":[{"type":"formula","expression":"a.constructor('x')()"}]"""))).isNotEmpty()
        }
    }

    @Test fun `a view model is written in the canonical shape too`() {
        val vm = MappingJson.viewModel(DataJson.parse("""{"id":"vm","mappingRef":"orders-map","cardinality":"LIST","fields":[{"name":"orderNo","type":"STRING","label":"Order"},{"name":"total","type":"NUMBER"}]}""".toByteArray()))
        assertThat(MappingJson.viewModel(MappingJson.toNode(vm))).isEqualTo(vm)
    }
}
