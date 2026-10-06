package com.systemwebstudio.data.query

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** The strict translation of a client document into a definition (Management API contract §3.5), and the stable hash an audit row records instead of it. */
class DefinitionDocumentsTests {
    private val t = UUID.randomUUID(); private val d = UUID.randomUUID()
    private fun doc(s: String) = DataJson.parse(s.toByteArray())
    private fun invalid(block: () -> Unit): String { try { block() } catch (e: ConnectorFailure) { return e.code }; throw AssertionError("expected a ConnectorFailure") }

    @Test fun `a SQL document becomes a SQL definition with the identity given by the server`() {
        val def = DefinitionDocuments.parseQuery("SQL", "q.one", t, d, 4, doc("""{"sql":"SELECT 1 WHERE a = :a","params":[{"name":"a","type":"INTEGER","required":false,"default":5}],"maxRows":20,"cacheTtlSeconds":30}"""))
        def as SqlQueryDefinition
        assertThat(def.id).isEqualTo("q.one"); assertThat(def.tenantId).isEqualTo(t); assertThat(def.dataSourceId).isEqualTo(d); assertThat(def.version).isEqualTo(4L)
        assertThat(def.maxRows).isEqualTo(20); assertThat(def.cacheTtlSeconds).isEqualTo(30)
        assertThat(def.params.single().name).isEqualTo("a"); assertThat(def.params.single().type).isEqualTo(ParamType.INTEGER); assertThat(def.params.single().required).isFalse()
        assertThat(def.params.single().default!!.asInt()).isEqualTo(5)
    }

    @Test fun `a REST document becomes a REST definition`() {
        val def = DefinitionDocuments.parseQuery("REST", "r.one", t, d, 1, doc("""{"pathTemplate":"/c/{id}","params":[{"name":"id","type":"STRING"}],"queryParams":{"id":"q"},"limitParam":"limit","discoverable":true}"""))
        def as RestQueryDefinition
        assertThat(def.pathTemplate).isEqualTo("/c/{id}"); assertThat(def.queryParams).containsEntry("id", "q"); assertThat(def.limitParam).isEqualTo("limit"); assertThat(def.discoverable).isTrue()
    }

    @Test fun `a mutation document becomes a mutation definition`() {
        val def = DefinitionDocuments.parseMutation(MutationKind.UPDATE, "m.one", t, d, 2, doc("""{"target":"public.orders key=id","params":[{"name":"id","type":"INTEGER"},{"name":"total","type":"NUMBER"}],"invalidates":["orders.list"],"entity":"orders"}"""))
        assertThat(def.kind).isEqualTo(MutationKind.UPDATE); assertThat(def.target).isEqualTo("public.orders key=id"); assertThat(def.invalidates).containsExactly("orders.list"); assertThat(def.entity).isEqualTo("orders"); assertThat(def.version).isEqualTo(2L)
    }

    @Test fun `anything unexpected is INVALID_QUERY and no message carries a submitted value`() {
        val marker = "SUBMITTED-VALUE-4411"
        val bad = listOf(
            "[]", "\"$marker\"", """{"sql":"$marker","extra":1}""", """{"sql":1}""", """{"params":[]}""", """{"sql":"x","params":{"a":"$marker"}}""", """{"sql":"x","params":[{"name":"$marker","type":"STRING"}]}""",
            """{"sql":"x","params":[{"name":"a","type":"$marker"}]}""", """{"sql":"x","params":[{"name":"a","type":"STRING","default":[1]}]}""", """{"sql":"x","maxRows":1.5}""", """{"sql":"x","maxRows":99999999}""")
        for (b in bad) {
            val e = try { DefinitionDocuments.parseQuery("SQL", "q", t, d, 1, doc(b)); null } catch (e: ConnectorFailure) { e }
            assertThat(e).describedAs("accepted: $b").isNotNull()
            assertThat(e!!.code).describedAs(b).isEqualTo(FailureCodes.INVALID_QUERY); assertThat(e.message + e.safeMessage).describedAs(b).doesNotContain(marker)
        }
        assertThat(invalid { DefinitionDocuments.parseQuery("GRAPHQL", "q", t, d, 1, doc("{}")) }).isEqualTo(FailureCodes.INVALID_QUERY)
        assertThat(invalid { DefinitionDocuments.parseQuery("REST", "q", t, d, 1, doc("""{"pathTemplate":"//evil/x"}""")) }).isEqualTo(FailureCodes.INVALID_QUERY)
        assertThat(invalid { DefinitionDocuments.parseMutation(MutationKind.CREATE, "m", t, d, 1, doc("""{"target":"","params":[]}""")) }).isEqualTo(FailureCodes.INVALID_QUERY)
        assertThat(invalid { DefinitionDocuments.parseMutation(MutationKind.CREATE, "m", t, d, 1, doc("""{"target":"t","sql":"DELETE FROM x"}""")) }).isEqualTo(FailureCodes.INVALID_QUERY)
        assertThat(invalid { DefinitionDocuments.parseMutation(MutationKind.CREATE, "m", t, d, 1, doc("""{"target":"t","invalidates":["bad id"]}""")) }).isEqualTo(FailureCodes.INVALID_QUERY)
    }

    @Test fun `the projection round-trips and carries no tenant, data source or credential`() {
        val src = """{"sql":"SELECT 1 WHERE a = :a","params":[{"name":"a","type":"STRING","required":true}],"maxRows":10,"cacheTtlSeconds":0}"""
        val def = DefinitionDocuments.parseQuery("SQL", "q", t, d, 1, doc(src))
        val projected = DataJson.toNode(DefinitionDocuments.queryDocument(def))
        val again = DefinitionDocuments.parseQuery("SQL", "q", t, d, 1, projected)
        assertThat(again).isEqualTo(def)
        assertThat(projected.toString()).doesNotContain(t.toString()).doesNotContain(d.toString())
        val m = DefinitionDocuments.parseMutation(MutationKind.CREATE, "m", t, d, 1, doc("""{"target":"public.orders","params":[{"name":"customer","type":"STRING"}]}"""))
        assertThat(DefinitionDocuments.parseMutation(MutationKind.CREATE, "m", t, d, 1, DataJson.toNode(DefinitionDocuments.mutationDocument(m)))).isEqualTo(m)
    }

    @Test fun `the audit hash is stable for the same document and changes with any change`() {
        val a = DefinitionDocuments.parseQuery("SQL", "q", t, d, 1, doc("""{"sql":"SELECT 1"}"""))
        val same = DefinitionDocuments.parseQuery("SQL", "q", UUID.randomUUID(), UUID.randomUUID(), 9, doc("""{"sql":"SELECT 1"}"""))
        val other = DefinitionDocuments.parseQuery("SQL", "q", t, d, 1, doc("""{"sql":"SELECT 2"}"""))
        assertThat(DefinitionDocuments.fingerprint(a)).matches("[0-9a-f]{64}").isEqualTo(DefinitionDocuments.fingerprint(same)).isNotEqualTo(DefinitionDocuments.fingerprint(other))
        assertThat(DefinitionDocuments.fingerprint(a)).doesNotContain("SELECT")
    }
}
