package com.systemwebstudio.app.definition

import com.systemwebstudio.app.definition.AppDefinitionTestSupport.check
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** D-C0-36 · `queries[].public`: the author's declaration that a READ query may be offered to anonymous visitors of the published site. Default false; never on a WRITE query. */
class QueryPublicFieldTests {
    private val base = """"schemaVersion":2,"kind":"PAGE_SCHEMA","dataSources":[{"id":"erp-db","name":"E","type":"postgres"}],"""
    private fun q(extra: String) = """$base"queries":[{"id":"orders-list","dataSourceRef":"erp-db","operationKey":"orders.list"$extra}]"""

    @Test fun `a public READ query, a private one and the default are valid`() {
        assertThat(check(q(""","public":true"""))).isEmpty()
        assertThat(check(q(""","public":false"""))).isEmpty()
        assertThat(check(q(""))).isEmpty()
    }

    @Test fun `public must be a boolean and a WRITE query can never be public`() {
        assertThat(check(q(""","public":"true"""")).map { it.path }).contains("queries[0].public")
        assertThat(check(q(""","public":1""")).map { it.path }).contains("queries[0].public")
        assertThat(check(q(""","mode":"WRITE","public":true""")).map { it.path }).contains("queries[0].public")
        assertThat(check(q(""","mode":"WRITE","public":false"""))).isEmpty()
    }

    @Test fun `public survives the codec round trip, and false is not written`() {
        val codec = AppDefinitionCodec(tools.jackson.databind.json.JsonMapper.builder().build())
        val doc = AppDefinitionTestSupport.doc(q(""","public":true"""))
        val def = codec.fromJson(doc)
        assertThat(def.queries.single().public).isTrue()
        assertThat(codec.toJson(def).get("queries").get(0).get("public").asBoolean()).isTrue()
        val plain = codec.fromJson(AppDefinitionTestSupport.doc(q("")))
        assertThat(plain.queries.single().public).isFalse()
        assertThat(codec.toJson(plain).get("queries").get(0).has("public")).isFalse()
    }
}
