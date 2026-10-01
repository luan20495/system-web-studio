package com.systemwebstudio.component

import com.systemwebstudio.common.ApiException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

data class ComponentVersionDto(
    val version: String, val status: String, val propsSchema: JsonNode, val actions: JsonNode, val permissions: JsonNode
)

data class ComponentDto(
    val id: String, val name: String, val category: String, val description: String,
    val latestVersion: String, val status: String, val versions: List<ComponentVersionDto> = emptyList()
)

/** Read side of the approved component registry; the only source of component types the schema may use. */
@Service
class ComponentRegistry(private val jdbc: JdbcTemplate, private val json: JsonMapper) {
    fun list(): List<ComponentDto> = jdbc.query(
        "SELECT id, name, category, description, latest_version, status FROM components ORDER BY category, id"
    ) { rs, _ -> ComponentDto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)) }

    fun get(id: String): ComponentDto {
        val base = list().firstOrNull { it.id == id } ?: throw ApiException.notFound("COMPONENT_NOT_FOUND", "Component not found")
        return base.copy(versions = versions().values.filter { it.componentId == id }.map { it.dto })
    }

    class Entry(val componentId: String, val dto: ComponentVersionDto)

    /** key = "componentId@version" */
    fun versions(): Map<String, Entry> = jdbc.query(
        "SELECT component_id, version, status, props_schema, actions, permissions FROM component_versions"
    ) { rs, _ ->
        Entry(rs.getString(1), ComponentVersionDto(rs.getString(2), rs.getString(3),
            json.readTree(rs.getString(4)), json.readTree(rs.getString(5)), json.readTree(rs.getString(6))))
    }.associateBy { "${it.componentId}@${it.dto.version}" }
}

@RestController
@RequestMapping("/api/v1/components")
class ComponentController(private val registry: ComponentRegistry) {
    @GetMapping
    fun list(): List<ComponentDto> = registry.list()

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ComponentDto = registry.get(id)
}
