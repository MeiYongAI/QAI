package io.github.meiyongai.qai.llm.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import io.github.meiyongai.qai.llm.utils.ModelUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class StreamChatClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var eventSource: EventSource? = null
    private var isCancelled = false

    interface Callback {
        fun onChunk(chunk: String)
        fun onReasoning(chunk: String)
        fun onComplete(fullText: String)
        fun onError(error: String)
    }

    /**
     * 发起 OpenAI 兼容接口的 SSE 流式聊天请求。
     *
     * 支持文本与图文多模态交互、系统提示词注入、温度调节以及思维链推理内容解析。
     *
     * Args:
     *     baseUrl (String): 模型服务接口基础地址。
     *     apiKey (String): 接口调用认证令牌。
     *     model (String): 调用的模型名称标识。
     *     systemPrompt (String): 设定的系统提示词（人设/规则）。
     *     userPrompt (String): 用户的输入提问或指令。
     *     imagePaths (List<String>): 可选的本地图片绝对路径列表，若存在则逐一组装为多模态内容。
     *     temperature (Float): 模型采样温度。
     *     reasoningEffort (Int): 推理思考级别（0 为关闭）。
     *     callback (Callback): 流式响应回调接收器。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.startAiRequest`
     */
    fun startStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imagePaths: List<String> = emptyList(),
        temperature: Float,
        reasoningEffort: Int = 0,
        callback: Callback
    ) {
        val messages = JSONArray().apply {
            if (systemPrompt.isNotBlank()) {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            val userMsg = JSONObject().put("role", "user")
            val base64Images = imagePaths.mapNotNull { loadBase64Image(it) }
            if (base64Images.isNotEmpty()) {
                val parts = JSONArray()
                if (userPrompt.isNotBlank()) {
                    parts.put(JSONObject().put("type", "text").put("text", userPrompt))
                }
                for (b64 in base64Images) {
                    parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", b64)))
                }
                userMsg.put("content", parts)
            } else {
                userMsg.put("content", userPrompt)
            }
            put(userMsg)
        }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("stream", true)
            if (ModelUtil.supportsTemperature(model)) {
                put("temperature", temperature)
            }
            ModelUtil.applyReasoningParameters(this, baseUrl, model, reasoningEffort)
        }

        val cleanUrl = baseUrl.trim().trimEnd('/')
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            callback.onError("接口地址无效（缺少 http:// 或 https:// 前缀）: $baseUrl")
            return
        }

        val request = try {
            Request.Builder()
                .url("$cleanUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "text/event-stream")
                .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        } catch (e: Throwable) {
            callback.onError("构建请求失败: ${e.message}")
            return
        }

        val fullTextBuilder = StringBuilder()

        try {
            eventSource = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (data == "[DONE]") {
                        callback.onComplete(fullTextBuilder.toString())
                        return
                    }
                    try {
                        val jsonObj = JSONObject(data)
                        val choices = jsonObj.optJSONArray("choices") ?: return
                        if (choices.length() == 0) return
                        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return

                        // 解析思维链 / 推理内容 (兼容 reasoning_content, reasoning, thinking)
                        val reasoningChunk = extractText(delta, "reasoning_content")
                            .ifEmpty { extractText(delta, "reasoning") }
                            .ifEmpty { extractText(delta, "thinking") }
                        if (reasoningChunk.isNotEmpty()) {
                            callback.onReasoning(reasoningChunk)
                        }

                        // 正式文本
                        val chunk = extractText(delta, "content")
                        if (chunk.isNotEmpty()) {
                            fullTextBuilder.append(chunk)
                            callback.onChunk(chunk)
                        }
                    } catch (ignored: Exception) {
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (isCancelled) return
                    val code = response?.code ?: 0
                    val bodyStr = runCatching { response?.body?.string() }.getOrNull()
                    val errorDetail = when {
                        !bodyStr.isNullOrBlank() -> {
                            // 尝试解析常见 OpenAI error json 格式
                            runCatching {
                                val errObj = JSONObject(bodyStr).optJSONObject("error")
                                errObj?.optString("message") ?: bodyStr
                            }.getOrDefault(bodyStr)
                        }
                        t != null -> t.localizedMessage ?: t.javaClass.simpleName
                        else -> "HTTP $code"
                    }
                    callback.onError(errorDetail)
                }

                override fun onClosed(eventSource: EventSource) {
                    if (isCancelled) return
                    callback.onComplete(fullTextBuilder.toString())
                }
            })
        } catch (e: Throwable) {
            if (!isCancelled) {
                callback.onError("发起流式连接失败: ${e.message}")
            }
        }
    }

    /**
     * 主动中断当前正在进行的流式生成连接与数据流。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.buildDialogView`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.dismiss`
     */
    fun cancel() {
        isCancelled = true
        eventSource?.cancel()
        eventSource = null
    }

    private fun extractText(json: JSONObject, key: String): String {
        if (json.isNull(key)) return ""
        val value = json.opt(key)
        if (value is String && value.isNotEmpty() && value != "null") {
            return value
        }
        return ""
    }

    /**
     * 将本地指定路径的图片压缩并编码为 Base64 Data-URI 字符串。
     *
     * 自动按最大边长限制进行下采样解码，并通过 JPEG 压缩将图像字节量限制在目标上限内。
     *
     * Args:
     *     path (String): 本地图片文件的绝对路径。
     *     maxSide (Int): 图像最大边长像素限制（默认为 1024）。
     *     maxBytes (Int): 压缩后的最大字节限制（默认为 800KB）。
     *
     * Returns:
     *     String?: 带有 "data:image/jpeg;base64," 前缀的字符串，若文件不存在或读取失败则返回 null。
     *
     * Callers:
     *     - `StreamChatClient.kt:StreamChatClient.startStream`
     */
    private fun loadBase64Image(path: String, maxSide: Int = 1024, maxBytes: Int = 800 * 1024): String? {
        return runCatching {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxSide || bounds.outHeight / sampleSize > maxSide) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
            val out = ByteArrayOutputStream()
            try {
                for (quality in listOf(85, 70, 50)) {
                    out.reset()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    if (out.size() <= maxBytes) {
                        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    }
                }
                "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }
}
