package com.systemwebstudio.tenancy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Ratchet for the V26 compatibility removal (runbook §4, migration request `tenant_compat_removal`).
 *
 * The follow-up migration may drop `workspaces.tenant_id DEFAULT` and the three `*_tenant_fill` triggers only when every INSERT into
 * workspaces / workspace_members / projects / project_members passes `tenant_id`. This test lists the code that does NOT yet:
 * [PENDING] is the exact current list. A NEW statement without tenant_id fails the test; fixing one makes the test fail until its entry is
 * deleted from the list, so the list can only shrink. When it is empty the compatibility objects can be removed.
 */
class TenantInsertPathsGrepTest {
    private val tables = listOf("workspaces", "workspace_members", "projects", "project_members")
    private val sqlInsert = Regex("""INSERT\s+INTO\s+(workspaces|workspace_members|projects|project_members)\b[^"]*""", RegexOption.IGNORE_CASE)

    // file (relative to backend/) : table  — C1 owns the identity and member packages, the others are C0/C2/test code
    private val PENDING = setOf(
        "src/main/kotlin/com/systemwebstudio/identity/RegistrationController.kt:workspaces",
        "src/main/kotlin/com/systemwebstudio/identity/RegistrationController.kt:workspace_members",
        "src/main/kotlin/com/systemwebstudio/identity/BootstrapAdmin.kt:workspaces",
        "src/main/kotlin/com/systemwebstudio/identity/BootstrapAdmin.kt:workspace_members",
        "src/main/kotlin/com/systemwebstudio/identity/Accounts.kt:workspaces",
        "src/main/kotlin/com/systemwebstudio/identity/Accounts.kt:workspace_members",
        "src/main/kotlin/com/systemwebstudio/identity/Scim.kt:workspace_members",
        "src/main/kotlin/com/systemwebstudio/member/MemberController.kt:workspace_members",
        "src/main/kotlin/com/systemwebstudio/member/MemberController.kt:project_members",
        "src/main/kotlin/com/systemwebstudio/admin/AdminController.kt:project_members",
        "src/test/kotlin/com/systemwebstudio/runtime/ServerRuntimeTests.kt:project_members",
        "src/test/kotlin/com/systemwebstudio/publish/StaticSiteTests.kt:project_members",
        // JPA entities (C2 project/Project.kt) do not map tenant_id: every save() relies on the compatibility default/trigger
        "src/main/kotlin/com/systemwebstudio/project/Project.kt:entity:workspaces",
        "src/main/kotlin/com/systemwebstudio/project/Project.kt:entity:workspace_members",
        "src/main/kotlin/com/systemwebstudio/project/Project.kt:entity:projects",
        "src/main/kotlin/com/systemwebstudio/project/Project.kt:entity:project_members"
    )

    private fun backend(): File = listOf(File("."), File("backend")).first { File(it, "src/main/kotlin").isDirectory }

    private fun scan(): Set<String> {
        val root = backend()
        val found = linkedSetOf<String>()
        for (dir in listOf("src/main/kotlin", "src/test/kotlin")) File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            if (rel.contains("/tenancy/")) return@forEach                       // this module's own tests insert tenant_id on purpose (and probe rejects)
            val text = f.readText()
            for (m in sqlInsert.findAll(text)) if (!m.value.contains("tenant_id", ignoreCase = true)) found += "$rel:${m.groupValues[1].lowercase()}"
            for (t in tables) if (text.contains("@Table(name = \"$t\")") && !text.contains("tenant_id")) found += "$rel:entity:$t"
        }
        return found
    }

    @Test
    fun `no new INSERT into the four tenant tables omits tenant_id, and the pending list only shrinks`() {
        val actual = scan()
        assertThat(actual - PENDING).describedAs("NEW insert paths without tenant_id (pass tenant_id explicitly)").isEmpty()
        assertThat(PENDING - actual).describedAs("fixed paths still listed in PENDING (delete them from the list)").isEmpty()
    }
}
