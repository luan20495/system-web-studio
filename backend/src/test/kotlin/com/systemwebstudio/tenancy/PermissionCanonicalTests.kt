package com.systemwebstudio.tenancy

import com.systemwebstudio.access.Permission
import com.systemwebstudio.access.PermissionCodes
import com.systemwebstudio.access.PermissionMatrix
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** C1 · canonical permission vocabulary (contract v2 §5): storage mapping, default deny, legacy semantics preserved. Pure unit test. */
class PermissionCanonicalTests {
    private val newCodes = listOf("APP_USE", "DATA_SOURCE_VIEW", "DATA_SOURCE_MANAGE", "QUERY_EXECUTE", "DATA_MUTATE", "ACTION_EXECUTE", "WORKFLOW_EXECUTE", "WORKFLOW_MANAGE")

    @Test
    fun `legacy permission mapping - storage constants keep their names and map to the canonical APP codes`() {
        assertThat(PermissionCodes.codeOf(Permission.PROJECT_READ)).isEqualTo("APP_VIEW")
        assertThat(PermissionCodes.codeOf(Permission.PROJECT_EDIT)).isEqualTo("APP_EDIT")
        assertThat(PermissionCodes.codeOf(Permission.PROJECT_PUBLISH)).isEqualTo("APP_PUBLISH")
        assertThat(PermissionCodes.codeOf(Permission.PROJECT_MEMBERS)).isEqualTo("APP_SHARE")                // temporary mapping until T15
        assertThat(PermissionCodes.fromCode("APP_VIEW")).isEqualTo(Permission.PROJECT_READ)
        assertThat(PermissionCodes.fromCode("APP_EDIT")).isEqualTo(Permission.PROJECT_EDIT)
        assertThat(PermissionCodes.fromCode("APP_PUBLISH")).isEqualTo(Permission.PROJECT_PUBLISH)
        assertThat(PermissionCodes.fromCode("APP_SHARE")).isEqualTo(Permission.PROJECT_MEMBERS)
        // legacy names are storage, not vocabulary: a boundary that sends them is refused
        for (legacy in listOf("PROJECT_READ", "PROJECT_EDIT", "PROJECT_PUBLISH", "PROJECT_MEMBERS", "PROJECT_SETTINGS", "FOO", "")) assertThat(PermissionCodes.fromCode(legacy)).describedAs(legacy).isNull()
        assertThat(PermissionCodes.fromCode(null)).isNull()
        // every legacy constant still exists under its old name (≈56 call sites depend on them)
        for (n in listOf("PROJECT_READ", "PROJECT_EDIT", "PROJECT_SETTINGS", "PROJECT_DELETE", "PROJECT_PUBLISH", "PROJECT_CREATE", "PROJECT_MEMBERS", "MEMBER_MANAGE", "AUDIT_READ", "REGISTRY_WRITE"))
            assertThat(Permission.valueOf(n).name).isEqualTo(n)
    }

    @Test
    fun `new codes exist with exactly the canonical name and the canonical set is closed and round-trips`() {
        for (c in newCodes + listOf("TENANT_MANAGE", "TENANT_MEMBERS")) { assertThat(Permission.valueOf(c).name).isEqualTo(c); assertThat(PermissionCodes.fromCode(c)).isEqualTo(Permission.valueOf(c)); assertThat(PermissionCodes.codeOf(Permission.valueOf(c))).isEqualTo(c) }
        assertThat(PermissionCodes.CANONICAL).containsExactlyInAnyOrder(*(newCodes + listOf("APP_VIEW", "APP_EDIT", "APP_PUBLISH", "APP_SHARE", "TENANT_MANAGE", "TENANT_MEMBERS")).toTypedArray())
        for (code in PermissionCodes.CANONICAL) assertThat(PermissionCodes.codeOf(PermissionCodes.fromCode(code)!!)).isEqualTo(code)
    }

    @Test
    fun `default deny - managing, mutating and workflow codes belong to WORKSPACE_ADMIN only, VIEWER can only view and use`() {
        val managerOnly = setOf(Permission.DATA_SOURCE_MANAGE, Permission.DATA_MUTATE, Permission.WORKFLOW_EXECUTE, Permission.WORKFLOW_MANAGE)
        for ((role, perms) in PermissionMatrix.projectRoles) assertThat(perms.intersect(managerOnly)).describedAs("project role $role").isEmpty()
        for ((role, perms) in PermissionMatrix.workspaceRoles) if (role != "WORKSPACE_ADMIN") assertThat(perms.intersect(newCodes.map { Permission.valueOf(it) }.toSet())).describedAs("workspace role $role").isEmpty()
        assertThat(PermissionMatrix.workspaceRoles.getValue("WORKSPACE_ADMIN")).containsAll(newCodes.map { Permission.valueOf(it) })
        assertThat(PermissionMatrix.projectRoles.getValue("VIEWER")).containsExactlyInAnyOrder(Permission.PROJECT_READ, Permission.APP_USE)
        assertThat(PermissionMatrix.projectRoles.getValue("EDITOR")).contains(Permission.DATA_SOURCE_VIEW, Permission.QUERY_EXECUTE, Permission.ACTION_EXECUTE, Permission.APP_USE)
        assertThat(PermissionMatrix.projectRoles.getValue("PUBLISHER")).doesNotContain(Permission.QUERY_EXECUTE, Permission.PROJECT_EDIT)
    }

    @Test
    fun `legacy role semantics are unchanged`() {
        assertThat(PermissionMatrix.projectRoles.getValue("VIEWER")).doesNotContain(Permission.PROJECT_EDIT, Permission.PROJECT_PUBLISH, Permission.PROJECT_DELETE)
        assertThat(PermissionMatrix.projectRoles.getValue("EDITOR")).contains(Permission.PROJECT_READ, Permission.PROJECT_EDIT, Permission.PROJECT_SETTINGS)
        assertThat(PermissionMatrix.projectRoles.getValue("EDITOR")).doesNotContain(Permission.PROJECT_PUBLISH, Permission.PROJECT_DELETE, Permission.PROJECT_MEMBERS)
        assertThat(PermissionMatrix.projectRoles.getValue("OWNER")).contains(Permission.PROJECT_READ, Permission.PROJECT_EDIT, Permission.PROJECT_SETTINGS, Permission.PROJECT_DELETE, Permission.PROJECT_PUBLISH, Permission.PROJECT_MEMBERS)
        assertThat(PermissionMatrix.workspaceRoles.getValue("WORKSPACE_ADMIN")).contains(Permission.PROJECT_CREATE, Permission.MEMBER_MANAGE, Permission.AUDIT_READ, Permission.PROJECT_PUBLISH)
        assertThat(PermissionMatrix.workspaceRoles.getValue("EDITOR")).containsExactly(Permission.PROJECT_CREATE)
        assertThat(PermissionMatrix.workspaceRoles.getValue("VIEWER")).isEmpty()
    }

    @Test
    fun `platform scope and tenant roles hold no business permission`() {
        val business = Permission.entries.toSet() - setOf(Permission.TENANT_MANAGE, Permission.TENANT_MEMBERS, Permission.MEMBER_MANAGE, Permission.PROJECT_CREATE)
        assertThat(PermissionMatrix.platformScope.intersect(business)).isEmpty()
        assertThat(PermissionMatrix.tenantRoles.getValue("TENANT_ADMIN")).containsExactlyInAnyOrder(Permission.TENANT_MANAGE, Permission.TENANT_MEMBERS)
        assertThat(PermissionMatrix.tenantRoles.getValue("MEMBER")).isEmpty()
        assertThat(PermissionMatrix.systemAdmin).isEqualTo(Permission.entries.toSet())          // only reachable behind the flag
    }

    @Test
    fun `canonicalCodesOf exposes canonical codes only - internal storage constants are dropped, legacy names never leak`() {
        val all = Permission.entries
        val codes = PermissionCodes.canonicalCodesOf(all)
        assertThat(PermissionCodes.CANONICAL).containsAll(codes)
        assertThat(codes).doesNotContain("PROJECT_READ", "PROJECT_EDIT", "PROJECT_PUBLISH", "PROJECT_MEMBERS", "PROJECT_SETTINGS", "PROJECT_DELETE", "PROJECT_CREATE", "MEMBER_MANAGE", "AUDIT_READ", "REGISTRY_WRITE")
        assertThat(codes).containsExactlyInAnyOrderElementsOf(PermissionCodes.CANONICAL)         // every canonical code is reachable from some storage constant
        assertThat(PermissionCodes.canonicalCodesOf(listOf(Permission.PROJECT_READ, Permission.PROJECT_READ, Permission.MEMBER_MANAGE))).containsExactly("APP_VIEW")
    }

    @Test
    fun `unknown, blank, lower-case and legacy storage names are not canonical codes`() {
        for (c in listOf("", " ", "app_view", "APP_VIEW ", "PROJECT_READ", "PROJECT_MEMBERS", "MEMBER_MANAGE", "AUDIT_READ", "GOD", "DATA_SOURCE")) assertThat(PermissionCodes.fromCode(c)).describedAs(c).isNull()
    }
}
