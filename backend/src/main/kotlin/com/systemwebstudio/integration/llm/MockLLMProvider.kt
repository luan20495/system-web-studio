package com.systemwebstudio.integration.llm

import com.systemwebstudio.schema.SchemaOperation
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.text.Normalizer

/** Deterministic keyword planner for local use and tests. Not an AI model. */
@Component
class MockLLMProvider(private val json: JsonMapper) : LLMProvider {
    override val name = "mock"

    private fun JsonNode.sections(type: String) = get("sections").filter { it.get("type").asString() == type }

    override fun plan(request: LLMRequest): LLMResponse {
        val text = Normalizer.normalize(request.prompt, Normalizer.Form.NFC).lowercase()
        val page = request.pageSchema
        val ops = ArrayList<SchemaOperation>()
        val notes = ArrayList<String>()
        var matched = false

        if (text.contains("so sánh") || text.contains("compare")) {
            matched = true
            if (page.sections("ComparisonBlock").isEmpty()) {
                val products = page.sections("ProductGrid").firstOrNull()?.get("props")?.get("items")?.toList().orEmpty()
                val rows = json.createArrayNode().apply {
                    add(json.createObjectNode().apply {
                        put("id", "r1"); put("label", "Phù hợp")
                        set("values", json.createArrayNode().apply { products.take(3).forEach { add(it.get("description")?.asString() ?: "") } })
                    })
                }
                val columns = json.createArrayNode().apply { products.take(3).forEach { add(it.get("name").asString()) } }
                val before = page.sections("Testimonials").firstOrNull() ?: page.sections("ContactForm").firstOrNull() ?: page.sections("Footer").firstOrNull()
                ops += SchemaOperation(
                    type = "ADD_SECTION", sectionType = "ComparisonBlock", sectionId = "comparison-1",
                    beforeSectionId = before?.get("id")?.asString(),
                    props = json.createObjectNode().apply { put("heading", "So sánh sản phẩm"); set("columns", columns); set("rows", rows) }
                )
                notes += "Đã thêm ComparisonBlock từ thư viện" + (before?.let { " trước phần ${it.get("type").asString()}" } ?: "") + "."
            } else notes += "Trang đã có bảng so sánh."
        }

        val testimonials = page.sections("Testimonials").firstOrNull()
        if (text.contains("đánh giá")) {
            val id = testimonials?.get("id")?.asString()
            val visible = testimonials?.get("props")?.get("visible")?.asBoolean(true) ?: false
            when {
                text.contains("xóa") || text.contains("xoá") -> {
                    matched = true
                    if (id != null) { ops += SchemaOperation(type = "REMOVE_SECTION", sectionId = id); notes += "Đã xóa phần đánh giá." }
                    else notes += "Không có phần đánh giá để xóa."
                }
                text.contains("bỏ") || text.contains("ẩn") -> {
                    matched = true
                    if (id != null && visible) { ops += SchemaOperation(type = "UPDATE_PROP", sectionId = id, path = "visible", value = json.valueToTree<JsonNode>(false)); notes += "Đã ẩn phần đánh giá." }
                    else notes += "Phần đánh giá đã ẩn hoặc không tồn tại."
                }
                text.contains("hiện") || text.contains("hiển thị") || text.contains("thêm") -> {
                    matched = true
                    when {
                        id == null -> {
                            val before = page.sections("ContactForm").firstOrNull() ?: page.sections("Footer").firstOrNull()
                            ops += SchemaOperation(type = "ADD_SECTION", sectionType = "Testimonials", sectionId = "testimonials-1", beforeSectionId = before?.get("id")?.asString(),
                                props = json.readTree("""{"heading":"Khách hàng nói gì","visible":true,"items":[]}"""))
                            notes += "Đã thêm phần đánh giá."
                        }
                        !visible -> { ops += SchemaOperation(type = "UPDATE_PROP", sectionId = id, path = "visible", value = json.valueToTree<JsonNode>(true)); notes += "Đã hiện phần đánh giá." }
                        else -> notes += "Phần đánh giá đang hiển thị."
                    }
                }
            }
        }

        val grid = page.sections("ProductGrid").firstOrNull()
        if (text.contains("thêm") && text.contains("sản phẩm") && !text.contains("so sánh") && grid != null) {
            matched = true
            val items = grid.get("props").get("items")
            val ids: Set<String> = items.toList().map { it.get("id").asString() }.toSet()
            var n = items.size() + 1
            while ("p$n" in ids) n++
            val name = if (items.toList().any { it.get("name").asString() == "Ultra Fresh" }) "Ultra Fresh $n" else "Ultra Fresh"
            ops += SchemaOperation(
                type = "ADD_ITEM", sectionId = grid.get("id").asString(),
                item = json.createObjectNode().apply { put("id", "p$n"); put("name", name); put("description", "Thiết kế mới • Khoáng tự nhiên • Phù hợp gia đình hiện đại") }
            )
            notes += "Đã thêm sản phẩm \"$name\"."
        }

        if (text.contains("rút gọn") && text.contains("hero")) {
            matched = true
            val hero = page.sections("Hero").firstOrNull()
            if (hero != null) {
                val short = "Nước sạch. Sống khỏe."
                if (hero.get("props").get("title").asString() != short) {
                    ops += SchemaOperation(type = "UPDATE_PROP", sectionId = hero.get("id").asString(), path = "title", value = json.valueToTree<JsonNode>(short))
                    ops += SchemaOperation(type = "UPDATE_PROP", sectionId = hero.get("id").asString(), path = "description", value = json.valueToTree<JsonNode>("Giải pháp lọc nước hiện đại cho gia đình Việt."))
                    notes += "Đã rút gọn tiêu đề hero."
                } else notes += "Hero đã ngắn gọn."
            } else notes += "Trang chưa có hero."
        }

        return when {
            !matched -> LLMResponse("UNSUPPORTED", emptyList(), "Mock LLM chưa hiểu yêu cầu này; nội dung không thay đổi.")
            ops.isEmpty() -> LLMResponse("NO_CHANGE", emptyList(), notes.joinToString(" "))
            else -> LLMResponse("EDIT_PAGE", ops, notes.joinToString(" "))
        }
    }
}
