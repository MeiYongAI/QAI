package io.github.meiyongai.qai.llm.preset

object PresetRegistry {
    const val GEMINI = 0
    const val OPENAI = 1
    const val OPENROUTER = 2
    const val DEEPSEEK = 3
    const val CUSTOM = 4

    private val presetBaseUrls = mapOf(
        GEMINI to "https://generativelanguage.googleapis.com/v1beta/openai",
        OPENAI to "https://api.openai.com/v1",
        OPENROUTER to "https://openrouter.ai/api/v1",
        DEEPSEEK to "https://api.deepseek.com/v1",
        CUSTOM to ""
    )

    private val defaultModels = mapOf(
        GEMINI to "gemini-3.5-flash",
        OPENAI to "gpt-5-mini",
        OPENROUTER to "openai/gpt-5-mini",
        DEEPSEEK to "deepseek-chat",
        CUSTOM to ""
    )

    fun getPresetBaseUrl(preset: Int): String {
        return presetBaseUrls[preset] ?: ""
    }

    fun getDefaultModel(preset: Int): String {
        return defaultModels[preset] ?: ""
    }
}
