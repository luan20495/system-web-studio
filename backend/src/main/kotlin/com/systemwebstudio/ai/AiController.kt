package com.systemwebstudio.ai

import com.systemwebstudio.integration.llm.AiModel
import com.systemwebstudio.integration.llm.AiService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class AiStatus(
    val provider: String, val configured: Boolean, val defaultModel: String, val dailyLimitPerUser: Long,
    val models: List<AiModel>, val dataNotice: String
)

/** Tells the UI which AI is active and which (free) models may be chosen. Never exposes the key, only whether one is set. */
@RestController
@RequestMapping("/api/v1/ai")
class AiController(private val ai: AiService) {
    @GetMapping("/status")
    fun status(): AiStatus = AiStatus(
        provider = if (ai.externalEnabled) "openrouter" else "mock",
        configured = ai.externalEnabled,
        defaultModel = if (ai.externalEnabled) "auto" else "mock",
        dailyLimitPerUser = ai.dailyLimitPerUser,
        models = if (ai.externalEnabled) ai.freeModels() else emptyList(),
        dataNotice = if (ai.externalEnabled) "Yêu cầu của bạn, nội dung trang hiện tại và danh sách component được gửi tới OpenRouter và nhà cung cấp model miễn phí đã chọn."
        else "Chưa cấu hình OPENROUTER_API_KEY: đang dùng bộ mô phỏng (không gửi dữ liệu ra ngoài)."
    )
}
