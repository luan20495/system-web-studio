package com.systemwebstudio.app.definition

import com.systemwebstudio.schema.PageSchemaValidator
import tools.jackson.databind.JsonNode

/*
 * Canonical READ VIEWS of an AppDefinitionV2 (D-C2-02).
 *
 * The target shape lists pages, navigation, components and assets next to the data / action / publish members. In the stored document they
 * already exist as the Page Schema (home page = root `sections`, extra pages = `pages[]`, `site.navigation`, section types, `asset://` props)
 * and are validated by PageSchemaValidator, so they are NOT stored a second time: a stored copy could only drift. These functions compute
 * the canonical view from the legacy part; nothing here writes, so legacy JSON round-trips exactly.
 */

/** A page of the app. The home page is the root `sections` of the document (id "home", slug ""), every other page is an entry of `pages[]`. */
data class PageDef(val id: String, val slug: String, val title: String?, val home: Boolean, val sections: List<SectionRef>)

/** A link of the site navigation: to a page of the app (`pageId`), to an anchor of the page, or to an approved https URL. */
data class NavItemDef(val id: String, val label: String, val pageId: String?, val anchor: String?, val url: String?)

/** A component type used by the app, in order of first use, with the sections that use it. */
data class ComponentUseDef(val type: String, val version: String?, val sectionIds: List<String>)

/** An asset (`asset://<uuid>`) referenced by section props, with the sections that reference it. */
data class AssetUseDef(val assetId: String, val sectionIds: List<String>)

/** C4's `ActionRef(id, trigger, actionId)` (action-workflow contract): "when [trigger] (`sectionId.event`) fires, run [actionId]". */
data class ActionRefDef(val id: String, val trigger: String, val actionId: String)

private fun JsonNode.textOrNull(key: String): String? = get(key)?.takeIf { it.isString }?.asString()

fun AppDefinitionV2.pages(): List<PageDef> {
    val sections = sections().groupBy { it.pageId }
    val site = pageSchema["site"]
    val homeTitle = site?.get("home")?.textOrNull("title") ?: site?.textOrNull("title") ?: pageSchema["page"]?.takeIf { it.isString }?.asString()
    val out = ArrayList<PageDef>()
    out += PageDef("home", "", homeTitle, true, sections["home"].orEmpty())
    val extra = pageSchema["pages"]
    if (extra != null && extra.isArray) extra.forEach { p ->
        if (!p.isObject) return@forEach
        val id = p.textOrNull("id") ?: return@forEach
        out += PageDef(id, p.textOrNull("slug") ?: id, p.textOrNull("title"), false, sections[id].orEmpty())
    }
    return out
}

fun AppDefinitionV2.navigation(): List<NavItemDef> {
    val nav = pageSchema["site"]?.get("navigation")
    if (nav == null || !nav.isArray) return emptyList()
    return nav.mapNotNull { n ->
        if (!n.isObject) return@mapNotNull null
        val id = n.textOrNull("id") ?: return@mapNotNull null
        NavItemDef(id, n.textOrNull("label") ?: "", n.textOrNull("pageId"), n.textOrNull("anchor"), n.textOrNull("url"))
    }
}

fun AppDefinitionV2.components(): List<ComponentUseDef> {
    val order = LinkedHashMap<String, Pair<String?, MutableList<String>>>()
    for (s in sections()) {
        val type = s.type ?: continue
        order.getOrPut(type + "@" + (s.componentVersion ?: "")) { s.componentVersion to ArrayList() }.second.add(s.id)
    }
    return order.map { (key, value) -> ComponentUseDef(key.substringBefore('@'), value.first, value.second) }
}

fun AppDefinitionV2.assets(): List<AssetUseDef> {
    val found = LinkedHashMap<String, MutableList<String>>()
    fun walk(node: JsonNode?, sectionId: String) {
        if (node == null) return
        when {
            node.isString -> node.asString().takeIf { PageSchemaValidator.ASSET_REF.matches(it) }?.let { found.getOrPut(it.removePrefix("asset://")) { ArrayList() }.let { l -> if (sectionId !in l) l.add(sectionId) } }
            node.isArray -> node.forEach { walk(it, sectionId) }
            node.isObject -> node.propertyNames().forEach { walk(node.get(it), sectionId) }
        }
    }
    fun sectionsOf(list: JsonNode?) {
        if (list == null || !list.isArray) return
        list.forEach { s -> if (s.isObject) walk(s.get("props"), s.textOrNull("id") ?: "?") }
    }
    sectionsOf(pageSchema["sections"])
    pageSchema["pages"]?.takeIf { it.isArray }?.forEach { p -> if (p.isObject) sectionsOf(p.get("sections")) }
    return found.map { (id, sections) -> AssetUseDef(id, sections) }
}

/** the triggers of the declared actions in C4's ActionRef form; an action without a UI trigger (started by a workflow) has none */
fun AppDefinitionV2.actionRefs(): List<ActionRefDef> =
    actions.mapNotNull { a -> a.trigger?.let { ActionRefDef(a.id + "-ref", it.sectionId + "." + it.event, a.id) } }
