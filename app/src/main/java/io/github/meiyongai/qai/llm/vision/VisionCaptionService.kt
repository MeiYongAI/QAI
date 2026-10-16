package io.github.meiyongai.qai.llm.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.util.LruCache
import io.github.meiyongai.qai.llm.config.LlmConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 视觉理解与图片反推描述（Image Captioning）独立服务。
 *
 * 借鉴 AstrBot 多模态图片前置处理架构设计，专为非多模态主模型（如纯文本模型）
 * 或群聊富媒体上下文理解提供图片事实提取。通过小模型快速反推简明文本摘要并注入聊天上下文，
 * 使大模型能精准理解群友发送的表情包、封禁截图、聊天记录截图等视觉媒介。
 */
object VisionCaptionService {
    private const val TAG = "QAI_VisionCaption"

    // 内存摘要缓存（Key 为图片文件 MD5，Value 为提取出的中文描述）
    private val captionCache = LruCache<String, String>(100)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 并发提取多个本地图片文件的简明事实摘要描述。
     *
     * 优先查询内存 LRU 缓存，未命中时并发调用配置的独立视觉大模型接口；
     * 若未启用独立视觉服务或服务未就绪，返回空 Map。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     imagePaths (List<String>): 本地图片绝对路径列表。
     *
     * Returns:
     *     Map<String, String>: 图片绝对路径与对应的中文简明描述映射。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.startAiRequest`
     *     - `AioMenuHook.kt:AioMenuHook.extractMessagePayload`
     */
    suspend fun getCaptionsForImages(context: Context, imagePaths: List<String>): Map<String, String> {
        if (imagePaths.isEmpty() || !LlmConfig.isVisionCaptionReady(context)) {
            return emptyMap()
        }

        val baseUrl = LlmConfig.getVisionBaseUrl(context)
        val apiKey = LlmConfig.getVisionApiKey(context)
        val model = LlmConfig.getEffectiveVisionModel(context)

        return withContext(Dispatchers.IO) {
            coroutineScope {
                val deferredList = imagePaths.distinct().map { path ->
                    async {
                        val caption = getOrRequestCaption(baseUrl, apiKey, model, path)
                        path to caption
                    }
                }
                deferredList.awaitAll().filter { it.second.isNotBlank() }.toMap()
            }
        }
    }

    /**
     * 单张图片获取或在线请求反推描述。
     *
     * Args:
     *     baseUrl (String): 视觉服务基础接口地址。
     *     apiKey (String): 视觉服务 API 密钥。
     *     model (String): 视觉模型标识。
     *     imagePath (String): 目标本地图片路径。
     *
     * Returns:
     *     String: 提取出的简短描述文字，失败时返回空字符串。
     *
     * Callers:
     *     - `VisionCaptionService:getCaptionsForImages`
     */
    private fun getOrRequestCaption(
        baseUrl: String,
        apiKey: String,
        model: String,
        imagePath: String
    ): String {
        val file = File(imagePath)
        if (!file.exists() || !file.isFile || file.length() == 0L) {
            return ""
        }

        val md5 = calculateFileMd5(file)
        if (md5.isNotBlank()) {
            val cached = captionCache.get(md5)
            if (!cached.isNullOrBlank()) {
                Log.d(TAG, "Caption cache hit for $md5: $cached")
                return cached
            }
        }

        val base64Data = encodeImageToBase64(file)
        if (base64Data.isBlank()) {
            return ""
        }

        val cleanUrl = baseUrl.trim().trimEnd('/')
        val requestJson = JSONObject().apply {
            put("model", model)
            val messages = JSONArray().apply {
                put(
                    JSONObject().put("role", "system").put(
                        "content",
                        "你是一个高效准确的聊天配图速读助手。请用1句话极其简明扼要地概括图片中的事实内容、主要文字、人物情绪或截图通知（例如“微信被封禁15天的提示界面截图”、“一张猫咪震惊表情包”）。严禁任何前言、分析或废话，直接输出纯描述。"
                    )
                )
                val userMsg = JSONObject().put("role", "user")
                val contentParts = JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", "请简短概括这张图片："))
                    put(
                        JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put("url", base64Data)
                        )
                    )
                }
                userMsg.put("content", contentParts)
                put(userMsg)
            }
            put("messages", messages)
            put("max_tokens", 80)
            put("temperature", 0.2)
        }

        val request = Request.Builder()
            .url("$cleanUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w(TAG, "Vision request failed: HTTP ${response.code}, body=$body")
                    return ""
                }
                val respJson = JSONObject(body)
                val choices = respJson.optJSONArray("choices") ?: return ""
                if (choices.length() == 0) return ""
                val firstChoice = choices.getJSONObject(0)
                val msg = firstChoice.optJSONObject("message") ?: return ""
                val content = msg.optString("content", "").trim()
                if (content.isNotBlank()) {
                    if (md5.isNotBlank()) {
                        captionCache.put(md5, content)
                    }
                    Log.i(TAG, "Successfully extracted caption for $imagePath: $content")
                    return content
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during vision caption request for $imagePath", e)
        }
        return ""
    }

    /**
     * 计算本地文件的 MD5 摘要校验值。
     *
     * Args:
     *     file (File): 待计算的目标文件对象。
     *
     * Returns:
     *     String: 32位十六进制 MD5 字符串，计算失败时返回空字符串。
     *
     * Callers:
     *     - `VisionCaptionService:getOrRequestCaption`
     */
    private fun calculateFileMd5(file: File): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            val digest = md.digest()
            val sb = StringBuilder()
            for (b in digest) {
                sb.append(String.format("%02x", b.toInt() and 0xff))
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 将本地图片文件采样压缩并转为 Data URL 格式的 Base64 字符串。
     *
     * Args:
     *     file (File): 待编码的本地图片文件。
     *
     * Returns:
     *     String: "data:image/jpeg;base64,..." 格式字符串，失败时返回空字符串。
     *
     * Callers:
     *     - `VisionCaptionService:getOrRequestCaption`
     */
    private fun encodeImageToBase64(file: File): String {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val maxDim = 800
            var sampleSize = 1
            while (options.outWidth / sampleSize > maxDim || options.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }
            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize

            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return ""
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            bitmap.recycle()
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$b64"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode image to Base64: ${file.absolutePath}", e)
            ""
        }
    }
}
