package com.systemwebstudio.ai

import com.systemwebstudio.integration.llm.AiModel
import com.systemwebstudio.integration.llm.AiProviderRegistry
import com.systemwebstudio.integration.llm.AiService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ProviderStatus(val id: String, val name: String, val paid: Boolean, val models: List<AiModel>, val dataNotice: String)
data class AiStatus(
    val provider: String, val configured: Boolean, val defaultModel: String, val dailyLimitPerUser: Long,
    /** every model the user may pick (OpenRouter free first) */
    val models: List<AiModel>, val dataNotice: String,
    /** the same models grouped by provider, with what each provider receives */
    val providers: List<ProviderStatus> = emptyList()
)

/** Tells the UI which AI is active and which models may be chosen. Never exposes a key, only whether one is set. */
@RestController
@RequestMapping("/api/v1/ai")
class AiController(private val ai: AiService, private val registry: AiProviderRegistry) {
    @GetMapping("/status")
    fun status(): AiStatus {
        val models = ai.catalog()
        val sent = "Yêu cầu của bạn, nội dung trang hiện tại và danh sách component"
        val providers = models.groupBy { it.provider }.map { (id, list) ->
            val name = if (id == "openrouter") "OpenRouter (model miễn phí)" else registry.providers[id]?.displayName ?: id
            ProviderStatus(id, name, list.any { it.paid }, list, when (id) {
                "openrouter" -> "$sent được gửi tới OpenRouter và nhà cung cấp model miễn phí đã chọn."
                "local" -> "$sent được gửi tới máy chủ model nội bộ ${registry.providers[id]?.endpointHost ?: ""}."
                else -> "$sent được gửi tới $name (tính phí theo hợp đồng của công ty)."
            })
        }
        val any = models.isNotEmpty()
        return AiStatus(
            provider = if (ai.externalEnabled) "openrouter" else if (any) "providers" else "mock",
            configured = any,
            defaultModel = if (ai.externalEnabled) "auto" else "mock",
            dailyLimitPerUser = ai.dailyLimitPerUser,
            models = models,
            dataNotice = when {
                ai.externalEnabled -> "$sent được gửi tới OpenRouter và nhà cung cấp model miễn phí đã chọn."
                any -> "Chế độ tự động dùng bộ mô phỏng; model của các nhà cung cấp khác chỉ dùng khi bạn chọn rõ."
                else -> "Chưa cấu hình nhà cung cấp AI nào: đang dùng bộ mô phỏng (không gửi dữ liệu ra ngoài)."
            },
            providers = providers
        )
    }
}
