package com.systemwebstudio.organization

import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.Executors

/**
 * C1 CONTRACT TESTS (not PostgreSQL organization tests): the organization unit type and unit API, run against the in-memory test double of the C3 seams, with real PostgreSQL
 * for everything C1 owns (accounts, tenants, authorization, audit). Letters follow the H-C1-17 brief; this proves the C1 rules, not C3's SQL.
 */
@Import(InMemoryOrganizationConfig::class)
class OrganizationApiContractTests : OrganizationTestBase() {
    // ------------------------------------------------------------------------------------------------ A / B / C
    @Test
    fun `A a root unit has its type, defaults and a version, and the ETag is the version`() {
        val c = company(); val t = type(c, "company")
        val u = unit(c, t, "ROOT", name = "HBL Group")
        assertThat(u.get("parentId").isNull).isTrue(); assertThat(u.get("typeId").asString()).isEqualTo(id(t).toString()); assertThat(u.get("active").asBoolean()).isTrue(); assertThat(ver(u)).isZero()
        assertThat(u.get("tenantId").asString()).isEqualTo(c.id.toString()); assertThat(u.get("sortOrder").asInt()).isZero(); assertThat(u.get("metadata").isObject).isTrue()
        val r = c.admin.get("${units(c)}/${id(u)}")
        assertThat(r.response.getHeader("ETag")).isEqualTo("\"0\"")
        val d = c.admin.body(r); assertThat(d.get("path").toList().map { it.get("code").asString() }).containsExactly("ROOT"); assertThat(d.get("activeChildCount").asInt()).isZero(); assertThat(d.get("activeMemberCount").asInt()).isZero()
    }

    @Test
    fun `B twelve nested levels with a different tenant-defined type per level, one tree, the full path, nothing hard-coded`() {
        val c = company()
        val names = listOf("khoi", "chi-nhanh", "phong", "bo-phan", "team", "nhom", "to", "ban", "doi", "cum", "mang", "tuyen")
        val ts = names.map { type(c, it, it) }
        var parent: JsonNode? = null; val made = mutableListOf<JsonNode>()
        names.forEachIndexed { i, _ -> val u = unit(c, ts[i], "L$i", parent, name = "Level $i"); parent = u; made.add(u) }
        var node = c.admin.body(c.admin.get(units(c))).toList().single(); var depth = 1
        while (node.get("children").size() > 0) { node = node.get("children").toList().single(); depth++ }
        assertThat(depth).isEqualTo(12)
        val leaf = c.admin.body(c.admin.get("${units(c)}/${id(made.last())}"))
        assertThat(leaf.get("path").toList().map { it.get("code").asString() }).containsExactly(*(0..11).map { "L$it" }.toTypedArray())
        assertThat(flat(c)).hasSize(12)
    }

    @Test
    fun `C types are tenant data - rules are optional and absence blocks nothing, parent child root and depth rules apply when present`() {
        val c = company()
        val free = type(c, "free", "Free")                                                               // no rules at all
        val a = unit(c, free, "A"); val b = unit(c, free, "B", a); assertThat(b.get("parentId").asString()).isEqualTo(id(a).toString())
        assertThat(c.admin.body(c.admin.get("${types(c)}/${id(free)}")).get("rules").let { it.get("allowedParentTypeIds") == null || it.get("allowedParentTypeIds").isNull }).isTrue()
        // parent rule + no root
        val khoi = type(c, "khoi", "Khối"); val phong = type(c, "phong", "Phòng", """{"allowedParentTypeIds":["${id(khoi)}"]}""")
        val rootPhong = c.admin.post(units(c), """{"typeId":"${id(phong)}","code":"P0","name":"p"}""")
        assertThat(rootPhong.response.status).describedAs("a listed parent type and no allowRoot: not a root").isEqualTo(409); assertThat(code(rootPhong, c.admin)).isEqualTo("ORG_TYPE_RULE_VIOLATION")
        assertThat(c.admin.body(rootPhong).get("details").get("reason").asString()).isEqualTo("ROOT_NOT_ALLOWED")
        val k = unit(c, khoi, "K1"); val team = type(c, "team", "Team")
        val wrong = c.admin.post(units(c), """{"typeId":"${id(phong)}","code":"P1","name":"p","parentId":"${id(unit(c, team, "T1"))}"}""")
        assertThat(wrong.response.status).isEqualTo(409); assertThat(code(wrong, c.admin)).isEqualTo("ORG_TYPE_RULE_VIOLATION"); assertThat(c.admin.body(wrong).get("details").get("reason").asString()).isEqualTo("PARENT_TYPE_NOT_ALLOWED")
        assertThat(unit(c, phong, "P2", k).get("parentId").asString()).isEqualTo(id(k).toString())
        // allowRoot makes both placements legal
        val both = type(c, "both", "Both", """{"allowedParentTypeIds":["${id(khoi)}"],"allowRoot":true}""")
        unit(c, both, "R1"); unit(c, both, "R2", k)
        // child rule of the parent: a leaf type, and an explicit child whitelist
        val leaf = type(c, "leaf", "Leaf", """{"allowedChildTypeIds":[]}"""); val lu = unit(c, leaf, "LF")
        assertThat(code(c.admin.post(units(c), """{"typeId":"${id(free)}","code":"UNDER","name":"x","parentId":"${id(lu)}"}"""), c.admin)).isEqualTo("ORG_TYPE_RULE_VIOLATION")
        // maxDepth: the deepest level a unit of the type may sit at (root = 1)
        val shallow = type(c, "shallow", "Shallow", """{"maxDepth":2}"""); val s1 = unit(c, shallow, "S1"); val s2 = unit(c, shallow, "S2", s1)
        val deep = c.admin.post(units(c), """{"typeId":"${id(shallow)}","code":"S3","name":"x","parentId":"${id(s2)}"}""")
        assertThat(deep.response.status).isEqualTo(409); assertThat(c.admin.body(deep).get("details").get("reason").asString()).isEqualTo("MAX_DEPTH")
        // type management: code unique per tenant (case-insensitive), versioned update, disable / enable
        val dup = c.admin.post(types(c), """{"code":"KHOI","name":"again"}"""); assertThat(dup.response.status).isEqualTo(409); assertThat(code(dup, c.admin)).isEqualTo("ORG_UNIT_TYPE_CODE_TAKEN")
        assertThat(code(c.admin.post(types(c), """{"code":"Bad Code!","name":"x"}"""), c.admin)).isEqualTo("INVALID_CODE")
        val upd = c.admin.patch("${types(c)}/${id(khoi)}", """{"name":"Khối kinh doanh","expectedVersion":${ver(khoi)}}"""); assertThat(upd.response.status).isEqualTo(200); assertThat(c.admin.body(upd).get("code").asString()).isEqualTo("khoi")
        val stale = c.admin.patch("${types(c)}/${id(khoi)}", """{"name":"stale","expectedVersion":${ver(khoi)}}""")
        assertThat(stale.response.status).isEqualTo(409); assertThat(code(stale, c.admin)).isEqualTo("VERSION_CONFLICT"); assertThat(c.admin.body(stale).get("details").get("currentVersion").asLong()).isEqualTo(1)
        val off = c.admin.post("${types(c)}/${id(free)}/disable", """{"expectedVersion":${ver(free)}}"""); assertThat(off.response.status).isEqualTo(200); assertThat(c.admin.body(off).get("active").asBoolean()).isFalse()
        val newOfOff = c.admin.post(units(c), """{"typeId":"${id(free)}","code":"NEWFREE","name":"x"}"""); assertThat(newOfOff.response.status).isEqualTo(409); assertThat(code(newOfOff, c.admin)).isEqualTo("ORG_UNIT_TYPE_DISABLED")
        assertThat(unitNow(c, a).get("typeId").asString()).describedAs("existing units are untouched").isEqualTo(id(free).toString())
        assertThat(c.admin.post("${types(c)}/${id(free)}/enable", """{"expectedVersion":${ver(c.admin.body(off))}}""").response.status).isEqualTo(200)
        assertThat(c.admin.body(c.admin.get("${types(c)}?includeInactive=false")).toList().map { it.get("code").asString() }).contains("free").doesNotContain("nonexistent")
        // a rule that names a type of another tenant is the same 404 as an unknown type
        val other = company(); val foreign = type(other, "foreign", "Foreign")
        val bad = c.admin.post(types(c), """{"code":"badrule","name":"b","rules":{"allowedParentTypeIds":["${id(foreign)}"]}}"""); assertThat(bad.response.status).isEqualTo(404); assertThat(code(bad, c.admin)).isEqualTo("ORG_UNIT_TYPE_NOT_FOUND")
    }

    @Test
    fun `tree and flat lists have ONE deterministic order - sortOrder, then name, then id - and archived units are hidden by default`() {
        val c = company(); val t = type(c, "unit")
        val z = unit(c, t, "Z", sort = 1, name = "Zeta"); val b2 = unit(c, t, "B2", sort = 0, name = "beta"); val a1 = unit(c, t, "A1", sort = 0, name = "Alpha"); val b1 = unit(c, t, "B1", sort = 0, name = "Beta")
        val kid2 = unit(c, t, "K2", a1, sort = 5, name = "k"); val kid1 = unit(c, t, "K1", a1, sort = 1, name = "k")
        val roots = c.admin.body(c.admin.get(units(c))).toList()
        assertThat(roots.map { it.get("unit").get("code").asString() }.take(1)).containsExactly("A1")
        assertThat(roots.map { it.get("unit").get("sortOrder").asInt() }).isSorted()
        assertThat(roots.single { it.get("unit").get("code").asString() == "A1" }.get("children").toList().map { it.get("unit").get("code").asString() }).containsExactly("K1", "K2")
        assertThat(flat(c).map { it.get("code").asString() }).describedAs("flat uses the same order").containsSubsequence("A1", "K1", "K2").contains("Z")
        assertThat(roots.map { it.get("unit").get("name").asString().lowercase() }.filter { it in listOf("alpha", "beta") }).containsExactly("alpha", "beta", "beta")
        // archive hides it; includeArchived brings it back
        assertThat(c.admin.post("${units(c)}/${id(z)}/archive", """{"expectedVersion":${ver(z)}}""").response.status).isEqualTo(200)
        assertThat(flat(c).map { it.get("code").asString() }).doesNotContain("Z"); assertThat(flat(c, "&includeArchived=true").map { it.get("code").asString() }).contains("Z")
        assertThat(c.admin.get("${units(c)}?format=xml").response.status).isEqualTo(400)
        assertThat(b2.get("code").asString() + b1.get("code").asString() + kid2.get("code").asString() + kid1.get("code").asString()).isNotEmpty()
    }

    // ------------------------------------------------------------------------------------------------ D / E / F
    @Test
    fun `D moving a unit moves its whole subtree atomically, to another parent or to the root, and bumps only the moved unit`() {
        val c = company(); val t = type(c, "unit")
        val a = unit(c, t, "A"); val b = unit(c, t, "B"); val a1 = unit(c, t, "A1", a); val a11 = unit(c, t, "A11", a1); val a111 = unit(c, t, "A111", a11)
        val r = move(c, a1, b); assertThat(r.response.status).isEqualTo(200)
        val moved = c.admin.body(r); assertThat(moved.get("parentId").asString()).isEqualTo(id(b).toString()); assertThat(ver(moved)).isEqualTo(ver(a1) + 1)
        assertThat(c.admin.body(c.admin.get("${units(c)}/${id(a111)}")).get("path").toList().map { it.get("code").asString() }).containsExactly("B", "A1", "A11", "A111")
        assertThat(unitNow(c, a11).get("parentId").asString()).isEqualTo(id(a1).toString()); assertThat(ver(unitNow(c, a11))).describedAs("descendants are untouched").isEqualTo(ver(a11))
        assertThat(c.admin.body(move(c, moved, null)).get("parentId").isNull).describedAs("explicit null = to the root").isTrue()
        val stale = move(c, moved, a); assertThat(stale.response.status).isEqualTo(409); assertThat(code(stale, c.admin)).isEqualTo("VERSION_CONFLICT")
        // the key MUST be present: an absent newParentId is never a silent move to the root
        val absent = c.admin.post("${units(c)}/${id(a)}/move", """{"expectedVersion":${ver(a)}}"""); assertThat(absent.response.status).isEqualTo(400); assertThat(code(absent, c.admin)).isEqualTo("VALIDATION_FAILED")
        assertThat(c.admin.post("${units(c)}/${id(a)}/move", """{"newParentId":null}""").response.status).describedAs("expectedVersion is required").isEqualTo(400)
        assertThat(c.admin.post("${units(c)}/${id(a)}/move", """{"newParentId":"not-a-uuid","expectedVersion":0}""").response.status).isEqualTo(400)
        // the parent cannot change through PATCH
        c.admin.patch("${units(c)}/${id(a)}", """{"parentId":"${id(b)}","name":"A","expectedVersion":${ver(a)}}""")
        assertThat(unitNow(c, a).get("parentId").isNull).isTrue()
    }

    @Test
    fun `move honours the type rules and maxDepth for the unit AND every descendant of the subtree`() {
        val c = company()
        val shallow = type(c, "shallow", "Shallow", """{"maxDepth":3}"""); val free = type(c, "free", "Free")
        val r1 = unit(c, free, "R1"); val r2 = unit(c, free, "R2"); val r2c = unit(c, free, "R2C", r2)
        val s = unit(c, shallow, "S"); val sc = unit(c, shallow, "SC", s)                               // S (1) > SC (2)
        // moving S (with SC below) under R2C puts S at 3 and SC at 4: SC's type allows at most 3
        val tooDeep = move(c, s, r2c); assertThat(tooDeep.response.status).isEqualTo(409); assertThat(code(tooDeep, c.admin)).isEqualTo("ORG_TYPE_RULE_VIOLATION"); assertThat(c.admin.body(tooDeep).get("details").get("reason").asString()).isEqualTo("MAX_DEPTH")
        assertThat(move(c, s, r1).response.status).describedAs("under R1 it fits (2 and 3)").isEqualTo(200)
        assertThat(unitNow(c, sc).get("parentId").asString()).isEqualTo(id(s).toString())
        // allowedChildTypeIds of the new parent
        val leafOnly = type(c, "box", "Box", """{"allowedChildTypeIds":["${id(shallow)}"]}"""); val box = unit(c, leafOnly, "BOX")
        val notAllowed = move(c, r1, box); assertThat(notAllowed.response.status).isEqualTo(409); assertThat(c.admin.body(notAllowed).get("details").get("reason").asString()).isEqualTo("CHILD_TYPE_NOT_ALLOWED")
        // a type that cannot be a root cannot be moved to the root
        val noRoot = type(c, "inner", "Inner", """{"allowedParentTypeIds":["${id(free)}"]}"""); val inner = unit(c, noRoot, "IN", r2)
        val toRoot = move(c, inner, null); assertThat(toRoot.response.status).isEqualTo(409); assertThat(c.admin.body(toRoot).get("details").get("reason").asString()).isEqualTo("ROOT_NOT_ALLOWED")
    }

    @Test
    fun `E a unit cannot become its own parent or move below any descendant - ORG_CYCLE, nothing changes`() {
        val c = company(); val t = type(c, "unit")
        val a = unit(c, t, "A"); val b = unit(c, t, "B", a); val d = unit(c, t, "D", b); val e = unit(c, t, "E", d)
        for (target in listOf(a, b, d, e)) { val r = move(c, unitNow(c, a), target); assertThat(r.response.status).describedAs("A under ${target.get("code").asString()}").isEqualTo(409); assertThat(code(r, c.admin)).isEqualTo("ORG_CYCLE") }
        assertThat(code(move(c, unitNow(c, b), e), c.admin)).isEqualTo("ORG_CYCLE"); assertThat(code(move(c, unitNow(c, d), d), c.admin)).describedAs("itself").isEqualTo("ORG_CYCLE")
        assertThat(unitNow(c, a).get("parentId").isNull).isTrue(); assertThat(unitNow(c, b).get("parentId").asString()).isEqualTo(id(a).toString()); assertThat(ver(unitNow(c, a))).isZero()
    }

    @Test
    fun `F a parent, a type or a unit of another company is the same safe 404 as an unknown one, and nothing is created or moved`() {
        val a = company(); val b = company(); val ta = type(a, "unit"); val tb = type(b, "unit"); val ua = unit(a, ta, "A1"); val ub = unit(b, tb, "B1")
        val before = flat(a).size
        val foreignParent = a.admin.post(units(a), """{"typeId":"${id(ta)}","code":"X","name":"x","parentId":"${id(ub)}"}"""); assertThat(foreignParent.response.status).isEqualTo(404); assertThat(code(foreignParent, a.admin)).isEqualTo("ORG_UNIT_NOT_FOUND")
        val unknownParent = a.admin.post(units(a), """{"typeId":"${id(ta)}","code":"X","name":"x","parentId":"${UUID.randomUUID()}"}"""); assertThat(c0(foreignParent, a)).isEqualTo(c0(unknownParent, a))
        val foreignType = a.admin.post(units(a), """{"typeId":"${id(tb)}","code":"X","name":"x"}"""); assertThat(foreignType.response.status).isEqualTo(404); assertThat(code(foreignType, a.admin)).isEqualTo("ORG_UNIT_TYPE_NOT_FOUND")
        assertThat(flat(a)).hasSize(before)
        val moveForeign = move(a, ua, ub); assertThat(moveForeign.response.status).isEqualTo(404); assertThat(code(moveForeign, a.admin)).isEqualTo("ORG_UNIT_NOT_FOUND")
        for (path in listOf("${units(a)}/${id(ub)}", "${types(a)}/${id(tb)}")) assertThat(a.admin.get(path).response.status).describedAs("GET $path").isEqualTo(404)
        assertThat(a.admin.patch("${units(a)}/${id(ub)}", """{"name":"hijack","expectedVersion":0}""").response.status).isEqualTo(404)
        assertThat(a.admin.post("${units(a)}/${id(ub)}/archive", """{"expectedVersion":0}""").response.status).isEqualTo(404)
        assertThat(a.admin.post("${units(a)}/${id(ub)}/restore", """{"expectedVersion":0}""").response.status).isEqualTo(404)
        assertThat(unitNow(b, ub).get("version").asLong()).isZero(); assertThat(unitNow(a, ua).get("parentId").isNull).isTrue()
        // the tenant id of a body is not an authority: ignored
        val sneaky = a.admin.post(units(a), """{"typeId":"${id(ta)}","code":"SNEAKY","name":"s","tenantId":"${b.id}"}"""); assertThat(sneaky.response.status).isEqualTo(201); assertThat(a.admin.body(sneaky).get("tenantId").asString()).isEqualTo(a.id.toString())
        assertThat(flat(b).map { it.get("code").asString() }).containsExactly("B1")
    }
    private fun c0(r: org.springframework.test.web.servlet.MvcResult, c: Company) = c.admin.body(r).get("code").asString() + "|" + c.admin.body(r).get("message").asString()

    // ------------------------------------------------------------------------------------------------ archive / restore (H, I, J)
    @Test
    fun `H and I archive is soft and refuses while a child or a member remains - nothing cascades, there is no hard delete route`() {
        val c = company(); val t = type(c, "unit"); val root = unit(c, t, "R"); val kid = unit(c, t, "K", root)
        val blocked = c.admin.post("${units(c)}/${id(root)}/archive", """{"expectedVersion":${ver(root)}}"""); assertThat(blocked.response.status).isEqualTo(409); assertThat(code(blocked, c.admin)).isEqualTo("ORG_UNIT_HAS_CHILDREN")
        assertThat(c.admin.body(blocked).get("details").get("activeChildCount").asInt()).isEqualTo(1)
        // a member blocks the leaf
        val e = newEmployee(c); val userId = uid(e)
        assertThat(c.admin.post("${employees(c)}/$userId/organization-memberships", """{"organizationUnitId":"${id(kid)}"}""").response.status).isEqualTo(201)
        val withMembers = c.admin.post("${units(c)}/${id(kid)}/archive", """{"expectedVersion":${ver(kid)}}"""); assertThat(withMembers.response.status).isEqualTo(409); assertThat(code(withMembers, c.admin)).isEqualTo("ORG_UNIT_HAS_MEMBERS")
        // end the membership, archive the leaf, then the parent
        val mid = UUID.fromString(c.admin.body(c.admin.get("${employees(c)}/$userId/organization-memberships")).toList().single().get("id").asString())
        assertThat(c.admin.delete("${employees(c)}/$userId/organization-memberships/$mid?expectedVersion=${ver(c.admin.body(c.admin.get("${employees(c)}/$userId/organization-memberships")).toList().single())}").response.status).isEqualTo(200)
        val kidArchived = c.admin.post("${units(c)}/${id(kid)}/archive", """{"expectedVersion":${ver(kid)}}"""); assertThat(kidArchived.response.status).isEqualTo(200); assertThat(c.admin.body(kidArchived).get("active").asBoolean()).isFalse()
        assertThat(c.admin.post("${units(c)}/${id(root)}/archive", """{"expectedVersion":${ver(root)}}""").response.status).isEqualTo(200)
        assertThat(code(c.admin.post("${units(c)}/${id(root)}/archive", """{"expectedVersion":${ver(unitNow(c, root))}}"""), c.admin)).describedAs("already archived").isEqualTo("ORG_UNIT_ARCHIVED")
        assertThat(code(c.admin.patch("${units(c)}/${id(root)}", """{"name":"x","expectedVersion":${ver(unitNow(c, root))}}"""), c.admin)).describedAs("an archived unit is read-only").isEqualTo("ORG_UNIT_ARCHIVED")
        assertThat(code(move(c, unitNow(c, root), null), c.admin)).isEqualTo("ORG_UNIT_ARCHIVED")
        val intoArchived = c.admin.post(units(c), """{"typeId":"${id(t)}","code":"UNDER","name":"x","parentId":"${id(root)}"}"""); assertThat(intoArchived.response.status).isEqualTo(409); assertThat(code(intoArchived, c.admin)).isEqualTo("ORG_UNIT_ARCHIVED")
        // no hard delete in the tenant admin API
        assertThat(c.admin.delete("${units(c)}/${id(root)}").response.status).isIn(404, 405)
        assertThat(flat(c, "&includeArchived=true").map { it.get("code").asString() }).contains("R", "K")
    }

    @Test
    fun `J restore only when the parent, the type, the code, the rules and the tenant allow it - otherwise RESTORE_CONFLICT with the reason`() {
        val c = company(); val t = type(c, "unit"); val root = unit(c, t, "R"); val kid = unit(c, t, "K", root)
        val kidOff = c.admin.body(c.admin.post("${units(c)}/${id(kid)}/archive", """{"expectedVersion":${ver(kid)}}""")); val rootOff = c.admin.body(c.admin.post("${units(c)}/${id(root)}/archive", """{"expectedVersion":${ver(root)}}"""))
        fun reason(r: org.springframework.test.web.servlet.MvcResult): String { assertThat(r.response.status).isEqualTo(409); assertThat(code(r, c.admin)).isEqualTo("RESTORE_CONFLICT"); return c.admin.body(r).get("details").get("reason").asString() }
        assertThat(reason(c.admin.post("${units(c)}/${id(kid)}/restore", """{"expectedVersion":${ver(kidOff)}}"""))).isEqualTo("PARENT_ARCHIVED")
        // the code was taken meanwhile
        val clash = unit(c, t, "R", name = "new R")
        assertThat(reason(c.admin.post("${units(c)}/${id(root)}/restore", """{"expectedVersion":${ver(rootOff)}}"""))).isEqualTo("CODE_TAKEN")
        assertThat(c.admin.patch("${units(c)}/${id(clash)}", """{"code":"R2","expectedVersion":${ver(clash)}}""").response.status).isEqualTo(200)
        // type disabled
        val tOff = c.admin.post("${types(c)}/${id(t)}/disable", """{"expectedVersion":${ver(t)}}"""); assertThat(tOff.response.status).isEqualTo(200)
        assertThat(reason(c.admin.post("${units(c)}/${id(root)}/restore", """{"expectedVersion":${ver(rootOff)}}"""))).isEqualTo("TYPE_DISABLED")
        assertThat(c.admin.post("${types(c)}/${id(t)}/enable", """{"expectedVersion":${ver(c.admin.body(tOff))}}""").response.status).isEqualTo(200)
        // not archived
        assertThat(reason(c.admin.post("${units(c)}/${id(clash)}/restore", """{"expectedVersion":${ver(unitNow(c, clash))}}"""))).isEqualTo("NOT_ARCHIVED")
        // now it works, parent first
        val rootOn = c.admin.post("${units(c)}/${id(root)}/restore", """{"expectedVersion":${ver(rootOff)}}"""); assertThat(rootOn.response.status).isEqualTo(200); assertThat(c.admin.body(rootOn).get("active").asBoolean()).isTrue()
        assertThat(c.admin.post("${units(c)}/${id(kid)}/restore", """{"expectedVersion":${ver(kidOff)}}""").response.status).isEqualTo(200)
        // a type rule that no longer holds blocks the restore
        val strict = type(c, "strict", "Strict", """{"allowedParentTypeIds":["${id(t)}"]}"""); val s = unit(c, strict, "S", root)
        val sOff = c.admin.body(c.admin.post("${units(c)}/${id(s)}/archive", """{"expectedVersion":${ver(s)}}"""))
        assertThat(c.admin.patch("${types(c)}/${id(strict)}", """{"rules":{"allowedParentTypeIds":[]},"expectedVersion":${ver(strict)}}""").response.status).isEqualTo(200)
        assertThat(reason(c.admin.post("${units(c)}/${id(s)}/restore", """{"expectedVersion":${ver(sOff)}}"""))).isEqualTo("TYPE_RULE")
    }

    // ------------------------------------------------------------------------------------------------ N / O
    @Test
    fun `N the type and unit events are audited with tenant, ids and before - after values, and no secret is in the trail`() {
        val c = company(); val t = type(c, "unit"); val a = unit(c, t, "A"); val b = unit(c, t, "B")
        val upd = c.admin.body(c.admin.patch("${units(c)}/${id(a)}", """{"name":"A2","expectedVersion":${ver(a)}}"""))
        val mv = c.admin.body(move(c, b, a)); c.admin.post("${units(c)}/${id(b)}/archive", """{"expectedVersion":${ver(mv)}}""")
        val arch = unitNow(c, b); c.admin.post("${units(c)}/${id(b)}/restore", """{"expectedVersion":${ver(arch)}}""")
        val off = c.admin.body(c.admin.post("${types(c)}/${id(t)}/disable", """{"expectedVersion":${ver(t)}}""")); c.admin.post("${types(c)}/${id(t)}/enable", """{"expectedVersion":${ver(off)}}""")
        c.admin.patch("${types(c)}/${id(t)}", """{"name":"Unit 2","expectedVersion":${ver(off) + 1}}""")
        for ((action, rid) in listOf("ORG_UNIT_CREATED" to id(a), "ORG_UNIT_UPDATED" to id(a), "ORG_UNIT_MOVED" to id(b), "ORG_UNIT_ARCHIVED" to id(b), "ORG_UNIT_RESTORED" to id(b),
            "ORG_UNIT_TYPE_CREATED" to id(t), "ORG_UNIT_TYPE_DISABLED" to id(t), "ORG_UNIT_TYPE_ENABLED" to id(t), "ORG_UNIT_TYPE_UPDATED" to id(t))) assertThat(auditCount(action, rid)).describedAs(action).isEqualTo(1L)
        val moved = jdbc.queryForMap("SELECT actor_id, old_value::text AS o, new_value::text AS n FROM audit_events WHERE action = 'ORG_UNIT_MOVED' AND resource_id = ?", id(b).toString())
        assertThat(moved["actor_id"]).isEqualTo(c.adminId); assertThat(moved["o"] as String).contains(c.id.toString()).contains("parentId"); assertThat(moved["n"] as String).contains(id(a).toString())
        val trail = jdbc.queryForList("SELECT coalesce(old_value::text,'') || coalesce(new_value::text,'') AS t FROM audit_events WHERE actor_id = ?", c.adminId).joinToString { it["t"] as String }.lowercase()
        assertThat(trail).doesNotContain("password", "argon", "token", "secret", "hash"); assertThat(upd.get("name").asString()).isEqualTo("A2")
    }

    @Test
    /**
     * NOTE: the in-memory double serialises every repository call under one mutex and re-checks cycles itself, so this test proves the SERVICE contract (409 for the loser), NOT the
     * structural lock: removing `repos.lock.acquire` would not fail it. The race proofs (move vs move, archive vs insert, end vs addPosition, setPrimary vs setPrimary) are C3's mandatory
     * PostgreSQL concurrency tests, listed in the contract doc. Lock usage itself is pinned by the CF-4 test below (acquisition count).
     */
    fun `O a stale write is a 409 with the current version, also under concurrency, and two concurrent moves never close a cycle`() {
        val c = company(); val t = type(c, "unit"); val u = unit(c, t, "A")
        assertThat(c.admin.patch("${units(c)}/${id(u)}", """{"name":"first","expectedVersion":0}""").response.status).isEqualTo(200)
        val stale = c.admin.patch("${units(c)}/${id(u)}", """{"name":"second","expectedVersion":0}"""); assertThat(stale.response.status).isEqualTo(409); assertThat(code(stale, c.admin)).isEqualTo("VERSION_CONFLICT")
        assertThat(c.admin.body(stale).get("details").get("currentVersion").asLong()).isEqualTo(1); assertThat(unitNow(c, u).get("name").asString()).isEqualTo("first")
        val v = ver(unitNow(c, u)); val pool = Executors.newFixedThreadPool(2)
        val results = listOf("left", "right").map { n -> pool.submit<Int> { c.admin.patch("${units(c)}/${id(u)}", """{"name":"$n","expectedVersion":$v}""").response.status } }.map { it.get() }; pool.shutdown()
        assertThat(results.sorted()).containsExactly(200, 409)
        val x = unit(c, t, "X"); val y = unit(c, t, "Y"); val pool2 = Executors.newFixedThreadPool(2)
        val r2 = listOf(x to y, y to x).map { (child, parent) -> pool2.submit<Int> { move(c, child, parent).response.status } }.map { it.get() }; pool2.shutdown()
        assertThat(r2.count { it == 200 }).describedAs("moves: $r2").isEqualTo(1); assertThat(r2.count { it == 409 }).isEqualTo(1)
        assertThat(listOf(unitNow(c, x), unitNow(c, y)).count { it.get("parentId").isNull }).describedAs("exactly one of X, Y is still a root: no cycle").isEqualTo(1)
        assertThat(c.admin.post("${units(c)}/${id(u)}/archive", """{}""").response.status).describedAs("expectedVersion required").isEqualTo(400)
    }

    @Test
    fun `CF-3 unit code is REQUIRED, CANONICAL (trimmed, upper-case) and unique among the non-archived SIBLINGS - roots are siblings, other parents may reuse it, a move or a restore re-checks it`() {
        val a = company(); val b = company(); val ta = type(a, "unit"); val tb = type(b, "unit")
        val root = unit(a, ta, "ROOT-A"); val rootB = unit(a, ta, "ROOT-B"); unit(b, tb, "ROOT-A")                 // another company reuses everything
        // canonical form: whatever the case or the padding, the unit is stored and returned trimmed and upper-case
        val sales = c0Unit(a, ta, "  sales ", root); assertThat(sales.get("code").asString()).isEqualTo("SALES")
        // the SAME code under a different parent, and as a root, is allowed
        val salesB = unit(a, ta, "Sales", rootB); assertThat(salesB.get("code").asString()).isEqualTo("SALES"); assertThat(unit(a, ta, "SALES").get("parentId").isNull).isTrue()
        // siblings: refused whatever the case; the wording names the sibling scope, not "the company"
        val dup = a.admin.post(units(a), """{"typeId":"${id(ta)}","code":"sales","name":"dup","parentId":"${id(root)}"}"""); assertThat(dup.response.status).isEqualTo(409); assertThat(code(dup, a.admin)).isEqualTo("ORG_UNIT_CODE_TAKEN")
        assertThat(a.admin.body(dup).get("message").asString()).contains("sibling").doesNotContain("company")
        val dupRoot = a.admin.post(units(a), """{"typeId":"${id(ta)}","code":"root-a","name":"dup"}"""); assertThat(code(dupRoot, a.admin)).describedAs("two roots").isEqualTo("ORG_UNIT_CODE_TAKEN")
        // update onto a sibling's code
        val ops = unit(a, ta, "OPS", root); assertThat(code(a.admin.patch("${units(a)}/${id(ops)}", """{"code":" sales ","expectedVersion":${ver(ops)}}"""), a.admin)).isEqualTo("ORG_UNIT_CODE_TAKEN")
        assertThat(a.admin.body(a.admin.patch("${units(a)}/${id(ops)}", """{"code":"ops2","expectedVersion":${ver(ops)}}""")).get("code").asString()).isEqualTo("OPS2")
        // a MOVE next to a sibling that already has the code is refused (nothing moves); under a parent without it, it works
        val moveClash = move(a, salesB, root); assertThat(moveClash.response.status).isEqualTo(409); assertThat(code(moveClash, a.admin)).isEqualTo("ORG_UNIT_CODE_TAKEN")
        assertThat(unitNow(a, salesB).get("parentId").asString()).isEqualTo(id(rootB).toString())
        val ops3 = unit(a, ta, "FREE", rootB); assertThat(move(a, ops3, root).response.status).isEqualTo(200)
        // archiving frees the code among siblings, restore re-checks it
        val off = c0Archive(a, sales); val again = unit(a, ta, "sales", root)
        val restoreClash = a.admin.post("${units(a)}/${id(sales)}/restore", """{"expectedVersion":${ver(off)}}"""); assertThat(restoreClash.response.status).isEqualTo(409); assertThat(a.admin.body(restoreClash).get("details").get("reason").asString()).isEqualTo("CODE_TAKEN")
        assertThat(again.get("code").asString()).isEqualTo("SALES")
        // input validation: required, shape
        assertThat(code(a.admin.post(units(a), """{"typeId":"${id(ta)}","name":"x"}"""), a.admin)).describedAs("a code is required").isEqualTo("INVALID_CODE")
        assertThat(code(a.admin.post(units(a), """{"typeId":"${id(ta)}","code":"   ","name":"x"}"""), a.admin)).isEqualTo("INVALID_CODE")
        assertThat(code(a.admin.post(units(a), """{"typeId":"${id(ta)}","code":"bad code","name":"x"}"""), a.admin)).isEqualTo("INVALID_CODE")
        assertThat(code(a.admin.post(units(a), """{"typeId":"${id(ta)}","code":"OK","name":""}"""), a.admin)).isEqualTo("VALIDATION_FAILED")
        assertThat(code(a.admin.post(units(a), """{"code":"NOTYPE","name":"x"}"""), a.admin)).isEqualTo("VALIDATION_FAILED")
        assertThat(a.admin.post(units(a), """{"typeId":"${id(ta)}","code":"META","name":"m","metadata":[1]}""").response.status).isEqualTo(400)
        assertThat(a.admin.post(units(a), """{"typeId":"${id(ta)}","code":"BIG","name":"m","metadata":{"x":"${"y".repeat(9000)}"}}""").response.status).isEqualTo(400)
    }

    private fun c0Unit(c: Company, type: JsonNode, rawCode: String, parent: JsonNode?): JsonNode {
        val r = c.admin.post(units(c), """{"typeId":"${id(type)}","code":"$rawCode","name":"n"${if (parent != null) ""","parentId":"${id(parent)}"""" else ""}}""")
        assertThat(r.response.status).isEqualTo(201); return c.admin.body(r)
    }
    private fun c0Archive(c: Company, u: JsonNode): JsonNode { val r = c.admin.post("${units(c)}/${id(u)}/archive", """{"expectedVersion":${ver(unitNow(c, u))}}"""); assertThat(r.response.status).isEqualTo(200); return c.admin.body(r) }

    @Autowired lateinit var store: InMemoryOrganization

    @Test
    fun `CF-4 the tenant structural lock is taken by the subtree MOVE only - employee, membership, position, catalog and ordinary unit operations never serialise on it`() {
        val c = company(); val t = type(c, "unit"); val a = unit(c, t, "A"); val b = unit(c, t, "B")
        val before = store.lockAcquisitions.get()
        // everything except the move
        val e = newEmployee(c); val u = uid(e); val m = c.admin.body(c.admin.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${id(a)}"}"""))
        val pos = c.admin.body(c.admin.post(positions(c), """{"code":"P","name":"P"}""")); val g = c.admin.body(c.admin.post(grades(c), """{"code":"G","name":"G"}"""))
        val held = c.admin.body(c.admin.post("${employees(c)}/$u/positions", """{"membershipId":"${id(m)}","positionId":"${id(pos)}","gradeId":"${id(g)}"}"""))
        fun ok(r: org.springframework.test.web.servlet.MvcResult) = assertThat(r.response.status).describedAs(r.request.requestURI).isEqualTo(200)
        ok(c.admin.patch("${employees(c)}/$u/positions/${id(held)}", """{"clearGrade":true,"expectedVersion":${ver(held)}}""")); ok(c.admin.post("${employees(c)}/$u/disable")); ok(c.admin.post("${employees(c)}/$u/enable"))
        ok(c.admin.patch("${units(c)}/${id(b)}", """{"name":"B2","expectedVersion":0}""")); ok(c.admin.post("${types(c)}/${id(t)}/disable", """{"expectedVersion":${ver(t)}}""")); ok(c.admin.post("${types(c)}/${id(t)}/enable", """{"expectedVersion":1}"""))
        val kid = c.admin.body(c.admin.post(units(c), """{"typeId":"${id(t)}","code":"KID","name":"kid","parentId":"${id(b)}"}""")); ok(c.admin.post("${units(c)}/${id(kid)}/archive", """{"expectedVersion":0}""")); ok(c.admin.post("${units(c)}/${id(kid)}/restore", """{"expectedVersion":1}"""))
        assertThat(store.lockAcquisitions.get()).describedAs("no structural lock for ordinary operations").isEqualTo(before)
        // a move (and only a move) takes it, once per transaction
        assertThat(move(c, unitNow(c, b), a).response.status).isEqualTo(200); assertThat(store.lockAcquisitions.get()).isEqualTo(before + 1)
        assertThat(code(move(c, unitNow(c, a), unitNow(c, b)), c.admin)).describedAs("a refused move still took the lock first").isEqualTo("ORG_CYCLE"); assertThat(store.lockAcquisitions.get()).isEqualTo(before + 2)
        // the lock is released with the transaction: the next move is not blocked
        assertThat(move(c, unitNow(c, b), null).response.status).isEqualTo(200)
    }
}
