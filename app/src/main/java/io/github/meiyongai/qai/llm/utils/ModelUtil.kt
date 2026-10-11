package io.github.meiyongai.qai.llm.utils

import io.github.meiyongai.qai.llm.config.LlmConfig
import org.json.JSONObject
import java.util.Locale

object ModelUtil {

    private val nonTextGenerationKeywords = listOf(
        "embed", "embedding", "rerank", "tts", "whisper", "speech", "audio", "image", "dall-e", "flux", "sora", "video"
    )

    fun isTextGenerationModel(model: String?): Boolean {
        if (model.isNullOrBlank()) return true
        val normalized = model.trim().lowercase(Locale.ROOT)
        return nonTextGenerationKeywords.none { normalized.contains(it) }
    }

    fun supportsTemperature(model: String?): Boolean {
        if (model.isNullOrBlank()) return true
        val lower = model.lowercase(Locale.ROOT)
        return !lower.startsWith("o1") && !lower.startsWith("o3")
    }

    fun isReasoningModel(model: String?): Boolean {
        if (model.isNullOrBlank()) return false
        val lower = model.lowercase(Locale.ROOT)
        return lower.startsWith("o1") || lower.startsWith("o3") || 
               lower.contains("r1") || lower.contains("reasoner") || lower.contains("deepseek-r1")
    }

    fun applyReasoningParameters(requestJson: JSONObject, baseUrl: String, model: String?, effort: Int = LlmConfig.REASONING_OFF) {
        if (effort <= LlmConfig.REASONING_OFF) {
            // 当推理关闭时，普通模型绝不能传入 reasoning_effort，否则大部分 API 会报错 400
            // 只有当模型本身是已知推理模型时，才允许传入 "none" 以显式关闭思考
            if (isReasoningModel(model)) {
                val lowerUrl = baseUrl.lowercase(Locale.ROOT)
                if (lowerUrl.contains("openrouter.ai")) {
                    requestJson.put("reasoning", JSONObject().put("effort", "none"))
                } else {
                    requestJson.put("reasoning_effort", "none")
                }
            }
            return
        }

        val effortStr = when (effort) {
            LlmConfig.REASONING_LOW -> "low"
            LlmConfig.REASONING_HIGH -> "high"
            else -> "medium"
        }
        val lowerUrl = baseUrl.lowercase(Locale.ROOT)
        if (lowerUrl.contains("openrouter.ai")) {
            requestJson.put("reasoning", JSONObject().put("effort", effortStr))
        } else {
            requestJson.put("reasoning_effort", effortStr)
        }
    }
}
