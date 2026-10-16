package io.github.meiyongai.qai.llm.memory

import android.content.Context
import android.util.Log
import io.github.meiyongai.qai.llm.config.LlmConfig
import io.github.meiyongai.qai.llm.net.StreamChatClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 智能上下文增量压缩器。
 *
 * 对齐 AstrBot 的 LLMSummaryCompressor 思想，当单会话积累的未压缩历史字符数或轮数达到设定阈值时，
 * 异步调用大模型将早期超额轮次浓缩提炼为长期记忆摘要（事实提要/偏好/进展），保留最新若干轮次作为精确语境。
 */
object ContextCompressor {
    private const val TAG = "QAI_Compressor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 检查并按需异步触发指定会话的上下文压缩操作。
     *
     * 评估该会话当前未压缩历史的总字符长度及对话轮数。
     * 若未开启压缩或未超出阈值则直接返回；若超出阈值则切分出早期轮次发起后台提炼总结。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     sessionId (String): 会话标识符。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.onComplete`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.btnSend.setOnClickListener`
     */
    fun triggerCompressionIfNeeded(context: Context, sessionId: String) {
        if (!LlmConfig.isContextCompressEnabled(context)) return

        scope.launch {
            val session = SessionMemoryManager.getSession(context, sessionId) ?: return@launch
            val turns = session.turns
            val keepTurns = LlmConfig.getContextCompressKeepTurns(context)
            if (turns.size <= keepTurns) return@launch

            val threshold = LlmConfig.getContextCompressThreshold(context)
            var totalChars = 0
            for (turn in turns) {
                totalChars += turn.userPrompt.length + turn.assistantReply.length
            }

            if (totalChars < threshold) return@launch

            Log.i(TAG, "Triggering context compression for $sessionId: totalChars=$totalChars, threshold=$threshold, turns=${turns.size}")

            val splitIndex = turns.size - keepTurns
            val earlyTurns = turns.subList(0, splitIndex)
            val retainedTurns = turns.subList(splitIndex, turns.size)

            val promptBuilder = StringBuilder()
            promptBuilder.append("你是一个专业长对话记忆提炼助手。请根据以下早期对话记录，提炼出客观、精炼的长期事实摘要。\n")
            promptBuilder.append("【提炼要求】\n")
            promptBuilder.append("1. 重点提取用户的身份信息、核心诉求、个人偏好、重要决定与待办事项；\n")
            promptBuilder.append("2. 剔除一切无意义的客套寒暄与冗余废话，直接罗列核心事实要点；\n")
            promptBuilder.append("3. 使用客观陈述句，全文字数控制在 300 字以内。\n\n")

            if (session.summary.isNotBlank()) {
                promptBuilder.append("【已有旧记忆提要】\n")
                promptBuilder.append(session.summary).append("\n\n")
                promptBuilder.append("请将已有提要与下方新产生的早期对话合并更新为一份统一的精炼摘要。\n\n")
            }

            promptBuilder.append("【待提炼对话内容】\n")
            for (turn in earlyTurns) {
                promptBuilder.append("User: ").append(turn.userPrompt).append("\n")
                promptBuilder.append("Assistant: ").append(turn.assistantReply).append("\n")
            }

            val preset = LlmConfig.getSelectedPreset(context)
            val baseUrl = LlmConfig.getEffectiveBaseUrl(context, preset)
            val apiKey = LlmConfig.getFirstApiKey(context, preset)
            val model = LlmConfig.getModel(context, preset)

            if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
                Log.w(TAG, "Cannot run compression: LLM configuration incomplete")
                return@launch
            }

            val client = StreamChatClient()
            client.startStream(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                systemPrompt = "你是一个高效精确的对话摘要提炼助手。",
                userPrompt = promptBuilder.toString(),
                imagePaths = emptyList(),
                temperature = 0.3f,
                reasoningEffort = 0,
                callback = object : StreamChatClient.Callback {
                    override fun onChunk(chunk: String) {}
                    override fun onReasoning(chunk: String) {}
                    override fun onComplete(fullText: String) {
                        val trimmedSummary = fullText.trim()
                        if (trimmedSummary.isNotBlank()) {
                            SessionMemoryManager.updateSummary(context, sessionId, trimmedSummary, retainedTurns)
                            Log.i(TAG, "Context compression finished for $sessionId, new summary len=${trimmedSummary.length}")
                        }
                    }
                    override fun onError(error: String) {
                        Log.e(TAG, "Context compression failed for $sessionId: $error")
                    }
                }
            )
        }
    }
}
