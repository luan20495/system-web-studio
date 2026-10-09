package com.systemwebstudio.organization

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * NO C3 persistence wired (this class imports no store): the release candidate must keep booting, and the organization routes answer `501 ORG_PERSISTENCE_NOT_AVAILABLE`
 * to a caller who is AUTHORIZED - while the authorization answers (401, 404, 403) still come first, so the missing store leaks nothing to anyone else.
 */
class OrganizationUnavailableTests : OrganizationTestBase() {
    @Test
    fun `the application boots without C3 persistence, an authorized caller gets 501 and nobody else learns anything from it`() {
        val sys = sysAdmin(); val c = company(sys); val other = company(sys)
        for (path in listOf(units(c), types(c), employees(c), positions(c), grades(c), "${units(c)}/${UUID.randomUUID()}", "${employees(c)}/${UUID.randomUUID()}/organization-memberships")) {
            val r = c.admin.get(path); assertThat(r.response.status).describedAs("Tenant Admin GET $path").isEqualTo(501); assertThat(code(r, c.admin)).isEqualTo("ORG_PERSISTENCE_NOT_AVAILABLE")
        }
        assertThat(c.admin.post(units(c), """{"typeId":"${UUID.randomUUID()}","code":"X","name":"x"}""").response.status).isEqualTo(501)
        assertThat(c.admin.post("${units(c)}/${UUID.randomUUID()}/move", """{"newParentId":null,"expectedVersion":0}""").response.status).isEqualTo(501)
        // authorization first: 401 anonymous, 404 foreign company, 403 SYSTEM_ADMIN and plain member - never a 501
        assertThat(session().get(units(c)).response.status).isEqualTo(401)
        assertThat(other.admin.get(units(c)).response.status).isEqualTo(404)
        assertThat(sys.get(units(c)).response.status).isEqualTo(403)
        // the C1-owned parts do not need it: the company, the account, the permissions and the audit work without any organization store
        assertThat(c.admin.body(c.admin.get("/api/v1/auth/me")).get("permissions").toList().map { it.asString() }).contains("ORG_STRUCTURE_MANAGE", "EMPLOYEE_MANAGE", "POSITION_GRADE_MANAGE")
        assertThat(c.admin.post("${base(c)}/users", """{"username":"${uname("plain")}","displayName":"P"}""").response.status).isEqualTo(201)
    }
}
