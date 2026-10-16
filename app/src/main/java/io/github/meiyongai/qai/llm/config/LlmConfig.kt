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
    const val MAX_CONTEXT_COUNT = 1000

    // 会话记忆与上下文压缩配置项
    private const val KEY_SESSION_MEMORY_ENABLED = "qai_session_memory_enabled"
    private const val KEY_CONTEXT_COMPRESS_ENABLED = "qai_context_compress_enabled"
    private const val KEY_CONTEXT_COMPRESS_THRESHOLD = "qai_context_compress_threshold"
    private const val KEY_CONTEXT_COMPRESS_KEEP_TURNS = "qai_context_compress_keep_turns"

    // 独立视觉/图片理解（Image Captioning）配置项
    private const val KEY_VISION_CAPTION_ENABLED = "qai_vision_caption_enabled"
    private const val KEY_VISION_BASE_URL = "qai_vision_base_url"
    private const val KEY_VISION_API_KEY = "qai_vision_api_key"
    private const val KEY_VISION_MODEL = "qai_vision_model"

    const val DEFAULT_COMPRESS_THRESHOLD = 4000
    const val MIN_COMPRESS_THRESHOLD = 500
    const val MAX_COMPRESS_THRESHOLD = 30000

    const val DEFAULT_COMPRESS_KEEP_TURNS = 5
    const val MIN_COMPRESS_KEEP_TURNS = 1
    const val MAX_COMPRESS_KEEP_TURNS = 20

    const val REASONING_OFF = 0
    const val REASONING_LOW = 1
    const val REASONING_MEDIUM = 2
    const val REASONING_HIGH = 3

    private const val KEY_MULTI_CHOICE_ENABLED = "qai_multi_choice_enabled"

    val BUILTIN_ROLES = listOf(
        Role(
            id = "assistant",
            name = "默认助手",
            prompt = "你是一个QQ聊天智能辅助助手，请始终以我的立场代入，根据聊天背景与对方发言，直接输出纯台词聊天回复。",
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

    /**
     * 获取指定服务商预设下当前配置或默认的大语言模型名称。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     preset (Int): 服务商预设枚举常量（参见 PresetRegistry）。
     *
     * Returns:
     *     String: 配置的模型名称字符串，若未显式配置则回退到该预设官方默认模型。
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.loadData`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.startAiRequest`
     *     - `LlmConfig.kt:LlmConfig.getEffectiveVisionModel`
     */
    fun getModel(context: Context, preset: Int): String {
        val keyName = "qai_model_$preset"
        val saved = getPrefs(context).getString(keyName, "")?.trim() ?: ""
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

            【回复模式：多项候选】
            你现在正在聊天中亲自回复消息。请严格按照当前角色人设，直接输出多条真实聊天回复供用户选择。
            绝对禁止以助手身份进行局外分析，严禁输出任何“针对该情境”、“可以参考以下回复”、“回复角度”等前言、导语或总结说明。
            严禁输出任何分类标签、粗体标题或数字序号。
            每条回复必须且仅以 <<<OPTION>>> 单独一行开头。

            正例（必须严格遵循此结构）：
            <<<OPTION>>>
            直接回复内容一
            <<<OPTION>>>
            直接回复内容二
            <<<OPTION>>>
            直接回复内容三

            反例（绝对严禁输出此类内容）：
            可以参考以下几种回复角度：
            1. **角度一**
            “回复内容”
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
     *     List<String>: 提取出的候选回复列表；若无分隔符则将非空原文作为单项列表返回。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.onChunk`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.onComplete`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.getTargetTextToOperate`
     */
    fun parseCandidates(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val delimiterRegex = Regex("<<<OPTION(?:\\s*\\d+)?>>>", RegexOption.IGNORE_CASE)
        val parts = trimmed.split(delimiterRegex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.isNotEmpty()) parts else listOf(trimmed)
    }

    /**
     * 获取是否启用会话记忆持久化存储。
     *
     * 启用后每次对话生成的回复将自动记录在本地数据库中，实现跨次长程记忆。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Boolean: true 为启用会话记忆，false 为禁用。
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.loadData`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.buildDialogView`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.startAiRequest`
     */
    fun isSessionMemoryEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SESSION_MEMORY_ENABLED, false)
    }

    /**
     * 设置是否启用会话记忆持久化存储。
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
     */
    fun setSessionMemoryEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SESSION_MEMORY_ENABLED, enabled).apply()
    }

    /**
     * 获取是否启用上下文自动压缩功能。
     *
     * 当历史记忆长度达到阈值时自动通过大模型提炼核心事实并浓缩旧记录。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Boolean: true 为启用智能压缩，false 为禁用。
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.loadData`
     *     - `ContextCompressor.kt:ContextCompressor.triggerCompressionIfNeeded`
     */
    fun isContextCompressEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CONTEXT_COMPRESS_ENABLED, false)
    }

    /**
     * 设置是否启用上下文自动压缩功能。
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
     */
    fun setContextCompressEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CONTEXT_COMPRESS_ENABLED, enabled).apply()
    }

    /**
     * 获取触发上下文压缩的字符长度阈值。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Int: 字符长度阈值（介于 500 至 30000 之间，默认 4000）。
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.loadData`
     *     - `ContextCompressor.kt:ContextCompressor.triggerCompressionIfNeeded`
     */
    fun getContextCompressThreshold(context: Context): Int {
        val t = getPrefs(context).getInt(KEY_CONTEXT_COMPRESS_THRESHOLD, DEFAULT_COMPRESS_THRESHOLD)
        return t.coerceIn(MIN_COMPRESS_THRESHOLD, MAX_COMPRESS_THRESHOLD)
    }

    /**
     * 设置触发上下文压缩的字符长度阈值。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     threshold (Int): 目标字符长度阈值。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.bindViews`
     */
    fun setContextCompressThreshold(context: Context, threshold: Int) {
        val safe = threshold.coerceIn(MIN_COMPRESS_THRESHOLD, MAX_COMPRESS_THRESHOLD)
        getPrefs(context).edit().putInt(KEY_CONTEXT_COMPRESS_THRESHOLD, safe).apply()
    }

    /**
     * 获取上下文压缩时保留的最新未压缩轮数。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Int: 保留的最新轮数（介于 1 至 20 之间，默认 5 轮）。
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.loadData`
     *     - `ContextCompressor.kt:ContextCompressor.triggerCompressionIfNeeded`
     */
    fun getContextCompressKeepTurns(context: Context): Int {
        val turns = getPrefs(context).getInt(KEY_CONTEXT_COMPRESS_KEEP_TURNS, DEFAULT_COMPRESS_KEEP_TURNS)
        return turns.coerceIn(MIN_COMPRESS_KEEP_TURNS, MAX_COMPRESS_KEEP_TURNS)
    }

    /**
     * 设置上下文压缩时保留的最新未压缩轮数。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     turns (Int): 保留的最新轮数。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.bindViews`
     */
    fun setContextCompressKeepTurns(context: Context, turns: Int) {
        val safe = turns.coerceIn(MIN_COMPRESS_KEEP_TURNS, MAX_COMPRESS_KEEP_TURNS)
        getPrefs(context).edit().putInt(KEY_CONTEXT_COMPRESS_KEEP_TURNS, safe).apply()
    }

    /**
     * 判断是否启用了独立图片理解（Image Captioning）辅助服务。
     *
     * 专为使用非多模态大模型（如纯文本模型）的用户提供图片描述提取，
     * 当主模型本身具备多模态能力时亦可关闭此开关，直接将原图传入主模型。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Boolean: true 为启用独立图片理解服务，false 为关闭。
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.loadData`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.startAiRequest`
     *     - `AioMenuHook.kt:AioMenuHook.extractMessagePayload`
     */
    fun isVisionCaptionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VISION_CAPTION_ENABLED, false)
    }

    /**
     * 设置是否启用独立图片理解辅助服务。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     enabled (Boolean): 是否开启独立图片理解。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.bindViews`
     */
    fun setVisionCaptionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VISION_CAPTION_ENABLED, enabled).apply()
    }

    /**
     * 获取配置的独立图片理解 API 基础地址。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     String: 图片理解服务 Base URL（若未设置则返回空字符串）。
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.loadData`
     *     - `VisionCaptionService.kt:VisionCaptionService.requestCaption`
     */
    fun getVisionBaseUrl(context: Context): String {
        return getPrefs(context).getString(KEY_VISION_BASE_URL, "") ?: ""
    }

    /**
     * 设置独立图片理解 API 基础地址。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     url (String): 目标接口地址。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.showVisionBaseUrlDialog`
     */
    fun setVisionBaseUrl(context: Context, url: String) {
        val normalized = UrlNormalizer.normalizeBaseUrl(url)
        getPrefs(context).edit().putString(KEY_VISION_BASE_URL, normalized).apply()
    }

    /**
     * 获取配置的独立图片理解 API 密钥。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     String: 图片理解服务密钥字符串。
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.loadData`
     *     - `VisionCaptionService.kt:VisionCaptionService.requestCaption`
     */
    fun getVisionApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_VISION_API_KEY, "")?.trim() ?: ""
    }

    /**
     * 设置独立图片理解 API 密钥。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     apiKey (String): 目标 API 密钥。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.showVisionApiKeyDialog`
     */
    fun setVisionApiKey(context: Context, apiKey: String) {
        getPrefs(context).edit().putString(KEY_VISION_API_KEY, apiKey.trim()).apply()
    }

    /**
     * 获取用户显式配置的独立图片理解模型名称。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     String: 用户显式保存的模型标识符，若未自定义则返回空字符串。
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.loadData`
     *     - `AiSettingsActivity.kt:AiSettingsActivity.showVisionModelDialog`
     *     - `LlmConfig.kt:LlmConfig.getEffectiveVisionModel`
     */
    fun getVisionModel(context: Context): String {
        return getPrefs(context).getString(KEY_VISION_MODEL, "")?.trim() ?: ""
    }

    /**
     * 获取当前生效的独立图片理解模型名称。
     *
     * 若用户显式配置了专用视觉模型则优先使用；若留空，则自动继承当前主模型配置的模型标识。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     String: 当前生效的多模态模型名称。
     *
     * Callers:
     *     - `LlmConfig.kt:LlmConfig.isVisionCaptionReady`
     *     - `VisionCaptionService.kt:VisionCaptionService.getCaptionsForImages`
     */
    fun getEffectiveVisionModel(context: Context): String {
        val custom = getVisionModel(context)
        if (custom.isNotBlank()) return custom
        val preset = getSelectedPreset(context)
        return getModel(context, preset)
    }

    /**
     * 设置独立图片理解模型名称。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     model (String): 目标模型标识，允许传入空字符串以表示继承主模型。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.showVisionModelDialog`
     */
    fun setVisionModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_VISION_MODEL, model.trim()).apply()
    }

    /**
     * 判断独立图片理解服务是否具备完整且可用的调用配置。
     *
     * 当开关启用且已具备有效的 Base URL、API Key 与生效模型名称时返回 true。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Boolean: 若可用返回 true，否则返回 false。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.startAiRequest`
     *     - `AioMenuHook.kt:AioMenuHook.extractMessagePayload`
     */
    fun isVisionCaptionReady(context: Context): Boolean {
        if (!isVisionCaptionEnabled(context)) return false
        val baseUrl = getVisionBaseUrl(context)
        val apiKey = getVisionApiKey(context)
        val model = getEffectiveVisionModel(context)
        return isValidBaseUrl(baseUrl) && apiKey.isNotBlank() && model.isNotBlank()
    }
}
