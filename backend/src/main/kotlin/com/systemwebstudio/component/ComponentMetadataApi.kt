package com.systemwebstudio.component

import com.systemwebstudio.common.ApiException
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

/**
 * Read-only Component Registry V2 metadata (D-C2-12): the registry's own rows (category, latest version, props schema, declared actions)
 * combined with [ComponentOverlays]. No table is added or changed; nothing is written.
 */
@Service
class ComponentMetadataService(private val registry: ComponentRegistry, json: JsonMapper) {
    private val builder = ComponentMetadataBuilder(json)

    /** the metadata of every active component at its latest version */
    fun all(): List<ComponentMetadataV2> {
        val versions = registry.versions()
        return registry.list().filter { it.status != "DEPRECATED" && it.status != "RETIRED" }.mapNotNull { c ->
            versions["${c.id}@${c.latestVersion}"]?.let { builder.build(c.id, it.dto.version, c.category, it.dto.propsSchema, it.dto.actions) }
        }
    }

    fun get(id: String): ComponentMetadataV2 =
        all().firstOrNull { it.id == id } ?: throw ApiException.notFound("COMPONENT_NOT_FOUND", "Component not found")
}

/** Open to every signed-in user, like `/api/v1/components`; exposes only what the registry and the code-defined overlays declare. */
@RestController
@RequestMapping("/api/v1/component-metadata")
class ComponentMetadataController(private val service: ComponentMetadataService) {
    @GetMapping
    fun list(): List<ComponentMetadataV2> = service.all()

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ComponentMetadataV2 = service.get(id)
}
