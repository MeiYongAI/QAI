package io.github.meiyongai.qai.llm.config

import android.content.Context
import android.content.SharedPreferences
import io.github.meiyongai.qai.llm.model.Role
import io.github.meiyongai.qai.llm.preset.PresetRegistry
import io.github.meiyongai.qai.llm.utils.UrlNormalizer
import org.json.JSONArray
import org.json.JSONObject

object LlmConfig {
    private const val PREF_NAME = "qai_settings"

    private const val KEY_PRESET = "qai_provider_preset"
    private const val KEY_CUSTOM_BASE_URL = "qai_custom_base_url"
    private const val KEY_TEMPERATURE = "qai_temperature"
    private const val KEY_REASONING_EFFORT = "qai_reasoning_effort"

    // 角色管理配置项
    private const val KEY_SELECTED_ROLE_ID = "qai_selected_role_id"
    private const val KEY_CUSTOM_ROLES = "qai_custom_roles_json"

    // 引用发送配置项
    private const val KEY_QUOTE_ON_SEND = "qai_quote_on_send"

    // 上下文消息条数与排除项配置
    private const val KEY_CONTEXT_COUNT = "qai_context_count"
    private const val KEY_EXCLUDE_IMAGE = "qai_exclude_image"
    private const val KEY_EXCLUDE_EMOJI = "qai_exclude_emoji"
    private const val KEY_EXCLUDE_CARD = "qai_exclude_card"
    private const val KEY_EXCLUDE_VOICE = "qai_exclude_voice"

    // 在线模型缓存
    private const val KEY_CACHED_MODELS_PREFIX = "qai_cached_models_"

    const val DEFAULT_CONTEXT_COUNT = 5
    const val MAX_CONTEXT_COUNT = 100

    const val REASONING_OFF = 0
    const val REASONING_LOW = 1
    const val REASONING_MEDIUM = 2
    const val REASONING_HIGH = 3

    private const val KEY_MULTI_CHOICE_ENABLED = "qai_multi_choice_enabled"

    val BUILTIN_ROLES = listOf(
        Role(
            id = "assistant",
            name = "默认助手",
            prompt = "你是一个QQ聊天智能辅助助手，请用简练友好的语言回答。适合直接作为聊天回复。",
            isBuiltIn = true
        )
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getSelectedPreset(context: Context): Int {
        val p = getPrefs(context).getInt(KEY_PRESET, PresetRegistry.DEEPSEEK)
        return if (p in PresetRegistry.GEMINI..PresetRegistry.CUSTOM) p else PresetRegistry.DEEPSEEK
    }

    fun setSelectedPreset(context: Context, preset: Int) {
        val safePreset = if (preset in PresetRegistry.GEMINI..PresetRegistry.CUSTOM) preset else PresetRegistry.DEEPSEEK
        getPrefs(context).edit().putInt(KEY_PRESET, safePreset).apply()
    }

    fun getEffectiveBaseUrl(context: Context, preset: Int): String {
        val safePreset = if (preset in PresetRegistry.GEMINI..PresetRegistry.CUSTOM) preset else PresetRegistry.DEEPSEEK
        return if (safePreset == PresetRegistry.CUSTOM) {
            val url = getPrefs(context).getString(KEY_CUSTOM_BASE_URL, "") ?: ""
            UrlNormalizer.normalizeBaseUrl(url)
        } else {
            val defaultUrl = PresetRegistry.getPresetBaseUrl(safePreset)
            if (defaultUrl.isNotBlank()) defaultUrl else PresetRegistry.getPresetBaseUrl(PresetRegistry.DEEPSEEK)
        }
    }

    fun isValidBaseUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
    }

    fun setCustomBaseUrl(context: Context, url: String) {
        val normalized = UrlNormalizer.normalizeBaseUrl(url)
        getPrefs(context).edit().putString(KEY_CUSTOM_BASE_URL, normalized).apply()
    }

    fun getCustomBaseUrl(context: Context): String {
        return getPrefs(context).getString(KEY_CUSTOM_BASE_URL, "") ?: ""
    }

    fun getApiKey(context: Context, preset: Int): String {
        val keyName = "qai_api_key_$preset"
        return getPrefs(context).getString(keyName, "")?.trim() ?: ""
    }

    fun getFirstApiKey(context: Context, preset: Int): String {
        val raw = getApiKey(context, preset)
        if (raw.isBlank()) return ""
        return raw.split(",")
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() } ?: ""
    }

    fun setApiKey(context: Context, preset: Int, key: String) {
        val keyName = "qai_api_key_$preset"
        getPrefs(context).edit().putString(keyName, key.trim()).apply()
    }

    fun getModel(context: Context, preset: Int): String {
        val keyName = "qai_model_$preset"
        val saved = getPrefs(context).getString(keyName, "")?.trim() ?: ""
        if (saved == "gpt-4o-mini" && preset == PresetRegistry.CUSTOM) {
            return ""
        }
        return if (saved.isNotBlank()) saved else PresetRegistry.getDefaultModel(preset)
    }

    fun setModel(context: Context, preset: Int, model: String) {
        val keyName = "qai_model_$preset"
        getPrefs(context).edit().putString(keyName, model.trim()).apply()
    }

    // 缓存拉取到的在线模型列表
    fun getCachedModels(context: Context, preset: Int): List<String> {
        val key = KEY_CACHED_MODELS_PREFIX + preset
        val raw = getPrefs(context).getString(key, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setCachedModels(context: Context, preset: Int, models: List<String>) {
        val key = KEY_CACHED_MODELS_PREFIX + preset
        val arr = JSONArray(models)
        getPrefs(context).edit().putString(key, arr.toString()).apply()
    }

    // 角色管理
    fun getAllRoles(context: Context): List<Role> {
        val result = ArrayList<Role>(BUILTIN_ROLES)
        val raw = getPrefs(context).getString(KEY_CUSTOM_ROLES, "") ?: ""
        if (raw.isNotBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    result.add(Role.fromJson(arr.getJSONObject(i)))
                }
            } catch (ignored: Exception) {
            }
        }
        return result
    }

    fun getCustomRoles(context: Context): List<Role> {
        val raw = getPrefs(context).getString(KEY_CUSTOM_ROLES, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<Role>()
            for (i in 0 until arr.length()) {
                list.add(Role.fromJson(arr.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveCustomRoles(context: Context, list: List<Role>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        getPrefs(context).edit().putString(KEY_CUSTOM_ROLES, arr.toString()).apply()
    }

    fun getSelectedRole(context: Context): Role {
        val selectedId = getPrefs(context).getString(KEY_SELECTED_ROLE_ID, "assistant") ?: "assistant"
        val all = getAllRoles(context)
        return all.firstOrNull { it.id == selectedId } ?: BUILTIN_ROLES.first()
    }

    fun setSelectedRoleId(context: Context, roleId: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_ROLE_ID, roleId).apply()
    }

    fun saveCustomRole(context: Context, role: Role) {
        val list = getCustomRoles(context).toMutableList()
        val index = list.indexOfFirst { it.id == role.id }
        if (index >= 0) {
            list[index] = role
        } else {
            list.add(role)
        }
        saveCustomRoles(context, list)
    }

    fun deleteCustomRole(context: Context, roleId: String) {
        val list = getCustomRoles(context).toMutableList()
        list.removeAll { it.id == roleId }
        saveCustomRoles(context, list)
        if (getSelectedRole(context).id == roleId) {
            setSelectedRoleId(context, "assistant")
        }
    }

    /**
     * 获取当前生效的系统提示词。
     *
     * 当多项候选回复模式开启时，在基础角色 Prompt 的基础上附加生成 3 条不同切入点候选回复的结构化约束指令；
     * 否则直接使用当前选中角色的基础 Prompt。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     String: 组合后的完整系统提示词字符串。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.startAiRequest`
     */
    fun getEffectiveSystemPrompt(context: Context): String {
        val basePrompt = getSelectedRole(context).prompt
        return if (isMultiChoiceEnabled(context)) {
            """
            $basePrompt

            【输出要求】
            请针对当前聊天场景和上下文，严格基于上述设定，生成多条不同切入点或风格语气的候选回复供用户选择。
            每条候选回复必须使用 <<<OPTION>>> 作为前缀单独一行分隔。
            格式示例：
            <<<OPTION>>>
            候选回复内容
            <<<OPTION>>>
            候选回复内容
            严禁输出任何多余的前言、解释、序号或标记外的说明，直接输出各个选项内容。
            """.trimIndent()
        } else {
            basePrompt
        }
    }

    fun getTemperature(context: Context): Float {
        return getPrefs(context).getFloat(KEY_TEMPERATURE, 0.7f)
    }

    fun setTemperature(context: Context, temp: Float) {
        getPrefs(context).edit().putFloat(KEY_TEMPERATURE, temp).apply()
    }

    fun getReasoningEffort(context: Context): Int {
        return getPrefs(context).getInt(KEY_REASONING_EFFORT, REASONING_OFF)
    }

    fun setReasoningEffort(context: Context, effort: Int) {
        getPrefs(context).edit().putInt(KEY_REASONING_EFFORT, effort).apply()
    }

    fun getReasoningEffortString(context: Context): String {
        return when (getReasoningEffort(context)) {
            REASONING_LOW -> "low"
            REASONING_MEDIUM -> "medium"
            REASONING_HIGH -> "high"
            else -> "off"
        }
    }

    /**
     * 读取是否开启引用发送功能
     *
     * 开启时点击发送按钮将引用触发本次 QAI 提问的消息并发送回复文本；
     * 未开启时点击发送按钮直接将文本发送到聊天窗口。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Boolean: true 表示开启引用发送，false 表示直接发送。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog:startAiRequest`
     *     - `AiSettingsActivity:loadData`
     */
    fun isQuoteOnSend(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_QUOTE_ON_SEND, false)
    }

    /**
     * 设置是否开启引用发送功能
     *
     * 切换引用发送配置项的开关状态。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     enabled (Boolean): 目标开关状态。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:bindViews`
     */
    fun setQuoteOnSend(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_QUOTE_ON_SEND, enabled).apply()
    }

    /**
     * 获取上下文携带的消息条数。
     *
     * 返回 0 表示关闭上下文功能，仅针对当前长按单条消息进行提问；
     * 返回大于 0 则表示携带触发消息前序的指定条数消息作为聊天语境。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Int: 设定的上下文消息条数（0 至 100 之间，默认 5）。
     *
     * Callers:
     *     - `AiSettingsActivity:loadData`
     *     - `AioMenuHook:extractContextMessages`
     */
    fun getContextCount(context: Context): Int {
        val count = getPrefs(context).getInt(KEY_CONTEXT_COUNT, DEFAULT_CONTEXT_COUNT)
        return count.coerceIn(0, MAX_CONTEXT_COUNT)
    }

    /**
     * 设置上下文携带的消息条数。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     count (Int): 目标条数（自动限制在 0 至 100 范围内）。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:bindViews`
     */
    fun setContextCount(context: Context, count: Int) {
        getPrefs(context).edit().putInt(KEY_CONTEXT_COUNT, count.coerceIn(0, MAX_CONTEXT_COUNT)).apply()
    }

    /**
     * 获取是否在上下文中排除图片消息。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Boolean: true 为排除图片消息，false 为保留。
     *
     * Callers:
     *     - `AiSettingsActivity:loadData`
     *     - `AioMenuHook:extractContextMessages`
     */
    fun isExcludeImage(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_EXCLUDE_IMAGE, false)
    }

    /**
     * 设置是否在上下文中排除图片消息。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     exclude (Boolean): 是否排除。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:bindViews`
     */
    fun setExcludeImage(context: Context, exclude: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_EXCLUDE_IMAGE, exclude).apply()
    }

    /**
     * 获取是否在上下文中排除表情与表情包消息。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Boolean: true 为排除表情消息，false 为保留。
     *
     * Callers:
     *     - `AiSettingsActivity:loadData`
     *     - `AioMenuHook:extractContextMessages`
     */
    fun isExcludeEmoji(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_EXCLUDE_EMOJI, false)
    }

    /**
     * 设置是否在上下文中排除表情与表情包消息。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     exclude (Boolean): 是否排除。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:bindViews`
     */
    fun setExcludeEmoji(context: Context, exclude: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_EXCLUDE_EMOJI, exclude).apply()
    }

    /**
     * 获取是否在上下文中排除卡片与小程序类消息。
     *
     * 覆盖 Ark 卡片及结构化 XML 消息。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Boolean: true 为排除卡片消息，false 为保留。
     *
     * Callers:
     *     - `AiSettingsActivity:loadData`
     *     - `AioMenuHook:extractContextMessages`
     */
    fun isExcludeCard(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_EXCLUDE_CARD, false)
    }

    /**
     * 设置是否在上下文中排除卡片与小程序类消息。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     exclude (Boolean): 是否排除。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:bindViews`
     */
    fun setExcludeCard(context: Context, exclude: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_EXCLUDE_CARD, exclude).apply()
    }

    /**
     * 获取是否在上下文中排除语音消息。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Boolean: true 为排除语音消息，false 为保留。
     *
     * Callers:
     *     - `AiSettingsActivity:loadData`
     *     - `AioMenuHook:extractContextMessages`
     */
    fun isExcludeVoice(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_EXCLUDE_VOICE, false)
    }

    /**
     * 设置是否在上下文中排除语音消息。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     exclude (Boolean): 是否排除。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:bindViews`
     */
    fun setExcludeVoice(context: Context, exclude: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_EXCLUDE_VOICE, exclude).apply()
    }

    /**
     * 获取是否开启多项候选回复模式。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Boolean: true 为开启多项选择模式，false 为单条回复模式（默认）。
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.loadData`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.buildDialogView`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.startAiRequest`
     *     - `LlmConfig.kt:LlmConfig.getEffectiveSystemPrompt`
     */
    fun isMultiChoiceEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MULTI_CHOICE_ENABLED, false)
    }

    /**
     * 设置是否开启多项候选回复模式。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     enabled (Boolean): 是否开启。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.bindViews`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.buildDialogView`
     */
    fun setMultiChoiceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MULTI_CHOICE_ENABLED, enabled).apply()
    }

    /**
     * 解析大模型输出的文本并拆分为多条候选回复列表。
     *
     * 使用 <<<OPTION>>> 分隔符拆分候选内容并去除首尾空白字符。
     *
     * Args:
     *     raw (String): 大模型返回的原始累积响应文本。
     *
     * Returns:
     *     List<String>: 提取出的候选回复列表；若无明确标记则将非空原文作为单项列表返回。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.onChunk`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.onComplete`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.getTargetTextToOperate`
     */
    fun parseCandidates(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val delimiterRegex = Regex("<<<OPTION(?:\\s*\\d+)?>>>", RegexOption.IGNORE_CASE)
        val parts = raw.split(delimiterRegex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.isNotEmpty()) parts else listOf(raw.trim())
    }
}
