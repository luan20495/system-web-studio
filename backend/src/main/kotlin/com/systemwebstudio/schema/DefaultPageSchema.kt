package com.systemwebstudio.schema

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Starting page for a new project. Only registry components, only plain data. */
@Component
class DefaultPageSchema(private val json: JsonMapper) {
    fun create(brand: String = "Pure Living"): JsonNode = json.readTree(
        """
        {"page":"product-site","sections":[
          {"id":"navbar-1","type":"Navbar","componentVersion":"1.0.0","props":{"brand":${json.writeValueAsString(brand)},"links":[
            {"id":"l1","label":"Sản phẩm","href":"#products"},{"id":"l2","label":"Liên hệ","href":"#contact"}]}},
          {"id":"hero-1","type":"Hero","componentVersion":"1.0.0","props":{"eyebrow":"Pure living • smart water",
            "title":"Nước sạch mỗi ngày, sống khỏe mỗi ngày.",
            "description":"Giải pháp lọc nước hiện đại cho gia đình Việt — thiết kế tinh gọn, vận hành thông minh và trải nghiệm chăm sóc an tâm.",
            "ctaLabel":"Khám phá sản phẩm"}},
          {"id":"products-1","type":"ProductGrid","componentVersion":"1.0.0","props":{"heading":"Chọn giải pháp phù hợp","items":[
            {"id":"p1","name":"K-Series Pure","description":"Thiết kế tối giản • 10 lõi lọc • Gia đình 2–4 người"},
            {"id":"p2","name":"Smart RO Max","description":"RO thông minh • Theo dõi chất lượng nước"},
            {"id":"p3","name":"Eco Compact","description":"Nhỏ gọn • Tiết kiệm điện • Không gian hiện đại"}]}},
          {"id":"technology-1","type":"TechnologySection","componentVersion":"1.0.0","props":{"heading":"Công nghệ lọc thông minh",
            "body":"Nhiều tầng lọc, cảm biến chất lượng nước và nhắc thay lõi đúng lúc."}},
          {"id":"testimonials-1","type":"Testimonials","componentVersion":"1.0.0","props":{"heading":"Khách hàng nói gì","visible":true,"items":[
            {"id":"t1","author":"Nguyễn Mai","location":"Hà Nội","quote":"Thiết kế đẹp, nước uống ngon và trải nghiệm sử dụng tiện.","rating":5},
            {"id":"t2","author":"Trần Hoàng","location":"Hải Phòng","quote":"Lắp đặt nhanh và sản phẩm phù hợp căn hộ nhỏ.","rating":5}]}},
          {"id":"contact-1","type":"ContactForm","componentVersion":"1.0.0","props":{"heading":"Liên hệ tư vấn","submitLabel":"Gửi yêu cầu"}},
          {"id":"footer-1","type":"Footer","componentVersion":"1.0.0","props":{"text":"© Pure Living"}}
        ]}
        """.trimIndent()
    )
}
