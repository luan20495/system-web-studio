package com.systemwebstudio.project

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Deep links (/studio/projects/{id}) know only the project id: resolve its workspace, then apply the normal access check (404 if not visible). */
@RestController
class ProjectLookupController(private val access: AccessService, private val jdbc: JdbcTemplate) {
    @GetMapping("/api/v1/projects/{projectId}")
    @Transactional(readOnly = true)
    fun lookup(@PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): ProjectResponse {
        val ws = jdbc.queryForList("SELECT workspace_id FROM projects WHERE id = ? AND active", UUID::class.java, projectId).firstOrNull()
            ?: throw ApiException.notFound("PROJECT_NOT_FOUND", "Project not found")
        val ctx = access.forProject(me.userId, ws, projectId)
        return ctx.project!!.toResponse(ctx.permissions)
    }
}
