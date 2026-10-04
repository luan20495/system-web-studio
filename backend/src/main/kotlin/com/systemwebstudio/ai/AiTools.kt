package com.systemwebstudio.ai

import com.systemwebstudio.access.AccessContext
import com.systemwebstudio.access.Permission
import com.systemwebstudio.code.CodeChangePolicy
import com.systemwebstudio.code.CodeProjectService
import com.systemwebstudio.component.ComponentRegistry
import com.systemwebstudio.integration.git.GitFileChange
import com.systemwebstudio.integration.llm.AiTools
import com.systemwebstudio.integration.llm.BlockInfo
import com.systemwebstudio.integration.llm.ToolRefused
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Controlled tool calling (ADR 0014 §tools). The model can only NAME a tool and pass arguments; the server validates the arguments, runs
 * the tool with the prompting user's permissions on the prompt's own project, and records every call in ai_tool_calls (arguments without
 * file contents, result size). Tools read approved catalogs or the user's own project; the only "write" tool (propose_file_change) just
 * stages a file that then goes through the normal change policy, build sandbox and review. No tool runs code, SQL or network requests.
 */
@Service
class AiToolbox(private val jdbc: JdbcTemplate, private val json: JsonMapper, private val registry: ComponentRegistry, private val code: CodeProjectService) {
    /** ids the tools returned during one prompt, so reuse claims can be verified */
    class Session { val templates = mutableSetOf<String>(); val blocks = mutableSetOf<String>(); val proposed = mutableListOf<GitFileChange>() }

    private fun str(args: JsonNode, name: String, max: Int, required: Boolean = true): String? {
        val v = args.get(name)
        if (v == null || v.isNull) { if (required) throw ToolRefused("INVALID", "argument '$name' is required"); return null }
        if (!v.isString) throw ToolRefused("INVALID", "argument '$name' must be a string")
        return v.asString().take(max)
    }

    private fun record(promptId: UUID, ctx: AccessContext, tool: String, args: Any, outcome: String, chars: Int) {
        jdbc.update("INSERT INTO ai_tool_calls (id, prompt_id, user_id, project_id, tool, arguments, outcome, result_chars) VALUES (?,?,?,?,?,CAST(? AS jsonb),?,?)",
            UUID.randomUUID(), promptId, ctx.userId, ctx.project!!.id, tool.take(40), json.writeValueAsString(args), outcome, chars)
    }

    /** Approved blocks (contributed presets of registry components), newest approvals first: retrieval step 2 after the registry. */
    fun approvedBlocks(limit: Int = 30): List<BlockInfo> = jdbc.query("""SELECT p.id, p.name, v.base_component, v.props::text FROM component_packages p
        JOIN component_package_versions v ON v.package_id = p.id AND v.version = p.approved_version
        WHERE p.approved_version IS NOT NULL AND p.status <> 'DEPRECATED' ORDER BY v.decided_at DESC NULLS LAST LIMIT ?""",
        { rs, _ -> BlockInfo(rs.getObject(1, UUID::class.java).toString(), rs.getString(2), rs.getString(3), json.readTree(rs.getString(4))) }, limit)

    private fun like(q: String) = "%" + q.trim().lowercase().replace("%", "\\%").replace("_", "\\_") + "%"

    private fun searchComponent(args: JsonNode, s: Session): String {
        val q = str(args, "query", 80)!!.trim().lowercase()
        val comps = registry.list().filter { it.status == "ACTIVE" && (q.isEmpty() || q in it.id.lowercase() || q in it.category.lowercase()) }.take(10)
            .map { mapOf("source" to "registry", "id" to it.id, "category" to it.category, "version" to it.latestVersion) }
        val blocks = jdbc.query("""SELECT p.id, p.name, p.description, v.base_component, v.props::text FROM component_packages p
            JOIN component_package_versions v ON v.package_id = p.id AND v.version = p.approved_version
            WHERE p.approved_version IS NOT NULL AND p.status <> 'DEPRECATED' AND (lower(p.name) LIKE ? OR lower(p.description) LIKE ? OR lower(v.base_component) LIKE ?) LIMIT 10""",
            { rs, _ -> mapOf("source" to "block", "id" to rs.getObject(1, UUID::class.java).toString(), "name" to rs.getString(2), "description" to rs.getString(3),
                "component" to rs.getString(4), "props" to json.readTree(rs.getString(5))) }, like(q), like(q), like(q))
        blocks.forEach { s.blocks += it["id"] as String }
        return json.writeValueAsString(mapOf("components" to comps, "blocks" to blocks))
    }

    private fun searchTemplate(args: JsonNode, ctx: AccessContext, s: Session): String {
        val q = str(args, "query", 80)!!
        val rows = jdbc.query("""SELECT id, name, description, schema::text FROM templates WHERE status = 'ACTIVE' AND (visibility = 'COMPANY' OR author_id = ?)
            AND (lower(name) LIKE ? OR lower(description) LIKE ?) ORDER BY updated_at DESC LIMIT 5""", { rs, _ ->
            val schema = json.readTree(rs.getString(4))
            mapOf("id" to rs.getObject(1, UUID::class.java).toString(), "name" to rs.getString(2), "description" to rs.getString(3),
                "sections" to schema.get("sections")?.toList().orEmpty().map { sec -> mapOf("type" to sec.get("type")?.asString(), "props" to sec.get("props")) })
        }, ctx.userId, like(q), like(q))
        rows.forEach { s.templates += it["id"] as String }
        return json.writeValueAsString(rows).take(8000)
    }

    /** Connector metadata (names, operations, schemas; never credentials). Empty until a connector catalog exists on this server. */
    private fun searchConnectors(args: JsonNode): String {
        val q = str(args, "query", 80)!!
        val exists = jdbc.queryForObject("SELECT to_regclass('public.connectors') IS NOT NULL", Boolean::class.java) == true
        if (!exists) return """{"connectors":[],"note":"no connector catalog on this server"}"""
        val rows = jdbc.queryForList("""SELECT key, name, description, operations FROM connectors WHERE status = 'APPROVED' AND (lower(name) LIKE ? OR lower(description) LIKE ?) LIMIT 10""", like(q), like(q))
        return json.writeValueAsString(mapOf("connectors" to rows)).take(8000)
    }

    private fun wrap(promptId: UUID, ctx: AccessContext, allowed: Set<String>, run: (String, JsonNode) -> Pair<String, Any>): (String, JsonNode) -> String = { name, args ->
        if (name !in allowed) { record(promptId, ctx, name, mapOf<String, Any>(), "DENIED", 0); throw ToolRefused("DENIED", "tool '$name' is not available here") }
        try {
            val (result, logged) = run(name, args)
            record(promptId, ctx, name, logged, "OK", result.length); result
        } catch (e: ToolRefused) { record(promptId, ctx, name, mapOf("error" to e.message), e.outcome, 0); throw e }
        catch (e: Exception) { record(promptId, ctx, name, mapOf<String, Any>(), "ERROR", 0); throw ToolRefused("ERROR", "tool failed") }
    }

    /** Page prompts: catalogs and the current page. */
    fun forPage(ctx: AccessContext, promptId: UUID, page: JsonNode, s: Session): AiTools {
        ctx.require(Permission.PROJECT_EDIT)
        val run = wrap(promptId, ctx, setOf("search_component", "search_template", "read_project_schema", "search_connector_metadata")) { name, args ->
            when (name) {
                "search_component" -> searchComponent(args, s) to mapOf("query" to args.get("query")?.asString())
                "search_template" -> searchTemplate(args, ctx, s) to mapOf("query" to args.get("query")?.asString())
                "read_project_schema" -> json.writeValueAsString(page) to mapOf<String, Any>()
                else -> searchConnectors(args) to mapOf("query" to args.get("query")?.asString())
            }
        }
        return AiTools("""Available tools:
- search_component {"query": string}: approved company components and approved blocks matching the query
- search_template {"query": string}: approved company templates (and your own) with their sections
- read_project_schema {}: the current page JSON
- search_connector_metadata {"query": string}: approved data connectors (metadata only)""", run)
    }

    /** Code prompts: read files of this project, stage file changes, catalogs. */
    fun forCode(ctx: AccessContext, promptId: UUID, s: Session): AiTools {
        ctx.require(Permission.PROJECT_EDIT)
        val repo = code.repo(ctx.project!!.id)
        val run = wrap(promptId, ctx, setOf("search_component", "read_project_file", "propose_file_change", "search_connector_metadata")) { name, args ->
            when (name) {
                "search_component" -> searchComponent(args, s) to mapOf("query" to args.get("query")?.asString())
                "read_project_file" -> {
                    val path = str(args, "path", 200)!!.trim()
                    if (!Regex("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$").matches(path) || path.split('/').any { it == ".." }) throw ToolRefused("INVALID", "invalid path")
                    val bytes = code.git { code.client.raw(repo.name, path, "main") } ?: throw ToolRefused("INVALID", "no such file")
                    if (bytes.size > 40_000) throw ToolRefused("INVALID", "file too large to read")
                    bytes.toString(Charsets.UTF_8) to mapOf("path" to path)
                }
                "propose_file_change" -> {
                    val path = str(args, "path", 200)!!.trim(); val content = str(args, "content", CodeChangePolicy.MAX_FILE_BYTES)!!
                    val change = GitFileChange(path, content.toByteArray(Charsets.UTF_8))
                    try { CodeChangePolicy.check(listOf(change), server = ctx.project!!.appKind in com.systemwebstudio.code.CodeProjectService.SERVER_KINDS) } catch (e: Exception) { throw ToolRefused("DENIED", e.message ?: "not allowed") }
                    if (s.proposed.size >= 10) throw ToolRefused("DENIED", "at most 10 files per answer")
                    s.proposed.removeIf { it.path == path }; s.proposed += change
                    "staged $path (${content.length} chars); it will be built and checked like any change" to mapOf("path" to path, "chars" to content.length)
                }
                else -> searchConnectors(args) to mapOf("query" to args.get("query")?.asString())
            }
        }
        return AiTools("""Available tools:
- search_component {"query": string}: approved company components and blocks
- read_project_file {"path": string}: a file of this project's main branch (files not included above)
- propose_file_change {"path": string, "content": string}: stage a COMPLETE file; staged files are added to your final "files"
- search_connector_metadata {"query": string}: approved data connectors (metadata only)""", run)
    }
}
