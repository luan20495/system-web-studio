package com.systemwebstudio.template

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The 13 business templates every installation ships with (scope SYSTEM). They are defined in code, not seeded by a migration, so they can
 * not be edited or deleted by anyone, always match the code that serves them, and need no schema change (D-C2-09).
 *
 * Each one is an AppDefinition V2 built ONLY from the approved components (Navbar, Hero, ProductGrid, TechnologySection, ComparisonBlock,
 * Testimonials, ContactForm, Footer): a UI, an unbound data slot (`main`) with a READ query + a WRITE query, a mapping, a view model, a data
 * binding, a create action wired to the form, permission defaults and sample rows. No connector id, no operation key, no URL, no credential:
 * the project that uses the template binds the slot to one of ITS OWN granted data sources.
 *
 * PROVISIONAL: the data / action / workflow declarations follow the C3 / C4 drafts (B-C2-01, B-C2-02).
 */
data class BuiltInTemplate(
    val id: UUID, val key: String, val name: String, val description: String, val category: String, val tags: List<String>, val schema: JsonNode
)

object BusinessTemplates {
    /** category key → label, in the order the library shows them */
    val categories: LinkedHashMap<String, String> = linkedMapOf(
        "crm" to "CRM", "sales" to "Bán hàng", "hr" to "Nhân sự", "project" to "Dự án", "helpdesk" to "Hỗ trợ khách hàng", "inventory" to "Kho",
        "approval" to "Phê duyệt", "marketing" to "Marketing", "cms" to "Quản lý nội dung", "ecommerce" to "Thương mại điện tử",
        "portal" to "Cổng thông tin", "dashboard" to "Báo cáo", "form" to "Biểu mẫu"
    )

    fun idOf(key: String): UUID = UUID.nameUUIDFromBytes("xweb.template.$key".toByteArray(Charsets.UTF_8))

    fun isBuiltIn(id: UUID): Boolean = categories.keys.any { idOf(it) == id }

    fun all(json: JsonMapper): List<BuiltInTemplate> = specs.map { it.build(json) }

    fun find(json: JsonMapper, id: UUID): BuiltInTemplate? = specs.firstOrNull { idOf(it.key) == id }?.build(json)

    private class Spec(
        val key: String, val name: String, val description: String, val tags: List<String>, val brand: String,
        val heroTitle: String, val heroText: String, val cta: String,
        val listHeading: String, val columns: Pair<String, String>, val rows: List<Pair<String, String>>,
        val infoHeading: String, val infoBody: String, val formHeading: String, val submitLabel: String,
        val compare: Boolean = false, val testimonials: Boolean = false, val workflow: Boolean = false
    ) {
        fun build(json: JsonMapper): BuiltInTemplate {
            val items = rows.mapIndexed { i, r -> linkedMapOf("id" to "$key-${i + 1}", "name" to r.first, "description" to r.second) }
            val sections = ArrayList<Any>()
            sections += section("navbar-1", "Navbar", mapOf("brand" to brand))
            sections += section("hero-1", "Hero", mapOf("title" to heroTitle, "description" to heroText, "ctaLabel" to cta))
            sections += section("list-1", "ProductGrid", mapOf("heading" to listHeading, "items" to items))
            sections += section("info-1", "TechnologySection", mapOf("heading" to infoHeading, "body" to infoBody))
            if (compare) sections += section("compare-1", "ComparisonBlock", mapOf(
                "heading" to "So sánh nhanh", "columns" to listOf("Cơ bản", "Nâng cao"),
                "rows" to listOf(mapOf("id" to "r1", "label" to "Tự động hóa", "values" to listOf("Không", "Có")), mapOf("id" to "r2", "label" to "Báo cáo", "values" to listOf("Cơ bản", "Đầy đủ")))))
            if (testimonials) sections += section("voices-1", "Testimonials", mapOf(
                "heading" to "Đội ngũ nói gì", "items" to listOf(mapOf("id" to "t1", "quote" to "Mọi thứ nằm gọn trong một màn hình.", "author" to "Người dùng mẫu"))))
            sections += section("contact-1", "ContactForm", mapOf("heading" to formHeading, "submitLabel" to submitLabel))
            sections += section("footer-1", "Footer", mapOf("text" to "$brand · mẫu do XWEB cung cấp"))

            val list = "list-$key"; val create = "create-$key"; val vm = "$key-vm"; val map = "$key-map"; val submit = "submit-$key"
            val actions = arrayListOf<Any>(
                linkedMapOf("id" to submit, "name" to submitLabel, "type" to "CREATE_RECORD", "queryRef" to create, "permissionRef" to "perm-create",
                    "trigger" to mapOf("sectionId" to "contact-1", "event" to "onSubmit"),
                    "inputs" to listOf(mapOf("name" to "name", "type" to "STRING", "required" to true), mapOf("name" to "note", "type" to "STRING"))),
                linkedMapOf("id" to "go-home", "type" to "NAVIGATE", "pageRef" to "home", "trigger" to mapOf("sectionId" to "hero-1", "event" to "onClick"))
            )
            val doc = linkedMapOf<String, Any>(
                "schemaVersion" to 2, "kind" to "PAGE_SCHEMA", "page" to key, "sections" to sections,
                "dataSources" to listOf(linkedMapOf("id" to "main", "name" to "Nguồn dữ liệu chính", "type" to "rest")),
                "queries" to listOf(
                    linkedMapOf("id" to list, "dataSourceRef" to "main", "maxRows" to 100, "params" to listOf(mapOf("name" to "search", "type" to "STRING"))),
                    linkedMapOf("id" to create, "dataSourceRef" to "main", "mode" to "WRITE",
                        "params" to listOf(mapOf("name" to "name", "type" to "STRING"), mapOf("name" to "note", "type" to "STRING", "required" to false)))),
                "mappings" to listOf(linkedMapOf("id" to map, "queryRef" to list, "fields" to listOf(mapOf("from" to columns.first, "to" to "name"), mapOf("from" to columns.second, "to" to "description")))),
                "viewModels" to listOf(linkedMapOf("id" to vm, "name" to listHeading, "queryRef" to list, "mappingRef" to map,
                    "fields" to listOf(mapOf("name" to "name", "label" to "Tên"), mapOf("name" to "description", "label" to "Mô tả")))),
                "dataBindings" to listOf(linkedMapOf("id" to "bind-list", "sectionId" to "list-1", "prop" to "items", "viewModelRef" to vm)),
                "actions" to actions,
                "permissions" to listOf(
                    linkedMapOf("id" to "perm-read", "permission" to "QUERY_EXECUTE", "resourceType" to "QUERY", "resourceRef" to list),
                    linkedMapOf("id" to "perm-create", "permission" to "ACTION_EXECUTE", "resourceType" to "ACTION", "resourceRef" to submit)),
                "theme" to linkedMapOf("colors" to mapOf("primary" to "#1A73E8", "background" to "#FFFFFF"), "fontFamily" to "SYSTEM", "radius" to "MD"),
                "extensions" to mapOf(TemplateSanitizer.SAMPLE_NAMESPACE to linkedMapOf("contractVersion" to 1,
                    "sampleData" to mapOf(vm to rows.map { mapOf("name" to it.first, "description" to it.second) })))
            )
            if (workflow) {
                actions += linkedMapOf("id" to "notify-team", "name" to "Thông báo cho nhóm", "type" to "NOTIFY",
                    "channel" to "IN_APP", "templateRef" to "tpl-review-request")
                actions += linkedMapOf("id" to "start-review", "name" to "Bắt đầu quy trình", "type" to "START_WORKFLOW", "workflowRef" to "review-flow")
                doc["workflows"] = listOf(linkedMapOf("id" to "review-flow", "name" to "Quy trình xử lý", "trigger" to "ACTION",
                    "steps" to listOf(mapOf("id" to "s1", "actionRef" to "notify-team"))))
            }
            return BuiltInTemplate(idOf(key), key, name, description, key, tags, toNode(json, doc))
        }

        private fun section(id: String, type: String, props: Map<String, Any>) =
            linkedMapOf("id" to id, "type" to type, "componentVersion" to "1.0.0", "props" to props)
    }

    /** Maps / lists / text / numbers / booleans → JsonNode (the specs are plain Kotlin data, so no reflection is involved) */
    internal fun toNode(json: JsonMapper, v: Any?): JsonNode = when (v) {
        is Map<*, *> -> json.createObjectNode().also { o -> v.forEach { (k, x) -> o.set(k as String, toNode(json, x)) } }
        is List<*> -> json.createArrayNode().also { a -> v.forEach { a.add(toNode(json, it)) } }
        is String -> json.createObjectNode().put("v", v).get("v")
        is Int -> json.createObjectNode().put("v", v).get("v")
        is Boolean -> json.createObjectNode().put("v", v).get("v")
        else -> throw IllegalArgumentException("unsupported template value: $v")
    }

    private val specs = listOf(
        Spec("crm", "CRM khách hàng", "Danh sách khách hàng, giai đoạn chăm sóc và biểu mẫu thêm liên hệ mới.", listOf("khách-hàng", "liên-hệ"), "CRM",
            "Quản lý khách hàng", "Theo dõi từng liên hệ và giai đoạn chăm sóc ở một nơi.", "Về trang chủ",
            "Khách hàng gần đây", "company" to "stage", listOf("Công ty An Phát" to "Đang trao đổi", "Cửa hàng Minh Khoa" to "Đã báo giá", "Studio Lam" to "Khách mới"),
            "Cách dùng", "Gắn nguồn dữ liệu khách hàng của bạn vào mẫu này để danh sách tự cập nhật.", "Thêm liên hệ", "Lưu liên hệ", workflow = true),
        Spec("sales", "Bán hàng", "Cơ hội bán hàng theo giai đoạn, bảng so sánh gói và biểu mẫu tạo đơn.", listOf("cơ-hội", "đơn-hàng"), "Sales",
            "Pipeline bán hàng", "Cơ hội đang mở và giá trị dự kiến.", "Về trang chủ",
            "Cơ hội đang mở", "deal" to "value", listOf("Gói triển khai Q4" to "120 triệu", "Gia hạn hợp đồng" to "45 triệu", "Dự án thử nghiệm" to "18 triệu"),
            "Quy trình", "Mỗi cơ hội đi qua các bước: liên hệ, báo giá, chốt.", "Tạo cơ hội", "Tạo cơ hội", compare = true),
        Spec("hr", "Nhân sự", "Hồ sơ nhân viên, yêu cầu nghỉ phép và biểu mẫu gửi đề nghị.", listOf("nhân-viên", "nghỉ-phép"), "HR",
            "Cổng nhân sự", "Hồ sơ, nghỉ phép và thông báo nội bộ.", "Về trang chủ",
            "Nhân viên", "full_name" to "department", listOf("Nguyễn Thu Hà" to "Kế toán", "Trần Quốc Bảo" to "Kỹ thuật", "Lê Ngọc Anh" to "Kinh doanh"),
            "Chính sách", "Tóm tắt chính sách nghỉ phép và quy trình gửi đề nghị.", "Gửi đề nghị nghỉ phép", "Gửi đề nghị", workflow = true),
        Spec("project", "Quản lý dự án", "Danh sách công việc, tiến độ và biểu mẫu thêm việc mới.", listOf("công-việc", "tiến-độ"), "Projects",
            "Bảng công việc", "Việc cần làm, đang làm và đã xong.", "Về trang chủ",
            "Công việc", "title" to "status", listOf("Thiết kế màn hình đăng nhập" to "Đang làm", "Viết tài liệu API" to "Cần làm", "Kiểm thử thanh toán" to "Đã xong"),
            "Quy ước", "Mỗi việc có một người phụ trách và một hạn.", "Thêm công việc", "Thêm việc"),
        Spec("helpdesk", "Hỗ trợ khách hàng", "Danh sách yêu cầu hỗ trợ, mức ưu tiên và biểu mẫu gửi yêu cầu.", listOf("ticket", "hỗ-trợ"), "Helpdesk",
            "Trung tâm hỗ trợ", "Gửi yêu cầu và theo dõi trạng thái xử lý.", "Về trang chủ",
            "Yêu cầu gần đây", "subject" to "priority", listOf("Không đăng nhập được" to "Cao", "Hỏi về hóa đơn" to "Thường", "Đề xuất tính năng" to "Thấp"),
            "Mức ưu tiên", "Yêu cầu mức Cao được xử lý trong 4 giờ làm việc.", "Gửi yêu cầu hỗ trợ", "Gửi yêu cầu", compare = true, workflow = true),
        Spec("inventory", "Quản lý kho", "Tồn kho theo mặt hàng, cảnh báo sắp hết và biểu mẫu nhập kho.", listOf("tồn-kho", "mặt-hàng"), "Inventory",
            "Tồn kho", "Số lượng từng mặt hàng và cảnh báo sắp hết.", "Về trang chủ",
            "Mặt hàng", "sku" to "quantity", listOf("Áo thun trắng" to "120", "Cốc sứ" to "35", "Túi vải" to "8"),
            "Cảnh báo", "Mặt hàng dưới mức tối thiểu được đánh dấu để nhập thêm.", "Nhập kho", "Ghi nhận nhập kho"),
        Spec("approval", "Phê duyệt", "Yêu cầu chờ duyệt, người duyệt và quy trình thông báo.", listOf("phê-duyệt", "quy-trình"), "Approvals",
            "Yêu cầu chờ duyệt", "Gửi yêu cầu, người duyệt nhận thông báo và quyết định.", "Về trang chủ",
            "Chờ duyệt", "request" to "approver", listOf("Mua thiết bị văn phòng" to "Giám đốc", "Công tác Đà Nẵng" to "Trưởng phòng", "Hoàn ứng tháng 9" to "Kế toán trưởng"),
            "Quy trình", "Người gửi → người duyệt → hoàn tất; mỗi bước đều có thông báo.", "Gửi yêu cầu phê duyệt", "Gửi duyệt", workflow = true),
        Spec("marketing", "Marketing", "Chiến dịch, kết quả và biểu mẫu đăng ký nhận tin.", listOf("chiến-dịch", "đăng-ký"), "Marketing",
            "Chiến dịch tháng này", "Theo dõi kênh, ngân sách và kết quả.", "Về trang chủ",
            "Chiến dịch", "campaign" to "channel", listOf("Khuyến mãi mùa thu" to "Email", "Ra mắt sản phẩm mới" to "Mạng xã hội", "Hội thảo trực tuyến" to "Webinar"),
            "Kết quả", "Tổng hợp lượt tiếp cận và đăng ký theo từng kênh.", "Đăng ký nhận tin", "Đăng ký", testimonials = true),
        Spec("cms", "Quản lý nội dung", "Bài viết, trạng thái xuất bản nội dung và biểu mẫu thêm bài.", listOf("bài-viết", "nội-dung"), "Content",
            "Kho nội dung", "Bài viết đang soạn và đã đăng.", "Về trang chủ",
            "Bài viết", "title" to "state", listOf("Hướng dẫn bắt đầu" to "Đã đăng", "Câu chuyện khách hàng" to "Bản nháp", "Thông báo cập nhật" to "Chờ duyệt"),
            "Quy trình biên tập", "Soạn thảo, duyệt, rồi xuất bản.", "Thêm bài viết", "Lưu bài viết", testimonials = true),
        Spec("ecommerce", "Thương mại điện tử", "Danh mục sản phẩm, so sánh gói và biểu mẫu đặt hàng.", listOf("sản-phẩm", "đặt-hàng"), "Shop",
            "Cửa hàng trực tuyến", "Chọn sản phẩm và đặt hàng ngay.", "Về trang chủ",
            "Sản phẩm nổi bật", "product" to "price", listOf("Bình giữ nhiệt" to "290.000đ", "Túi vải canvas" to "120.000đ", "Sổ tay bìa da" to "180.000đ"),
            "Giao hàng", "Giao trong 2-4 ngày; đổi trả trong 7 ngày.", "Đặt hàng", "Đặt hàng", compare = true, testimonials = true),
        Spec("portal", "Cổng thông tin", "Trang thông báo, tài nguyên và biểu mẫu liên hệ cho một cộng đồng.", listOf("thông-báo", "tài-nguyên"), "Portal",
            "Cổng thông tin nội bộ", "Thông báo, tài liệu và đường dẫn hữu ích.", "Về trang chủ",
            "Thông báo", "title" to "date", listOf("Lịch nghỉ lễ" to "01/09", "Cập nhật quy định" to "12/09", "Họp toàn công ty" to "25/09"),
            "Tài nguyên", "Tài liệu hướng dẫn và biểu mẫu thường dùng.", "Gửi góp ý", "Gửi góp ý", testimonials = true),
        Spec("dashboard", "Báo cáo tổng quan", "Các chỉ số chính, bảng so sánh kỳ và biểu mẫu ghi chú.", listOf("chỉ-số", "báo-cáo"), "Dashboard",
            "Tổng quan hôm nay", "Các chỉ số quan trọng nhất của đội ngũ.", "Về trang chủ",
            "Chỉ số chính", "metric" to "value", listOf("Doanh thu tuần" to "1,2 tỷ", "Khách mới" to "86", "Tỷ lệ chuyển đổi" to "3,4%"),
            "Cách đọc", "Mỗi chỉ số so với tuần trước; màu xanh là tăng.", "Thêm ghi chú", "Lưu ghi chú", compare = true),
        Spec("form", "Biểu mẫu", "Một biểu mẫu thu thập thông tin kèm trang xác nhận.", listOf("biểu-mẫu", "khảo-sát"), "Forms",
            "Biểu mẫu thu thập", "Điền thông tin, chúng tôi sẽ phản hồi sớm nhất.", "Về trang chủ",
            "Câu trả lời gần đây", "respondent" to "answer", listOf("Khách A" to "Rất hài lòng", "Khách B" to "Cần cải thiện", "Khách C" to "Hài lòng"),
            "Lưu ý", "Thông tin chỉ dùng để phản hồi yêu cầu của bạn.", "Gửi thông tin", "Gửi")
    )
}
