package com.systemwebstudio.schema

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.component.ComponentRegistry
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

data class Violation(val path: String, val message: String)

@Service
class PageSchemaValidator(private val registry: ComponentRegistry, private val json: JsonMapper) {
    companion object {
        val SECTION_ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
        const val MAX_BYTES = 256 * 1024
        const val MAX_SECTIONS = 50
    }

    fun validate(schema: JsonNode): List<Violation> {
        val out = ArrayList<Violation>()
        if (!schema.isObject) return listOf(Violation("$", "schema must be an object"))
        if (json.writeValueAsString(schema).length > MAX_BYTES) return listOf(Violation("$", "schema exceeds $MAX_BYTES bytes"))
        val page = schema.get("page")
        if (page == null || !page.isString || page.asString().isBlank() || page.asString().length > 64) out += Violation("page", "page must be a non-empty string up to 64 chars")
        val sections = schema.get("sections")
        if (sections == null || !sections.isArray) return out + Violation("sections", "sections must be an array")
        if (sections.size() > MAX_SECTIONS) out += Violation("sections", "at most $MAX_SECTIONS sections")
        val versions = registry.versions()
        val seen = HashSet<String>()
        sections.forEachIndexed { i, s ->
            val at = "sections[$i]"
            if (!s.isObject) { out += Violation(at, "section must be an object"); return@forEachIndexed }
            val id = s.get("id")?.takeIf { it.isString }?.asString()
            if (id == null || !SECTION_ID.matches(id)) out += Violation("$at.id", "id must match ${SECTION_ID.pattern}")
            else if (!seen.add(id)) out += Violation("$at.id", "duplicate section id '$id'")
            val type = s.get("type")?.takeIf { it.isString }?.asString()
            val version = s.get("componentVersion")?.takeIf { it.isString }?.asString()
            val props = s.get("props")
            if (type == null) { out += Violation("$at.type", "type is required"); return@forEachIndexed }
            if (version == null) { out += Violation("$at.componentVersion", "componentVersion is required"); return@forEachIndexed }
            val entry = versions["$type@$version"]
            if (entry == null) { out += Violation("$at.type", "unknown component '$type' version '$version'"); return@forEachIndexed }
            if (entry.dto.status == "BLOCKED") { out += Violation("$at.type", "component '$type@$version' is blocked"); return@forEachIndexed }
            if (props == null || !props.isObject) { out += Violation("$at.props", "props must be an object"); return@forEachIndexed }
            checkProps(props, entry.dto.propsSchema, "$at.props", out)
        }
        return out
    }

    fun requireValid(schema: JsonNode) {
        val v = validate(schema)
        if (v.isNotEmpty()) {
            throw ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "SCHEMA_INVALID", "Page schema is invalid",
                mapOf("violations" to v.take(20).map { mapOf("path" to it.path, "message" to it.message) }))
        }
    }

    private fun checkProps(props: JsonNode, spec: JsonNode, at: String, out: MutableList<Violation>) {
        val properties = spec.get("properties")
        spec.get("required")?.forEach { r ->
            val key = r.asString()
            if (!props.has(key) || props.get(key).isNull) out += Violation("$at.$key", "required prop '$key' is missing")
        }
        props.propertyNames().forEach { key ->
            val def = properties?.get(key)
            if (def == null) { out += Violation("$at.$key", "unknown prop '$key'"); return@forEach }
            checkValue(props.get(key), def, "$at.$key", out)
            if (def.get("type")?.asString() == "array" && spec.get("properties")?.get(key)?.has("itemProperties") == true) checkItems(props.get(key), def, "$at.$key", out)
        }
    }

    private fun checkValue(value: JsonNode, def: JsonNode, at: String, out: MutableList<Violation>) {
        when (val type = def.get("type")?.asString()) {
            "string" -> {
                if (!value.isString) { out += Violation(at, "must be a string"); return }
                def.get("maxLength")?.let { if (value.asString().length > it.asInt()) out += Violation(at, "longer than ${it.asInt()} characters") }
                def.get("enum")?.let { e -> if (e.none { it.asString() == value.asString() }) out += Violation(at, "must be one of ${e.joinToString { it.asString() }}") }
            }
            "number" -> if (!value.isNumber) out += Violation(at, "must be a number")
            "boolean" -> if (!value.isBoolean) out += Violation(at, "must be a boolean")
            "array" -> {
                if (!value.isArray) { out += Violation(at, "must be an array"); return }
                def.get("maxItems")?.let { if (value.size() > it.asInt()) out += Violation(at, "more than ${it.asInt()} items") }
            }
            "object" -> if (!value.isObject) out += Violation(at, "must be an object")
            else -> out += Violation(at, "registry declares unsupported type '$type'")
        }
    }

    private fun checkItems(items: JsonNode, def: JsonNode, at: String, out: MutableList<Violation>) {
        if (!items.isArray) return
        val itemProps = def.get("itemProperties")
        val required = def.get("itemRequired")
        val ids = HashSet<String>()
        items.forEachIndexed { i, item ->
            val p = "$at[$i]"
            if (!item.isObject) { out += Violation(p, "item must be an object"); return@forEachIndexed }
            required?.forEach { r -> if (!item.has(r.asString())) out += Violation("$p.${r.asString()}", "required item prop '${r.asString()}' is missing") }
            item.propertyNames().forEach { key ->
                val d = itemProps?.get(key)
                if (d == null) out += Violation("$p.$key", "unknown item prop '$key'") else checkValue(item.get(key), d, "$p.$key", out)
            }
            item.get("id")?.takeIf { it.isString }?.asString()?.let { if (!ids.add(it)) out += Violation("$p.id", "duplicate item id '$it'") }
        }
    }
}
