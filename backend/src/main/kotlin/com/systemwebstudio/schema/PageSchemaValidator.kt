package com.systemwebstudio.schema

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.component.ComponentRegistry
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

data class Violation(val path: String, val message: String)

@Service
class PageSchemaValidator(private val registry: ComponentRegistry, private val json: JsonMapper,
                          private val settings: com.systemwebstudio.settings.SettingsService? = null) {
    companion object {
        val SECTION_ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
        val SLUG = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        val RESERVED_SLUGS = setOf("assets", "api", "sites", "_app", "_preview", "_access", "_forms", "__factory", "404", "index", "home", "static")
        const val MAX_PAGES = 20
        const val MAX_NAV = 12
        const val MAX_BYTES = 256 * 1024
        const val MAX_SECTIONS = 50
        val ASSET_REF = Regex("^asset://[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        /** Every asset id referenced anywhere in a page schema. */
        fun assetRefs(node: JsonNode?): Set<java.util.UUID> {
            val out = HashSet<java.util.UUID>()
            fun walk(n: JsonNode?) {
                when {
                    n == null -> {}
                    n.isString -> n.asString().takeIf { ASSET_REF.matches(it) }?.let { out += java.util.UUID.fromString(it.removePrefix("asset://")) }
                    n.isArray -> n.forEach { walk(it) }
                    n.isObject -> n.propertyNames().forEach { walk(n.get(it)) }
                }
            }
            walk(node)
            return out
        }
    }

    fun validate(schema: JsonNode): List<Violation> {
        val out = ArrayList<Violation>()
        if (!schema.isObject) return listOf(Violation("$", "schema must be an object"))
        if (json.writeValueAsString(schema).length > MAX_BYTES) return listOf(Violation("$", "schema exceeds $MAX_BYTES bytes"))
        val page = schema.get("page")
        if (page == null || !page.isString || page.asString().isBlank() || page.asString().length > 64) out += Violation("page", "page must be a non-empty string up to 64 chars")
        val sections = schema.get("sections")
        if (sections == null || !sections.isArray) return out + Violation("sections", "sections must be an array")
        val versions = registry.versions()
        val seen = HashSet<String>()
        checkSections(sections, "sections", versions, seen, out)
        // multi-page site (stage G): extra pages, navigation, SEO and the 404 page; the root page above is the home page
        val pageIds = mutableSetOf("home")
        schema.get("pages")?.let { pages ->
            if (!pages.isArray) { out += Violation("pages", "pages must be an array"); return@let }
            if (pages.size() > MAX_PAGES) out += Violation("pages", "at most $MAX_PAGES pages")
            val slugs = HashSet<String>()
            pages.forEachIndexed { i, pg ->
                val at = "pages[$i]"
                if (!pg.isObject) { out += Violation(at, "page must be an object"); return@forEachIndexed }
                pg.propertyNames().filter { it !in setOf("id", "slug", "title", "seo", "sections") }.forEach { out += Violation("$at.$it", "unknown page field '$it'") }
                val id = pg.get("id")?.takeIf { it.isString }?.asString()
                if (id == null || !SECTION_ID.matches(id) || id == "home") out += Violation("$at.id", "page id must match ${SECTION_ID.pattern} and not be 'home'")
                else if (!pageIds.add(id)) out += Violation("$at.id", "duplicate page id '$id'")
                val slug = pg.get("slug")?.takeIf { it.isString }?.asString()
                if (slug == null || slug.length > 60 || !SLUG.matches(slug)) out += Violation("$at.slug", "slug: lowercase letters, digits and single dashes, up to 60 characters")
                else if (slug in RESERVED_SLUGS) out += Violation("$at.slug", "slug '$slug' is reserved")
                else if (!slugs.add(slug)) out += Violation("$at.slug", "duplicate slug '$slug'")
                text(pg.get("title"), "$at.title", 80, out, required = true)
                pg.get("seo")?.let { checkSeo(it, "$at.seo", out) }
                val secs = pg.get("sections")
                if (secs == null || !secs.isArray) out += Violation("$at.sections", "sections must be an array") else checkSections(secs, "$at.sections", versions, seen, out)
            }
        }
        schema.get("site")?.let { site ->
            if (!site.isObject) { out += Violation("site", "site must be an object"); return@let }
            site.propertyNames().filter { it !in setOf("title", "home", "navigation", "notFound") }.forEach { out += Violation("site.$it", "unknown site field '$it'") }
            text(site.get("title"), "site.title", 80, out)
            site.get("home")?.let { h ->
                if (!h.isObject) { out += Violation("site.home", "must be an object"); return@let }
                h.propertyNames().filter { it !in setOf("title", "seo") }.forEach { out += Violation("site.home.$it", "unknown field '$it'") }
                text(h.get("title"), "site.home.title", 80, out); h.get("seo")?.let { checkSeo(it, "site.home.seo", out) }
            }
            site.get("notFound")?.let { nf ->
                if (!nf.isObject) { out += Violation("site.notFound", "must be an object"); return@let }
                nf.propertyNames().filter { it !in setOf("title", "message") }.forEach { out += Violation("site.notFound.$it", "unknown field '$it'") }
                text(nf.get("title"), "site.notFound.title", 80, out); text(nf.get("message"), "site.notFound.message", 300, out)
            }
            site.get("navigation")?.let { nav -> checkNavigation(nav, pageIds, out) }
        }
        return out
    }

    private fun text(v: JsonNode?, at: String, max: Int, out: MutableList<Violation>, required: Boolean = false) {
        if (v == null || v.isNull) { if (required) out += Violation(at, "is required"); return }
        if (!v.isString) { out += Violation(at, "must be a string"); return }
        if (required && v.asString().isBlank()) out += Violation(at, "must not be empty")
        if (v.asString().length > max) out += Violation(at, "longer than $max characters")
    }

    private fun checkSeo(seo: JsonNode, at: String, out: MutableList<Violation>) {
        if (!seo.isObject) { out += Violation(at, "must be an object"); return }
        seo.propertyNames().filter { it !in setOf("title", "description", "noindex") }.forEach { out += Violation("$at.$it", "unknown SEO field '$it'") }
        text(seo.get("title"), "$at.title", 70, out); text(seo.get("description"), "$at.description", 160, out)
        seo.get("noindex")?.let { if (!it.isBoolean) out += Violation("$at.noindex", "must be a boolean") }
    }

    /** external navigation URLs whose host is not (or no longer) on the approved list */
    fun unapprovedLinks(schema: JsonNode): List<String> {
        val hosts = externalHosts()
        return schema.get("site")?.get("navigation")?.toList().orEmpty().mapNotNull { l -> l.get("url")?.takeIf { it.isString }?.asString() }.filter { url ->
            val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
            host == null || hosts.none { host == it || host.endsWith(".$it") }
        }
    }

    /** approved external link hosts (Admin → Settings → site.external-link-domains); empty = no external links */
    fun externalHosts(): Set<String> = settings?.raw("site.external-link-domains").orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** Navigation links point at a page of this site, an anchor on the page, or an https URL on an approved host; nothing else (no javascript:, data:, http:). */
    private fun checkNavigation(nav: JsonNode, pageIds: Set<String>, out: MutableList<Violation>) {
        if (!nav.isArray) { out += Violation("site.navigation", "must be an array"); return }
        if (nav.size() > MAX_NAV) out += Violation("site.navigation", "at most $MAX_NAV links")
        val ids = HashSet<String>()
        nav.forEachIndexed { i, l ->
            val at = "site.navigation[$i]"
            if (!l.isObject) { out += Violation(at, "link must be an object"); return@forEachIndexed }
            l.propertyNames().filter { it !in setOf("id", "label", "pageId", "url", "anchor") }.forEach { out += Violation("$at.$it", "unknown link field '$it'") }
            val id = l.get("id")?.takeIf { it.isString }?.asString()
            if (id == null || !SECTION_ID.matches(id)) out += Violation("$at.id", "id must match ${SECTION_ID.pattern}") else if (!ids.add(id)) out += Violation("$at.id", "duplicate link id '$id'")
            text(l.get("label"), "$at.label", 40, out, required = true)
            val targets = listOf("pageId", "url", "anchor").filter { l.get(it) != null && !l.get(it).isNull }
            if (targets.size != 1) { out += Violation(at, "a link needs exactly one of pageId, url, anchor"); return@forEachIndexed }
            val v = l.get(targets[0]).takeIf { it.isString }?.asString() ?: run { out += Violation("$at.${targets[0]}", "must be a string"); return@forEachIndexed }
            when (targets[0]) {
                "pageId" -> if (v !in pageIds) out += Violation("$at.pageId", "page '$v' does not exist")
                "anchor" -> if (!Regex("^#[a-z0-9][a-z0-9-]{0,63}$").matches(v)) out += Violation("$at.anchor", "anchor must look like #section-id")
                "url" -> {
                    val uri = runCatching { java.net.URI(v) }.getOrNull()
                    val host = uri?.host?.lowercase()
                    // the approved-host list is enforced when a link is added and at publish time (SchemaCommitService, DeploymentProcessor), not
                    // here, so removing a host from the list does not make every later edit of a site fail
                    if (uri == null || uri.scheme != "https" || host == null || uri.userInfo != null || v.length > 500 || v.any { it.isWhitespace() || it.isISOControl() })
                        out += Violation("$at.url", "external links must be plain https:// URLs")
                }
            }
        }
    }

    private fun checkSections(sections: JsonNode, prefix: String, versions: Map<String, com.systemwebstudio.component.ComponentRegistry.Entry>, seen: MutableSet<String>, out: MutableList<Violation>) {
        if (sections.size() > MAX_SECTIONS) out += Violation(prefix, "at most $MAX_SECTIONS sections")
        sections.forEachIndexed { i, s ->
            val at = "$prefix[$i]"
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
                if (def.get("format")?.asString() == "asset" && value.asString().isNotEmpty() && !ASSET_REF.matches(value.asString())) out += Violation(at, "must be an asset reference asset://<id>")
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
